#!/usr/bin/env python3
"""
GXLF 图表编辑服务
启动: python3 modeling/server.py  (从项目根目录)
      python3 server.py           (从 modeling/ 目录)
访问: http://localhost:8080
"""
import json
import os
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = 8080
DIR = os.path.dirname(os.path.abspath(__file__))

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIR, **kwargs)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        super().end_headers()

    def do_POST(self):
        if self.path == '/save':
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            filename = os.path.basename(body['filename'])
            filepath = os.path.join(DIR, filename)
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(body['xml'])
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'ok': True}).encode())
            print(f'  saved: {filename}')
        else:
            self.send_response(404)
            self.end_headers()

if __name__ == '__main__':
    print(f'GXLF 图表编辑器: http://localhost:{PORT}')
    print(f'目录: {DIR}')
    HTTPServer(('', PORT), Handler).serve_forever()
