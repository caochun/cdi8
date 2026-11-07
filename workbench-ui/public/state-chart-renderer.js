'use strict';
// Layout once per model in an isolated renderer. Device cards reuse the SVG.
window.addEventListener('message', async event => {
  if (event.source !== parent || event.origin !== location.origin || event.data?.type !== 'layout-model') return;
  const {xml, id, labels} = event.data;
  const LABEL_FONT = 6;
  const LABEL_LINE_HEIGHT = 7.2;
  const MAX_LABEL_LINE_WIDTH = 54;
  const metrics = document.createElement('canvas').getContext('2d');
  metrics.font = `650 ${LABEL_FONT}px "PingFang SC", "Microsoft YaHei", sans-serif`;
  const measureWidth = text => metrics.measureText(text).width;
  const words = new Intl.Segmenter('zh', {granularity:'word'});
  const wrapLabel = text => {
    const lines = [];
    for (const paragraph of String(text).split(/\r?\n/)) {
      let line = '';
      const tokens = [...words.segment(paragraph)].flatMap(({segment}) => measureWidth(segment) > MAX_LABEL_LINE_WIDTH ? [...segment] : [segment]);
      for (const token of tokens) {
        if (line && measureWidth(line + token) > MAX_LABEL_LINE_WIDTH && !/^[，、；：。！？,.!?;:）)\]]+$/.test(token)) {
          // Prefer a pause in the name; otherwise keep Chinese/English words together.
          const pauses = [...line.matchAll(/[，、；：,;:/\s]/g)];
          const pause = pauses.at(-1)?.index;
          const split = pause !== undefined && pause >= line.length * .4 ? pause + 1 : line.length;
          lines.push(line.slice(0, split)); line = line.slice(split);
        }
        line += token;
      }
      lines.push(line);
    }
    return lines.length ? lines : [''];
  };
  const report = payload => parent.postMessage({type:'model-layout', id, ...payload}, location.origin);
  try {
    const root = document.getElementById('root');
    root.classList.toggle('readable-labels', Boolean(labels));
    // SCHVIZ predates SCXML 1.0. Only its presentation input is adapted:
    // metadata is not a state, and internal sibling transitions draw as
    // ordinary edges (SCXML also gives them external transition semantics).
    const doc = new DOMParser().parseFromString(xml,'application/xml');
    for (const data of [...doc.getElementsByTagNameNS('*','datamodel')]) data.remove();
    for (const transition of doc.getElementsByTagNameNS('*','transition')) {
      if (transition.getAttribute('type')!=='internal') continue;
      const source=transition.parentElement;
      const targets=(transition.getAttribute('target')||'').split(/\s+/).filter(Boolean);
      const states=[...doc.getElementsByTagNameNS('*','state')];
      if(targets.some(id=>{const target=states.find(s=>s.getAttribute('id')===id);return target && target!==source && !source.contains(target);}))
        transition.removeAttribute('type');
    }
    const graph = ReactDOM.render(React.createElement(window.scion.schviz, {
      id:'component', scxmlDocumentString:new XMLSerializer().serializeToString(doc), expandAllStatesByDefault:true,
      hideActions:true,
      disableAnimation:true, disableZoomAnimation:true, configuration:[], previousConfiguration:[]
    }), root);
    const deadline = Date.now() + 25000;
    let labelsLaidOut = false;
    const poll = () => {
      const svg = root.querySelector('svg');
      if (graph.graphRoot?.state.kgraph && svg?.querySelector('g.node') && (!labels || !graph.graphRoot.state.loading)) {
        if (labels && !labelsLaidOut) {
          // Re-measure with business labels before laying out nodes and edges.
          // IDs remain unchanged so selection and live-state highlights still match XML.
          labelsLaidOut = true;
          const kgraphRoot = JSON.parse(JSON.stringify(graph.graphRoot.state.kgraph.root));
          const labelLines = new Map();
          const rename = node => {
            const full = labels[node.id] || node.labels?.[0]?.text;
            if (full && !['initial','final','pseudonode'].includes(node.$type)) {
              const lines = wrapLabel(full);
              labelLines.set(node.id, lines);
              node.labels = [{text: lines.join('\n')}];
              if (node.children?.length) node.properties = {...node.properties, borderSpacing: Math.max(14, lines.length * LABEL_LINE_HEIGHT + 8)};
            }
            (node.children || []).forEach(rename);
          };
          rename(kgraphRoot);
          // SCHVIZ measures state labels as one line. Adapt only this isolated
          // editor renderer, before Klay routes edges using the node dimensions.
          const prototype = Object.getPrototypeOf(graph.graphRoot.state.kgraph);
          const measure = prototype._processKGraphPreLayout;
          prototype._processKGraphPreLayout = function(kgraph) {
            const pending = measure.call(this, kgraph);
            pending.push(Promise.all([...pending]).then(() => {
              const resize = node => {
                const lines = labelLines.get(node.id);
                if (lines) {
                  node.width = Math.max(40, ...lines.map(line => measureWidth(line) + 12));
                  node.height = Math.max(20, lines.length * LABEL_LINE_HEIGHT + 10);
                }
                (node.children || []).forEach(resize);
              };
              resize(kgraph);
            }));
            return pending;
          };
          graph.graphRoot.initKGraph({...graph.graphRoot.props, kgraphRoot}, false);
          setTimeout(poll, 100);
          return;
        }
        for(const marker of svg.querySelectorAll('marker')) {marker.setAttribute('markerWidth','2.4');marker.setAttribute('markerHeight','4');}
        if (labels) for (const group of svg.querySelectorAll('g.node')) {
          const raw = group.getAttribute('data-id') || group.id.replace(/^component:/, '');
          const text = group.querySelector(':scope > text');
          const rect = group.querySelector(':scope > rect');
          if (!text || !rect || group.classList.contains('type__initial') || group.classList.contains('type__final')) continue;
          const full = labels[raw] || text.textContent;
          const lines = wrapLabel(full);
          const width = Number(rect.getAttribute('width'));
          const compound = group.classList.contains('compound');
          const y = compound ? 7 : Number(rect.getAttribute('height')) / 2 - (lines.length - 1) * LABEL_LINE_HEIGHT / 2;
          text.replaceChildren();
          text.setAttribute('x', String(width / 2));
          text.setAttribute('y', String(y));
          lines.forEach((line, index) => {
            const tspan = document.createElementNS('http://www.w3.org/2000/svg', 'tspan');
            tspan.setAttribute('x', String(width / 2));
            tspan.setAttribute('dy', index ? String(LABEL_LINE_HEIGHT) : '0');
            tspan.textContent = line;
            text.appendChild(tspan);
          });
          const separator = group.querySelector(':scope > line');
          if (separator) for (const attr of ['y1','y2']) separator.setAttribute(attr, String(lines.length * LABEL_LINE_HEIGHT + 5));
        }
        const nodes = {};
        // Bake the current viewport transform into SVG coordinates for stable crops.
        const matrix = svg.getScreenCTM().inverse();
        for (const group of svg.querySelectorAll('g.node')) {
          const raw = group.getAttribute('data-id') || group.id.replace(/^component:/, '');
          if (!raw || raw.includes(':') || raw.startsWith('$generated')) continue;
          const shape = group.querySelector(':scope > rect') || group;
          const box = shape.getBBox(), transform = shape.getScreenCTM();
          const points = [[box.x,box.y],[box.x+box.width,box.y+box.height]]
            .map(([x,y]) => new DOMPoint(x,y).matrixTransform(transform).matrixTransform(matrix));
          nodes[raw] = {x:points[0].x,y:points[0].y,width:points[1].x-points[0].x,height:points[1].y-points[0].y};
          group.setAttribute('data-state', raw);
        }
        const boxes = Object.values(nodes);
        if (!boxes.length) { report({error:'模型中没有可绘制状态'}); return; }
        const x = Math.min(...boxes.map(b=>b.x))-30, y = Math.min(...boxes.map(b=>b.y))-30;
        const width = Math.max(...boxes.map(b=>b.x+b.width))-x+30;
        const height = Math.max(...boxes.map(b=>b.y+b.height))-y+30;
        const content=svg.getBBox();
        const left=Math.min(x,content.x-15), top=Math.min(y,content.y-15);
        const right=Math.max(x+width,content.x+content.width+15), bottom=Math.max(y+height,content.y+content.height+15);
        svg.setAttribute('viewBox', `${left} ${top} ${right-left} ${bottom-top}`);
        svg.setAttribute('preserveAspectRatio','xMidYMid meet');
        for(const text of svg.querySelectorAll('text')) {
          const computed=getComputedStyle(text);
          for(const name of ['font-size','font-weight','text-anchor','dominant-baseline','font-family']) text.setAttribute(name,computed.getPropertyValue(name));
        }
        svg.removeAttribute('style');
        svg.removeAttribute('width'); svg.removeAttribute('height');
        report({svg:new XMLSerializer().serializeToString(svg), nodes});
      } else if (Date.now() < deadline) setTimeout(poll,100);
      else report({error:'状态图布局超时，请重试'});
    };
    setTimeout(poll,100);
  } catch(error) { report({error:error.message}); }
});
parent.postMessage({type:'renderer-ready'}, location.origin);
