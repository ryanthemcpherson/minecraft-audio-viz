#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, HTTPServer


class H(BaseHTTPRequestHandler):
    def _redir(self):
        target = f"http://192.168.1.204:8081{self.path}"
        self.send_response(302)
        self.send_header("Location", target)
        self.end_headers()

    def do_GET(self):
        self._redir()

    def do_HEAD(self):
        self._redir()

    def log_message(self, fmt, *args):
        pass


HTTPServer(("0.0.0.0", 8080), H).serve_forever()
