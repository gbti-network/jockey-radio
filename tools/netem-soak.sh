#!/usr/bin/env bash
# netem-soak.sh: impair ONLY the Simple Voice Chat audio egress (UDP source port 24454) with
# realistic WAN/wifi conditions, to soak-test whether a STOCK SVC client (no SavePoint client
# mod) holds up, and whether Opus FEC (opusFecPacketLossPercent > 0) measurably helps.
#
# How it stays out of the way: the interface's own root qdisc is never touched. A clsact qdisc
# (added beside the root qdisc, or reused if one is already there) carries two egress filters
# that redirect SVC audio to a private ifb device, jrsoak0, whose root qdisc is the netem
# profile. SSH, rcon, the web stack and everything else leave through the interface's qdisc
# exactly as before, so this is safe on a remote server.
#
# The impairment lasts while the script runs. Ctrl-C, the optional time limit, or any exit
# (a trap covers INT, TERM and HUP) removes exactly what it added: its two filters, jrsoak0,
# and the clsact qdisc if it added that. After a SIGKILL, run `netem-soak.sh clear <iface>`.
#
# WHERE to run: on the MACHINE RUNNING THE MINECRAFT SERVER, on a real Linux host that is NOT
# freezing (production, cloud or bare metal; verify `hostFreezes=0` in the feed stats first).
# Under the WSL2 dev VM it is pointless: the ~815 ms/33 s host freeze confounds every result,
# and the WSL kernel ships without ifb or netem.
#
# TEST PROTOCOL (the point of this tool):
#   Preconditions: server freeze-free (hostFreezes=0); the LISTENER runs a STOCK SVC client
#   (SavePoint client-svc-buffer mod REMOVED) with SVC output_buffer_size=16 (the max in-game
#   setting; the default is only 5 = 100 ms). Feed diagnostics on.
#   For each profile below: run it, listen ~3-5 min, note audible dropouts and SVC's own
#   "lost packets"/stats, then stop it. Then set opusFecPacketLossPercent=15 (restart the
#   server), repeat, and compare. FEC should cut isolated-loss dropouts; it will NOT help pure
#   jitter/late arrival (only a deeper client buffer does).
#
# Usage:
#   sudo tools/netem-soak.sh <profile> [iface] [port] [minutes]   # runs until Ctrl-C or minutes
#   sudo tools/netem-soak.sh clear     [iface]                    # recover after a SIGKILL
#   sudo tools/netem-soak.sh status    [iface]
#   DRY_RUN=1 tools/netem-soak.sh ...  # print the changing tc/ip commands instead of running them
#
# Profiles (delay = one-way added latency; loss/reorder/dup as noted):
#   wan       40ms, 15ms jitter (25% corr), 0.5% loss          (a good broadband path)
#   lossy     60ms, 30ms jitter, 2% loss, 1% reorder           (a mediocre path)
#   wifi      30ms, 40ms bursty jitter, 1% loss (30% burst)    (flaky wifi)
#   harsh     80ms, 50ms jitter, 5% loss, 2% reorder, 0.2% dup (worst-case mobile/DSL)
set -euo pipefail

PROFILE="${1:-}"
IFACE="${2:-$(ip route 2>/dev/null | awk '/default/{print $5; exit}')}"
PORT="${3:-24454}"
MINUTES="${4:-}"
IFB=jrsoak0
# Filter preferences that mark the filters as ours, so clear never removes anyone else's.
PREF_V4=49152
PREF_V6=49153

usage() { sed -n '2,43p' "$0"; exit 1; }
die() { echo "!! $*" >&2; exit 1; }
# Commands that change state go through run, so DRY_RUN can print them. Reads always run.
if [[ "${DRY_RUN:-0}" == 1 ]]; then run() { echo "+ $*"; }; else run() { "$@"; }; fi

[[ -n "$PROFILE" ]] || usage
[[ -n "$IFACE" ]] || die "could not auto-detect the interface; pass it explicitly: netem-soak.sh $PROFILE <iface>"
[[ "${DRY_RUN:-0}" == 1 || $EUID -eq 0 ]] || die "tc needs root; re-run with sudo (or DRY_RUN=1 to preview)"
ip link show "$IFACE" > /dev/null 2>&1 || die "no interface '$IFACE'"

qdiscs() { tc qdisc show dev "$IFACE" 2>/dev/null; }
has_clsact() { qdiscs | grep -q '^qdisc clsact '; }
# First line of each filter on the clsact hooks, ours or everyone's.
filters() { { tc filter show dev "$IFACE" ingress; tc filter show dev "$IFACE" egress; } 2>/dev/null | grep '^filter ' || true; }
our_filters() { filters | grep -E " pref ($PREF_V4|$PREF_V6) " || true; }
other_filters() { filters | grep -vE " pref ($PREF_V4|$PREF_V6) " || true; }
ifb_exists() { ip link show "$IFB" > /dev/null 2>&1; }

