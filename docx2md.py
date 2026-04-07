"""
docx → markdown 转换器
使用 python-docx 直接解析，正确处理多级编号标题、表格、图片。
"""
import re
import subprocess
import sys
from pathlib import Path

from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph
from docx.oxml.ns import qn

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def wns(tag: str) -> str:
    return f"{{{W}}}{tag}"


# ── 标题层级检测 ──────────────────────────────────────────────

def get_outline_level(para: Paragraph) -> int:
    """
    返回 1-6 表示标题层级，0 表示正文。
    依次检查：样式名、样式链中的 outlineLvl、段落直接格式。
    """
    style_name = para.style.name if para.style else ""

    # 标准样式名 "Heading 1" / "标题 1"
    m = re.match(r"(?:Heading|标题)\s+(\d+)", style_name, re.IGNORECASE)
    if m:
        return int(m.group(1))

    # 沿继承链找 outlineLvl
    style = para.style
    while style:
        el = style.element
        ol = el.find(f".//{wns('outlineLvl')}")
        if ol is not None:
            val = ol.get(wns("val"))
            if val is not None:
                lvl = int(val)
                if lvl < 9:          # 9 = 正文
                    return lvl + 1
        style = style.base_style

    # 段落直接格式
    pPr = para._element.find(wns("pPr"))
    if pPr is not None:
        ol = pPr.find(wns("outlineLvl"))
        if ol is not None:
            val = ol.get(wns("val"))
            if val is not None:
                lvl = int(val)
                if lvl < 9:
                    return lvl + 1

    return 0


# ── 行内格式 ─────────────────────────────────────────────────

def runs_to_text(para: Paragraph) -> str:
    # 先把 runs 按 (bold, italic) 合并，避免 **a****b** 这种双星号
    groups: list[tuple[bool, bool, str]] = []
    for run in para.runs:
        t = run.text
        if not t:
            continue
        b, i = bool(run.bold), bool(run.italic)
        if groups and groups[-1][0] == b and groups[-1][1] == i:
            groups[-1] = (b, i, groups[-1][2] + t)
        else:
            groups.append((b, i, t))

    parts = []
    for bold, italic, text in groups:
        if bold and italic:
            text = f"***{text}***"
        elif bold:
            text = f"**{text}**"
        elif italic:
            text = f"*{text}*"
        parts.append(text)
    return "".join(parts)


# ── 图片提取 ─────────────────────────────────────────────────

VML_NS = "urn:schemas-microsoft-com:vml"
R_EMBED = f"{{{R_NS}}}embed"
R_ID = f"{{{R_NS}}}id"

EXT_MAP = {"jpeg": "jpg", "x-emf": "emf", "x-wmf": "wmf", "x-png": "png"}


def _save_rel_image(rel_id: str, doc, images_dir: Path, counter: list) -> str | None:
    """Save an image by relationship ID, return markdown ref or None."""
    try:
        rel = doc.part.rels[rel_id]
        data = rel.target_part.blob
        ct = rel.target_part.content_type
        ext = ct.split("/")[-1]
        ext = EXT_MAP.get(ext, ext)
        counter[0] += 1
        filename = f"image_{counter[0]:03d}.{ext}"
        (images_dir / filename).write_bytes(data)
        return f"![]({images_dir.name}/{filename})"
    except Exception:
        return None


def extract_images_from_para(para: Paragraph, doc, images_dir: Path, counter: list) -> list[str]:
    """返回该段落中图片对应的 markdown 引用列表（DrawingML + VML）。"""
    refs = []
    seen_ids: set[str] = set()

    # DrawingML: <a:blip r:embed="rIdN"/>
    for blip in para._element.iter(qn("a:blip")):
        embed = blip.get(R_EMBED)
        if not embed or embed in seen_ids:
            continue
        seen_ids.add(embed)
        ref = _save_rel_image(embed, doc, images_dir, counter)
        if ref:
            refs.append(ref)

    # VML: <v:imagedata r:id="rIdN"/>
    for imgdata in para._element.iter(f"{{{VML_NS}}}imagedata"):
        rid = imgdata.get(R_ID)
        if not rid or rid in seen_ids:
            continue
        seen_ids.add(rid)
        ref = _save_rel_image(rid, doc, images_dir, counter)
        if ref:
            refs.append(ref)

    return refs


# ── 列表检测 ─────────────────────────────────────────────────

def get_list_level(para: Paragraph) -> int | None:
    """如果是列表项，返回缩进级别（0-based）；否则返回 None。"""
    pPr = para._element.find(wns("pPr"))
    if pPr is None:
        return None
    numPr = pPr.find(wns("numPr"))
    if numPr is None:
        return None
    ilvl = numPr.find(wns("ilvl"))
    val = ilvl.get(wns("val")) if ilvl is not None else "0"
    return int(val)


# ── 表格转换 ─────────────────────────────────────────────────

def _tc_text(tc_el) -> str:
    """从 <w:tc> XML 元素提取纯文本。"""
    parts = []
    for p in tc_el.findall(f".//{wns('p')}"):
        seg = []
        for r in p.findall(f".//{wns('r')}"):
            t = r.find(wns("t"))
            if t is not None and t.text:
                seg.append(t.text)
        if seg:
            parts.append("".join(seg))
    return " ".join(parts).strip()


