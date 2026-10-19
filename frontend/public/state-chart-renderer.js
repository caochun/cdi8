'use strict';
// Layout once per model in an isolated renderer. Device cards reuse the SVG.
window.addEventListener('message', async event => {
  if (event.source !== parent || event.origin !== location.origin || event.data?.type !== 'layout-model') return;
  const {xml, id} = event.data;
  const report = payload => parent.postMessage({type:'model-layout', id, ...payload}, location.origin);
  try {
    const root = document.getElementById('root');
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
    const poll = () => {
      const svg = root.querySelector('svg');
      if (graph.graphRoot?.state.kgraph && svg?.querySelector('g.node')) {
        for(const marker of svg.querySelectorAll('marker')) {marker.setAttribute('markerWidth','2.4');marker.setAttribute('markerHeight','4');}
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
          for(const name of ['font-size','text-anchor','dominant-baseline','font-family']) text.setAttribute(name,computed.getPropertyValue(name));
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
