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


def complete_run(base, bridge, process, sources, permit=False):
    run = request(base + '/api/runs', {'processKey': process, 'sources': sources})
    completed = set()
    deadline = time.monotonic() + 60
    gated = False
    while time.monotonic() < deadline:
        state = request(base + '/api/runs/' + run['id'])
        if permit and 'wait_safety' in state['activeActivities'] and not gated:
            # It must remain blocked when the external permit is false.
            time.sleep(.4)
            held = request(base + '/api/runs/' + run['id'])
            assert 'wait_safety' in held['activeActivities'], held
            request(bridge + '/devices/context_01/conditions', {'safety_permit': True})
            gated = True
        if permit and 'wait_environment' in state['activeActivities']:
            request(bridge + '/devices/context_01/conditions', {'collimation_environment_ready': True})
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
            assert len(completed) == len(state['commands']), (completed, state)
            assert all(c.get('commandResult') and json.loads(c['commandResult'])['status'] == 'succeeded'
                       for c in state['commands']), state
            print('PASS', process, len(completed), 'commands', state['outcome'], run['id'])
            return state
        time.sleep(.1)
    raise AssertionError('Workflow timeout: ' + json.dumps(state, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--bridge', default='http://127.0.0.1:8766')
    parser.add_argument('--sources', nargs='+', choices=['seed', 'shg'], default=['seed'])
    args = parser.parse_args()
    complete_run(args.base, args.bridge, 'component_initialization', args.sources)
    complete_run(args.base, args.bridge, 'optical_frontend_preparation', args.sources, permit=True)
    for device in request(args.bridge + '/devices'):
        snapshot = device['snapshot']
        if device['component'] in args.sources:
            assert snapshot['outer_state'] == 'emitting', snapshot
        if device['component'] in {'seed', 'shg'} - set(args.sources):
            assert snapshot['outer_state'] == 'ready', snapshot
        if device['component'] == 'preamp':
            assert snapshot['state_id'] == 'fineLoopOk', snapshot
        if device['component'] == 'multipass':
            assert snapshot['state_id'] == 'collimationReady', snapshot
    print('PASS final component states / source selection / explicit external conditions')


if __name__ == '__main__':
    main()
