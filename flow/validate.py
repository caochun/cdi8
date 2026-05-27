"""
GXLF 流程定义校验脚本

用法:
    python flow/validate.py flow/gxlf-firing-flow.yaml

校验项:
    1. Schema 校验 — JSON Schema 验证 YAML 结构和类型
    2. DAG 环检测 — 构建有向图检测环
    3. 可达性 — 从入口节点出发所有节点可达
    4. 终点收敛 — 所有叶子节点能到达结束节点
    5. join 完备性 — join 的 depends 引用都存在
    6. 系统引用 — 节点 target.system 在 systems 中注册
    7. 模板引用 — timed_sequence 引用的 template 存在
    8. ID 唯一性 — 所有节点 id 不重复
    9. 关键路径 — 基于 timeout 的最长路径估算
"""

import json
import sys
from pathlib import Path

import yaml

try:
    import jsonschema
except ImportError:
    jsonschema = None

try:
    import networkx as nx
except ImportError:
    nx = None


SCHEMA_PATH = Path(__file__).parent / "schema" / "flow-definition.schema.json"

PASS = "\033[32m✓\033[0m"
FAIL = "\033[31m✗\033[0m"
WARN = "\033[33m⚠\033[0m"


def load_flow(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_schema() -> dict:
    with open(SCHEMA_PATH, encoding="utf-8") as f:
        return json.load(f)


def parse_timeout(t) -> float:
    if t is None:
        return 0.0
    s = str(t).strip()
    if s.endswith("min"):
        return float(s[:-3]) * 60
    if s.endswith("s"):
        return float(s[:-1])
    return float(s)


def check_schema(flow: dict) -> list[str]:
    if jsonschema is None:
        return [f"{WARN} jsonschema 未安装，跳过 Schema 校验 (pip install jsonschema)"]

    schema = load_schema()
    errors = []
    validator = jsonschema.Draft202012Validator(schema)
    for err in validator.iter_errors(flow):
        path = ".".join(str(p) for p in err.absolute_path) or "(root)"
        errors.append(f"  {path}: {err.message}")

    if errors:
        return [f"{FAIL} Schema 校验失败 ({len(errors)} 项错误):"] + errors[:20]
    return [f"{PASS} Schema 校验通过"]


def build_graph(nodes: dict):
    if nx is None:
        return None, None

    G = nx.DiGraph()
    name_to_id = {}

    for name, node in nodes.items():
        nid = node["id"]
        G.add_node(name, **node)
        name_to_id[name] = nid

    for name, node in nodes.items():
        for dep in node.get("depends", []):
            if dep in nodes:
                G.add_edge(dep, name)

    return G, name_to_id


def check_dag_acyclic(G) -> list[str]:
    if G is None:
        return [f"{WARN} networkx 未安装，跳过 DAG 分析 (pip install networkx)"]

    if nx.is_directed_acyclic_graph(G):
        return [f"{PASS} DAG 无环 ({G.number_of_nodes()} 节点, {G.number_of_edges()} 条边)"]

    cycles = list(nx.simple_cycles(G))
    msgs = [f"{FAIL} DAG 存在 {len(cycles)} 个环:"]
    for c in cycles[:5]:
        msgs.append(f"  {' → '.join(c)} → {c[0]}")
    return msgs


def check_reachability(G, nodes: dict) -> list[str]:
    if G is None:
        return []

    entries = [n for n, node in nodes.items() if not node.get("depends")]
    if not entries:
        return [f"{FAIL} 没有入口节点 (depends 为空的节点)"]

    reachable = set()
    for entry in entries:
        reachable |= nx.descendants(G, entry) | {entry}

    unreachable = set(nodes.keys()) - reachable
    if unreachable:
        return [f"{FAIL} {len(unreachable)} 个节点从入口不可达: {', '.join(sorted(unreachable))}"]

    return [f"{PASS} 所有节点从入口 [{', '.join(entries)}] 可达"]


def check_convergence(G, nodes: dict) -> list[str]:
    if G is None:
        return []

    exits = [n for n in G.nodes() if G.out_degree(n) == 0]
    if not exits:
        return [f"{FAIL} 没有终点节点 (出度为0的节点)"]

    if len(exits) > 1:
        return [f"{WARN} 多个终点节点: {', '.join(exits)}"]

    for name in nodes:
        if name in exits:
            continue
        for exit_node in exits:
            if nx.has_path(G, name, exit_node):
                break
        else:
            return [f"{FAIL} 节点 '{name}' 无法到达任何终点"]

    return [f"{PASS} 所有节点收敛到终点 [{', '.join(exits)}]"]


def check_depends_exist(nodes: dict) -> list[str]:
    errors = []
    for name, node in nodes.items():
        for dep in node.get("depends", []):
            if dep not in nodes:
                errors.append(f"  节点 '{name}' 依赖不存在的节点 '{dep}'")

    if errors:
        return [f"{FAIL} 依赖引用错误 ({len(errors)} 项):"] + errors
    return [f"{PASS} 所有 depends 引用的节点都存在"]


def check_system_refs(nodes: dict, systems: dict) -> list[str]:
    errors = []
    for name, node in nodes.items():
        target = node.get("target", {})
        for sys_name in target.get("system", []):
            if sys_name not in systems:
                errors.append(f"  节点 '{name}' 引用未注册系统 '{sys_name}'")

    if errors:
        return [f"{FAIL} 系统引用错误 ({len(errors)} 项):"] + errors
    return [f"{PASS} 所有系统引用已注册 ({len(systems)} 个系统)"]


def check_template_refs(nodes: dict, templates: dict) -> list[str]:
    errors = []
    for name, node in nodes.items():
        tmpl = node.get("template")
        if tmpl and tmpl not in (templates or {}):
            errors.append(f"  节点 '{name}' 引用不存在的模板 '{tmpl}'")

    if errors:
        return [f"{FAIL} 模板引用错误:"] + errors
    return [f"{PASS} 所有模板引用存在"]


def check_id_unique(nodes: dict) -> list[str]:
    ids = {}
    for name, node in nodes.items():
        nid = node["id"]
        if nid in ids:
            return [f"{FAIL} ID 重复: '{nid}' 被 '{ids[nid]}' 和 '{name}' 同时使用"]
        ids[nid] = name

    return [f"{PASS} 所有节点 ID 唯一 ({len(ids)} 个)"]


def check_critical_path(G, nodes: dict) -> list[str]:
    if G is None:
        return []

    if not nx.is_directed_acyclic_graph(G):
        return [f"{WARN} DAG 有环，无法计算关键路径"]

    longest = {}
    path_trace = {}

    for node in nx.topological_sort(G):
        timeout = parse_timeout(nodes[node].get("timeout"))
        preds = list(G.predecessors(node))
        if not preds:
            longest[node] = timeout
            path_trace[node] = [node]
        else:
            best_pred = max(preds, key=lambda p: longest.get(p, 0))
            longest[node] = longest.get(best_pred, 0) + timeout
            path_trace[node] = path_trace.get(best_pred, []) + [node]

    if not longest:
        return []

    end_node = max(longest, key=longest.get)
    total_seconds = longest[end_node]
    total_minutes = total_seconds / 60
    path = path_trace[end_node]

    path_short = path[:3] + ["..."] + path[-2:] if len(path) > 6 else path

    return [f"{WARN} 关键路径估算: {total_minutes:.1f}min ({total_seconds:.0f}s)",
            f"  路径: {' → '.join(path_short)}"]


def main():
    if len(sys.argv) < 2:
        print(f"用法: python {sys.argv[0]} <flow-definition.yaml>")
        sys.exit(1)

    path = sys.argv[1]
    print(f"\n{'='*60}")
    print(f"  GXLF 流程定义校验: {path}")
    print(f"{'='*60}\n")

    flow = load_flow(path)
    nodes = flow.get("nodes", {})
    systems = flow.get("systems", {})
    templates = flow.get("templates", {})

    results = []
    has_failure = False

    checks = [
        ("1. Schema 校验", lambda: check_schema(flow)),
        ("2. DAG 环检测", lambda: (
            build_result := build_graph(nodes),
            check_dag_acyclic(build_result[0])
        )[-1]),
        ("3. 可达性检查", lambda: check_reachability(*build_graph(nodes)[:1], nodes)),
        ("4. 终点收敛", lambda: check_convergence(*build_graph(nodes)[:1], nodes)),
        ("5. 依赖引用检查", lambda: check_depends_exist(nodes)),
        ("6. 系统引用检查", lambda: check_system_refs(nodes, systems)),
        ("7. 模板引用检查", lambda: check_template_refs(nodes, templates)),
        ("8. ID 唯一性", lambda: check_id_unique(nodes)),
        ("9. 关键路径", lambda: check_critical_path(*build_graph(nodes)[:1], nodes)),
    ]

    G, _ = build_graph(nodes)

    all_results = []
    all_results += check_schema(flow)
    all_results += check_dag_acyclic(G)
    all_results += check_reachability(G, nodes)
    all_results += check_convergence(G, nodes)
    all_results += check_depends_exist(nodes)
    all_results += check_system_refs(nodes, systems)
    all_results += check_template_refs(nodes, templates)
    all_results += check_id_unique(nodes)
    all_results += check_critical_path(G, nodes)

    for line in all_results:
        print(line)
        if FAIL in line:
            has_failure = True

    print(f"\n{'='*60}")
    node_counts = {"action": 0, "join": 0, "timed_sequence": 0}
    for node in nodes.values():
        node_counts[node["type"]] = node_counts.get(node["type"], 0) + 1
    print(f"  节点统计: {len(nodes)} 总计 "
          f"(action: {node_counts.get('action',0)}, "
          f"join: {node_counts.get('join',0)}, "
          f"timed_sequence: {node_counts.get('timed_sequence',0)})")
    print(f"  系统注册: {len(systems)} 类")
    if G:
        print(f"  DAG 边数: {G.number_of_edges()}")
    print(f"{'='*60}\n")

    sys.exit(1 if has_failure else 0)


if __name__ == "__main__":
    main()
