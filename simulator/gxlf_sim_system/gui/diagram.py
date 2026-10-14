"""SCXML state-chart view backed by the SCHVIZ hierarchical SVG renderer.

The Qt GUI owns the device controls while SCHVIZ owns graph layout and SVG
interaction. The SCXML document is passed to SCHVIZ directly; labels and
runtime highlights are a presentation layer on top of that document.
"""

import base64
import json
import tempfile
from pathlib import Path

from taurus.external.qt import Qt

from PyQt6.QtCore import QUrl
from PyQt6.QtWebChannel import QWebChannel
from PyQt6.QtWebEngineWidgets import QWebEngineView

from ..component_model import ComponentModel


class DiagramBridge(Qt.QObject):
    """Bridge node clicks from the SCHVIZ page back to the Qt window."""

    selected = Qt.pyqtSignal(str)

    @Qt.pyqtSlot(str)
    def selectState(self, state_id):
        self.selected.emit(state_id)


def _asset_text(name):
    path = Path(__file__).with_name("assets") / "schviz" / name
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise RuntimeError(f"缺少 SCHVIZ GUI 资源：{path}") from exc


def _script(text):
    # The vendored bundles contain no closing script tags. Keeping them inline
    # makes the view work from an editable install, a wheel, or a frozen app.
    return "<script>\n" + text + "\n</script>"


