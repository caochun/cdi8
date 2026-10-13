"""Native Qt graphics items for the nested state graph (no SVG DOM or WebView)."""
import math

from taurus.external.qt import Qt

from ..machine import STATES
from .model import edges, edge_matches


COLORS = {"未就绪": "#54758e", "就绪": "#237b72", "出光": "#b37818", "设备异常": "#b9424e"}
# Geometry only: transition rules and state names come from machine.py.
LAYOUT = {
    "unchecked": (135, 80), "selfTestOk": (30, 235), "checkFailed": (245, 235),
    "configIdle": (135, 80), "configOk": (135, 235),
    "collectIdle": (135, 80), "collectOk": (30, 235), "collectFailed": (245, 235),
    "faultDetected": (155, 80), "faultLock": (10, 235), "commFault": (155, 235), "timeoutFault": (300, 235),
}


def text_item(parent, text, size=15, color="#243d4a", width=None):
    item = Qt.QGraphicsTextItem(text, parent)
    font = Qt.QFont()
    font.setPointSize(size)
    item.setFont(font)
    item.setDefaultTextColor(Qt.QColor(color))
    if width is not None:
        item.setTextWidth(width)
    return item


class StateItem(Qt.QGraphicsRectItem):
    def __init__(self, state_id, parent):
        self.width = 140 if STATES[state_id][0] == "设备异常" else 175
        super().__init__(0, 0, self.width, 62, parent)
        self.state_id = state_id
        self.active = False
        self.setData(0, state_id)
        self.setZValue(3)
        self.setCursor(Qt.Qt.CursorShape.PointingHandCursor)
        self.setToolTip(f"{STATES[state_id][0]} / {STATES[state_id][1]}\n编号：{state_id}")
        self.label = text_item(self, STATES[state_id][1], 15, width=self.width - 6)
        option = self.label.document().defaultTextOption()
        option.setAlignment(Qt.Qt.AlignmentFlag.AlignCenter)
        self.label.document().setDefaultTextOption(option)
        self.label.setPos(3, (62 - self.label.boundingRect().height()) / 2)
        self.update_style(False)

    def update_style(self, active, connected=True):
        self.active = active
        color = COLORS[STATES[self.state_id][0]]
        if self.state_id.endswith("Failed"):
            color = "#b9424e"
        self.setPen(Qt.QPen(Qt.QColor(color if active and connected else "#b9cbd2"), 3 if active else 1))
        self.setBrush(Qt.QColor(color if active and connected else "#ffffff"))
        self.label.setDefaultTextColor(Qt.QColor("#ffffff" if active and connected else "#334c59"))

    def paint(self, painter, option, widget=None):
        painter.setPen(self.pen())
        painter.setBrush(self.brush())
        painter.drawRoundedRect(self.rect(), 10, 10)


