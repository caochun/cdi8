"""
FlowEngine: 三阶段发射流程控制引擎。

用法：
    import asyncio
    from flow_engine.engine import FlowEngine

    engine = FlowEngine(shot_id=1, task_id=42)
    state  = asyncio.run(engine.run_all())
    print(state.summary())
"""

import asyncio
import logging
import time
from typing import List, Optional

from .executor import run_stage
from .loader import load_all_recipes, load_recipe
from .state import FlowState, PhaseResult, Status

logger = logging.getLogger(__name__)


class StageFailed(Exception):
    """Critical stage 失败，向上传播以触发阶段熔断。"""
    def __init__(self, stage_id: str, details: str):
        self.stage_id = stage_id
        self.details = details
        super().__init__(f"Stage {stage_id} failed: {details}")


class FlowEngine:
    """
    发射流程控制引擎。

    Parameters
    ----------
    shot_id:  本发次编号
    task_id:  所属实验任务编号
    phase_ids: 要执行的阶段列表，默认全部三阶段
    """

    def __init__(
        self,
        shot_id: int,
        task_id: int,
        phase_ids: Optional[List[int]] = None,
    ):
        self.shot_id = shot_id
        self.task_id = task_id
        self.phase_ids = phase_ids or [1, 2, 3]
        self.state = FlowState(shot_id=shot_id, task_id=task_id)
        self._ctx = {"shot_id": shot_id, "task_id": task_id}

    # ── 公开接口 ──────────────────────────────────────────────────────────────

    async def run_all(self) -> FlowState:
        """依次执行所有配置阶段，任一阶段熔断则停止。"""
        loop = asyncio.get_event_loop()
        recipes = {r["phase_id"]: r for r in load_all_recipes()}

        for phase_id in self.phase_ids:
            recipe = recipes[phase_id]
            phase_result = await self._run_phase(recipe, loop)
            self.state.add_phase(phase_result)

            if phase_result.status == Status.FAILED:
                logger.error(
                    "=== Phase %d [%s] ABORTED — stopping flow ===",
                    phase_id, recipe["phase"]
                )
                break

        return self.state

    async def run_phase(self, phase_id: int) -> PhaseResult:
        """单独执行某一阶段（供调试或人工逐步触发使用）。"""
        recipe = load_recipe(phase_id)
        loop = asyncio.get_event_loop()
        phase_result = await self._run_phase(recipe, loop)
        self.state.add_phase(phase_result)
        return phase_result

    # ── 内部实现 ──────────────────────────────────────────────────────────────

    async def _run_phase(self, recipe: dict, loop: asyncio.AbstractEventLoop) -> PhaseResult:
        phase_result = PhaseResult(
            phase_id=recipe["phase_id"],
            phase_name=recipe["phase"],
        )
        phase_result.started_at = time.time()
        phase_result.status = Status.RUNNING

        logger.info(
            "======== Phase %d: %s ========",
            recipe["phase_id"], recipe["phase"]
        )

        for stage_def in recipe["stages"]:
            stage_result = await run_stage(stage_def, self._ctx, loop)
            phase_result.stages.append(stage_result)

            if stage_result.status == Status.FAILED:
                failed_steps = [
                    f"{r.name}({r.error})"
                    for r in stage_result.steps
                    if r.status == Status.FAILED
                ]
                phase_result.status = Status.FAILED
                phase_result.abort_reason = (
                    f"Stage [{stage_result.stage_id}] {stage_result.name} "
                    f"failed: {'; '.join(failed_steps)}"
                )
                phase_result.finished_at = time.time()
                return phase_result

        phase_result.status = Status.SUCCESS
        phase_result.finished_at = time.time()
        logger.info(
            "======== Phase %d DONE (%.1fs) ========",
            recipe["phase_id"], phase_result.elapsed
        )
        return phase_result
