#!/usr/bin/env bash
# Zero-touch install check: a local stand-in for a managed host (no shell, no ffmpeg).
#
# Boots a throwaway Fabric server holding only the jar under test, Fabric API and Simple
# Voice Chat, on WSL's own Linux filesystem (DrvFs under /mnt marks every file executable,
# which would make the permission checks meaningless), with an empty PATH so no host
# ffmpeg or yt-dlp can be found. Four boots:
#
#   fresh     the bundled ffmpeg, yt-dlp and deno self-extract, owner-only, and run
#   reboot    the version marker is current, so nothing is extracted again
#   disabled  useBundledBinaries=false: ffmpeg is reported missing and the server still starts
#   stale     ffmpegBinary names a path that no longer exists: the bundled copy is used instead
#
# Usage: tools/zero-touch-check.sh [path/to/jockey-radio.jar]   (default: newest build/libs jar)
# Reuses .server/ for the launcher, the vanilla server jar, libraries and the EULA you
# accepted there, so it runs offline. KEEP=1 keeps the work directory after a pass.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/.server"
JAVA="${JAVA_HOME:-$HOME/.jdks/jdk-25.0.3+9}/bin/java"
WORK="${TMPDIR:-/tmp}/jockey-zero-touch"
BIN="$WORK/config/jockey_radio/bin"
SERVER_PORT=25587
VOICE_PORT=24467
AUDIO_PORT=24472
BOOT_TIMEOUT_SECONDS=300

JAR="${1:-$(ls -t "$REPO"/build/libs/jockey-radio-*.jar 2>/dev/null | grep -v -- '-sources' | head -n 1 || true)}"

die() { echo "zero-touch: $*" >&2; exit 2; }
[[ -f "$JAR" ]] || die "no jar to test (build one, or pass a path)"
[[ -x "$JAVA" ]] || die "no Java 25 at $JAVA (set JAVA_HOME)"
[[ -f "$SRC/fabric-server-launch.jar" && -d "$SRC/.fabric/server" ]] \
	|| die "no dev server at $SRC (run: python3 tools/server-panel/panel.py --setup)"
grep -q '^eula=true' "$SRC/eula.txt" 2>/dev/null \
	|| die "accept the Minecraft EULA for the dev server first (the panel walks you through it)"

echo "zero-touch: $(basename "$JAR") ($(( $(stat -c %s "$JAR") / 1048576 )) MB) in $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/mods" "$WORK/.fabric" "$WORK/config/voicechat"
cp -r "$SRC/libraries" "$SRC/versions" "$WORK/"
cp -r "$SRC/.fabric/server" "$WORK/.fabric/"
cp "$SRC/fabric-server-launch.jar" "$SRC/eula.txt" "$WORK/"
cp "$SRC"/mods/fabric-api-*.jar "$SRC"/mods/voicechat-*.jar "$JAR" "$WORK/mods/"
printf 'server-port=%s\nenable-rcon=false\nlevel-type=minecraft\\:flat\nmotd=jockey zero-touch check\n' \
	"$SERVER_PORT" > "$WORK/server.properties"
echo "port=$VOICE_PORT" > "$WORK/config/voicechat/voicechat-server.properties"
# Keep the local audio server off the dev server's port (24462): with WSL mirrored networking a
# WSL listener that just exited can briefly block the same port for a Windows server.
printf '{\n  "localAudioServerPort": %s,\n  "useBundledBinaries": true,\n  "ffmpegBinary": "ffmpeg"\n}\n' \
	"$AUDIO_PORT" > "$WORK/config/jockey_radio.json"

