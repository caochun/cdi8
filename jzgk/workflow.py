"""
jzgk/workflow.py — 轻量 DAG 工作流引擎

步骤以有向无环图（DAG）方式声明依赖，引擎按拓扑序并发执行。

每步支持：
  - 重试（retries / retry_delay）
  - 每步超时（timeout，真实时间秒）
  - 软/硬故障分级（soft=True → 失败仅告警，不中止）
  - 同步或异步 action（均支持）
  - 耗时和尝试次数记录

用法::

    wf = Workflow("phase_a", [
        Step("a", [],    action_a),
        Step("b", ["a"], action_b),
        Step("c", ["a"], action_c, soft=True, retries=2, timeout=5.0),
        Step("d", ["b", "c"], action_d),
    ])
    results = await wf.run()   # dict[name, StepResult]
"""
import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class Step:
    """单个工作流步骤描述。"""
    name: str
    deps: list[str]                          # 依赖步骤名列表（空列表 = 无依赖）
    action: Callable[[], Awaitable | None]   # 同步或异步，无参数
    retries: int = 0          # 失败后最多重试次数（不含首次）
    retry_delay: float = 0.0  # 重试前等待（秒，真实时间）
    soft: bool = False        # True → 失败仅 WARNING，不中止工作流
    timeout: float | None = None  # 单次执行超时（秒，真实时间；None = 不限）


@dataclass
class StepResult:
    """单步执行结果。"""
    name: str
    elapsed: float            # 总耗时（含所有重试，秒）
    success: bool
    attempts: int = 1
    error: BaseException | None = None


class WorkflowAborted(Exception):
    """硬步骤失败，工作流中止。"""
    def __init__(self, step: str, cause: BaseException):
        super().__init__(f"步骤 [{step}] 失败: {cause}")
        self.step = step
        self.cause = cause