# Removes exactly what this script adds. clsact goes only if this run added it, or (for clear,
# which cannot know) only if no filter of anyone else's is left on it.
remove() {
	local drop_clsact="$1"
	if [[ -n "$(our_filters)" || "$drop_clsact" == added ]]; then
		run tc filter del dev "$IFACE" egress protocol ip pref "$PREF_V4" 2> /dev/null || true
		run tc filter del dev "$IFACE" egress protocol ipv6 pref "$PREF_V6" 2> /dev/null || true
	fi
	if [[ "$drop_clsact" == added ]] || { [[ "$drop_clsact" == if-empty ]] && has_clsact && [[ -z "$(other_filters)" ]]; }; then
		run tc qdisc del dev "$IFACE" clsact 2> /dev/null || true
	fi
	if [[ "${CREATED_IFB:-0}" == 1 ]] || ifb_exists; then
		run ip link del "$IFB" 2> /dev/null || true
	fi
}

case "$PROFILE" in
	clear|baseline)
		remove if-empty
		[[ -n "$(other_filters)" ]] && echo "left the clsact qdisc on $IFACE: it carries filters that are not ours"
		echo "cleared netem-soak from $IFACE (its root qdisc was never changed)"; exit 0 ;;
	status)
		echo "=== qdiscs on $IFACE (the root qdisc is not ours) ==="; qdiscs
		echo "=== clsact filters (ours: pref $PREF_V4, $PREF_V6) ==="; filters
		echo "=== $IFB ==="; if ifb_exists; then tc qdisc show dev "$IFB"; else echo "(not present)"; fi
		exit 0 ;;
	wan)   NETEM="delay 40ms 15ms 25% loss 0.5%" ;;
	lossy) NETEM="delay 60ms 30ms loss 2% reorder 1% 50%" ;;
	wifi)  NETEM="delay 30ms 40ms loss 1% 30%" ;;
	harsh) NETEM="delay 80ms 50ms loss 5% reorder 2% 50% duplicate 0.2%" ;;
	*) echo "!! unknown profile '$PROFILE'" >&2; usage ;;
esac

ifb_exists && die "$IFB already exists (a previous run was killed?); run: netem-soak.sh clear $IFACE"
[[ -n "$(our_filters)" ]] && die "netem-soak filters are already on $IFACE; run: netem-soak.sh clear $IFACE"
if qdiscs | grep -q '^qdisc ingress ' && ! has_clsact; then
	die "$IFACE has an ingress qdisc, and clsact cannot be added beside it; nothing was changed"
fi

# Armed before the first change, so a failure partway through still restores the interface.
ADDED_CLSACT=0
CREATED_IFB=0
WAIT_PID=""
restore() {
	trap - EXIT INT TERM HUP
	set +e
	[[ -n "$WAIT_PID" ]] && kill "$WAIT_PID" 2> /dev/null
	remove "$([[ $ADDED_CLSACT == 1 ]] && echo added || echo keep)"
	echo "restored $IFACE: everything netem-soak added is gone, and its root qdisc was never changed"
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

run modprobe ifb numifbs=0 2> /dev/null || true
run ip link add "$IFB" type ifb
CREATED_IFB=1
run tc qdisc add dev "$IFB" root netem $NETEM
run ip link set "$IFB" up
if ! has_clsact; then
	run tc qdisc add dev "$IFACE" clsact
	ADDED_CLSACT=1
fi
# UDP (protocol 17) from source port $PORT, IPv4 and IPv6, redirected to the netem device.
run tc filter add dev "$IFACE" egress protocol ip pref "$PREF_V4" u32 \
	match ip protocol 17 0xff match ip sport "$PORT" 0xffff action mirred egress redirect dev "$IFB"
run tc filter add dev "$IFACE" egress protocol ipv6 pref "$PREF_V6" u32 \
	match ip6 protocol 17 0xff match ip6 sport "$PORT" 0xffff action mirred egress redirect dev "$IFB" \
	|| echo "!! no IPv6 filter (IPv4 SVC audio is still impaired)" >&2

echo "applied '$PROFILE' to SVC audio egress on $IFACE (UDP sport $PORT): $NETEM"
echo "everything else on $IFACE is unaffected. Ctrl-C stops it and restores $IFACE."
if [[ -n "$MINUTES" ]]; then
	sleep "$(awk -v m="$MINUTES" 'BEGIN { print m * 60 }')" &
else
	sleep infinity &
fi
WAIT_PID=$!
wait "$WAIT_PID"
