"""Exercise the Java seed over real Tango, compare Python behavior, and run Flowable.

Build with the java-simulator Maven profile first. All processes and data are
isolated; this test never replaces the workbench's existing demo environment.
"""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
from queue import Empty, Queue
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'simulator'))


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def stop(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def spawn(stack, command, log):
    with log.open('ab') as output:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    stack.callback(stop, process)
    return process


def wait_for(predicate, process, log, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Service exited: ' + log.read_text()[-6000:])
        try:
            value = predicate()
            if value:
                return value
        except (OSError, ValueError):
            pass
        time.sleep(.1)
    raise AssertionError('Service readiness timeout: ' + log.read_text()[-6000:])


def snapshot(proxy):
    return json.loads(proxy.read_attribute('Snapshot').value)


def projection(value):
    keys = ['state_revision', 'model', 'model_version', 'component', 'state_id', 'outer_state', 'inner_state',
            'outer_state_label', 'inner_state_label',
            'active_command_id', 'active_action', 'task_state', 'context', 'configuration', 'faults', 'last_result']
    return {key: value[key] for key in keys if key in value}


def unsubscribe(proxy, token):
    try:
        proxy.unsubscribe_event(token)
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java', help='Java 17+ executable')
    parser.add_argument('--jar', type=Path, default=ROOT / 'device-simulator/target/device-simulator-0.1.0.jar')
    parser.add_argument('--server-jar', type=Path, default=ROOT / 'server/target/workbench-server-0.1.0.jar')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--skip-workflow', action='store_true')
    args = parser.parse_args()
    import tango
    from tango.test_context import MultiDeviceTestContext
    from gxlf_component_simulator.component_model import SEED_MODEL
    from gxlf_component_simulator.component_runtime import ComponentRuntime
    from gxlf_component_simulator.tango.demo import component_specs, component_definition, component_bindings
    from gxlf_component_simulator.tango.preparation_context import PreparationContextDevice

    folder = args.output_dir or Path(tempfile.mkdtemp(prefix='cdi8-java-seed-smoke-'))
    folder.mkdir(parents=True, exist_ok=True)
    if any(folder.iterdir()):
        raise ValueError('Use an empty output directory')
    print('Test artifacts:', folder, flush=True)
    shutil.copy2(args.jar, folder / 'device.jar')
    database, ready, java_log = folder / 'java.sqlite3', folder / 'ready.json', folder / 'java.log'
    events = Queue()
    java_command = [args.java, '-jar', str(folder / 'device.jar'), '--port', '0',
                    '--db', str(database), '--ready-file', str(ready)]

    with ExitStack() as stack:
        def start_java():
            ready.unlink(missing_ok=True)
            process = spawn(stack, java_command, java_log)
            value = wait_for(lambda: json.loads(ready.read_text()), process, java_log)
            proxy = tango.DeviceProxy(value['address'])
            proxy.set_timeout_millis(5000)
            wait_for(lambda: snapshot(proxy).get('engine') == 'apache-commons-scxml2', process, java_log)
            token = proxy.subscribe_event('Snapshot', tango.EventType.CHANGE_EVENT,
                lambda event: events.put(json.loads(event.attr_value.value)) if not event.err else None,
                stateless=True)
            stack.callback(unsubscribe, proxy, token)
            return process, proxy, token, value['address']

        process, proxy, token, address = start_java()
        reference = ComponentRuntime(SEED_MODEL, folder / 'python.sqlite3')
        stack.callback(reference.close)

        def compare(operation, payload):
            java_name = {'execute': 'Execute', 'complete': 'Complete', 'simulate': 'Simulate', 'report_event': 'ReportEvent'}[operation]
            actual = json.loads(proxy.command_inout(java_name, json.dumps(payload, ensure_ascii=True)))
            expected = getattr(reference, operation)(payload)
            for key in ['command_id', 'action', 'parameters', 'status', 'key_state', 'reason', 'event_id', 'event', 'interrupted_command_id']:
                assert actual.get(key) == expected.get(key), (operation, key, actual, expected)
            if 'snapshot' in expected:
                assert projection(actual['snapshot']) == projection(expected['snapshot']), (operation, actual, expected)
            assert projection(snapshot(proxy)) == projection(reference.snapshot()), (operation, snapshot(proxy), reference.snapshot())
            return actual

        def reject(operation, payload):
            java_name = {'execute': 'Execute', 'simulate': 'Simulate', 'report_event': 'ReportEvent'}[operation]
            previous = projection(snapshot(proxy))
            try:
                proxy.command_inout(java_name, json.dumps(payload))
            except tango.DevFailed:
                pass
            else:
                raise AssertionError('Java accepted an invalid request')
            try:
                getattr(reference, operation)(payload)
            except ValueError:
                pass
            else:
                raise AssertionError('Python accepted an invalid request')
            assert projection(snapshot(proxy)) == projection(reference.snapshot()) == previous

        def perform(command_id, action, outcome='success', parameters=None):
            payload = {'command_id': command_id, 'action': action}
            if parameters is not None:
                payload['parameters'] = parameters
            compare('execute', payload)
            return compare('simulate', {'command_id': command_id, 'outcome': outcome})

        reject('execute', {'command_id': 'too-early', 'action': 'parameter_dispatch'})
        compare('execute', {'command_id': 'power', 'action': 'power_on_check'})
        compare('execute', {'command_id': 'power', 'action': 'power_on_check'})
        reject('execute', {'command_id': 'power', 'action': 'shutdown'})
        reject('execute', {'command_id': 'busy', 'action': 'shutdown'})
        compare('simulate', {'command_id': 'power', 'outcome': 'success'})
        compare('simulate', {'command_id': 'power', 'outcome': 'success'})
        reject('simulate', {'command_id': 'power', 'outcome': 'failure'})
        perform('function', 'function_check')
        perform('parameters', 'parameter_dispatch', parameters={'备注': '脉冲参数'})
        compare('execute', {'command_id': 'emit', 'action': 'seed_source_emit'})
        compare('report_event', {'event_id': 'fault', 'event': 'fault_confirmed', 'observed_outer_state': '异常'})
        compare('report_event', {'event_id': 'fault', 'event': 'fault_confirmed', 'observed_outer_state': '异常'})
        assert compare('simulate', {'command_id': 'emit', 'outcome': 'success'})['status'] == 'interrupted'
        for event in ['communication_fault_confirmed', 'timeout_confirmed']:
            compare('report_event', {'event_id': event, 'event': event, 'observed_outer_state': '异常'})
        compare('report_event', {'event_id': 'recover', 'event': 'recovery_confirmed', 'observed_outer_state': '未就绪'})
        for command_id, action in [('power2', 'power_on_check'), ('function2', 'function_check'),
                                   ('parameters2', 'parameter_dispatch'), ('emit2', 'seed_source_emit')]:
            perform(command_id, action)
        perform('bad-collect', 'laser_parameter_collect', 'failure')
        perform('collect', 'laser_parameter_collect')
        perform('standby', 'standby_reset')
        perform('shutdown', 'shutdown')
        compare('execute', {'command_id': 'pending-restart', 'action': 'power_on_check'})
        unsubscribe(proxy, token)
        stop(process)
        reference.close()
        reference = ComponentRuntime(SEED_MODEL, folder / 'python.sqlite3')
        stack.callback(reference.close)
        process, proxy, token, address = start_java()
        assert projection(snapshot(proxy)) == projection(reference.snapshot())
        assert json.loads(proxy.command_inout('GetResult', 'pending-restart'))['status'] == 'accepted'
        compare('simulate', {'command_id': 'pending-restart', 'outcome': 'success'})
        perform('function3', 'function_check')
        perform('parameters3', 'parameter_dispatch')
        perform('shutdown3', 'shutdown')
        assert len(json.loads(proxy.command_inout('History', 100))) == len(reference.history())
        observed = set()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not {'stableEmission', 'faultLocked'}.issubset(observed):
            try:
                observed.add(events.get(timeout=.2)['state_id'])
            except Empty:
                pass
        assert {'stableEmission', 'faultLocked'}.issubset(observed), observed
        print('PASS real JTango commands / CHANGE_EVENT / Python comparison / restart recovery', flush=True)

        if not args.skip_workflow:
            specs = [item for item in component_specs({'seed': 1, 'shg': 1, 'preamp': 1, 'multipass': 1}) if item[1] != 'seed']
            definition = component_definition(specs, folder / 'python-devices')
            definition.append({'class': PreparationContextDevice, 'devices': [{'name': 'gxlf/context/01'}]})
            context = stack.enter_context(MultiDeviceTestContext(definition, process=True, host='127.0.0.1', timeout=20,
                                                                instance_name='java_seed_integration'))
            bindings = component_bindings(context, specs)
            bindings['context_01'] = {'component': 'context', 'address': context.get_device_access('gxlf/context/01')}
            bindings['seed_01'] = {'component': 'seed', 'address': address}
            devices_file = folder / 'devices.json'
            devices_file.write_text(json.dumps(bindings))
            bridge_port, server_port = free_port(), free_port()
            bridge_log, server_log = folder / 'bridge.log', folder / 'server.log'
            bridge = spawn(stack, [sys.executable, '-m', 'gxlf_component_simulator.tango.control_bridge',
                                   '--devices', str(devices_file), '--port', str(bridge_port)], bridge_log)
            shutil.copy2(args.server_jar, folder / 'server.jar')
            server = spawn(stack, [args.java, '-jar', str(folder / 'server.jar'), '--server.port=' + str(server_port),
                '--control.tango-url=http://127.0.0.1:' + str(bridge_port),
                '--spring.datasource.url=jdbc:h2:file:' + str(folder / 'control'),
                '--control.workbench-directory=' + str(folder / 'workbench')], server_log)
            base, bridge_url = 'http://127.0.0.1:' + str(server_port), 'http://127.0.0.1:' + str(bridge_port)

            def connected():
                with urlopen(base + '/api/connection', timeout=2) as response:
                    return json.load(response)['connected']
            wait_for(connected, server, server_log)
            subprocess.run([sys.executable, str(ROOT / 'tests/e2e/gxlf_lifecycle_smoke.py'),
                            '--base', base, '--bridge', bridge_url], cwd=ROOT, check=True)
            assert snapshot(proxy)['engine'] == 'apache-commons-scxml2'
            assert snapshot(proxy)['state_id'] == 'collectionOk'
            print('PASS Flowable lifecycle with Java seed and Python peer devices over Tango', flush=True)


if __name__ == '__main__':
    main()
