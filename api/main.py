"""
api/main.py — JZGK Web 控制界面后端

运行：
    python -m api.main [--speed FACTOR] [--port PORT]

访问：
    http://localhost:8000
"""
import argparse
import asyncio
import logging
import sys
import time
from contextlib import asynccontextmanager
from pathlib import Path

import tango
import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

# 将项目根目录加入路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from jzgk import JZGK, JZGKState, ShotRecipe
from simulators import SimulatorRegistry

from .broadcaster import EventBroadcaster
from .log_handler import SSELogHandler
from .shot_queue import ShotQueue, _record_to_dict

# ── 全局应用状态 ──────────────────────────────────────────────

_broadcaster: EventBroadcaster | None = None
_registry: SimulatorRegistry | None = None
_jzgk: JZGK | None = None
_queue: ShotQueue | None = None
_sim_speed: float = 5.0

# ── 每个阶段的 DAG 结构（静态，供前端初始渲染） ───────────────

_PHASE_DAGS = {
    "a": {
        "steps": [
            {"name": "zk_monitor",      "deps": [],                  "soft": False, "label": "ZK Monitor"},
            {"name": "zzz_out",         "deps": ["zk_monitor"],      "soft": False, "label": "ZZY Output"},
            {"name": "epj_out",         "deps": ["zk_monitor"],      "soft": False, "label": "EPJ Inject"},
            {"name": "yf_wakeup",       "deps": ["zk_monitor"],      "soft": False, "label": "YF Wakeup"},
            {"name": "yf_shot_output",  "deps": ["yf_wakeup"],       "soft": False, "label": "YF Shot Out"},
            {"name": "yf_coarse",       "deps": ["yf_shot_output"],  "soft": False, "label": "YF Coarse"},
            {"name": "yf_fine",         "deps": ["yf_coarse"],       "soft": False, "label": "YF Fine"},
            {"name": "dcf_align",       "deps": ["zk_monitor"],      "soft": False, "label": "DCF Align"},
            {"name": "jzt_shot_ready",  "deps": ["zk_monitor"],      "soft": False, "label": "JZT ShotRdy"},
            {"name": "jzt_measure_rep", "deps": ["jzt_shot_ready"],  "soft": False, "label": "JZT MeasRep"},
            {"name": "jzt_preamp_rep",  "deps": ["jzt_measure_rep"], "soft": False, "label": "JZT PreRep"},
            {"name": "bb_prepare",      "deps": ["zk_monitor"],      "soft": False, "label": "BB Prepare"},
            {"name": "bm_prepos",       "deps": ["zk_monitor"],      "soft": False, "label": "BM PrePos"},
            {"name": "bm_beam_guide",   "deps": ["bm_prepos"],       "soft": False, "label": "BM Guide"},
            {"name": "zk_evacuate",     "deps": ["zk_monitor"],      "soft": False, "label": "ZK Evacuate"},
            {"name": "wlzd_setup",      "deps": ["zk_monitor"],      "soft": True,  "label": "WLZD Setup"},
            {"name": "cly_setup",       "deps": ["zk_monitor"],      "soft": True,  "label": "CLY Setup"},
            {"name": "cly_target_ready","deps": ["cly_setup"],        "soft": True,  "label": "CLY TgtRdy"},
            {"name": "cly_motion_ready","deps": ["cly_target_ready"], "soft": True,  "label": "CLY MotRdy"},
            {"name": "ldb_reset",       "deps": ["zk_monitor"],      "soft": False, "label": "LDB Reset"},
            {"name": "fault_check",     "deps": ["zzz_out","epj_out","yf_fine","dcf_align","jzt_preamp_rep","bb_prepare","bm_beam_guide","zk_evacuate","wlzd_setup","cly_motion_ready","ldb_reset"], "soft": False, "label": "Fault Check"},
            {"name": "s3_check",        "deps": ["fault_check"],      "soft": False, "label": "S3 Check"},
            {"name": "dcf_rep_confirm", "deps": ["s3_check"],         "soft": False, "label": "DCF RepConf"},
            {"name": "dcf_shot_confirm","deps": ["dcf_rep_confirm"],  "soft": False, "label": "DCF ShotConf"},
            {"name": "bm_shot_confirm", "deps": ["dcf_shot_confirm"], "soft": False, "label": "BM ShotConf"},
            {"name": "aq_alarm_on",     "deps": ["bm_shot_confirm"],  "soft": False, "label": "AQ Alarm On"},
            {"name": "aq_clear",        "deps": ["aq_alarm_on"],      "soft": False, "label": "AQ Clear"},
            {"name": "aq_shield_close", "deps": ["aq_clear"],         "soft": False, "label": "AQ Door Close"},
            {"name": "aq_shield_lock",  "deps": ["aq_shield_close"],  "soft": False, "label": "AQ Door Lock"},
            {"name": "aq_lockdown",     "deps": ["aq_shield_lock"],   "soft": False, "label": "AQ Lockdown"},
            {"name": "aq_safety_active","deps": ["aq_lockdown"],      "soft": False, "label": "AQ Safety"},
        ]
    },
    "b": {
        "steps": [
            {"name": "jzt_measure_single", "deps": [],                       "soft": False, "label": "JZT MeasSgl"},
            {"name": "jzt_preamp_single",  "deps": ["jzt_measure_single"],   "soft": False, "label": "JZT PreSgl"},
            {"name": "bb_charge_ready",    "deps": ["jzt_preamp_single"],    "soft": False, "label": "BB ChgRdy"},
            {"name": "bb_charge",          "deps": ["bb_charge_ready"],      "soft": False, "label": "BB Charge"},
            {"name": "kg_charge",          "deps": ["jzt_preamp_single"],    "soft": False, "label": "KG Charge"},
            {"name": "plz_crystal",        "deps": ["jzt_preamp_single"],    "soft": False, "label": "PLZ Crystal"},
            {"name": "wlzd_arm",           "deps": ["jzt_preamp_single"],    "soft": False, "label": "WLZD Arm"},
            {"name": "yf_fine_loop",       "deps": ["jzt_preamp_single"],    "soft": False, "label": "YF FineLp"},
            {"name": "yf_clear_energy",    "deps": ["yf_fine_loop"],         "soft": False, "label": "YF ClrEnrg"},
            {"name": "cly_measure_ready",  "deps": ["jzt_preamp_single"],    "soft": False, "label": "CLY MeasRdy"},
            {"name": "ldb_open",           "deps": ["jzt_preamp_single"],    "soft": False, "label": "LDB Open"},
            {"name": "fault_check",        "deps": ["bb_charge","kg_charge","plz_crystal","wlzd_arm","yf_clear_energy","cly_measure_ready","ldb_open"], "soft": False, "label": "Fault Check"},
            {"name": "safety_chk",         "deps": ["fault_check"],          "soft": False, "label": "Safety Chk"},
            {"name": "s3_check",           "deps": ["safety_chk"],           "soft": False, "label": "S3 Check"},
            {"name": "bb_prep_trigger",    "deps": ["s3_check"],             "soft": False, "label": "BB PrepTrg"},
            {"name": "kg_prep_trigger",    "deps": ["s3_check"],             "soft": False, "label": "KG PrepTrg"},
            {"name": "bb_trigger",         "deps": ["bb_prep_trigger","kg_prep_trigger"], "soft": False, "label": "BB Trigger"},
            {"name": "kg_trigger",         "deps": ["bb_trigger"],           "soft": False, "label": "KG Trigger"},
            {"name": "yf_pump",            "deps": ["kg_trigger"],           "soft": False, "label": "YF Pump"},
            {"name": "dcf_fire",           "deps": ["kg_trigger"],           "soft": False, "label": "DCF Fire"},
            {"name": "plz_uv",             "deps": ["yf_pump","dcf_fire"],   "soft": False, "label": "PLZ UV"},
            {"name": "cly_sample",         "deps": ["plz_uv"],              "soft": False, "label": "CLY Sample"},
            {"name": "jzt_arm",            "deps": ["cly_sample"],           "soft": False, "label": "JZT Arm"},
            {"name": "jzt_fire",           "deps": ["jzt_arm"],              "soft": False, "label": "JZT Fire"},
            {"name": "capture_uv",         "deps": ["jzt_fire"],             "soft": False, "label": "Capture UV"},
        ]
    },
    "c": {
        "steps": [
            {"name": "wlzd_acquire",       "deps": [],                       "soft": False, "label": "WLZD Acquire"},
            {"name": "zzy_read",           "deps": ["wlzd_acquire"],         "soft": False, "label": "ZZY Read"},
            {"name": "epj_read",           "deps": ["wlzd_acquire"],         "soft": False, "label": "EPJ Read"},
            {"name": "yf_read",            "deps": ["wlzd_acquire"],         "soft": False, "label": "YF Read"},
            {"name": "bb_read",            "deps": ["wlzd_acquire"],         "soft": False, "label": "BB Read"},
            {"name": "kg_read",            "deps": ["wlzd_acquire"],         "soft": False, "label": "KG Read"},
            {"name": "bm_analyze",         "deps": ["wlzd_acquire"],         "soft": False, "label": "BM Analyze"},
            {"name": "cly_read",           "deps": ["wlzd_acquire"],         "soft": False, "label": "CLY Read"},
            {"name": "wlzd_read",          "deps": ["wlzd_acquire"],         "soft": False, "label": "WLZD Read"},
            {"name": "record_data",        "deps": ["zzy_read","epj_read","yf_read","bb_read","kg_read","bm_analyze","cly_read","wlzd_read"], "soft": False, "label": "Record Data"},
            {"name": "lk_purge_on",        "deps": ["record_data"],          "soft": False, "label": "LK Purge On"},
            {"name": "yf_purge",           "deps": ["lk_purge_on"],          "soft": False, "label": "YF Purge"},
            {"name": "lk_purge_off",       "deps": ["yf_purge"],             "soft": False, "label": "LK Purge Off"},
            {"name": "wlzd_post_shot",     "deps": ["record_data"],          "soft": False, "label": "WLZD PostShot"},
            {"name": "bm_collect",         "deps": ["record_data"],          "soft": False, "label": "BM Collect"},
            {"name": "ldb_close",          "deps": ["record_data"],          "soft": False, "label": "LDB Close"},
            {"name": "bb_preionize_ready", "deps": ["record_data"],          "soft": False, "label": "BB PreIon Rdy"},
            {"name": "bb_preionize_charge","deps": ["bb_preionize_ready"],   "soft": False, "label": "BB PreIon Chg"},
            {"name": "bb_power_off",       "deps": ["bb_preionize_charge"],  "soft": False, "label": "BB PowerOff"},
            {"name": "zzy_standby",        "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "ZZY Standby"},
            {"name": "epj_standby",        "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "EPJ Standby"},
            {"name": "yf_standby",         "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "YF Standby"},
            {"name": "kg_standby",         "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "KG Standby"},
            {"name": "cly_standby",        "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "CLY Standby"},
            {"name": "bb_standby",         "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "BB Standby"},
            {"name": "jzt_reset",          "deps": ["lk_purge_off","wlzd_post_shot","bm_collect","ldb_close","bb_power_off"], "soft": False, "label": "JZT Reset"},
            {"name": "aq_alarm_off",       "deps": ["zzy_standby","epj_standby","yf_standby","kg_standby","cly_standby","bb_standby","jzt_reset"], "soft": False, "label": "AQ Alarm Off"},
            {"name": "aq_light_off",       "deps": ["aq_alarm_off"],         "soft": False, "label": "AQ Light Off"},
            {"name": "aq_release",         "deps": ["aq_light_off"],         "soft": False, "label": "AQ Release"},
            {"name": "mx_calibrate",       "deps": ["aq_release"],           "soft": False, "label": "MX Calibrate"},
            {"name": "zk_stop_monitor",    "deps": ["mx_calibrate"],         "soft": False, "label": "ZK StopMon"},
        ]
    },
}


def _dev_state_str(state: tango.DevState) -> str:
    """将 tango.DevState 转换为字符串（如 'ON', 'FAULT', 'STANDBY'）。"""
    return str(state).split(".")[-1]


# ── FastAPI 应用 ─────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    global _broadcaster, _registry, _jzgk, _queue

    _broadcaster = EventBroadcaster()

    async with SimulatorRegistry.in_process(sim_speed=_sim_speed) as registry:
        _registry = registry
        _jzgk = JZGK(_registry)
        _queue = ShotQueue(_jzgk, _broadcaster)

        # ── 挂载事件钩子 ────────────────────────────────────────

        def on_state_changed(name: str, state: tango.DevState):
            asyncio.create_task(_broadcaster.broadcast("subsystem_state", {
                "name": name,
                "state": _dev_state_str(state),
            }))

        def on_safety(name: str, signal_id: str, message: str):
            asyncio.create_task(_broadcaster.broadcast("safety", {
                "signal": signal_id,
                "source": name,
                "msg": message,
                "ts": time.time(),
            }))

        def on_phase_change(old, new):
            asyncio.create_task(_broadcaster.broadcast("jzgk_state", {
                "old": old.value,
                "new": new.value,
            }))

        def on_step_start(phase_name: str, step_name: str):
            phase = phase_name.replace("phase_", "")
            asyncio.create_task(_broadcaster.broadcast("step_start", {
                "phase": phase,
                "name": step_name,
            }))

        def on_step_done(phase_name: str, result):
            phase = phase_name.replace("phase_", "")
            asyncio.create_task(_broadcaster.broadcast("step_done", {
                "phase": phase,
                "name": result.name,
                "elapsed": result.elapsed,
                "success": result.success,
                "soft": _PHASE_DAGS.get(phase, {}).get("steps") and any(
                    s["name"] == result.name and s["soft"]
                    for s in _PHASE_DAGS[phase]["steps"]
                ),
            }))

        _registry.on_state_changed(on_state_changed)
        _registry.on_safety(on_safety)
        _jzgk.on_phase_change(on_phase_change)
        _jzgk.set_step_callbacks(on_step_start, on_step_done)

        # ── 日志桥接 ────────────────────────────────────────────
        handler = SSELogHandler(_broadcaster)
        handler.setFormatter(logging.Formatter("%(message)s"))
        class _NoSSEFilter(logging.Filter):
            def filter(self, record):
                return not record.name.startswith(("sse_starlette", "uvicorn"))
        handler.addFilter(_NoSSEFilter())
        logging.getLogger().addHandler(handler)
        logging.getLogger().setLevel(logging.DEBUG)
        logging.getLogger("sse_starlette").setLevel(logging.WARNING)
        logging.getLogger("uvicorn").setLevel(logging.WARNING)

        # ── 启动发次工作循环 ─────────────────────────────────────
        asyncio.create_task(_queue.run_loop())

        logging.getLogger(__name__).info(
            "JZGK Web 控制台启动，仿真速度 %.1fx，访问 http://localhost:8000",
            _sim_speed,
        )

        yield


app = FastAPI(title="JZGK Web 控制台", lifespan=lifespan)

STATIC_DIR = Path(__file__).parent.parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR, html=False), name="static")


@app.middleware("http")
async def no_cache_static(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/static/") or request.url.path == "/":
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


# ── 路由 ─────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def index():
    html_file = STATIC_DIR / "index.html"
    return FileResponse(html_file)


@app.get("/api/state")
async def get_state():
    """系统快照：供新客户端连接时初始加载。"""
    subsystems = []
    for name, sim in _registry.all().items():
        subsystems.append({
            "name": name,
            "state": _dev_state_str(sim.dev_state),
            "metrics": _get_metrics(name, sim),
        })
    return {
        "jzgk_state": _jzgk.state.value,
        "sim_speed": _sim_speed,
        "queue": _queue.snapshot_queue(),
        "subsystems": subsystems,
        "history": _queue.get_history(),
    }


@app.get("/api/events")
async def sse_events(request: Request):
    """SSE 推送流。"""
    async def generator():
        async for line in _broadcaster.subscribe():
            if await request.is_disconnected():
                break
            yield line
    return EventSourceResponse(generator())


@app.get("/api/workflow/{phase}")
async def get_workflow(phase: str):
    """返回指定阶段的 DAG 结构（供前端 dagre 渲染）。"""
    if phase not in _PHASE_DAGS:
        raise HTTPException(404, f"未知阶段: {phase}")
    return _PHASE_DAGS[phase]


# ── 控制命令 ─────────────────────────────────────────────────

class EnqueueRequest(BaseModel):
    recipe_id: str = "SHOT_001"
    pump_energy_j: float = 500.0
    kg_voltage_kv: float = 8.0
    plz_pitch_mrad: float = 0.05
    plz_yaw_mrad: float = -0.02
    target_id: str = "TARGET_001"
    phase_a_timeout: float = 60.0
    phase_b_timeout: float = 30.0
    phase_c_timeout: float = 30.0


@app.post("/api/shot/enqueue")
async def enqueue_shot(req: EnqueueRequest):
    recipe = ShotRecipe(
        recipe_id=req.recipe_id,
        pump_energy_j=req.pump_energy_j,
        kg_voltage_kv=req.kg_voltage_kv,
        plz_pitch_mrad=req.plz_pitch_mrad,
        plz_yaw_mrad=req.plz_yaw_mrad,
        target_id=req.target_id,
        phase_a_timeout=req.phase_a_timeout,
        phase_b_timeout=req.phase_b_timeout,
        phase_c_timeout=req.phase_c_timeout,
    )
    await _queue.enqueue(recipe)
    return {"ok": True, "recipe_id": recipe.recipe_id}


@app.post("/api/shot/cancel/{recipe_id}")
async def cancel_shot(recipe_id: str):
    ok = await _queue.cancel_pending(recipe_id)
    return {"ok": ok}


@app.post("/api/emergency-stop")
async def emergency_stop():
    if _jzgk.state != JZGKState.IDLE:
        await _registry.get("AQ").simulate_intrusion()
        return {"ok": True, "action": "s1_injected"}
    return {"ok": False, "reason": "already_idle"}


@app.post("/api/inject-fault")
async def inject_fault(body: dict):
    subsystem = body.get("subsystem", "")
    reason = body.get("reason", "Web UI 手动注入故障")
    try:
        sim = _registry.get(subsystem)
    except KeyError:
        raise HTTPException(404, f"子系统 {subsystem} 不存在")
    await sim.inject_fault(reason)
    return {"ok": True}


@app.post("/api/subsystem/{name}/reset")
async def reset_subsystem(name: str):
    """将指定子系统从 FAULT 状态复位到 STANDBY。"""
    try:
        sim = _registry.get(name)
    except KeyError:
        raise HTTPException(404, f"子系统 {name} 不存在")
    if not sim.is_fault:
        return {"ok": False, "reason": "not_in_fault", "state": _dev_state_str(sim.dev_state)}
    await sim.reset()
    return {"ok": True, "state": _dev_state_str(sim.dev_state)}


@app.post("/api/subsystem/reset-all")
async def reset_all_faults():
    """将所有处于 FAULT 状态的子系统并发复位。"""
    faulted = [name for name, sim in _registry.all().items() if sim.is_fault]
    if not faulted:
        return {"ok": True, "reset": []}
    await asyncio.gather(*[_registry.get(n).reset() for n in faulted], return_exceptions=True)
    return {"ok": True, "reset": faulted}


@app.post("/api/inject-s1")
async def inject_s1():
    """直接触发 S1 紧急停机：AQ 进入 FAULT，需操作员手动复位。"""
    await _registry.get("AQ").simulate_intrusion()
    return {"ok": True}


@app.post("/api/config")
async def set_config(body: dict):
    """运行时调整仿真速度（不重建模拟器）。"""
    global _sim_speed
    speed = body.get("sim_speed")
    if speed is not None:
        speed = float(speed)
        if speed <= 0:
            raise HTTPException(400, "sim_speed 必须 > 0")
        _sim_speed = speed
        await _registry.set_sim_speed(speed)
        await _broadcaster.broadcast("config_update", {"sim_speed": speed})
        logging.getLogger(__name__).info("仿真速度已调整为 %.1fx", speed)
    return {"ok": True, "sim_speed": _sim_speed}


@app.get("/api/subsystem/{name}/metrics")
async def get_subsystem_metrics(name: str):
    try:
        sim = _registry.get(name)
    except KeyError:
        raise HTTPException(404, f"子系统 {name} 不存在")
    return {
        "name": name,
        "state": _dev_state_str(sim.dev_state),
        "metrics": _get_metrics(name, sim),
    }


# ── 辅助：获取子系统关键指标 ─────────────────────────────────

def _get_metrics(name: str, sim) -> dict:
    m = {}
    try:
        if name == "BB":
            m["energy_setpoint"] = getattr(sim, "energy_setpoint", None)
            m["charge_progress"] = getattr(sim, "charge_progress", None)
            m["charge_voltage"]  = getattr(sim, "charge_voltage", None)
            m["actual_energy"]   = getattr(sim, "actual_energy", None)
        elif name == "ZK":
            m["pressure"]    = getattr(sim, "pressure", None)
            m["vacuum_ok"]   = getattr(sim, "vacuum_ok", None)
            m["pump_active"] = getattr(sim, "pump_active", None)
        elif name == "DCF":
            m["alignment_status"] = getattr(sim, "alignment_status", None)
            m["alignment_error"]  = getattr(sim, "alignment_error", None)
            m["output_energy"]    = getattr(sim, "output_energy", None)
            m["shot_ready"]       = getattr(sim, "shot_ready", None)
        elif name == "KG":
            m["charge_voltage"] = getattr(sim, "charge_voltage", None)
            m["trigger_delay"]  = getattr(sim, "trigger_delay", None)
        elif name == "BM":
            m["positioned"]      = getattr(sim, "positioned", None)
            m["alignment_error"] = getattr(sim, "alignment_error", None)
            m["guidance_active"] = getattr(sim, "guidance_active", None)
        elif name == "LDB":
            m["deployed"]       = getattr(sim, "deployed", None)
            m["fuel_pressure"]  = getattr(sim, "fuel_pressure", None)
            m["temperature"]    = getattr(sim, "temperature", None)
        elif name == "LK":
            m["purge_flow_rate"] = getattr(sim, "purge_flow_rate", None)
            m["temperature"]     = getattr(sim, "temperature", None)
            m["purging"]         = getattr(sim, "purging", None)
        elif name == "MX":
            m["model_version"]      = getattr(sim, "model_version", None)
            m["calibration_status"] = getattr(sim, "calibration_status", None)
            m["correction_factors"] = getattr(sim, "correction_factors", None)
        elif name == "AQ":
            m["door_status"]          = getattr(sim, "door_status", None)
            m["person_count"]         = getattr(sim, "person_count", None)
            m["area_clear"]           = getattr(sim, "area_clear", None)
            m["emergency_stop_active"]= getattr(sim, "emergency_stop_active", None)
    except Exception:
        pass
    return m


# ── 入口 ─────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="JZGK Web 控制台")
    parser.add_argument("--speed", type=float, default=5.0, help="仿真速度倍率")
    parser.add_argument("--port",  type=int,   default=8000, help="监听端口")
    parser.add_argument("--host",  type=str,   default="127.0.0.1", help="监听地址")
    args = parser.parse_args()

    global _sim_speed
    _sim_speed = args.speed

    uvicorn.run(
        "api.main:app",
        host=args.host,
        port=args.port,
        reload=False,
        log_level="warning",
    )


if __name__ == "__main__":
    main()
