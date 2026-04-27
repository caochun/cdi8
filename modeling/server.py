#!/usr/bin/env python3
"""
GXLF 图表编辑服务
启动: python3 modeling/server.py  (从项目根目录)
      python3 server.py           (从 modeling/ 目录)
访问: http://localhost:8080
"""
import json
import os
import glob
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import unquote

PORT = 8080
DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(DIR, 'data')
os.makedirs(DATA_DIR, exist_ok=True)

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIR, **kwargs)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        path = unquote(self.path)
        if path == '/api/files':
            files = [os.path.basename(f) for f in glob.glob(os.path.join(DATA_DIR, '*.tldr'))]
            self._json_response({'files': files})
        elif path.startswith('/api/files/'):
            name = os.path.basename(path[len('/api/files/'):])
            filepath = os.path.join(DATA_DIR, name)
            if os.path.exists(filepath):
                with open(filepath, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                self._json_response(data)
            else:
                self.send_response(404)
                self.end_headers()
        else:
            super().do_GET()

    def do_POST(self):
        path = unquote(self.path)
        if path.startswith('/api/files/'):
            name = os.path.basename(path[len('/api/files/'):])
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            filepath = os.path.join(DATA_DIR, name)
            with open(filepath, 'wb') as f:
                f.write(body)
            self._json_response({'ok': True})
            print(f'  saved: {name}')
        elif path == '/save':
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            filename = os.path.basename(body['filename'])
            filepath = os.path.join(DIR, filename)
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(body['xml'])
            self._json_response({'ok': True})
            print(f'  saved: {filename}')
        else:
            self.send_response(404)
            self.end_headers()

    def _json_response(self, data):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

if __name__ == '__main__':
    print(f'GXLF 图表编辑器: http://localhost:{PORT}')
    print(f'目录: {DIR}')
    print(f'数据: {DATA_DIR}')
    HTTPServer(('', PORT), Handler).serve_forever()
