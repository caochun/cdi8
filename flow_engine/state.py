"""
FlowState: 追踪发射流程的执行状态。

状态机：IDLE → RUNNING → SUCCESS / FAILED / ABORTED
每个 Stage / Step 都有独立状态记录。
"""

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Optional


class Status(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED  = "FAILED"
    SKIPPED = "SKIPPED"   # non-critical 失败后跳过并继续


@dataclass
class StepResult:
    name: str
    device: str
    command: str
    status: Status = Status.PENDING
    return_code: Optional[int] = None
    error: Optional[str] = None
    started_at: Optional[float] = None
    finished_at: Optional[float] = None

    @property
    def elapsed(self) -> Optional[float]:
        if self.started_at and self.finished_at:
            return round(self.finished_at - self.started_at, 3)
        return None


@dataclass
class StageResult:
    stage_id: str
    name: str
    status: Status = Status.PENDING
    steps: List[StepResult] = field(default_factory=list)
    started_at: Optional[float] = None
    finished_at: Optional[float] = None

    @property
    def elapsed(self) -> Optional[float]:
        if self.started_at and self.finished_at:
            return round(self.finished_at - self.started_at, 3)
        return None


@dataclass
class PhaseResult:
    phase_id: int
    phase_name: str
    status: Status = Status.PENDING
    stages: List[StageResult] = field(default_factory=list)
    started_at: Optional[float] = None
    finished_at: Optional[float] = None
    abort_reason: Optional[str] = None

    @property
    def elapsed(self) -> Optional[float]:
        if self.started_at and self.finished_at:
            return round(self.finished_at - self.started_at, 3)
        return None


class FlowState:
    """整个三阶段发射流程的状态容器。"""

    def __init__(self, shot_id: int, task_id: int):
        self.shot_id = shot_id
        self.task_id = task_id
        self.overall_status: Status = Status.IDLE if hasattr(Status, 'IDLE') else Status.PENDING
        self.phases: List[PhaseResult] = []
        self.created_at: float = time.time()

    def add_phase(self, phase_result: PhaseResult):
        self.phases.append(phase_result)

    def summary(self) -> Dict:
        return {
            "shot_id": self.shot_id,
            "task_id": self.task_id,
            "phases": [
                {
                    "phase_id": p.phase_id,
                    "name": p.phase_name,
                    "status": p.status,
                    "elapsed": p.elapsed,
                    "abort_reason": p.abort_reason,
                    "stages": [
                        {
                            "id": s.stage_id,
                            "name": s.name,
                            "status": s.status,
                            "elapsed": s.elapsed,
                            "steps": [
                                {
                                    "name": r.name,
                                    "status": r.status,
                                    "return_code": r.return_code,
                                    "elapsed": r.elapsed,
                                    "error": r.error,
                                }
                                for r in s.steps
                            ],
                        }
                        for s in p.stages
                    ],
                }
                for p in self.phases
            ],
        }
