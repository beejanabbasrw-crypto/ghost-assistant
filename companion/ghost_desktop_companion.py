#!/usr/bin/env python3
"""
G.H.O.S.T. Desktop Companion Server
Lightweight companion daemon for Linux, macOS, and Windows to pair with Ghost Assistant on Android.
Handles remote system commands: open applications, adjust system volume, file search, and lock screen.
Usage:
    python3 ghost_desktop_companion.py [--port 8080] [--host 0.0.0.0]
"""

import sys
import os
import json
import socket
import platform
import subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Dict, Any

DEFAULT_PORT = 8080
DEFAULT_HOST = "0.0.0.0"

class CompanionHandler(BaseHTTPRequestHandler):

    def _send_json(self, status_code: int, data: Dict[str, Any]):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode("utf-8"))

    def do_OPTIONS(self):
        self._send_json(200, {"status": "ok"})

    def do_GET(self):
        if self.path == "/" or self.path == "/api/status":
            self._send_json(200, {
                "status": "online",
                "system": platform.system(),
                "node": platform.node(),
                "release": platform.release(),
                "version": "1.0.0"
            })
        else:
            self._send_json(404, {"error": "Endpoint not found"})

    def do_POST(self):
        if self.path == "/api/command":
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception as e:
                self._send_json(400, {"error": f"Invalid JSON: {e}"})
                return

            cmd = payload.get("command", "").lower().strip()
            param = payload.get("param", "").strip()

            result = self.handle_command(cmd, param)
            self._send_json(200, result)
        else:
            self._send_json(404, {"error": "Endpoint not found"})

    def handle_command(self, cmd: str, param: str) -> Dict[str, Any]:
        os_type = platform.system()

        if cmd == "open_app":
            app_name = param
            if os_type == "Darwin":
                subprocess.Popen(["open", "-a", app_name])
            elif os_type == "Windows":
                subprocess.Popen(f"start {app_name}", shell=True)
            else:
                subprocess.Popen([app_name], shell=True)
            return {"status": "ok", "message": f"Launched {app_name}"}

        elif cmd == "adjust_volume":
            direction = param.lower()
            if os_type == "Darwin":
                if direction == "up":
                    subprocess.run(["osascript", "-e", "set volume output volume ((output volume of (get volume settings)) + 10)"])
                elif direction == "down":
                    subprocess.run(["osascript", "-e", "set volume output volume ((output volume of (get volume settings)) - 10)"])
                elif direction == "mute":
                    subprocess.run(["osascript", "-e", "set volume with output muted"])
            elif os_type == "Linux":
                if direction == "up":
                    subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", "+10%"], check=False)
                elif direction == "down":
                    subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", "-10%"], check=False)
                elif direction == "mute":
                    subprocess.run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"], check=False)
            return {"status": "ok", "message": f"Volume modulated ({direction})"}

        elif cmd == "search_files":
            query = param
            home_dir = os.path.expanduser("~")
            matches = []
            for root, dirs, files in os.walk(home_dir):
                for f in files:
                    if query.lower() in f.lower():
                        matches.append(os.path.join(root, f))
                        if len(matches) >= 5:
                            break
                if len(matches) >= 5:
                    break
            count = len(matches)
            first_match = matches[0] if matches else "none"
            return {"status": "ok", "message": f"Found {count} file matches. First: {os.path.basename(first_match)}", "files": matches}

        elif cmd == "lock":
            if os_type == "Darwin":
                subprocess.run(["pmset", "displaysleepnow"])
            elif os_type == "Windows":
                subprocess.run(["rundll32.exe", "user32.dll,LockWorkStation"])
            else:
                subprocess.run(["loginctl", "lock-session"], check=False)
            return {"status": "ok", "message": "Workstation locked"}

        elif cmd == "status":
            return {"status": "ok", "message": f"Host {platform.node()} ({os_type}) operating normally"}

        return {"status": "ok", "message": f"Received directive: {cmd} with parameter {param}"}

def run_server(host=DEFAULT_HOST, port=DEFAULT_PORT):
    local_ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    server = HTTPServer((host, port), CompanionHandler)
    print(f"==================================================")
    print(f"  G.H.O.S.T. Tactical Desktop Companion Daemon   ")
    print(f"==================================================")
    print(f"[*] Companion Server listening on: http://{host}:{port}")
    print(f"[*] Local IP for Ghost App Config: {local_ip}:{port}")
    print(f"[*] System Architecture: {platform.system()} {platform.machine()}")
    print(f"[*] Ready to receive Ghost Android directives.")
    print(f"==================================================\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[!] Shutting down companion server.")
        server.server_close()

if __name__ == "__main__":
    port = DEFAULT_PORT
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        port = int(sys.argv[1])
    run_server(port=port)
