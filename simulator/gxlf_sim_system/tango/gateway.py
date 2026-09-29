"""HTTP to Tango gateway. Local mode is an explicit protocol-independent simulator."""
import argparse
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from .runtime import DeviceRuntime


def serve(devices, port, tango=False):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, code, value):
            data = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(code); self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

        def invoke(self, method, arg=None):
            parts = self.path.split('/')
            if len(parts)<3 or parts[1]!='devices' or parts[2] not in devices:
                raise ValueError('unknown device')
            device = devices[parts[2]]
            if tango:
                if method=='snapshot': return json.loads(device.read_attribute('Snapshot').value)
                names={'execute':'Execute','result':'GetResult','cancel':'Cancel','simulate':'Simulate'}
                return json.loads(device.command_inout(names[method], json.dumps(arg) if isinstance(arg, dict) else arg))
            return getattr(device, method)(arg) if arg is not None else device.snapshot()

        def do_GET(self):
            try:
                parts=self.path.split('/')
                if len(parts)==3: result=self.invoke('snapshot')
                elif len(parts)==5 and parts[3]=='commands': result=self.invoke('result',parts[4])
                else: raise ValueError('unknown endpoint')
                self.respond(200,result)
            except Exception as ex: self.respond(409,dict(error=str(ex)))

        def do_POST(self):
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=16384: raise ValueError('invalid payload size')
                request=json.loads(self.rfile.read(length)); endpoint=self.path.split('/')[-1]
                if endpoint=='commands': result=self.invoke('execute',request)
                elif endpoint=='cancel': result=self.invoke('cancel',request['command_id'])
                elif endpoint=='simulate': result=self.invoke('simulate',request)
                else: raise ValueError('unknown endpoint')
                self.respond(200,result)
            except Exception as ex: self.respond(409,dict(error=str(ex)))
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler)
    print(f'Device gateway: http://127.0.0.1:{port} mode={"tango" if tango else "local"}',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--mode',choices=['local','tango'],default='local')
    parser.add_argument('--port',type=int,default=8766)
    parser.add_argument('--data',type=Path,default=Path(__file__).resolve().parents[1]/'output'/'devices')
    parser.add_argument('--tango-devices',type=Path,help='JSON mapping of logical IDs to existing Tango Device names')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]/'models'
    configs={d:dict(model_path=str(root/('excel-shg-injector-state-machine.yaml' if d=='shg_01' else 'excel-seed-source-state-machine.yaml')),
                    database_path=str((args.data/(d+'.sqlite3')).resolve())) for d in ['seed_01','seed_02','seed_03','shg_01']}
    with ExitStack() as stack:
        if args.mode=='tango':
            if args.tango_devices:
                from tango import DeviceProxy
                bindings=json.loads(args.tango_devices.read_text())
                if set(bindings)!=set(configs):
                    raise ValueError('Tango device bindings must include seed_01/02/03 and shg_01')
                devices={d:DeviceProxy(name) for d,name in bindings.items()}
            else:
                from tango.test_context import MultiDeviceTestContext
                from .device import SubsystemDevice
                # Development no-db Tango server; deployed servers use the Tango database.
                context=stack.enter_context(MultiDeviceTestContext([{'class': SubsystemDevice, 'devices': [dict(name='gxlf/sim/'+d,properties=c) for d,c in configs.items()]}],process=True,host='127.0.0.1'))
                devices={d:context.get_device('gxlf/sim/'+d) for d in configs}
        else: devices={d:DeviceRuntime(c['model_path'],c['database_path']) for d,c in configs.items()}
        serve(devices,args.port,tango=args.mode=='tango')


if __name__=='__main__': main()
