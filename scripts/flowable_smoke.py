"""Exercise real REST/Flowable/adapter chain (start servers before running)."""
import argparse
import json
import time
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from uuid import uuid4


def request(url,data=None):
    try:
        with urlopen(Request(url,data=json.dumps(data).encode() if data is not None else None,
                             headers={'Content-Type':'application/json'}),timeout=10) as response:
            return json.load(response)
    except HTTPError as ex:raise RuntimeError(ex.read().decode()) from ex


def complete_run(base,run,timeout=60):
    completed=set();deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        state=request(base+'/api/runs/'+run)
        for command in state['commands']:
            if command['STATUS']=='SENT' and command['ID'] not in completed:
                request(base+'/api/runs/'+run+'/commands/'+command['ID']+'/simulate',{'outcome':'success'})
                completed.add(command['ID'])
        if state['OUTCOME']!='RUNNING':
            assert state['OUTCOME']=='SUCCEEDED',state
            assert len(state['commands'])==28,state
            print('PASS:',run,'14 action nodes / 28 device commands / SUCCEEDED')
            return state
        time.sleep(.1)
    raise RuntimeError('Run did not terminate within timeout')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base',default='http://127.0.0.1:8080')
    args=parser.parse_args();run='smoke-'+uuid4().hex[:12]
    request(args.base+'/api/runs',{'requestId':run});complete_run(args.base,run)
