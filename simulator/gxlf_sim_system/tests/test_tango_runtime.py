from pathlib import Path
import tempfile
import unittest

from gxlf_sim_system.tango.runtime import DeviceRuntime

MODEL=Path(__file__).resolve().parents[1]/'models'/'excel-seed-source-state-machine.yaml'


class DeviceRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.db=Path(self.tmp.name)/'device.sqlite3'
        self.device=DeviceRuntime(MODEL,self.db)
        self.addCleanup(self.device.db.close)

    def test_duplicate_dispatch_uses_same_task_even_after_restart(self):
        request={'command_id':'one','action':'power_on_self_test'}
        first=self.device.execute(request)
        self.assertEqual(first,self.device.execute(request))
        restarted=DeviceRuntime(MODEL,self.db);self.addCleanup(restarted.db.close)
        self.assertEqual(first,restarted.execute(request))
        result=restarted.simulate({'command_id':'one','outcome':'success'})
        self.assertEqual(result['snapshot']['current_state'],'自检完成')
        self.assertEqual(result,restarted.simulate({'command_id':'one','outcome':'success'}))
        again=DeviceRuntime(MODEL,self.db);self.addCleanup(again.db.close)
        self.assertEqual(result,again.result('one'))
        self.assertEqual('自检完成',again.snapshot()['current_state'])

    def test_cancel_before_send_prevents_a_late_dispatch(self):
        self.device.cancel('one')
        result=self.device.execute({'command_id':'one','action':'power_on_self_test'})
        self.assertEqual(result['status'],'cancelled')
        self.assertIsNone(self.device.snapshot()['active_action'])

    def test_rejection_does_not_modify_existing_active_task(self):
        first=self.device.execute({'command_id':'one','action':'power_on_self_test'})
        second=self.device.execute({'command_id':'two','action':'power_on_self_test'})
        self.assertEqual(second['status'],'rejected')
        self.assertEqual(self.device.snapshot()['task_id'],first['snapshot']['task_id'])
        self.device.cancel('two')
        self.assertIsNotNone(self.device.snapshot()['active_action'])
        with self.assertRaises(ValueError):
            self.device.execute({'command_id':'one','action':'shutdown'})

    def test_fault_and_cancellation_are_durable_results(self):
        self.device.execute({'command_id':'one','action':'power_on_self_test'})
        result=self.device.simulate({'command_id':'one','outcome':'communication_error'})
        self.assertEqual(result['status'],'failed')
        self.assertEqual(result['snapshot']['business_state'],'异常')
        self.assertEqual(result,self.device.cancel('one'))

    def test_parameters_persist_and_command_id_cannot_change_them(self):
        for action in ('power_on_self_test','function_check'):
            self.device.execute({'command_id':action,'action':action})
            self.device.simulate({'command_id':action,'outcome':'success'})
        request={'command_id':'configure','action':'parameter_dispatch','parameters':{'recipe_id':'recipe-42'}}
        result=self.device.execute(request)
        self.assertEqual(result['parameters'],request['parameters'])
        with self.assertRaises(ValueError):
            self.device.execute({**request,'parameters':{'recipe_id':'other'}})
        self.device.simulate({'command_id':'configure','outcome':'success'})
        restarted=DeviceRuntime(MODEL,self.db);self.addCleanup(restarted.db.close)
        self.assertEqual(restarted.result('configure')['parameters'],request['parameters'])


if __name__=='__main__':unittest.main()
