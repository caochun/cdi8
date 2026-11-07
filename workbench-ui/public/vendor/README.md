# BPMN viewer

Pinned bpmn-js 18.6.3, navigated viewer production bundle and original assets.

Source: https://registry.npmjs.org/bpmn-js/-/bpmn-js-18.6.3.tgz
Bundle: https://unpkg.com/bpmn-js@18.6.3/dist/bpmn-navigated-viewer.production.min.js

Served locally so workflow visualization does not depend on a CDN. See
BPMN-JS-LICENSE; the original visible bpmn.io watermark is retained.

SCHVIZ and its React/SCXML dependencies are copied at Maven resource processing
time from `frontend/public/vendor/schviz`, together with
their license files. The authoritative SCXML models are packaged from
`examples/gxlf/components`. No separate vendored copy is maintained
in this directory. The web renderer adapts only its display input for the legacy
SCHVIZ parser; it does not execute or modify the component models.
