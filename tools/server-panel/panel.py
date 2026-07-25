"""Jockey Radio - local dev server panel (sow-003).

Single-file, stdlib-only web panel that manages a Minecraft Fabric dedicated
server living in <repo>/.server. Originates from the sow-003 Scope of Work:
give mod developers a one-click way to start/stop a local test server, run
environment diagnostics (--doctor), and bootstrap the server jar (--setup),
without ever auto-accepting the Minecraft EULA on the user's behalf.

Usage:
    python3 panel.py --doctor   # environment checks, exit 0/1
    python3 panel.py --setup    # idempotent bootstrap of .server/
    python3 panel.py            # run the panel at http://127.0.0.1:<port>

The HTTP server binds 127.0.0.1 only and is not configurable to any other
interface by design.
"""

from __future__ import annotations

import argparse
import atexit
import errno
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import FrameType
from typing import Any
from urllib.parse import parse_qs, urlparse

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
SERVER_DIR = REPO_ROOT / ".server"
JAR_PATH = SERVER_DIR / "fabric-server-launch.jar"
EULA_PATH = SERVER_DIR / "eula.txt"
LATEST_LOG = SERVER_DIR / "logs" / "latest.log"
CONSOLE_LOG = SERVER_DIR / "panel-console.log"
PID_FILE = SERVER_DIR / "panel.pid"

FABRIC_JAR_URL = (
    "https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{installer}/server/jar"
)

REQUIRED_JAVA_MAJOR = 25

TEMURIN_HELP = (
    "Install Temurin JDK 25 into ~/.jdks:  mkdir -p ~/.jdks && "
    "curl -L 'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' "
    "-o /tmp/temurin25.tar.gz && tar -xzf /tmp/temurin25.tar.gz -C ~/.jdks"
)
SETUP_HELP = "Server jar missing - run: python3 panel.py --setup"
EULA_HELP = (
    "EULA not accepted - open the panel and click 'I accept the Minecraft EULA' "
    "(read it at https://aka.ms/MinecraftEULA)"
)


def load_config() -> dict[str, Any]:
    with open(SCRIPT_DIR / "config.json", encoding="utf-8") as fh:
        cfg: dict[str, Any] = json.load(fh)
    cfg["java_bin"] = str(Path(cfg["java_bin"]).expanduser())
    return cfg


CONFIG = load_config()

PANEL_PORT = int(CONFIG.get("panel_port", 8750))
# CSRF / DNS-rebinding guards: a web page in the user's browser can fire
# no-preflight "simple" POSTs at 127.0.0.1, and a rebound domain becomes
# same-origin with the panel. Browsers cannot strip Origin on cross-origin
# POSTs nor forge Host to these exact values, so exact-matching both blocks
# drive-by requests while keeping curl/CLI (no Origin header) working.
ALLOWED_HOSTS = frozenset({f"127.0.0.1:{PANEL_PORT}", f"localhost:{PANEL_PORT}"})
ALLOWED_ORIGINS = frozenset(
    {f"http://127.0.0.1:{PANEL_PORT}", f"http://localhost:{PANEL_PORT}"}
)


# ---------------------------------------------------------------------------
# Doctor checks
# ---------------------------------------------------------------------------

def parse_java_major(version_output: str) -> tuple[str, int]:
    """Extract ('25.0.3', 25) from `java -version` output (printed to stderr)."""
    match = re.search(r'version "([^"]+)"', version_output)
    if not match:
        return "", 0
    version = match.group(1)
    parts = version.split(".")
    # Legacy JDKs report "1.8.0_x"; the real major is the second component.
    major_str = parts[1] if parts[0] == "1" and len(parts) > 1 else parts[0]
    try:
        return version, int(major_str)
    except ValueError:
        return version, 0


def check_java() -> tuple[bool, str, list[str]]:
    """Return (ok, version_string, remediation_messages)."""
    java = Path(CONFIG["java_bin"])
    if not java.is_file():
        return False, "", [f"java_bin not found at {java}", TEMURIN_HELP]
    try:
        proc = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=15
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, "", [f"java_bin failed to run: {exc}", TEMURIN_HELP]
    version, major = parse_java_major(proc.stderr + proc.stdout)
    if major != REQUIRED_JAVA_MAJOR:
        return False, version, [
            f"java major version is {major or 'unknown'} (need {REQUIRED_JAVA_MAJOR})",
            TEMURIN_HELP,
        ]
    return True, version, []