# Boots the server with an empty environment, waits for "Done (", then sends "stop".
boot() {
	local log="$WORK/boot-$1.log" fifo="$WORK/.stdin" waited=0 pid
	rm -f "$fifo"
	mkfifo "$fifo"
	(cd "$WORK" && exec env -i HOME="$WORK" PATH=/nonexistent \
		"$JAVA" -Xms512M -Xmx1G -jar fabric-server-launch.jar nogui) < "$fifo" > "$log" 2>&1 &
	pid=$!
	exec 3> "$fifo" # held open so the server never reads EOF on stdin
	until grep -q 'Done (' "$log"; do
		if ! kill -0 "$pid" 2>/dev/null || (( waited >= BOOT_TIMEOUT_SECONDS )); then
			kill -9 "$pid" 2>/dev/null || true
			exec 3>&-
			return 0 # the "server ready" check reports it
		fi
		sleep 1
		waited=$((waited + 1))
	done
	echo stop >&3
	for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
	kill -9 "$pid" 2>/dev/null || true
	exec 3>&-
	wait "$pid" 2>/dev/null || true
}

passed=0
failed=0
check() {
	local desc="$1"
	shift
	if "$@" > /dev/null 2>&1; then
		echo "  PASS  $desc"
		passed=$((passed + 1))
	else
		echo "  FAIL  $desc"
		failed=$((failed + 1))
	fi
}
logged() { grep -qF -- "$2" "$WORK/boot-$1.log"; }
no_crash_reports() { [[ -z "$(ls -A "$WORK/crash-reports" 2>/dev/null)" ]]; }
owner_only_exec() { [[ "$(stat -c %a "$1")" == 700 ]]; }
ffmpeg_runs() { [[ "$(env -i "$BIN/ffmpeg" -hide_banner -version 2>&1 | head -n 1)" == "ffmpeg version"* ]]; }
no_jockey_errors() { ! grep -E '\[jockey\].*(could not install|Exception)' "$WORK/boot-$1.log"; }

echo "boot 1: fresh"
boot fresh
check "server reached ready" logged fresh 'Done ('
check "bundled binaries installed" logged fresh '[jockey] installed bundled ffmpeg + yt-dlp + deno'
for tool in ffmpeg yt-dlp deno; do
	check "$tool extracted, owner-only (700)" owner_only_exec "$BIN/$tool"
done
check "extracted ffmpeg runs" ffmpeg_runs
check "host verdict: extracted binaries are executable" logged fresh 'host VERDICT: extracted binaries'
check "no jockey errors" no_jockey_errors fresh
check "no crash reports" no_crash_reports

echo "boot 2: reboot"
boot reboot
check "server reached ready" logged reboot 'Done ('
check "nothing extracted again" bash -c "! grep -qF 'installed bundled' '$WORK/boot-reboot.log'"

echo "boot 3: bundling disabled"
CONFIG="$WORK/config/jockey_radio.json"
check "config has useBundledBinaries" grep -q '"useBundledBinaries": true' "$CONFIG"
sed -i 's/"useBundledBinaries": true/"useBundledBinaries": false/' "$CONFIG" 2>/dev/null || true
boot disabled
check "server reached ready" logged disabled 'Done ('
check "ffmpeg reported missing (empty PATH)" logged disabled 'host ffmpeg=missing'
check "no crash reports" no_crash_reports

echo "boot 4: stale configured path"
sed -i -e 's/"useBundledBinaries": false/"useBundledBinaries": true/' \
	-e 's#"ffmpegBinary": "[^"]*"#"ffmpegBinary": "D:/moved-away/ffmpeg.exe"#' "$CONFIG" 2>/dev/null || true
boot stale
check "server reached ready" logged stale 'Done ('
check "missing configured path falls back to the bundled copy" \
	logged stale "configured ffmpegBinary 'D:/moved-away/ffmpeg.exe' does not exist - using the bundled copy"
check "ffmpeg not reported missing" bash -c "! grep -qF 'host ffmpeg=missing' '$WORK/boot-stale.log'"

echo "zero-touch: $passed passed, $failed failed"
if (( failed > 0 )); then
	echo "zero-touch: logs kept in $WORK"
	exit 1
fi
[[ "${KEEP:-0}" == 1 ]] || rm -rf "$WORK"
