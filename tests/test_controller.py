"""
tests/test_controller.py — JZGK 控制器集成测试

运行：python3 -m unittest tests.test_controller -v
"""
import asyncio
import contextlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from simulators import SimulatorRegistry
from jzgk import JZGK, JZGKState, ShotRecipe, ShotRecord, AbortReason

# 高速仿真，让测试快速完成
SIM_SPEED = 100.0


class TestNormalShot(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._ctx = contextlib.AsyncExitStack()
        self.registry = await self._ctx.enter_async_context(
            SimulatorRegistry.in_process(sim_speed=SIM_SPEED)
        )
        self.jzgk = JZGK(self.registry)

    async def asyncTearDown(self):
        await self._ctx.aclose()

    async def test_shot_succeeds(self):
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="TEST_001"))
        self.assertTrue(record.success)
        self.assertIsNone(record.abort_reason)
        self.assertGreater(record.uv_energy_j, 0)
        self.assertGreater(record.laser_energy_kj, 0)
        self.assertGreater(record.model_version, 0)

    async def test_state_returns_to_idle(self):
        await self.jzgk.start_shot(ShotRecipe(recipe_id="TEST"))
        self.assertEqual(self.jzgk.state, JZGKState.IDLE)

    async def test_step_timings_all_phases(self):
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="TEST"))
        self.assertTrue(record.step_timings, "step_timings 不应为空")
        phases = {k.split(".")[0] for k in record.step_timings}
        self.assertEqual(phases, {"a", "b", "c"}, "三个阶段都应有步骤耗时")

    async def test_history_appended(self):
        await self.jzgk.start_shot(ShotRecipe(recipe_id="TEST"))
        self.assertEqual(len(self.jzgk.history), 1)

    async def test_sequential_shots(self):
        for i in range(3):
            r = await self.jzgk.start_shot(ShotRecipe(recipe_id=f"S{i}"))
            self.assertTrue(r.success, f"发次 {i} 应成功")
        self.assertEqual(len(self.jzgk.history), 3)

    async def test_start_while_not_idle_raises(self):
        self.jzgk._state = JZGKState.PREPARING
        with self.assertRaises(RuntimeError):
            await self.jzgk.start_shot(ShotRecipe(recipe_id="TEST"))


class TestPhaseAFault(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._ctx = contextlib.AsyncExitStack()
        self.registry = await self._ctx.enter_async_context(
            SimulatorRegistry.in_process(sim_speed=SIM_SPEED)
        )
        self.jzgk = JZGK(self.registry)

    async def asyncTearDown(self):
        await self._ctx.aclose()

    async def test_hard_fault_aborts_shot(self):
        # ZK 在抽真空前就已故障 → zk_evacuate 步骤失败
        await self.registry.get("ZK").inject_fault("测试故障注入")
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="FAULT"))
        self.assertFalse(record.success)
        self.assertEqual(record.abort_reason, AbortReason.SUBSYSTEM_FAULT)

    async def test_state_returns_to_idle_after_fault(self):
        await self.registry.get("ZK").inject_fault("测试")
        await self.jzgk.start_shot(ShotRecipe(recipe_id="FAULT"))
        self.assertEqual(self.jzgk.state, JZGKState.IDLE)

    async def test_abort_has_partial_step_timings(self):
        """中止发次也应记录已完成步骤的耗时。"""
        await self.registry.get("ZK").inject_fault("测试")
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="FAULT"))
        self.assertFalse(record.success)
        # A 阶段部分步骤应已完成（zk_monitor 肯定完成了）
        a_steps = {k for k in record.step_timings if k.startswith("a.")}
        self.assertTrue(a_steps, "中止发次应有已完成的 A 阶段步骤耗时")

    async def test_bb_fault_aborts_in_phase_b(self):
        """BB 充电故障 → B 阶段 fault_check 中止。"""
        await self.registry.get("BB").inject_fault("充电电容击穿")
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="BB_FAULT"))
        self.assertFalse(record.success)
        self.assertEqual(record.abort_reason, AbortReason.SUBSYSTEM_FAULT)


class TestSafetySignal(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._ctx = contextlib.AsyncExitStack()
        self.registry = await self._ctx.enter_async_context(
            SimulatorRegistry.in_process(sim_speed=SIM_SPEED)
        )
        self.jzgk = JZGK(self.registry)

    async def asyncTearDown(self):
        await self._ctx.aclose()

    def _schedule_s1_on_firing(self):
        """在 FIRING 阶段开始后立即注入 S1（用 on_phase_change 确保时机正确）。"""
        def _cb(old, new):
            if new == JZGKState.FIRING:
                async def _do():
                    await asyncio.sleep(0.01)
                    await self.registry.get("AQ").simulate_intrusion()
                asyncio.ensure_future(_do())
        self.jzgk.on_phase_change(_cb)

    async def test_s1_aborts_shot(self):
        self._schedule_s1_on_firing()
        record = await self.jzgk.start_shot(ShotRecipe(recipe_id="S1_TEST"))
        self.assertFalse(record.success)
        self.assertEqual(record.abort_reason, AbortReason.SAFETY_SIGNAL)

    async def test_s1_state_returns_to_idle(self):
        self._schedule_s1_on_firing()
        await self.jzgk.start_shot(ShotRecipe(recipe_id="S1_TEST"))
        self.assertEqual(self.jzgk.state, JZGKState.IDLE)

    async def test_safety_watcher_cleared_between_shots(self):
        """第一发次 S1 中止后，重建环境，第二发次应能正常运行。"""
        self._schedule_s1_on_firing()
        r1 = await self.jzgk.start_shot(ShotRecipe(recipe_id="S1_SHOT"))
        self.assertFalse(r1.success)

        async with SimulatorRegistry.in_process(sim_speed=SIM_SPEED) as registry:
            jzgk = JZGK(registry)
            r2 = await jzgk.start_shot(ShotRecipe(recipe_id="RECOVERY"))
        self.assertTrue(r2.success)


class TestStateMachineFSM(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._ctx = contextlib.AsyncExitStack()
        self.registry = await self._ctx.enter_async_context(
            SimulatorRegistry.in_process(sim_speed=SIM_SPEED)
        )
        self.jzgk = JZGK(self.registry)

    async def asyncTearDown(self):
        await self._ctx.aclose()

    async def test_invalid_transition_raises(self):
        with self.assertRaises(RuntimeError, msg="非法转换应抛出 RuntimeError"):
            await self.jzgk._set_state(JZGKState.POST)   # IDLE → POST 非法

    async def test_emergency_stop_always_allowed(self):
        """任意状态 → EMERGENCY_STOP 应允许（通配）。"""
        await self.jzgk._set_state(JZGKState.EMERGENCY_STOP)
        self.assertEqual(self.jzgk.state, JZGKState.EMERGENCY_STOP)

    async def test_phase_change_callbacks_fired(self):
        transitions = []
        self.jzgk.on_phase_change(lambda old, new: transitions.append((old, new)))
        await self.jzgk.start_shot(ShotRecipe(recipe_id="CB_TEST"))
        states = [new for _, new in transitions]
        self.assertIn(JZGKState.PREPARING, states)
        self.assertIn(JZGKState.FIRING, states)
        self.assertIn(JZGKState.POST, states)
        self.assertIn(JZGKState.IDLE, states)


if __name__ == "__main__":
    unittest.main(verbosity=2)
