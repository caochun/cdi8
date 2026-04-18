"""
RecipeLoader: 加载并校验 YAML 配方文件。

配方结构：
  phase: str
  phase_id: int
  stages:
    - id: str
      name: str
      steps:
        - name: str
          device: str
          command: str
          timeout: int      # 秒
          critical: bool    # True = 失败触发熔断；False = 仅告警
"""

import os
from pathlib import Path
from typing import Any, Dict, List

import yaml


RECIPES_DIR = Path(__file__).parent / "recipes"


def load_recipe(phase_id: int) -> Dict[str, Any]:
    """按 phase_id (1/2/3) 加载对应 YAML 配方，返回解析后的 dict。"""
    candidates = sorted(RECIPES_DIR.glob(f"phase{phase_id}_*.yaml"))
    if not candidates:
        raise FileNotFoundError(
            f"No recipe file found for phase_id={phase_id} in {RECIPES_DIR}"
        )
    path = candidates[0]
    with open(path, encoding="utf-8") as f:
        recipe = yaml.safe_load(f)
    _validate(recipe, path)
    return recipe


def load_all_recipes() -> List[Dict[str, Any]]:
    return [load_recipe(i) for i in (1, 2, 3)]


def _validate(recipe: Dict, path: Path):
    required_top = {"phase", "phase_id", "stages"}
    missing = required_top - recipe.keys()
    if missing:
        raise ValueError(f"Recipe {path.name} missing fields: {missing}")

    for stage in recipe["stages"]:
        for key in ("id", "name", "steps"):
            if key not in stage:
                raise ValueError(
                    f"Stage missing '{key}' in {path.name}: {stage}"
                )
        for step in stage["steps"]:
            for key in ("name", "device", "command", "timeout", "critical"):
                if key not in step:
                    raise ValueError(
                        f"Step missing '{key}' in stage {stage['id']}: {step}"
                    )
