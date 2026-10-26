These browser assets are the canonical resources for the web workbench's SCXML diagrams.

- `schviz.min.js`: `@scion-scxml/schviz` 3.2.28, Apache-2.0
- `scxml.min.js`: `@scion-scxml/scxml` 4.3.28, Apache-2.0
- `react.min.js`, `react-dom.min.js`: React 15.6.2, MIT
- `polyfill.min.js`: `babel-polyfill` 6.26.0, MIT

The matching license texts are included beside these files. The server packages
these files directly from frontend/public, so web rendering works without
network access. They are browser assets for the Java Workbench and are not a separate simulator distribution.
