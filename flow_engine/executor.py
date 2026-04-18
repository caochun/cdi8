"""
StepRunner + StageExecutor: 执行单条 Step 和整个 Stage。

- StepRunner  : 调用 Tango DeviceProxy，处理超时、返回码映射
- StageExecutor: 用 asyncio.gather 并行执行 Stage 内所有 Step，
                 汇总结果后由 CircuitBreaker 决策是否熔断
"""

import asyncio
import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List

from .state import Status, StepResult, StageResult

logger = logging.getLogger(__name__)

# 公共 Tango 输入：每次调用注入 shot_id / task_id 等上下文
_EXECUTOR = ThreadPoolExecutor(max_workers=32, thread_name_prefix="tango-worker")


def _build_argin(step: Dict, ctx: Dict) -> str:
    """将流程上下文合并到 step 参数，序列化为 JSON DevString。"""
    payload = {**ctx, "step_name": step["name"]}
    return json.dumps(payload, ensure_ascii=False)


def _call_tango(device_path: str, command: str, argin: str, timeout: int) -> int:
    """
    阻塞调用 Tango DeviceProxy。
    返回状态码整数：1=成功，0=失败，2=无权限。
    超时时抛出 TimeoutError，由调用方捕获。
    """
    try:
        import tango
        proxy = tango.DeviceProxy(device_path)
        proxy.set_timeout_millis(timeout * 1000)
        result = getattr(proxy, command)(argin)
        if isinstance(result, str):
            result = json.loads(result).get("accept_status", 0)
        return int(result)
    except tango.DevFailed as exc:
        raise RuntimeError(f"Tango DevFailed: {exc.args[0].desc}") from exc


async def run_step(
    step: Dict,
    ctx: Dict,
    loop: asyncio.AbstractEventLoop,
) -> StepResult:
    """异步执行单个 Step，返回 StepResult。"""
    result = StepResult(
        name=step["name"],
        device=step["device"],
        command=step["command"],
    )
    result.started_at = time.time()
    argin = _build_argin(step, ctx)

    try:
        code = await asyncio.wait_for(
            loop.run_in_executor(
                _EXECUTOR,
                _call_tango,
                step["device"],
                step["command"],
                argin,
                step["timeout"],
            ),
            timeout=step["timeout"] + 2,  # 给 Tango 自身超时留 2s 余量
        )
        result.return_code = code
        if code == 1:
            result.status = Status.SUCCESS
        elif code == 2:
            result.status = Status.FAILED
            result.error = "no_permission (code=2)"
        else:
            result.status = Status.FAILED
            result.error = f"return_code={code}"

    except asyncio.TimeoutError:
        result.status = Status.FAILED
        result.error = f"timeout after {step['timeout']}s"

    except Exception as exc:
        result.status = Status.FAILED
        result.error = str(exc)

    finally:
        result.finished_at = time.time()

    log_level = logging.INFO if result.status == Status.SUCCESS else logging.WARNING
    logger.log(
        log_level,
        "[%s] %s.%s → %s (%.2fs)",
        result.status,
        step["device"],
        step["command"],
        result.return_code,
        result.elapsed or 0,
    )
    return result


async def run_stage(
    stage: Dict,
    ctx: Dict,
    loop: asyncio.AbstractEventLoop,
) -> StageResult:
    """并行执行 Stage 内所有 Step，汇总为 StageResult。"""
    stage_result = StageResult(stage_id=stage["id"], name=stage["name"])
    stage_result.started_at = time.time()
    stage_result.status = Status.RUNNING

    logger.info(">>> Stage [%s] %s — %d steps", stage["id"], stage["name"], len(stage["steps"]))

    tasks = [run_step(step, ctx, loop) for step in stage["steps"]]
    step_results: List[StepResult] = await asyncio.gather(*tasks)
    stage_result.steps = list(step_results)

    # 判断 Stage 整体状态
    failed_critical = [
        r for r, s in zip(step_results, stage["steps"])
        if r.status != Status.SUCCESS and s["critical"]
    ]
    if failed_critical:
        stage_result.status = Status.FAILED
        for r in failed_critical:
            logger.error(
                "!!! Critical step FAILED: %s — %s", r.name, r.error
            )
    else:
        stage_result.status = Status.SUCCESS
        # 标记 non-critical 失败为 SKIPPED（已继续）
        for r, s in zip(step_results, stage["steps"]):
            if r.status != Status.SUCCESS and not s["critical"]:
                r.status = Status.SKIPPED
                logger.warning("--- Non-critical step skipped: %s — %s", r.name, r.error)

    stage_result.finished_at = time.time()
    logger.info(
        "<<< Stage [%s] %s — %s (%.2fs)",
        stage["id"], stage["name"], stage_result.status, stage_result.elapsed
    )
    return stage_result
