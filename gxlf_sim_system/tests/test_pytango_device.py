"""Optional real Tango transport test; run with the tango extra installed."""
import importlib.util
import json
from pathlib import Path
import tempfile
from threading import Event
import unittest


@unittest.skipUnless(importlib.util.find_spec('tango'), 'install PyTango to test real Tango transport')
class PyTangoDeviceTest(unittest.TestCase):
    def test_commands_attributes_and_change_event(self):
        import tango
        from tango.test_context import MultiDeviceTestContext
        from gxlf_sim_system.tango.device import SubsystemDevice
        model=Path(__file__).resolve().parents[1]/'models'/'excel-seed-source-state-machine.yaml'
        with tempfile.TemporaryDirectory() as tmp:
            config=[{'class':SubsystemDevice,'devices':[{'name':'gxlf/test/seed','properties':{
                'model_path':str(model),'database_path':str(Path(tmp)/'state.sqlite3')}}]}]
            with MultiDeviceTestContext(config,process=True,host='127.0.0.1') as context:
                device=context.get_device('gxlf/test/seed')
                changed=Event()
                def receive(event):
                    if not event.err and json.loads(event.attr_value.value)['current_state']=='自检完成':
                        changed.set()
                event_id=device.subscribe_event('Snapshot',tango.EventType.CHANGE_EVENT,receive)
                try:
                    initial=json.loads(device.read_attribute('Snapshot').value)
                    self.assertEqual(initial['current_state'],'未上电/离线')
                    request=json.dumps({'command_id':'tango-test','action':'power_on_self_test'})
                    first=json.loads(device.command_inout('Execute',request))
                    self.assertEqual(first['status'],'accepted')
                    self.assertEqual(first,json.loads(device.command_inout('Execute',request)))
                    result=json.loads(device.command_inout('Simulate',json.dumps({'command_id':'tango-test','outcome':'success'})))
                    self.assertEqual(result['snapshot']['current_state'],'自检完成')
                    self.assertTrue(changed.wait(3),'Tango change event was not delivered')
                    self.assertEqual(result,json.loads(device.command_inout('GetResult','tango-test')))
                finally:device.unsubscribe_event(event_id)


if __name__=='__main__':unittest.main()