def _grid_span(tc_el) -> int:
    """单元格水平跨列数（默认 1）。"""
    tcPr = tc_el.find(wns("tcPr"))
    if tcPr is None:
        return 1
    gs = tcPr.find(wns("gridSpan"))
    if gs is None:
        return 1
    return int(gs.get(wns("val"), "1"))


def _is_vmerge_cont(tc_el) -> bool:
    """是否为垂直合并的续行单元格（不含起始行）。"""
    tcPr = tc_el.find(wns("tcPr"))
    if tcPr is None:
        return False
    vm = tcPr.find(wns("vMerge"))
    if vm is None:
        return False
    # val="restart" 是合并起始行，无 val 属性或为空字符串是续行
    return vm.get(wns("val"), "") != "restart"


def table_to_markdown(table: Table) -> str:
    rows_data = []
    for tr in table._element.findall(wns("tr")):
        cells = []
        for tc in tr.findall(wns("tc")):
            span = _grid_span(tc)
            if _is_vmerge_cont(tc):
                # 垂直合并续行：用空格占位保持列对齐
                cells.extend([""] * span)
            else:
                cells.append(_tc_text(tc))
                cells.extend([""] * (span - 1))  # 水平合并后续列留空
        rows_data.append(cells)

    if not rows_data:
        return ""

    col_count = max(len(r) for r in rows_data)
    lines = []
    for i, cells in enumerate(rows_data):
        while len(cells) < col_count:
            cells.append("")
        lines.append("| " + " | ".join(cells) + " |")
        if i == 0:
            lines.append("| " + " | ".join(["---"] * col_count) + " |")
    return "\n".join(lines)


# ── 主转换 ───────────────────────────────────────────────────

def convert_body(doc, images_dir: Path) -> str:
    counter = [0]
    lines = []
    prev_blank = True  # 避免连续空行

    def emit(text: str):
        nonlocal prev_blank
        is_blank = not text.strip()
        if is_blank and prev_blank:
            return
        lines.append(text)
        prev_blank = is_blank

    for child in doc.element.body:
        local = child.tag.split("}")[-1] if "}" in child.tag else child.tag

        if local == "p":
            para = Paragraph(child, doc)

            # 提取图片
            img_refs = extract_images_from_para(para, doc, images_dir, counter)
            for ref in img_refs:
                emit("")
                emit(ref)
                emit("")
                continue

            text = runs_to_text(para)

            # 标题
            level = get_outline_level(para)
            if level > 0:
                emit("")
                emit(f"{'#' * level} {text}")
                emit("")
                continue

            # 列表项（只处理非标题的列表）
            list_lvl = get_list_level(para)
            if list_lvl is not None and text.strip():
                emit("  " * list_lvl + "- " + text)
                continue

            # 普通段落
            if text.strip():
                emit(text)
                emit("")   # Word 用行间距隔开段落，markdown 需要显式空行
            else:
                emit("")

        elif local == "tbl":
            table = Table(child, doc)
            emit("")
            emit(table_to_markdown(table))
            emit("")

    return "\n".join(lines)


# ── EMF → SVG ────────────────────────────────────────────────

def convert_emf_to_svg(images_dir: Path) -> int:
    converted = 0
    for emf in images_dir.glob("*.emf"):
        svg = emf.with_suffix(".svg")
        r = subprocess.run(
            ["emf2svg-conv", "-i", str(emf), "-o", str(svg)],
            capture_output=True,
        )
        if r.returncode == 0:
            emf.unlink()
            converted += 1
        else:
            print(f"  [warn] EMF 转换失败: {emf.name}", file=sys.stderr)
    return converted


def update_md_emf_refs(md_path: Path):
    text = md_path.read_text(encoding="utf-8")
    updated = text.replace(".emf)", ".svg)")
    if updated != text:
        md_path.write_text(updated, encoding="utf-8")


# ── 入口 ─────────────────────────────────────────────────────

def convert_docx_to_markdown(docx_path: str) -> tuple[str, int, str]:
    docx = Path(docx_path)
    output_path = docx.with_suffix(".md")
    images_dir = docx.parent / (docx.stem + "_images")
    images_dir.mkdir(exist_ok=True)

    doc = Document(docx_path)
    md = convert_body(doc, images_dir)

    output_path.write_text(md, encoding="utf-8")

    image_count = len(list(images_dir.glob("*")))
    return str(output_path), image_count, str(images_dir)


def main():
    docx_files = sys.argv[1:] if len(sys.argv) > 1 else list(Path(".").glob("*.docx"))
    if not docx_files:
        print("没有找到 .docx 文件")
        return
    for f in docx_files:
        print(f"转换: {f}")
        out, img_count, img_dir = convert_docx_to_markdown(str(f))
        print(f"  => {out}  ({img_count} 张图片)")

        svg_count = convert_emf_to_svg(Path(img_dir))
        if svg_count:
            update_md_emf_refs(Path(out))
            print(f"  => EMF → SVG: {svg_count} 张")


if __name__ == "__main__":
    main()