def _utf8_base64(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def _html(profile: ComponentModel, external_assets=False):
    scxml = profile.scxml_path.read_text(encoding="utf-8")
    metadata = {
        "outer_labels": profile.outer_labels,
        "inner_labels": profile.inner_labels,
    }
    config_json = json.dumps({"scxml": _utf8_base64(scxml), "metadata": metadata},
                             ensure_ascii=False, separators=(",", ":"))
    page_script = r"""
const diagramConfig = __CONFIG__;
const rootElement = document.getElementById('schviz-root');
let graphProps = null;
let graphComponent = null;
let diagramBridge = null;

function decodeUtf8(value) {
  const bytes = Uint8Array.from(atob(value), c => c.charCodeAt(0));
  return new TextDecoder('utf-8').decode(bytes);
}

const scxmlDocument = decodeUtf8(diagramConfig.scxml);
const metadata = diagramConfig.metadata || {};
const labels = Object.assign({}, metadata.outer_labels || {}, metadata.inner_labels || {});

function stateIdFromElement(element) {
  if (!element) return null;
  const explicit = element.getAttribute('data-id');
  if (explicit) return explicit;
  const match = (element.id || '').match(/(?:^|:)state-([A-Za-z0-9_]+)-\d+$/);
  return match ? match[1] : null;
}

function applyLabels() {
  rootElement.querySelectorAll('g.statediagram-state').forEach(group => {
    const id = stateIdFromElement(group);
    const label = id && labels[id];
    if (!label) return;
    const text = group.querySelector('.nodeLabel p');
    if (text && text.textContent !== label) text.textContent = label;
  });
}

function clearRecentEdge() {
  rootElement.querySelectorAll('path.link.recent').forEach(path => path.classList.remove('recent'));
}

function setRecentEdge(reference) {
  clearRecentEdge();
  if (!reference || reference.source === undefined || reference.index === undefined) return;
  const path = document.getElementById('component:' + reference.source + ':transition:' + reference.index);
  if (path) path.classList.add('recent');
}

function setShowLabels(value) {
  document.body.classList.toggle('show-schviz-labels', !!value);
}

function renderGraph() {
  graphProps = {
    id: 'component',
    scxmlDocumentString: scxmlDocument,
    expandAllStatesByDefault: true,
    disableAnimation: true,
    disableZoomAnimation: true,
    configuration: [],
    previousConfiguration: []
  };
  graphComponent = ReactDOM.render(
    React.createElement(window.scion.schviz, graphProps), rootElement);
  setTimeout(applyLabels, 50);
  setTimeout(applyLabels, 250);
}

window.updateState = function(configuration, previousConfiguration, connected) {
  if (!graphProps) return;
  graphProps = Object.assign({}, graphProps, {
    configuration: connected ? (configuration || []) : [],
    previousConfiguration: connected ? (previousConfiguration || []) : [],
    redraw: false
  });
  graphComponent = ReactDOM.render(
    React.createElement(window.scion.schviz, graphProps), rootElement);
  setTimeout(applyLabels, 20);
};

window.updateRecentEdge = setRecentEdge;
window.setShowLabels = setShowLabels;
window.fitGraph = function() {
  if (graphComponent && graphComponent.graphRoot && graphComponent.graphRoot.state.kgraph) {
    const root = graphComponent.graphRoot;
    root.zoomToViewbox(root.getOriginalViewbox(root.state.kgraph));
  } else {
    window.dispatchEvent(new Event('resize'));
  }
};

const observer = new MutationObserver(applyLabels);
observer.observe(rootElement, {childList: true, subtree: true});

document.addEventListener('click', event => {
  const node = event.target.closest && event.target.closest('g.node');
  if (!node || !diagramBridge) return;
  const id = stateIdFromElement(node);
  if (id) diagramBridge.selectState(id);
});

if (window.qt && qt.webChannelTransport) {
  new QWebChannel(qt.webChannelTransport, channel => {
    diagramBridge = channel.objects.diagramBridge;
  });
}
renderGraph();
""".replace("__CONFIG__", config_json)
    styles = """
html, body, #schviz-root { width: 100%; height: 100%; margin: 0; overflow: hidden; }
body { background: #f4f7f9; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
#schviz-root { user-select: none; }
.node > rect { fill: #ffffff; stroke: #9eb5bf; stroke-width: 1.2px; }
.compound > rect { fill: #edf3f5; stroke: #9eb5bf; }
.node.highlighted > rect { fill: #fff2d8; stroke: #c7781b; stroke-width: 3px; }
.node.leaf.highlighted > rect { fill: #c47712; stroke: #8e5207; }
.node.leaf.highlighted text { fill: #ffffff; font-weight: 600; }
.link { stroke: #9cabb2; stroke-width: 1.25px; fill: none; }
.link.highlighted { stroke: #c7781b; stroke-width: 2px; }
.link.recent { stroke: #e1741a; stroke-width: 3.5px; }
.edgeLabel { visibility: hidden; }
body.show-schviz-labels .edgeLabel { visibility: visible; }
.edgeLabel text, .nodeLabel { fill: #314d5a; font-size: 12px; }
.node.compound > .nodeLabel { font-weight: 600; }
"""
    if external_assets:
        assets = Path(__file__).with_name("assets") / "schviz"
        scripts = "".join(f"<script src='{(assets / name).as_uri()}'></script>"
                          for name in ("react.min.js", "react-dom.min.js", "polyfill.min.js",
                                       "scxml.min.js", "schviz.min.js"))
    else:
        scripts = (_script(_asset_text("react.min.js"))
                   + _script(_asset_text("react-dom.min.js"))
                   + _script(_asset_text("polyfill.min.js"))
                   + _script(_asset_text("scxml.min.js"))
                   + _script(_asset_text("schviz.min.js")))
    return ("<!doctype html><html><head><meta charset='utf-8'><meta http-equiv='Content-Security-Policy' content=\"default-src 'self' data: blob: file: qrc: 'unsafe-inline' 'unsafe-eval'\"><style>" + styles + "</style></head>"
            "<body><div id='schviz-root'></div>" + scripts
            + "<script src='qrc:///qtwebchannel/qwebchannel.js'></script>"
            + _script(page_script) + "</body></html>")


class DiagramView(QWebEngineView):
    """Native Qt host for the SCXML-driven SCHVIZ graph."""

    selected = Qt.pyqtSignal(str)

    def __init__(self, profile: ComponentModel, parent=None):
        super().__init__(parent)
        self.profile = profile
        self.latest_event = None
        self.active_state = None
        self.connected = False
        self.show_labels = False
        self._loaded = False
        self._pending_state = None
        self._pending_edge = None
        self._html_path = None
        self.setContextMenuPolicy(Qt.Qt.ContextMenuPolicy.NoContextMenu)
        self.setMinimumSize(450, 360)
        self.setStyleSheet("background: #f4f7f9; border: 1px solid #c8d6dc;")
        self.bridge = DiagramBridge()
        self.bridge.selected.connect(self.selected.emit)
        self.channel = QWebChannel(self.page())
        self.channel.registerObject("diagramBridge", self.bridge)
        self.page().setWebChannel(self.channel)
        self.loadFinished.connect(self._load_finished)
        # Keep the large SCHVIZ bundles as local script resources. Inline
        # setHtml pages are rejected by WebEngine on macOS once the combined
        # JavaScript exceeds its document limit.
        handle = tempfile.NamedTemporaryFile(prefix="gxlf-schviz-", suffix=".html",
                                              mode="w", encoding="utf-8", delete=False)
        self._html_path = Path(handle.name)
        handle.write(_html(profile, external_assets=True))
        handle.close()
        self.setUrl(QUrl.fromLocalFile(str(self._html_path)))

    def _load_finished(self, ok):
        self._loaded = bool(ok)
        if not self._loaded:
            return
        self.page().runJavaScript("window.setShowLabels(%s);" % ("true" if self.show_labels else "false"))
        self._apply_pending()

    def _apply_pending(self):
        if not self._loaded:
            return
        if self._pending_state is not None:
            configuration, previous, connected = self._pending_state
            self.page().runJavaScript(
                "window.updateState(%s, %s, %s);" % (
                    json.dumps(configuration), json.dumps(previous), "true" if connected else "false"))
        if self._pending_edge is not None:
            self.page().runJavaScript("window.updateRecentEdge(%s);" % json.dumps(self._pending_edge))

    def update_state(self, state_id, connected=True):
        previous = self._pending_state[0] if self._pending_state else []
        configuration = list(self.profile.machine.path(state_id)) if state_id in self.profile.states else []
        self.active_state, self.connected = state_id, connected
        self._pending_state = (configuration, previous, connected)
        self._apply_pending()

    def _transition_reference(self, event):
        if not event or event.get("source") not in self.profile.states:
            return None
        try:
            _, transition = self.profile.machine.dispatch(event["source"], event["event"])
        except Exception:
            return None
        node = self.profile.machine.nodes.get(transition.source)
        if node is None:
            return None
        for index, candidate in enumerate(node.transitions):
            if candidate == transition:
                return {"source": transition.source, "index": index}
        return None

    def update_edges(self, event=None):
        if event is not None:
            self.latest_event = event
            self._pending_edge = self._transition_reference(event)
        elif self.latest_event is None:
            self._pending_edge = None
        self._apply_pending()

    def fit(self):
        if self._loaded:
            self.page().runJavaScript("window.fitGraph();")

    def closeEvent(self, event):
        super().closeEvent(event)
        if self._html_path:
            try:
                self._html_path.unlink()
            except OSError:
                pass
            self._html_path = None
