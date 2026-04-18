"""
流程控制引擎测试脚本。

不依赖真实 Tango 环境：通过猴子补丁替换 _call_tango，
模拟仿真器的 normal / fail / timeout 三种响应模式。

用法：
    python test_engine.py
    python test_engine.py --phase 1          # 只跑第一阶段
    python test_engine.py --fail T05         # 在 T05 注入失败
    python test_engine.py --slow             # 所有调用加 0.05s 延迟
"""

import argparse
import asyncio
import json
import logging
import sys
import time
from typing import Optional
from unittest.mock import patch

# 确保 flow_engine 包可被导入（从项目根运行时）
sys.path.insert(0, str(__file__[: __file__.rfind("/flow_engine")]))

from flow_engine.engine import FlowEngine
from flow_engine.state import Status

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("test_engine")


# ── Mock Tango 调用 ────────────────────────────────────────────────────────────

_FAIL_STAGE: Optional[str] = None   # 需要注入失败的 stage id
_SLOW_MODE:  bool = False


def _mock_tango(device_path: str, command: str, argin: str, timeout: int) -> int:
    """替换 executor._call_tango，不依赖 PyTango。"""
    ctx = json.loads(argin)
    step_name = ctx.get("step_name", command)

    if _SLOW_MODE:
        time.sleep(0.05)

    # 根据 stage_id 注入失败
    # argin 中没有 stage_id，通过 device+command 的简单规则模拟
    if _FAIL_STAGE and _should_fail(device_path, command, step_name):
        logger.debug("[MOCK-FAIL] %s.%s", device_path, command)
        return 0

    logger.debug("[MOCK-OK] %s.%s", device_path, command)
    return 1


_FAIL_TRIGGERS = {
    "T01": lambda d, c, n: "CreateShotTask" in c,
    "T05": lambda d, c, n: "SeedLaserOn" in c or "PreampWakeup" in c,
    "T08": lambda d, c, n: "BeamlineAlign" in c,
}


def _should_fail(device: str, command: str, name: str) -> bool:
    if _FAIL_STAGE in _FAIL_TRIGGERS:
        return _FAIL_TRIGGERS[_FAIL_STAGE](device, command, name)
    return False


# ── 测试用例 ───────────────────────────────────────────────────────────────────

async def test_full_run(phase_ids):
    """正常模式：三阶段全部成功。"""
    print("\n" + "=" * 60)
    print("TEST: 正常模式全流程")
    print("=" * 60)

    engine = FlowEngine(shot_id=1001, task_id=42, phase_ids=phase_ids)
    state = await engine.run_all()

    summary = state.summary()
    _print_summary(summary)

    all_ok = all(p["status"] == Status.SUCCESS for p in summary["phases"])
    _assert(all_ok, "所有阶段应全部成功")
    return all_ok


async def test_critical_failure(phase_ids, fail_stage: str):
    """熔断模式：在指定 Stage 注入 critical 失败，验证后续停止。"""
    print("\n" + "=" * 60)
    print(f"TEST: 熔断模式 — 在 {fail_stage} 注入失败")
    print("=" * 60)

    engine = FlowEngine(shot_id=1002, task_id=42, phase_ids=phase_ids)
    state = await engine.run_all()

    summary = state.summary()
    _print_summary(summary)

    # 至少有一个 FAILED 阶段
    any_failed = any(p["status"] == Status.FAILED for p in summary["phases"])
    _assert(any_failed, f"注入 {fail_stage} 失败后，应有阶段熔断")

    # 熔断后不应有后续阶段进入 SUCCESS
    phases = summary["phases"]
    failed_idx = next(i for i, p in enumerate(phases) if p["status"] == Status.FAILED)
    subsequent_ok = all(
        p["status"] != Status.SUCCESS for p in phases[failed_idx + 1:]
    )
    _assert(subsequent_ok, "熔断后续阶段不应执行成功")
    return any_failed


def _print_summary(summary: dict):
    for phase in summary["phases"]:
        icon = "✓" if phase["status"] == Status.SUCCESS else "✗"
        elapsed = f"{phase['elapsed']:.2f}s" if phase["elapsed"] else "?"
        print(f"  {icon} Phase {phase['phase_id']} [{phase['name']}]  {phase['status']}  {elapsed}")
        if phase.get("abort_reason"):
            print(f"      熔断原因: {phase['abort_reason']}")
        for stage in phase["stages"]:
            s_icon = "·" if stage["status"] == Status.SUCCESS else "!"
            s_elapsed = f"{stage['elapsed']:.2f}s" if stage["elapsed"] is not None else "?"
            print(f"      {s_icon} [{stage['id']}] {stage['name']}  {stage['status']}  {s_elapsed}")
            for step in stage["steps"]:
                st_icon = "✓" if step["status"] == Status.SUCCESS else (
                    "~" if step["status"] == Status.SKIPPED else "✗"
                )
                err = f"  ← {step['error']}" if step.get("error") else ""
                print(f"            {st_icon} {step['name']}  {step['status']}{err}")


def _assert(condition: bool, msg: str):
    if condition:
        print(f"  [PASS] {msg}")
    else:
        print(f"  [FAIL] {msg}")


# ── 入口 ────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", type=int, choices=[1, 2, 3],
                        help="只执行指定阶段")
    parser.add_argument("--fail", metavar="STAGE_ID",
                        help="在指定 Stage（如 T05）注入 critical 失败")
    parser.add_argument("--slow", action="store_true",
                        help="每次 Tango 调用增加 50ms 延迟")
    args = parser.parse_args()

    global _FAIL_STAGE, _SLOW_MODE
    _SLOW_MODE = args.slow
    phase_ids = [args.phase] if args.phase else [1, 2, 3]

    passed = []

    with patch("flow_engine.executor._call_tango", side_effect=_mock_tango):
        if args.fail:
            _FAIL_STAGE = args.fail
            ok = asyncio.run(test_critical_failure(phase_ids, args.fail))
            passed.append(ok)
        else:
            # 正常模式
            ok = asyncio.run(test_full_run(phase_ids))
            passed.append(ok)

            # 自动补跑一次熔断验证
            _FAIL_STAGE = "T05"
            ok = asyncio.run(test_critical_failure(phase_ids, "T05"))
            passed.append(ok)

    print("\n" + "=" * 60)
    total = len(passed)
    wins  = sum(passed)
    print(f"Results: {wins}/{total} tests passed")
    sys.exit(0 if wins == total else 1)


if __name__ == "__main__":
    main()
