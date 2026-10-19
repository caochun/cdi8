"""Real Flowable + PyTango demo test; start both servers with fresh data first.

This test explicitly simulates hardware feedback at the Tango bridge. The
controller itself never produces successful feedback or external permits.
"""
import argparse
import json
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def request(url, data=None):
    try:
        with urlopen(Request(url, data=json.dumps(data).encode() if data is not None else None,
                             headers={'Content-Type': 'application/json'}), timeout=10) as response:
            return json.load(response)
    except HTTPError as ex:
        raise RuntimeError(ex.read().decode()) from ex


def flatten_runs(run):
    yield run
    for child in run.get("children", []):
        yield from flatten_runs(child)


def start_workbench_run(base, sources):
    """Publish the built-in versions as a pinned release in a fresh demo environment."""
    models = {model['id']: model for model in request(base + '/api/workbench/models')}
    process_keys = ['component_initialization', 'parameter_dispatch', 'optical_frontend_preparation',
                    'formal_emission', 'post_emission_processing', 'emission_task_lifecycle']
    components = {'seed-source-state-machine': 'seed', 'shg-injection-state-machine': 'shg',
                  'preamp-state-machine': 'preamp', 'multipass-state-machine': 'multipass'}
    config = {
        'entry': 'emission_task_lifecycle',
        'processes': [{'model': key, 'revision': models[key]['revisions'][-1]['id']} for key in process_keys],
        'devices': [{'model': key, 'revision': models[key]['revisions'][-1]['id'], 'component': component,
                     'count': 1, 'autoComplete': False, 'delayMs': 500} for key, component in components.items()],
        'bindings': {'sourceTargets': [source + '_01' for source in sources]},
        'variables': {'commandTimeout': 'PT30S', 'workflowTimeout': 'PT60S'},
        'autoComplete': False,
    }
    config = request(base + '/api/workbench/environment', config)
    return request(base + '/api/workbench/runs', config)


def complete_run(base, bridge, process, sources, permit=False, workbench=False):
    run = (start_workbench_run(base, sources) if workbench else
           request(base + '/api/runs', {'processKey': process, 'sources': sources}))
    completed = set()
    deadline = time.monotonic() + 60
    gated = False
    all_conditions = {
        'safety_permit': True, 'collimation_environment_ready': True,
        'diagnosis_acquisition_ready': True, 'crystal_pose_ready': True,
        'charge_preparation_ready': True, 'preamp_single_recipe_ready': True,
        'measurement_single_recipe_ready': True, 'target_measurement_ready': True,
        'switch_charge_ready': True, 'charge_complete': True,
        'warning_music_stopped': True, 'trigger_countdown_started': True,
        'pump_trigger_ready': True, 'switch_trigger_ready': True,
        'synchronized_triggered': True, 'diagnostic_postprocess_ready': True,
        'pump_data_collected': True, 'pump_standby_ready': True,
        'preionization_prepared': True, 'preionization_music_started': True,
        'preionization_charge_complete': True,
        'preionization_music_stopped': True, 'preionization_countdown_started': True,
        'preionization_pump_trigger_ready': True, 'preionization_sync_triggered': True,
        'preionization_data_collected': True, 'preionization_standby_ready': True,
        'warning_light_off': True, 'laser_parameter_collected': True,
        'target_analysis_ready': True, 'target_retracted': True,
        'switch_waveform_collected': True, 'switch_standby_ready': True,
        'slab_purge_complete': True, 'shielding_door_open': True,
    }
    while time.monotonic() < deadline:
        state = request(base + '/api/runs/' + run['id'])
        active = {activity for owner in flatten_runs(state) for activity in owner['activeActivities']}
        if permit and 'wait_safety' in active and not gated:
            # It must remain blocked when the external permit is false.
            time.sleep(.4)
            held = request(base + '/api/runs/' + run['id'])
            assert any('wait_safety' in owner['activeActivities'] for owner in flatten_runs(held)), held
            request(bridge + '/devices/context_01/conditions', {'safety_permit': True})
            gated = True
        if permit and 'wait_environment' in active:
            request(bridge + '/devices/context_01/conditions', {'collimation_environment_ready': True})
        if process == 'emission_task_lifecycle' and gated:
            request(bridge + '/devices/context_01/conditions', all_conditions)
        for device in request(bridge + '/devices'):
            snapshot = device.get('snapshot', {})
            command = snapshot.get('active_command_id')
            if command and command not in completed:
                result = request(bridge + '/devices/' + device['id'] + '/simulate',
                                 {'command_id': command, 'outcome': 'success'})
                assert result['status'] == 'succeeded', result
                # Repeated hardware feedback must be harmless.
                duplicate = request(bridge + '/devices/' + device['id'] + '/simulate',
                                    {'command_id': command, 'outcome': 'success'})
                assert duplicate['status'] == 'succeeded', duplicate
                completed.add(command)
        if state['outcome'] != 'RUNNING':
            assert state['outcome'] == 'SUCCEEDED', state
            commands = [command for owner in flatten_runs(state) for command in owner['commands']]
            assert len(completed) == len(commands), (completed, state)
            assert all(c.get('commandResult') and json.loads(c['commandResult'])['status'] == 'succeeded'
                       for c in commands), state
            print('PASS', process, len(completed), 'commands', state['outcome'], run['id'])
            return state
        time.sleep(.1)
    raise AssertionError('Workflow timeout: ' + json.dumps(state, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--bridge', default='http://127.0.0.1:8766')
    parser.add_argument('--sources', nargs='+', choices=['seed', 'shg'], default=['seed'])
    parser.add_argument('--workbench', action='store_true', help='apply a fresh demo environment and run pinned model versions')
    args = parser.parse_args()
    complete_run(args.base, args.bridge, 'emission_task_lifecycle', args.sources, permit=True, workbench=args.workbench)
    for device in request(args.bridge + '/devices'):
        snapshot = device['snapshot']
        if device['component'] in args.sources:
            assert snapshot['outer_state'] == 'emitting', snapshot
        if device['component'] in {'seed', 'shg'} - set(args.sources):
            assert snapshot['outer_state'] == 'ready', snapshot
        if device['component'] == 'preamp':
            assert snapshot['state_id'] == 'standbyReady', snapshot
        if device['component'] == 'multipass':
            assert snapshot['state_id'] == 'collimationReady', snapshot
    print('PASS full lifecycle / source selection / explicit external conditions')


if __name__ == '__main__':
    main()
