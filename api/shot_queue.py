"""
api/shot_queue.py — 发次队列与历史记录管理

ShotQueue 维护：
- 待执行队列（asyncio.Queue）
- 当前运行中发次
- 历史发次记录列表
"""
import asyncio
import logging
import time
from dataclasses import asdict

from jzgk import JZGK, ShotRecipe, ShotRecord

logger = logging.getLogger(__name__)


def _recipe_to_dict(recipe: ShotRecipe) -> dict:
    return {
        "recipe_id":       recipe.recipe_id,
        "pump_energy_j":   recipe.pump_energy_j,
        "kg_voltage_kv":   recipe.kg_voltage_kv,
        "plz_pitch_mrad":  recipe.plz_pitch_mrad,
        "plz_yaw_mrad":    recipe.plz_yaw_mrad,
        "target_id":       recipe.target_id,
        "phase_a_timeout": recipe.phase_a_timeout,
        "phase_b_timeout": recipe.phase_b_timeout,
        "phase_c_timeout": recipe.phase_c_timeout,
    }


def _record_to_dict(record: ShotRecord) -> dict:
    return {
        "shot_id":          record.shot_id,
        "recipe_id":        record.recipe.recipe_id,
        "success":          record.success,
        "abort_reason":     record.abort_reason.value if record.abort_reason else None,
        "abort_detail":     record.abort_detail,
        "phase_a_elapsed":  record.phase_a_elapsed,
        "phase_b_elapsed":  record.phase_b_elapsed,
        "phase_c_elapsed":  record.phase_c_elapsed,
        "total_elapsed":    record.total_elapsed,
        "laser_energy_kj":  record.laser_energy_kj,
        "uv_energy_j":      record.uv_energy_j,
        "xray_signal":      record.xray_signal,
        "neutron_count":    record.neutron_count,
        "gamma_signal":     record.gamma_signal,
        "model_version":    record.model_version,
        "correction_factors": record.correction_factors,
        "step_timings":     record.step_timings,
        "start_time":       record.start_time.isoformat() if record.start_time else None,
        "end_time":         record.end_time.isoformat() if record.end_time else None,
    }


class ShotQueue:
    def __init__(self, jzgk: JZGK, broadcaster):
        self._jzgk = jzgk
        self._broadcaster = broadcaster
        self._queue: asyncio.Queue[ShotRecipe] = asyncio.Queue()
        self._pending: list[ShotRecipe] = []   # 可取消的视图
        self._history: list[ShotRecord] = []
        self.current: ShotRecipe | None = None

    async def enqueue(self, recipe: ShotRecipe) -> None:
        self._pending.append(recipe)
        await self._queue.put(recipe)
        await self._broadcast_queue()
        logger.info("发次 %s 已入队（队列长度: %d）", recipe.recipe_id, len(self._pending))

    async def cancel_pending(self, recipe_id: str) -> bool:
        """从待执行列表移除（如果还未被消费）。"""
        for r in self._pending:
            if r.recipe_id == recipe_id:
                self._pending.remove(r)
                # 无法从 asyncio.Queue 直接删除，用 sentinel 标记跳过
                r._cancelled = True  # type: ignore[attr-defined]
                await self._broadcast_queue()
                return True
        return False

    def get_history(self) -> list[dict]:
        return [_record_to_dict(r) for r in self._history]

    def snapshot_queue(self) -> dict:
        return {
            "pending": [_recipe_to_dict(r) for r in self._pending],
            "current": _recipe_to_dict(self.current) if self.current else None,
        }

    async def run_loop(self) -> None:
        """持续消费队列并执行发次。"""
        logger.info("ShotQueue 工作循环启动")
        while True:
            recipe = await self._queue.get()
            # 跳过已取消的发次
            if getattr(recipe, "_cancelled", False):
                self._queue.task_done()
                continue

            if recipe in self._pending:
                self._pending.remove(recipe)
            self.current = recipe
            await self._broadcast_queue()

            logger.info("开始执行发次: %s", recipe.recipe_id)
            try:
                record = await self._jzgk.start_shot(recipe)
            except Exception as exc:
                logger.error("发次执行异常: %s", exc)
                record = None

            self.current = None
            if record is not None:
                self._history.append(record)
                await self._broadcaster.broadcast("shot_complete", _record_to_dict(record))
            await self._broadcast_queue()
            self._queue.task_done()

    async def _broadcast_queue(self) -> None:
        await self._broadcaster.broadcast("queue_update", self.snapshot_queue())
