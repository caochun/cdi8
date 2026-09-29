"""Loopback-only operator UI: python -m gxlf_sim_system.operator_server."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import secrets
from threading import Event, Thread
from urllib.parse import urlparse, parse_qs
from uuid import uuid4

from .operator import OperatorSession


def make_server(session, port=8765):
    token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, status, payload, content_type='application/json; charset=utf-8'):
            data = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def valid_host(self):
            return self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'

        def do_GET(self):
            if not self.valid_host():
                return self.respond(403, {'error': '请使用 127.0.0.1 地址'})
            parsed = urlparse(self.path)
            try:
                if parsed.path == '/':
                    page = (Path(__file__).parent / 'web' / 'operator.html').read_text(encoding='utf-8')
                    return self.respond(200, page.replace('__TOKEN__', token).encode(), 'text/html; charset=utf-8')
                if parsed.path == '/api/session':
                    return self.respond(200, session.view())
                if parsed.path == '/api/replay':
                    seq = int(parse_qs(parsed.query)['seq'][0])
                    with session.lock:
                        return self.respond(200, session.journal.replay(seq))
                if parsed.path == '/api/log':
                    with session.lock:
                        return self.respond(200, session.journal.path.read_bytes(), 'application/x-ndjson; charset=utf-8')
                self.respond(404, {'error': 'not found'})
            except (ValueError, KeyError) as exc:
                self.respond(400, {'error': str(exc)})

        def do_POST(self):
            if not self.valid_host() or self.headers.get('X-Operator-Token') != token:
                return self.respond(403, {'error': 'invalid local operator token'})
            if self.path != '/api/commands':
                return self.respond(404, {'error': 'not found'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 16384:
                    raise ValueError('invalid request length')
                request = json.loads(self.rfile.read(length))
                result = session.command(request)
                self.respond(200 if result['ok'] else 409, result)
            except (ValueError, KeyError) as exc:
                self.respond(400, {'error': str(exc)})

    return ThreadingHTTPServer(('127.0.0.1', port), Handler)


def main():
    parser = argparse.ArgumentParser(description='本地实验流程仿真操作台')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--log', type=Path)
    args = parser.parse_args()
    log = args.log or Path('gxlf_sim_system/output') / f'operator-{uuid4().hex}.jsonl'
    session = OperatorSession(log)
    server = make_server(session, args.port)
    stop = Event()

    def watchdog():
        while not stop.wait(0.2):
            session.tick()

    worker = Thread(target=watchdog, daemon=True)
    worker.start()
    print(f'操作台：http://127.0.0.1:{server.server_port}', flush=True)
    print(f'事件日志：{log.resolve()}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        worker.join()
        server.server_close()


if __name__ == '__main__':
    main()
