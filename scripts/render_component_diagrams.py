"""Generate Mermaid state definitions from the executable component SCXML.

Run from any directory. Render the resulting .mmd with Mermaid CLI when SVG
artifacts are needed; the simulator GUI reads SCXML directly.
"""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'s': 'http://www.w3.org/2005/07/scxml'}


def render(source, target):
    root = ET.parse(source).getroot()
    metadata = json.loads(root.find('.//s:data[@id="componentMetadata"]', NS).text)
    labels = {**metadata['outer_labels'], **metadata['inner_labels']}
    lines = ['stateDiagram-v2', '    direction TB',
             '    %% Generated from ' + source.name + '; SCXML is the execution source.',
             '    [*] --> ' + root.get('initial')]

    def state(element, depth):
        indent = '    ' * depth
        key = element.get('id')
        children = element.findall('s:state', NS)
        lines.append(indent + 'state "' + labels[key] + '" as ' + key + (' {' if children else ''))
        if children:
            for child in children:
                state(child, depth + 1)
            lines.append(indent + '    [*] --> ' + element.get('initial'))
            lines.append(indent + '}')

    for element in root.findall('s:state', NS):
        state(element, 1)
    for element in root.iterfind('.//s:state', NS):
        for edge in element.findall('s:transition', NS):
            lines.append('    ' + element.get('id') + ' --> ' + edge.get('target') + ': ' + edge.get('event'))
    target.write_text('\n'.join(lines) + '\n')


def main():
    for component in ['seed-source', 'shg-injection', 'preamp', 'multipass']:
        basename = component + '-state-machine'
        source = ROOT / 'docs' / (basename + '.scxml')
        # Keep existing links to the seed diagram valid.
        target = ROOT / 'docs/diagrams' / (basename + ('-draft' if component == 'seed-source' else '') + '.mmd')
        render(source, target)


if __name__ == '__main__':
    main()