_java_cache: tuple[float, tuple[bool, str, list[str]]] | None = None
_java_cache_lock = threading.Lock()


def cached_java_check() -> tuple[bool, str, list[str]]:
    """Cache `java -version` for 30 s; the UI polls /status every 2 s."""
    global _java_cache
    with _java_cache_lock:
        now = time.monotonic()
        if _java_cache is None or now - _java_cache[0] > 30.0:
            _java_cache = (now, check_java())
        return _java_cache[1]


def fresh_java_check() -> tuple[bool, str, list[str]]:
    """Uncached probe for rare actions (/start), refreshing the /status cache.

    The 30 s cache exists only for the status poll; consulting it on /start
    would report stale results in both directions (JDK just installed, or
    just removed).
    """
    global _java_cache
    result = check_java()
    with _java_cache_lock:
        _java_cache = (time.monotonic(), result)
    return result


def invalidate_java_cache() -> None:
    global _java_cache
    with _java_cache_lock:
        _java_cache = None


def orphan_server_pid() -> int | None:
    """PID of a server left behind by a previous panel run, or None.

    panel.pid survives an unclean panel exit (SIGKILL, closed terminal); the
    orphaned JVM then holds the world's session.lock and the server port, so
    a fresh panel must not spawn a second instance on top of it. Removes the
    file when the recorded process is provably dead.
    """
    try:
        pid = int(PID_FILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None
    if pid <= 0:
        return None
    try:
        os.kill(pid, 0)  # signal 0: existence probe only
    except ProcessLookupError:
        PID_FILE.unlink(missing_ok=True)  # stale file, process is gone
        return None
    except PermissionError:
        return pid  # alive, owned by another user
    return pid


def eula_accepted() -> bool:
    if not EULA_PATH.is_file():
        return False
    try:
        text = EULA_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return any(
        line.strip().lower().replace(" ", "") == "eula=true"
        for line in text.splitlines()
    )


def doctor_report(own_pid: int | None = None) -> dict[str, Any]:
    java_ok, version, messages = cached_java_check()
    jar_present = JAR_PATH.is_file()
    messages = list(messages)
    if not jar_present:
        messages.append(SETUP_HELP)
    orphan = orphan_server_pid()
    if orphan is not None and orphan != own_pid:
        messages.append(
            f"a server from a previous panel run appears to be alive "
            f"(pid {orphan}, recorded in {PID_FILE}) - stop it (kill {orphan}) "
            "or delete the file if it is wrong; Start is refused until then"
        )
    return {
        "java_ok": java_ok,
        "java_version": version,
        "jar_present": jar_present,
        "messages": messages,
    }


def cmd_doctor() -> int:
    """Human-readable environment checks. Exit 0 only if everything passes."""
    all_ok = True

    java_ok, version, messages = check_java()
    print(f"[{'PASS' if java_ok else 'FAIL'}] Java 25 at {CONFIG['java_bin']}"
          + (f" (version {version})" if version else ""))
    for msg in messages:
        print(f"       -> {msg}")
    all_ok &= java_ok

    jar_present = JAR_PATH.is_file()
    print(f"[{'PASS' if jar_present else 'FAIL'}] Server jar at {JAR_PATH}")
    if not jar_present:
        print(f"       -> {SETUP_HELP}")
    all_ok &= jar_present

    orphan = orphan_server_pid()
    if orphan is not None:
        print(f"[WARN] a server from a previous panel run appears to be alive "
              f"(pid {orphan}, recorded in {PID_FILE})")
        print(f"       -> stop it (kill {orphan}) or delete the file if wrong")

    if eula_accepted():
        print(f"[PASS] EULA accepted in {EULA_PATH}")
    else:
        all_ok = False
        print(f"[FAIL] EULA state in {EULA_PATH}")
        if EULA_PATH.is_file():
            print(f"       -> {EULA_HELP}")
        else:
            print("       -> eula.txt does not exist yet - start the server once "
                  "from the panel to generate it, then accept the EULA")

    print("doctor: all checks passed" if all_ok else "doctor: some checks failed")
    return 0 if all_ok else 1


# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

def cmd_setup() -> None:
    """Idempotent bootstrap: directories + server jar download."""
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    (SERVER_DIR / "mods").mkdir(exist_ok=True)
    print(f"ensured {SERVER_DIR} and {SERVER_DIR / 'mods'} exist")

    if JAR_PATH.is_file():
        print(f"server jar already present: {JAR_PATH} (nothing to download)")
        return

    url = FABRIC_JAR_URL.format(
        mc=CONFIG["mc_version"],
        loader=CONFIG["loader_version"],
        installer=CONFIG["installer_version"],
    )
    part = JAR_PATH.with_name(JAR_PATH.name + ".part")
    print(f"downloading {url}")
    try:
        with urllib.request.urlopen(url) as resp, open(part, "wb") as out:
            while chunk := resp.read(65536):
                out.write(chunk)
    except urllib.error.HTTPError as exc:
        part.unlink(missing_ok=True)
        print(
            f"download failed (HTTP {exc.code}) for {url}\n"
            "check mc_version/loader_version/installer_version in config.json "
            "against https://meta.fabricmc.net/v2/versions",
            file=sys.stderr,
        )
        sys.exit(1)
    except OSError as exc:  # URLError, socket/TLS errors, disk errors
        part.unlink(missing_ok=True)
        print(
            f"download failed for {url}: {exc}\n"
            "check your network connection and the version pins in config.json",
            file=sys.stderr,
        )
        sys.exit(1)
    # Atomic rename so an interrupted download never masquerades as a jar.
    part.replace(JAR_PATH)
    print(f"downloaded {JAR_PATH}")


# ---------------------------------------------------------------------------
# Process management
# ---------------------------------------------------------------------------

class ServerManager:
    """Module-level singleton owning the Minecraft server child process."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._proc: subprocess.Popen[bytes] | None = None
        self._started_at: float = 0.0
        self._stop_requested: bool = False
        self._stopping: bool = False  # a graceful stop is in flight (unlocked waits)
        self._eula_gate: bool = False
        self._done_seen: bool = False
        self._last_exit_state: str = "stopped"
        self._exit_note: str = ""  # human hint about the last exit, for /status
        # latest.log identity at spawn - (st_ino, st_size, first bytes) - and
        # the byte offset scanning resumes from once the file has demonstrably
        # changed. See _check_done_locked for why mtime alone is not enough.
        self._log_snapshot: tuple[int, int, bytes] | None = None
        self._log_scan_pos: int | None = None

    # -- state ------------------------------------------------------------

    def _state_locked(self) -> str:
        if self._proc is not None and self._proc.poll() is not None:
            # Crash-vs-stop detection: an exit we asked for is a clean stop.
            # A eula-gate first boot only counts as clean if it achieved its
            # purpose (eula.txt now exists); if the jar/JVM died before
            # vanilla's EULA check the user must see "crashed", not an
            # inviting "stopped" with an empty log pane.
            if self._stop_requested:
                self._last_exit_state = "stopped"
                self._exit_note = ""
            elif self._eula_gate and EULA_PATH.is_file():
                self._last_exit_state = "stopped"
                self._exit_note = ""
            elif self._eula_gate:
                self._last_exit_state = "crashed"
                self._exit_note = (
                    "first boot exited without writing eula.txt - "
                    f"check {CONSOLE_LOG} for the error"
                )
            else:
                self._last_exit_state = "crashed"
                self._exit_note = f"server exited unexpectedly - check {CONSOLE_LOG}"
            self._reap_locked()
        if self._proc is None:
            return self._last_exit_state
        if self._done_seen or self._check_done_locked():
            return "running"
        return "starting"

    def _check_done_locked(self) -> bool:
        # "Done (" from a previous run must not count. mtime comparisons are
        # not reliable here: on /restart the old latest.log was written well
        # under a second before the new spawn. Instead, at spawn we snapshot
        # the file's (inode, size) and refuse to scan until it demonstrably
        # changed (rotated by Fabric on boot, or grown), then scan FORWARD
        # from a boot-anchored offset so the marker can never scroll out of
        # a fixed tail window. Lazily evaluated on /status - no busy thread.
        try:
            st = LATEST_LOG.stat()
        except OSError:
            return False
        if self._log_scan_pos is None:
            if self._log_snapshot is None:
                self._log_scan_pos = 0  # file did not exist at spawn
            else:
                ino, size, prefix = self._log_snapshot
                try:
                    with open(LATEST_LOG, "rb") as fh:
                        cur_prefix = fh.read(len(prefix))
                except OSError:
                    return False
                # New inode, shrunk size, or changed leading bytes => the log
                # was rotated/rewritten: scan from the top. (The prefix check
                # catches truncate-then-grow between two polls, where stat
                # alone looks like an append.)
                if st.st_ino != ino or st.st_size < size or cur_prefix != prefix:
                    self._log_scan_pos = 0
                elif st.st_size == size:
                    return False  # still the previous boot's untouched log
                else:
                    self._log_scan_pos = size  # in-place append: new bytes only
        if st.st_size < self._log_scan_pos:
            self._log_scan_pos = 0  # rotated again mid-scan
        try:
            with open(LATEST_LOG, "rb") as fh:
                fh.seek(self._log_scan_pos)
                carry = b""  # small overlap so a marker split across chunks matches
                while chunk := fh.read(1 << 20):
                    if b"Done (" in carry + chunk:
                        self._done_seen = True
                        return True
                    carry = chunk[-8:]
                    self._log_scan_pos = fh.tell() - len(carry)
        except OSError:
            return False
        return False

    def _reap_locked(self) -> None:
        assert self._proc is not None
        self._proc.wait()  # already exited; reap so no zombie remains
        if self._proc.stdin is not None:
            try:
                self._proc.stdin.close()
            except OSError:
                pass
        self._proc = None
        PID_FILE.unlink(missing_ok=True)

    def status(self) -> dict[str, Any]:
        with self._lock:
            state = self._state_locked()
            pid = self._proc.pid if self._proc is not None else None
            uptime = time.time() - self._started_at if self._proc is not None else 0.0
            stopping = self._stopping
            note = self._exit_note
        doctor = doctor_report(own_pid=pid)
        if note:
            doctor["messages"].append(note)
        return {
            "state": state,
            "pid": pid,
            "uptime_s": round(uptime, 1),
            "stopping": stopping,
            "eula_accepted": eula_accepted(),
            "doctor": doctor,
        }

    # -- start / stop ------------------------------------------------------

    def start(self) -> tuple[int, dict[str, Any]]:
        with self._lock:
            # Double-start refusal is race-free: state check and spawn happen
            # under the same lock.
            if self._stopping:
                return 409, {"ok": False, "message": "server is still stopping"}
            state = self._state_locked()
            if state in ("starting", "running"):
                return 409, {"ok": False, "message": f"server is already {state}"}

            # A JVM orphaned by a previous panel run holds session.lock and
            # the server port; spawning on top of it fails confusingly.
            orphan = orphan_server_pid()
            if orphan is not None:
                return 409, {
                    "ok": False,
                    "message": (
                        f"a server from a previous panel run is still alive "
                        f"(pid {orphan}) - stop it first: kill {orphan}"
                    ),
                }

            java_ok, _, messages = fresh_java_check()
            if not java_ok:
                return 400, {"ok": False, "message": "; ".join(messages)}
            if not JAR_PATH.is_file():
                return 400, {"ok": False, "message": SETUP_HELP}

            # EULA guardrail: if eula.txt does not exist yet, allow exactly one
            # "eula-gate boot" - vanilla behaviour is to write eula.txt(false)
            # and exit, which we surface as a clean stop (only once the file
            # actually appears; see _state_locked). The panel itself NEVER
            # writes eula=true without a human click.
            gate = not EULA_PATH.exists()
            if not gate and not eula_accepted():
                return 400, {"ok": False, "message": EULA_HELP}

            try:
                self._spawn_locked(eula_gate=gate)
            except OSError as exc:
                # e.g. java_bin vanished since the check; without this the
                # exception would escape do_POST and the UI would only see a
                # dropped connection.
                invalidate_java_cache()
                return 500, {"ok": False, "message": f"failed to launch java: {exc}"}
            message = (
                "First boot started - the server will write eula.txt and exit; "
                "then accept the EULA to continue"
                if gate else "server starting"
            )
            return 200, {"ok": True, "message": message}

    def _spawn_locked(self, eula_gate: bool) -> None:
        SERVER_DIR.mkdir(parents=True, exist_ok=True)
        cmd = [CONFIG["java_bin"], *CONFIG["memory"], "-jar", JAR_PATH.name, "nogui"]
        # Snapshot latest.log BEFORE spawning so the child cannot rotate it
        # first; _check_done_locked uses this to ignore stale content.
        try:
            st = LATEST_LOG.stat()
            with open(LATEST_LOG, "rb") as fh:
                prefix = fh.read(256)
            self._log_snapshot = (st.st_ino, st.st_size, prefix)
        except OSError:
            self._log_snapshot = None
        self._log_scan_pos = None
        # stdout/stderr go to an append-mode file, never subprocess.PIPE: an
        # unread pipe fills up and deadlocks the JVM mid-log-line.
        console = open(CONSOLE_LOG, "ab")
        try:
            self._proc = subprocess.Popen(
                cmd,
                cwd=SERVER_DIR,
                stdin=subprocess.PIPE,
                stdout=console,
                stderr=console,
            )
        finally:
            console.close()  # child holds its own duplicate of the fd
        self._started_at = time.time()
        self._stop_requested = False
        self._eula_gate = eula_gate
        self._done_seen = False
        self._exit_note = ""
        PID_FILE.write_text(str(self._proc.pid), encoding="utf-8")

    def stop(self) -> tuple[int, dict[str, Any]]:
        with self._lock:
            if self._stopping:
                return 409, {"ok": False, "message": "server is already stopping"}
            if self._state_locked() not in ("starting", "running"):
                return 409, {"ok": False, "message": "server is not running"}
            proc = self._proc
            assert proc is not None
            self._stop_requested = True
            self._stopping = True
        # The waits below can take stop_timeout_s + 5 s; they run WITHOUT the
        # lock so /status polls (and the whole UI) stay responsive. /status
        # surfaces the in-flight stop via the "stopping" flag.
        self._stop_and_reap(proc)
        return 200, {"ok": True, "message": "server stopped"}

    def _stop_and_reap(self, proc: subprocess.Popen[bytes]) -> None:
        """Blocking graceful-stop escalation; must NOT be called holding _lock."""
        try:
            self._shutdown_proc(proc)
        finally:
            with self._lock:
                self._stopping = False
                # A concurrent /status may have observed the exit and reaped
                # already (classified "stopped" via _stop_requested).
                if self._proc is proc:
                    self._last_exit_state = "stopped"
                    self._exit_note = ""
                    self._reap_locked()

    @staticmethod
    def _shutdown_proc(proc: subprocess.Popen[bytes]) -> None:
        try:
            if proc.stdin is not None:
                proc.stdin.write(b"stop\n")  # graceful in-game save + shutdown
                proc.stdin.flush()
        except (BrokenPipeError, OSError):
            pass  # process already dying; escalate via wait/terminate below
        try:
            proc.wait(timeout=float(CONFIG["stop_timeout_s"]))
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()  # always reap after kill - never leave a zombie

    def restart(self) -> tuple[int, dict[str, Any]]:
        # A 409 from stop() just means "wasn't running" - restart proceeds.
        self.stop()
        return self.start()

    def shutdown(self) -> None:
        """Panel-exit hook: gracefully stop a still-running server."""
        while True:
            with self._lock:
                proc = self._proc
                if proc is None or proc.poll() is not None:
                    return
                if not self._stopping:
                    self._stop_requested = True
                    self._stopping = True
                    break
            # A stop is already in flight on a (daemon) HTTP thread; wait for
            # its bounded escalation instead of exiting out from under it.
            time.sleep(0.2)
        self._stop_and_reap(proc)


MANAGER = ServerManager()


# ---------------------------------------------------------------------------
# EULA + logs
# ---------------------------------------------------------------------------

def accept_eula() -> tuple[int, dict[str, Any]]:
    # Guardrail: this only rewrites an EXISTING eula.txt (written by the
    # server itself on first boot) and is only reachable from an explicit
    # human click in the UI. The panel never creates or auto-accepts it.
    if not EULA_PATH.is_file():
        return 400, {
            "ok": False,
            "message": "eula.txt does not exist yet - start the server once first",
        }
    lines = EULA_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    rewritten: list[str] = []
    changed = False
    for line in lines:
        if line.strip().lower().startswith("eula="):
            rewritten.append("eula=true")
            changed = True
        else:
            rewritten.append(line)  # preserve the timestamp/comment lines
    if not changed:
        rewritten.append("eula=true")
    EULA_PATH.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
    return 200, {"ok": True, "message": "EULA accepted"}


def read_log_tail(n: int) -> str:
    """Last n lines of logs/latest.log; empty string if the file is missing."""
    if not LATEST_LOG.is_file():
        return ""
    try:
        with open(LATEST_LOG, "rb") as fh:
            fh.seek(0, 2)
            size = fh.tell()
            fh.seek(max(0, size - 512_000))  # bounded read from the end
            data = fh.read()
    except OSError:
        return ""
    lines = data.decode("utf-8", errors="replace").splitlines()
    return "\n".join(lines[-n:])


# ---------------------------------------------------------------------------
# HTTP layer
# ---------------------------------------------------------------------------

PAGE_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Jockey Radio - Dev Server Panel</title>
<style>
  :root { color-scheme: dark; }
  body { background:#0f1216; color:#d7dde4; margin:0; padding:2rem 1rem;
         font:15px/1.5 system-ui, sans-serif; }
  main { max-width:60rem; margin:0 auto; }
  h1 { font-size:1.3rem; margin:0 0 1rem; }
  h2 { font-size:1rem; margin:1.5rem 0 .5rem; }
  .badge { padding:.15rem .65rem; border-radius:999px; font-weight:600;
           text-transform:uppercase; font-size:.75rem; letter-spacing:.06em; }
  .badge.stopped  { background:#3a4351; color:#c3ccd8; }
  .badge.starting { background:#7a5b13; color:#ffd98a; }
  .badge.running  { background:#1d5c33; color:#9ff0bb; }
  .badge.crashed  { background:#6b2027; color:#ffb3ba; }
  button { background:#2a3342; color:#e6ecf3; border:1px solid #3d4a5d;
           border-radius:6px; padding:.45rem 1.1rem; font-size:.95rem;
           cursor:pointer; margin-right:.4rem; }
  button:hover:not(:disabled) { background:#37455c; }
  button:disabled { opacity:.4; cursor:not-allowed; }
  #eula { border:1px solid #7a5b13; background:#241c09; border-radius:8px;
          padding:1rem; margin:1rem 0; }
  #eula a { color:#ffd98a; }
  pre#log { background:#090b0e; border:1px solid #232b36; border-radius:8px;
            padding:1rem; max-height:24rem; overflow:auto; font-size:.8rem;
            white-space:pre-wrap; }
  ul#doctor { color:#ffb3ba; }
  .meta { color:#8b96a5; font-size:.85rem; }
</style>
</head>
<body>
<main>
  <h1>Jockey Radio - Dev Server Panel</h1>
  <p>
    <span id="badge" class="badge stopped">stopped</span>
    <span id="meta" class="meta"></span>
  </p>
  <p>
    <button id="btnStart">Start</button>
    <button id="btnStop" disabled>Stop</button>
    <button id="btnRestart" disabled>Restart</button>
    <span id="msg" class="meta"></span>
  </p>
  <ul id="doctor"></ul>
  <section id="eula" hidden>
    <strong>Minecraft EULA not accepted.</strong>
    <p>The dedicated server refuses to run until <code>.server/eula.txt</code>
       contains <code>eula=true</code>. Read the
       <a href="https://aka.ms/MinecraftEULA" target="_blank" rel="noopener">Minecraft EULA</a>.
       If <code>eula.txt</code> does not exist yet, press Start once to
       generate it, then accept below.</p>
    <button id="btnEula">I accept the Minecraft EULA</button>
  </section>
  <h2>Log tail (logs/latest.log)</h2>
  <pre id="log"></pre>
</main>
<script>
"use strict";
var $ = function (id) { return document.getElementById(id); };

function fmtUptime(s) {
  s = Math.floor(s);
  var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + "h " : "") + ((h || m) ? m + "m " : "") + sec + "s";
}

var msgSetAt = 0;
function setMsg(text) {
  $("msg").textContent = text;
  msgSetAt = Date.now();
}

function refreshStatus() {
  fetch("/status").then(function (r) { return r.json(); }).then(function (s) {
    var badge = $("badge");
    badge.textContent = s.state;
    badge.className = "badge " + s.state;
    $("meta").textContent =
      (s.pid ? "pid " + s.pid + " | up " + fmtUptime(s.uptime_s) : "")
      + (s.stopping ? " | stopping..." : "");
    var live = s.state === "starting" || s.state === "running";
    $("btnStart").disabled = live || s.stopping;
    $("btnStop").disabled = !live || s.stopping;
    $("btnRestart").disabled = !live || s.stopping;
    // Hide the EULA section while a boot is in flight: during the eula-gate
    // first boot the file does not exist yet, so accepting cannot work.
    $("eula").hidden = s.eula_accepted || s.state === "starting";
    var doctor = $("doctor");
    doctor.textContent = "";
    s.doctor.messages.forEach(function (m) {
      var li = document.createElement("li");
      li.textContent = m;
      doctor.appendChild(li);
    });
    // Age out button-click messages so stale errors do not contradict the
    // (now green) live state indefinitely.
    if ($("msg").textContent && Date.now() - msgSetAt > 15000) {
      $("msg").textContent = "";
    }
  }).catch(function () { /* panel unreachable; keep last view */ });
}

function refreshLogs() {
  fetch("/logs?tail=200").then(function (r) { return r.text(); }).then(function (text) {
    var log = $("log");
    var pinned = log.scrollTop + log.clientHeight >= log.scrollHeight - 8;
    log.textContent = text;
    if (pinned) log.scrollTop = log.scrollHeight;
  }).catch(function () {});
}

function post(path) {
  fetch(path, { method: "POST" }).then(function (r) { return r.json(); })
    .then(function (j) {
      setMsg(j.message || "");
      refreshStatus();
    })
    .catch(function () { setMsg("request failed"); });
}

$("btnStart").onclick = function () { post("/start"); };
$("btnStop").onclick = function () { post("/stop"); };
$("btnRestart").onclick = function () { post("/restart"); };
$("btnEula").onclick = function () {
  if (confirm("Accept the Minecraft EULA? This writes eula=true to .server/eula.txt.")) {
    post("/accept-eula");
  }
};

refreshStatus();
refreshLogs();
setInterval(refreshStatus, 2000);
setInterval(refreshLogs, 3000);
</script>
</body>
</html>
"""


class PanelHandler(BaseHTTPRequestHandler):
    server_version = "SavePointPanel/1.0"

    def log_message(self, format: str, *args: Any) -> None:
        pass  # the UI polls every 2-3 s; default access logging is pure noise

    # -- helpers -----------------------------------------------------------

    def _send_bytes(self, code: int, content_type: str, payload: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _send_json(self, code: int, body: dict[str, Any]) -> None:
        self._send_bytes(
            code, "application/json; charset=utf-8", json.dumps(body).encode("utf-8")
        )

    # -- request guards ----------------------------------------------------

    def _host_ok(self) -> bool:
        # DNS-rebinding guard: a domain rebound to 127.0.0.1 reaches this
        # socket but carries its own Host header, so exact-matching Host
        # denies rebound pages both reads (GET) and writes (POST).
        if self.headers.get("Host") in ALLOWED_HOSTS:
            return True
        self._send_json(403, {"ok": False, "message": "forbidden: bad Host header"})
        return False

    def _origin_ok(self) -> bool:
        # CSRF guard: cross-origin form/no-cors POSTs are "simple requests"
        # that skip preflight, but browsers always attach Origin to them and
        # never let a page forge it. Absent Origin means a non-browser
        # client (curl), which is fine for a localhost dev tool.
        origin = self.headers.get("Origin")
        if origin is None or origin in ALLOWED_ORIGINS:
            return True
        self._send_json(
            403, {"ok": False, "message": "forbidden: cross-origin request rejected"}
        )
        return False

    # -- routes ------------------------------------------------------------

    def do_GET(self) -> None:
        if not self._host_ok():
            return
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self._send_bytes(200, "text/html; charset=utf-8", PAGE_HTML.encode("utf-8"))
        elif parsed.path == "/status":
            self._send_json(200, MANAGER.status())
        elif parsed.path == "/logs":
            self._handle_logs(parsed.query)
        else:
            self._send_json(404, {"ok": False, "message": "not found"})

    def do_POST(self) -> None:
        if not self._host_ok() or not self._origin_ok():
            return
        routes = {
            "/start": MANAGER.start,
            "/stop": MANAGER.stop,
            "/restart": MANAGER.restart,
            "/accept-eula": accept_eula,
        }
        handler = routes.get(urlparse(self.path).path)
        if handler is None:
            self._send_json(404, {"ok": False, "message": "not found"})
            return
        code, body = handler()
        self._send_json(code, body)

    def _handle_logs(self, query: str) -> None:
        # The file path is fixed server-side; only the line count is client input.
        raw = parse_qs(query).get("tail", ["100"])[0]
        try:
            n = int(raw)
        except ValueError:
            self._send_json(400, {"ok": False, "message": "tail must be an integer"})
            return
        n = max(1, min(n, 1000))
        self._send_bytes(
            200, "text/plain; charset=utf-8", read_log_tail(n).encode("utf-8")
        )


# ---------------------------------------------------------------------------
# Entrypoints
# ---------------------------------------------------------------------------

def _handle_signal(signum: int, frame: FrameType | None) -> None:
    # First interrupt wins: further SIGINT/SIGTERM are ignored from here on,
    # because a second Ctrl+C would otherwise raise inside the graceful
    # stop's proc.wait(), unwind the terminate/kill escalation, and orphan
    # the JVM with a stale panel.pid on disk.
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    if MANAGER._proc is not None:
        print(
            f"\nstopping the server first (up to {CONFIG['stop_timeout_s']} s) "
            "- please wait...",
            file=sys.stderr,
        )
    # SystemExit unwinds serve_forever; atexit + finally run the graceful stop.
    raise SystemExit(0)


def run_panel() -> None:
    port = PANEL_PORT
    try:
        # Hard-coded loopback bind: the panel has no auth, so it must never be
        # reachable from other machines.
        httpd = ThreadingHTTPServer(("127.0.0.1", port), PanelHandler)
    except OSError as exc:
        if exc.errno == errno.EADDRINUSE:
            print(
                f"port {port} is already in use - is another panel running? "
                "Stop it or change panel_port in config.json.",
                file=sys.stderr,
            )
            sys.exit(1)
        raise
    atexit.register(MANAGER.shutdown)
    signal.signal(signal.SIGINT, _handle_signal)
    signal.signal(signal.SIGTERM, _handle_signal)
    orphan = orphan_server_pid()
    if orphan is not None:
        print(
            f"warning: a server from a previous panel run appears to be alive "
            f"(pid {orphan}, recorded in {PID_FILE}) - Start is refused until "
            f"it is stopped (kill {orphan}) or the stale file is removed",
            file=sys.stderr,
        )
    print(f"Jockey Radio dev server panel: http://127.0.0.1:{port}")
    try:
        httpd.serve_forever()
    finally:
        MANAGER.shutdown()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--doctor", action="store_true", help="run environment checks")
    parser.add_argument("--setup", action="store_true", help="bootstrap .server/")
    args = parser.parse_args()
    if args.doctor:
        sys.exit(cmd_doctor())
    if args.setup:
        cmd_setup()
        return
    run_panel()


if __name__ == "__main__":
    main()