class Workflow:
    """异步 DAG 工作流引擎。"""

    def __init__(
        self,
        name: str,
        steps: list[Step],
        on_step_start: Callable[[str, str], None] | None = None,
        on_step_done: Callable[[str, "StepResult"], None] | None = None,
    ):
        self.name = name
        self._steps: dict[str, Step] = {s.name: s for s in steps}
        self.results: dict[str, StepResult] = {}
        self._on_step_start = on_step_start  # (phase_name, step_name)
        self._on_step_done = on_step_done    # (phase_name, StepResult)
        self._validate()

    # ── 校验 ────────────────────────────────────────────────

    def _validate(self):
        """检查依赖引用存在，并用 DFS 检测环。"""
        names = set(self._steps)
        for step in self._steps.values():
            for dep in step.deps:
                if dep not in names:
                    raise ValueError(
                        f"[{self.name}] 步骤 [{step.name}] 依赖 [{dep}] 不存在"
                    )
        visited: set[str] = set()
        path: set[str] = set()

        def dfs(n: str):
            if n in path:
                raise ValueError(
                    f"[{self.name}] 存在依赖环，经过节点 [{n}]"
                )
            if n in visited:
                return
            path.add(n)
            for dep in self._steps[n].deps:
                dfs(dep)
            path.discard(n)
            visited.add(n)

        for n in names:
            dfs(n)

    # ── 执行 ────────────────────────────────────────────────

    async def run(self) -> dict[str, StepResult]:
        """
        执行工作流，返回所有步骤的结果字典。
        任何硬步骤失败时抛出 WorkflowAborted。
        外部取消（CancelledError）时保证清理所有内部任务后再传播。
        """
        pending = set(self._steps.keys())
        completed: set[str] = set()

        while pending:
            ready = {
                n for n in pending
                if all(d in completed for d in self._steps[n].deps)
            }
            if not ready:
                raise RuntimeError(
                    f"[{self.name}] 死锁，待执行: {pending}"
                )

            batch, hard_fail = await self._run_batch(ready)

            for r in batch:
                self.results[r.name] = r
                pending.discard(r.name)
                if r.success or self._steps[r.name].soft:
                    completed.add(r.name)

            if hard_fail:
                raise WorkflowAborted(hard_fail.name, hard_fail.error)

        return self.results

    async def _run_batch(
        self, names: set[str]
    ) -> tuple[list[StepResult], StepResult | None]:
        """
        并发执行一批就绪步骤。
        - 首个硬步骤失败时立即取消同批其余任务
        - 外部 CancelledError 到来时取消所有内部任务后 re-raise，避免孤儿任务
        """
        tasks: list[asyncio.Task] = [
            asyncio.create_task(self._run_step(self._steps[n]), name=n)
            for n in names
        ]
        try:
            results: list[StepResult] = []
            hard_fail: StepResult | None = None
            pending: set[asyncio.Task] = set(tasks)

            while pending:
                done, pending = await asyncio.wait(
                    pending, return_when=asyncio.FIRST_COMPLETED
                )
                for task in done:
                    r: StepResult = task.result()   # _run_step 从不抛异常
                    results.append(r)
                    if not r.success and not self._steps[r.name].soft and hard_fail is None:
                        hard_fail = r
                        for t in pending:
                            t.cancel()
                        if pending:
                            await asyncio.gather(*pending, return_exceptions=True)
                        pending = set()
                        break

            return results, hard_fail

        except asyncio.CancelledError:
            # 外部取消（如 safety.guard()）：清理所有内部任务，避免孤儿任务继续运行
            for t in tasks:
                if not t.done():
                    t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise

    async def _run_step(self, step: Step) -> StepResult:
        last_exc: BaseException | None = None
        t0 = time.monotonic()

        if self._on_step_start is not None:
            try:
                self._on_step_start(self.name, step.name)
            except Exception:
                pass

        for attempt in range(step.retries + 1):
            try:
                r = step.action()
                if asyncio.iscoroutine(r):
                    if step.timeout is not None:
                        await asyncio.wait_for(r, timeout=step.timeout)
                    else:
                        await r
                elapsed = time.monotonic() - t0
                if attempt > 0:
                    logger.info(
                        "[%s] 步骤 [%s] 第 %d 次重试成功",
                        self.name, step.name, attempt + 1,
                    )
                result = StepResult(
                    name=step.name, elapsed=elapsed,
                    success=True, attempts=attempt + 1,
                )
                if self._on_step_done is not None:
                    try:
                        self._on_step_done(self.name, result)
                    except Exception:
                        pass
                return result
            except asyncio.TimeoutError:
                last_exc = TimeoutError(f"超时（>{step.timeout:.1f}s）")
                if attempt < step.retries:
                    logger.warning(
                        "[%s] 步骤 [%s] 第 %d 次超时，%.2fs 后重试",
                        self.name, step.name, attempt + 1, step.retry_delay,
                    )
                    if step.retry_delay > 0:
                        await asyncio.sleep(step.retry_delay)
            except Exception as exc:
                last_exc = exc
                if attempt < step.retries:
                    logger.warning(
                        "[%s] 步骤 [%s] 第 %d 次失败，%.2fs 后重试: %s",
                        self.name, step.name, attempt + 1, step.retry_delay, exc,
                    )
                    if step.retry_delay > 0:
                        await asyncio.sleep(step.retry_delay)

        elapsed = time.monotonic() - t0
        logger.log(
            logging.WARNING if step.soft else logging.ERROR,
            "[%s] 步骤 [%s] 最终失败（%s）: %s",
            self.name, step.name, "软" if step.soft else "硬", last_exc,
        )
        result = StepResult(
            name=step.name, elapsed=elapsed,
            success=False, error=last_exc, attempts=step.retries + 1,
        )
        if self._on_step_done is not None:
            try:
                self._on_step_done(self.name, result)
            except Exception:
                pass
        return result
