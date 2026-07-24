#!/usr/bin/env python3
"""Minimal RCON client for the dev server (sow-001 spike/integration tooling).

Reads host/port/password from .server/server.properties, so there is no secret
to pass around. Usage:

    python3 tools/rcon.py <command ...>       # one command, prints the response
    python3 tools/rcon.py --stdin             # one command per line from stdin

Protocol: classic Source RCON (login type 3, command type 2), little-endian
int32 length/id/type + body + two NUL bytes.
"""

from __future__ import annotations

import socket
import struct
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = REPO_ROOT / ".server" / "server.properties"


def load_rcon_config() -> tuple[str, int, str]:
    props: dict[str, str] = {}
    for line in PROPERTIES.read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, _, v = line.partition("=")
            props[k.strip()] = v.strip()
    if props.get("enable-rcon") != "true":
        sys.exit("RCON is not enabled in .server/server.properties")
    return "127.0.0.1", int(props.get("rcon.port", "25575")), props["rcon.password"]


class Rcon:
    def __init__(self, host: str, port: int, password: str) -> None:
        self.sock = socket.create_connection((host, port), timeout=10)
        self._req_id = 0
        self._send(3, password)
        rid, _ = self._recv()
        if rid == -1:
            sys.exit("RCON authentication failed")

    def _send(self, ptype: int, body: str) -> int:
        self._req_id += 1
        payload = struct.pack("<ii", self._req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self._req_id

    def _recv(self) -> tuple[int, str]:
        def read_exact(n: int) -> bytes:
            buf = b""
            while len(buf) < n:
                chunk = self.sock.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError("RCON connection closed")
                buf += chunk
            return buf

        (length,) = struct.unpack("<i", read_exact(4))
        data = read_exact(length)
        rid, _ptype = struct.unpack("<ii", data[:8])
        return rid, data[8:-2].decode("utf-8", errors="replace")

    def command(self, cmd: str) -> str:
        self._send(2, cmd)
        _, body = self._recv()
        return body

    def close(self) -> None:
        self.sock.close()


def main() -> None:
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    host, port, password = load_rcon_config()
    rcon = Rcon(host, port, password)
    try:
        if args == ["--stdin"]:
            for line in sys.stdin:
                line = line.strip()
                if line:
                    print(rcon.command(line))
        else:
            print(rcon.command(" ".join(args)))
    finally:
        rcon.close()


if __name__ == "__main__":
    main()