class DiagramView(Qt.QGraphicsView):
    selected = Qt.pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setScene(Qt.QGraphicsScene(self))
        self.setRenderHint(Qt.QPainter.RenderHint.Antialiasing)
        self.setDragMode(Qt.QGraphicsView.DragMode.ScrollHandDrag)
        self.setHorizontalScrollBarPolicy(Qt.Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setTransformationAnchor(Qt.QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setBackgroundBrush(Qt.QColor("#f4f7f9"))
        self.setMinimumSize(450, 360)
        self.nodes, self.groups, self.lines = {}, {}, []
        self.active_state = None
        self.connected = False
        self.show_labels = False
        self.latest_event = None
        self.auto_fit = True
        self.scene().setSceneRect(0, 0, 1510, 860)
        origins = {"未就绪": (25, 30), "就绪": (535, 30), "出光": (1045, 30), "设备异常": (535, 490)}
        for name, (x, y) in origins.items():
            width, height = (450, 390) if name != "设备异常" else (450, 320)
            group = Qt.QGraphicsRectItem(0, 0, width, height)
            group.setPos(x, y)
            group.setZValue(0)
            group.setPen(Qt.QPen(Qt.QColor("#c8d6dc"), 1.5))
            group.setBrush(Qt.QColor("#eaf0f3"))
            self.scene().addItem(group)
            title = text_item(group, name + " · 外层主状态", 18, COLORS[name])
            title.setPos(18, 10)
            self.groups[name] = group
        for id, (business, _) in STATES.items():
            node = StateItem(id, self.groups[business])
            node.setPos(*LAYOUT[id])
            self.nodes[id] = node
        all_edges = edges()
        incoming = {}
        for edge_index, edge in enumerate(all_edges):
            incoming.setdefault(edge.target, []).append(edge_index)
        for index, edge in enumerate(all_edges):
            source = self.groups[edge.source[9:]] if edge.source.startswith("business:") else self.nodes[edge.source]
            target = self.nodes[edge.target]
            source_group = self.groups[edge.source[9:]] if edge.source.startswith("business:") else self.groups[STATES[edge.source][0]]
            target_group = self.groups[STATES[edge.target][0]]
            fan = incoming[edge.target]
            path = self._route(source, target, source_group, target_group, index, fan.index(index), len(fan))
            line = self.scene().addPath(path, Qt.QPen(Qt.QColor("#9daeb7"), 1.3))
            # Keep routes above the compound-state background and below nodes.
            # This preserves every transition segment while letting the state
            # cards and arrowheads remain visually prominent.
            line.setZValue(1)
            line.setToolTip(edge.event)
            end, before = path.pointAtPercent(1), path.pointAtPercent(0.98)
            angle = math.atan2(end.y() - before.y(), end.x() - before.x())
            polygon = Qt.QPolygonF([end] + [Qt.QPointF(end.x() - 10 * math.cos(angle + delta), end.y() - 10 * math.sin(angle + delta)) for delta in (-0.45, 0.45)])
            arrow = self.scene().addPolygon(polygon, Qt.QPen(Qt.QColor("#9daeb7")), Qt.QBrush(Qt.QColor("#9daeb7")))
            arrow.setZValue(2)
            label = text_item(None, edge.event, 10, "#536e7b", 210)
            self.scene().addItem(label)
            point = path.pointAtPercent(0.5)
            label.setPos(point.x() - 85, point.y() - label.boundingRect().height())
            label.setZValue(5)
            label.setVisible(False)
            self.lines.append((edge, line, arrow, label))

    @staticmethod
    def _route(source, target, source_group, target_group, index, fan_rank=0, fan_count=1):
        """Route one edge with stable lanes so parallel transitions stay distinct."""
        a, b = source.sceneBoundingRect(), target.sceneBoundingRect()
        offset = ((index % 7) - 3) * 13
        fan_spacing = min(12, 140 / max(1, fan_count))
        fan_offset = (fan_rank - (fan_count - 1) / 2) * fan_spacing
        if source == target:
            start = Qt.QPointF(a.right(), a.center().y() + offset)
            end = Qt.QPointF(a.right(), a.center().y() - offset)
            loop = a.right() + 34 + abs(offset)
            path = Qt.QPainterPath(start)
            path.cubicTo(Qt.QPointF(loop, start.y()), Qt.QPointF(loop, end.y()), end)
            return path
        sg, tg = source_group.sceneBoundingRect(), target_group.sceneBoundingRect()
        if source_group != target_group and abs(sg.center().y() - tg.center().y()) < 1:
            # Adjacent compound states can share a horizontal channel at the
            # target's level. Long jumps use the free space above the row so
            # their routes do not cross the middle compound state.
            adjacent = abs(sg.center().x() - tg.center().x()) <= sg.width() + 100
            if adjacent:
                y = b.center().y() + offset
                if sg.center().x() < tg.center().x():
                    start = Qt.QPointF(sg.right(), y)
                    end = Qt.QPointF(b.left(), y)
                else:
                    start = Qt.QPointF(sg.left(), y)
                    end = Qt.QPointF(b.right(), y)
                middle = (start.x() + end.x()) / 2
                path = Qt.QPainterPath(start)
                path.cubicTo(Qt.QPointF(middle, y), Qt.QPointF(middle, y), end)
                return path
            corridor = 8 + (index % 5) * 7
            start = Qt.QPointF(sg.center().x() + offset, sg.top())
            end = Qt.QPointF(b.center().x() + fan_offset, b.top())
            path = Qt.QPainterPath(start)
            path.cubicTo(Qt.QPointF(start.x(), corridor), Qt.QPointF(end.x(), corridor), end)
            return path
        if source_group == target_group:
            if abs(b.center().y() - a.center().y()) < 5:
                if b.center().x() >= a.center().x():
                    start = Qt.QPointF(a.right(), a.center().y() + offset)
                    end = Qt.QPointF(b.left(), b.center().y() + offset)
                else:
                    start = Qt.QPointF(a.left(), a.center().y() + offset)
                    end = Qt.QPointF(b.right(), b.center().y() - offset)
                middle = (start.x() + end.x()) / 2
                path = Qt.QPainterPath(start)
                path.cubicTo(Qt.QPointF(middle, start.y()), Qt.QPointF(middle, end.y()), end)
                return path
            if b.center().y() > a.center().y():
                start = Qt.QPointF(a.center().x() + offset, a.bottom())
                end = Qt.QPointF(b.center().x() + offset, b.top())
            else:
                lane = source_group.sceneBoundingRect().right() - 22 + offset
                start = Qt.QPointF(a.right(), a.center().y() + offset)
                end = Qt.QPointF(b.right(), b.center().y() - offset)
                path = Qt.QPainterPath(start)
                path.cubicTo(Qt.QPointF(lane, start.y()), Qt.QPointF(lane, end.y()), end)
                return path
            middle = (start.y() + end.y()) / 2
            path = Qt.QPainterPath(start)
            path.cubicTo(Qt.QPointF(start.x(), middle), Qt.QPointF(end.x(), middle), end)
            return path

        if sg.bottom() <= tg.top():
            start = Qt.QPointF(sg.center().x() + offset * 2, sg.bottom())
            end = Qt.QPointF(b.center().x() + offset, b.top())
            lane = (start.x() + end.x()) / 2 + offset
            path = Qt.QPainterPath(start)
            path.cubicTo(Qt.QPointF(lane, start.y()), Qt.QPointF(lane, end.y()), end)
            return path
        if tg.bottom() <= sg.top():
            start = Qt.QPointF(sg.center().x() + offset * 2, sg.top())
            end = Qt.QPointF(b.center().x() + fan_offset, b.bottom())
            lane = (start.x() + end.x()) / 2 + offset
            path = Qt.QPainterPath(start)
            path.cubicTo(Qt.QPointF(lane, start.y()), Qt.QPointF(lane, end.y()), end)
            return path

        # Compound states on the same row use the gap between their boxes.
        if sg.center().x() < tg.center().x():
            start, end = Qt.QPointF(sg.right(), sg.center().y() + offset), Qt.QPointF(b.left(), b.center().y() + offset)
        else:
            start, end = Qt.QPointF(sg.left(), sg.center().y() + offset), Qt.QPointF(b.right(), b.center().y() + offset)
        middle = (start.x() + end.x()) / 2 + offset
        path = Qt.QPainterPath(start)
        path.cubicTo(Qt.QPointF(middle, start.y()), Qt.QPointF(middle, end.y()), end)
        return path

    def update_state(self, state_id, connected=True):
        self.active_state, self.connected = state_id, connected
        business = STATES[state_id][0] if state_id in STATES else None
        for name, group in self.groups.items():
            active = connected and name == business
            group.setPen(Qt.QPen(Qt.QColor(COLORS[name] if active else "#c8d6dc"), 3 if active else 1.5))
            fills = {"未就绪": "#e8eff5", "就绪": "#e8f4f1", "出光": "#fff7e5", "设备异常": "#fcecef"}
            group.setBrush(Qt.QColor(fills[name] if active else "#eaf0f3"))
        for id, node in self.nodes.items():
            node.update_style(id == state_id, connected)
        self.update_edges()

    def update_edges(self, event=None):
        if event is not None:
            self.latest_event = event
        for edge, line, arrow, label in self.lines:
            active = self.connected and edge_matches(edge, self.latest_event)
            color = Qt.QColor("#e17b22" if active else "#9daeb7")
            line.setPen(Qt.QPen(color, 3.5 if active else 1.3))
            arrow.setPen(Qt.QPen(color))
            arrow.setBrush(Qt.QBrush(color))
            line.setOpacity(1 if active else 0.5)
            arrow.setOpacity(1 if active else 0.5)
            label.setVisible(self.show_labels or active)
            label.setDefaultTextColor(Qt.QColor("#a54800" if active else "#536e7b"))

    def fit(self):
        self.auto_fit = True
        self.fitInView(self.sceneRect(), Qt.Qt.AspectRatioMode.KeepAspectRatio)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if getattr(self, "auto_fit", False):
            self.fit()

    def wheelEvent(self, event):
        factor = 1.15 if event.angleDelta().y() > 0 else 1 / 1.15
        next_scale = self.transform().m11() * factor
        if 0.2 < next_scale < 3:
            self.auto_fit = False
            self.scale(factor, factor)
        event.accept()

    def mousePressEvent(self, event):
        item = self.itemAt(event.pos())
        while item is not None:
            if isinstance(item, StateItem):
                self.selected.emit(item.state_id)
                break
            item = item.parentItem()
        super().mousePressEvent(event)
