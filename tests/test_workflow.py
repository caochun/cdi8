"""
tests/test_workflow.py — Workflow 引擎单元测试

运行：python3 -m unittest tests.test_workflow -v
"""
import asyncio
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from jzgk.workflow import Step, StepResult, Workflow, WorkflowAborted


def run(coro):
    return asyncio.run(coro)


# ── 辅助 action ──────────────────────────────────────────────

def ok():
    """同步成功 action。"""
    pass


async def ok_async():
    """异步成功 action。"""
    pass


def fail():
    raise RuntimeError("step failed")


async def slow_ok(delay: float):
    await asyncio.sleep(delay)


class _Counter:
    """记录调用次数，第 N 次成功。"""
    def __init__(self, succeed_on: int = 1):
        self.calls = 0
        self.succeed_on = succeed_on

    def action(self):
        self.calls += 1
        if self.calls < self.succeed_on:
            raise RuntimeError(f"call {self.calls} failed")


# ── 校验测试 ─────────────────────────────────────────────────

class TestValidation(unittest.TestCase):
    def test_missing_dep_raises(self):
        with self.assertRaises(ValueError, msg="引用不存在的依赖应报错"):
            Workflow("w", [
                Step("a", ["nonexistent"], ok),
            ])

    def test_cycle_raises(self):
        with self.assertRaises(ValueError, msg="环形依赖应报错"):
            Workflow("w", [
                Step("a", ["b"], ok),
                Step("b", ["a"], ok),
            ])

    def test_self_dep_raises(self):
        with self.assertRaises(ValueError, msg="自依赖应报错"):
            Workflow("w", [
                Step("a", ["a"], ok),
            ])


# ── 执行语义测试 ──────────────────────────────────────────────

class TestExecution(unittest.IsolatedAsyncioTestCase):
    async def test_sequential(self):
        order = []

        async def step(name):
            order.append(name)

        results = await Workflow("w", [
            Step("a", [],    lambda: step("a")),
            Step("b", ["a"], lambda: step("b")),
            Step("c", ["b"], lambda: step("c")),
        ]).run()

        self.assertEqual(order, ["a", "b", "c"])
        self.assertTrue(all(r.success for r in results.values()))

    async def test_parallel_steps_run_concurrently(self):
        """并行步骤应重叠执行，总耗时 < 各步之和。"""
        delay = 0.05

        wf = Workflow("w", [
            Step("a", [], lambda: slow_ok(delay)),
            Step("b", [], lambda: slow_ok(delay)),
            Step("c", [], lambda: slow_ok(delay)),
        ])
        t0 = time.monotonic()
        await wf.run()
        elapsed = time.monotonic() - t0

        self.assertLess(elapsed, delay * 2.5, "并行步骤应显著节省时间")

    async def test_sync_action(self):
        counter = _Counter(succeed_on=1)
        results = await Workflow("w", [Step("a", [], counter.action)]).run()
        self.assertTrue(results["a"].success)

    async def test_step_result_fields(self):
        results = await Workflow("w", [Step("a", [], ok_async)]).run()
        r = results["a"]
        self.assertEqual(r.name, "a")
        self.assertTrue(r.success)
        self.assertGreaterEqual(r.elapsed, 0)
        self.assertEqual(r.attempts, 1)
        self.assertIsNone(r.error)


# ── 故障处理测试 ──────────────────────────────────────────────

class TestFaultHandling(unittest.IsolatedAsyncioTestCase):
    async def test_hard_failure_raises_workflow_aborted(self):
        with self.assertRaises(WorkflowAborted) as ctx:
            await Workflow("w", [Step("a", [], fail)]).run()
        self.assertEqual(ctx.exception.step, "a")
        self.assertIsInstance(ctx.exception.cause, RuntimeError)

    async def test_soft_failure_continues(self):
        order = []

        async def soft_fail():
            order.append("soft")
            raise RuntimeError("soft error")

        async def after():
            order.append("after")

        results = await Workflow("w", [
            Step("soft", [],       soft_fail, soft=True),
            Step("after", ["soft"], after),
        ]).run()

        self.assertIn("soft", order)
        self.assertIn("after", order)
        self.assertFalse(results["soft"].success)
        self.assertTrue(results["after"].success)

    async def test_hard_failure_cancels_parallel_peers(self):
        """硬步骤失败后，同批并行的其他步骤应被取消，不等待完成。"""
        started = []
        finished = []

        async def fast_fail():
            started.append("fail")
            raise RuntimeError("fast fail")

        async def slow_step():
            started.append("slow")
            await asyncio.sleep(10)  # 极长延迟
            finished.append("slow")

        t0 = time.monotonic()
        with self.assertRaises(WorkflowAborted):
            await Workflow("w", [
                Step("fail", [], fast_fail),
                Step("slow", [], slow_step),
            ]).run()
        elapsed = time.monotonic() - t0

        self.assertIn("slow", started, "慢步骤应已启动")
        self.assertNotIn("slow", finished, "慢步骤不应运行完成")
        self.assertLess(elapsed, 5.0, "硬故障后不应等待慢步骤自然结束")

    async def test_downstream_steps_skipped_on_failure(self):
        """硬步骤失败后，其下游步骤不应执行。"""
        ran = []

        await self._run_catching(Workflow("w", [
            Step("fail",     [],       fail),
            Step("child",    ["fail"], lambda: ran.append("child")),
            Step("grandchild", ["child"], lambda: ran.append("grandchild")),
        ]))

        self.assertNotIn("child", ran)
        self.assertNotIn("grandchild", ran)

    async def _run_catching(self, wf: Workflow):
        try:
            await wf.run()
        except WorkflowAborted:
            pass


# ── 重试测试 ──────────────────────────────────────────────────

class TestRetry(unittest.IsolatedAsyncioTestCase):
    async def test_retry_succeeds_on_nth_attempt(self):
        counter = _Counter(succeed_on=3)
        results = await Workflow("w", [
            Step("a", [], counter.action, retries=2),
        ]).run()
        self.assertTrue(results["a"].success)
        self.assertEqual(results["a"].attempts, 3)
        self.assertEqual(counter.calls, 3)

    async def test_retry_exhausted_raises(self):
        counter = _Counter(succeed_on=999)
        with self.assertRaises(WorkflowAborted):
            await Workflow("w", [
                Step("a", [], counter.action, retries=2),
            ]).run()
        self.assertEqual(counter.calls, 3)

    async def test_soft_retry_exhausted_continues(self):
        counter = _Counter(succeed_on=999)
        results = await Workflow("w", [
            Step("a", [], counter.action, retries=1, soft=True),
            Step("b", ["a"], ok),
        ]).run()
        self.assertFalse(results["a"].success)
        self.assertEqual(results["a"].attempts, 2)
        self.assertTrue(results["b"].success)


if __name__ == "__main__":
    unittest.main(verbosity=2)
