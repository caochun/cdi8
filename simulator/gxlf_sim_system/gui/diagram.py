"""Native Qt graphics items for the nested state graph (no SVG DOM or WebView)."""
import math

from taurus.external.qt import Qt

from ..machine import STATES
from .model import edges, edge_matches


COLORS = {"未就绪": "#54758e", "就绪": "#237b72", "出光": "#b37818", "异常": "#b9424e"}
# Geometry only: transition rules and state names come from machine.py.
LAYOUT = {
    "Idle": (135, 80), "Testing": (135, 190), "TestOK": (30, 305),
    "TestFailed": (245, 305), "Checking": (30, 435), "CheckFailed": (245, 435),
    "ConfigIdle": (135, 80), "Configuring": (135, 190), "ConfigOK": (30, 305),
    "ConfigFailed": (245, 305), "Starting": (30, 435), "StartFailed": (245, 435),
    "CollectIdle": (135, 80), "Collecting": (135, 220), "CollectOK": (30, 365),
    "CollectFailed": (245, 365), "Fault": (40, 60),
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
        super().__init__(0, 0, 175, 62, parent)
        self.state_id = state_id
        self.active = False
        self.setData(0, state_id)
        self.setZValue(3)
        self.setCursor(Qt.Qt.CursorShape.PointingHandCursor)
        self.setToolTip(f"{STATES[state_id][0]} / {STATES[state_id][1]}\n编号：{state_id}")
        self.label = text_item(self, STATES[state_id][1], 15, width=169)
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
        origins = {"未就绪": (25, 30), "就绪": (535, 30), "出光": (1045, 30), "异常": (625, 670)}
        for name, (x, y) in origins.items():
            width, height = (450, 560) if name != "异常" else (260, 145)
            group = Qt.QGraphicsRectItem(0, 0, width, height)
            group.setPos(x, y)
            group.setZValue(0)
            group.setPen(Qt.QPen(Qt.QColor("#c8d6dc"), 1.5))
            group.setBrush(Qt.QColor("#eaf0f3"))
            self.scene().addItem(group)
            title = text_item(group, name + " · 业务状态", 18, COLORS[name])
            title.setPos(18, 10)
            self.groups[name] = group
        for id, (business, _) in STATES.items():
            node = StateItem(id, self.groups[business])
            node.setPos(*LAYOUT[id])
            self.nodes[id] = node
        for index, edge in enumerate(edges()):
            source = self.groups[edge.source[9:]] if edge.source.startswith("business:") else self.nodes[edge.source]
            target = self.nodes[edge.target]
            a, b = source.sceneBoundingRect(), target.sceneBoundingRect()
            external = edge.source.startswith("business:") or STATES[edge.source][0] != STATES[edge.target][0]
            path = Qt.QPainterPath()
            if external:
                # Route parent-level edges through lanes outside state boxes.
                start = Qt.QPointF(a.center().x(), a.bottom())
                end = Qt.QPointF(b.center().x(), b.bottom())
                lane = 610 + (index % 5) * 9
                path.moveTo(start)
                path.cubicTo(Qt.QPointF(start.x(), lane), Qt.QPointF(end.x(), lane), end)
            elif b.center().y() > a.center().y():
                start, end = Qt.QPointF(a.center().x(), a.bottom()), Qt.QPointF(b.center().x(), b.top())
                middle = (start.y() + end.y()) / 2
                path.moveTo(start)
                path.cubicTo(Qt.QPointF(start.x(), middle), Qt.QPointF(end.x(), middle), end)
            else:
                start, end = Qt.QPointF(a.right(), a.center().y()), Qt.QPointF(b.right(), b.center().y())
                lane = self.groups[STATES[edge.target][0]].sceneBoundingRect().right() - 12
                path.moveTo(start)
                path.cubicTo(Qt.QPointF(lane, start.y()), Qt.QPointF(lane, end.y()), end)
            line = self.scene().addPath(path, Qt.QPen(Qt.QColor("#9daeb7"), 1.3))
            line.setZValue(1)
            line.setToolTip(edge.event)
            end, before = path.pointAtPercent(1), path.pointAtPercent(0.98)
            angle = math.atan2(end.y() - before.y(), end.x() - before.x())
            polygon = Qt.QPolygonF([end] + [Qt.QPointF(end.x() - 10 * math.cos(angle + delta), end.y() - 10 * math.sin(angle + delta)) for delta in (-0.45, 0.45)])
            arrow = self.scene().addPolygon(polygon, Qt.QPen(Qt.QColor("#9daeb7")), Qt.QBrush(Qt.QColor("#9daeb7")))
            arrow.setZValue(2)
            label = text_item(None, edge.event, 11, "#536e7b", 180)
            self.scene().addItem(label)
            point = path.pointAtPercent(0.5)
            label.setPos(point.x() - 85, point.y() - label.boundingRect().height())
            label.setZValue(5)
            label.setVisible(False)
            self.lines.append((edge, line, arrow, label))

    def update_state(self, state_id, connected=True):
        self.active_state, self.connected = state_id, connected
        business = STATES[state_id][0] if state_id in STATES else None
        for name, group in self.groups.items():
            active = connected and name == business
            group.setPen(Qt.QPen(Qt.QColor(COLORS[name] if active else "#c8d6dc"), 3 if active else 1.5))
            fills = {"未就绪": "#e8eff5", "就绪": "#e8f4f1", "出光": "#fff7e5", "异常": "#fcecef"}
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
