#!/usr/bin/env bash
# netem-soak.sh — impair ONLY the Simple Voice Chat audio egress (UDP sport 24454)
# with realistic WAN/wifi conditions, to soak-test whether a STOCK SVC client (no
# SavePoint client mod) holds up — and whether Opus FEC (opusFecPacketLossPercent > 0)
# measurably helps.
#
# WHY only sport 24454: the tc filter matches UDP source-port 24454 (the SVC server's
# audio egress toward clients), so SSH / rcon / the web stack / everything else is
# UNTOUCHED. Safe to run on a remote server without cutting your own connection.
#
# WHERE to run: on the MACHINE RUNNING THE MINECRAFT SERVER, on a real Linux host that
# is NOT freezing (production / cloud / bare-metal — verify `hostFreezes=0` in the feed
# stats first). Running it under the WSL2 dev VM is pointless: the ~815 ms/33 s host
# freeze confounds every result, and mirrored-networking loopback may not honor tc.
#
# TEST PROTOCOL (the point of this tool):
#   Preconditions: server freeze-free (hostFreezes=0); the LISTENER runs a STOCK SVC
#   client — SavePoint client-svc-buffer mod REMOVED — with SVC output_buffer_size=16
#   (the max in-game setting; default is only 5 = 100 ms). Feed diagnostics on.
#   For each profile below: apply it, listen ~3-5 min, note audible dropouts and SVC's
#   own "lost packets"/stats. Then set opusFecPacketLossPercent=15 (restart server),
#   repeat, and compare. FEC should cut isolated-loss dropouts; it will NOT help pure
#   jitter/late-arrival (only a deeper client buffer does).
#
# Usage:
#   sudo tools/netem-soak.sh <profile> [iface] [port]
#   sudo tools/netem-soak.sh clear      [iface]
#   sudo tools/netem-soak.sh status     [iface]
#
# Profiles (delay = one-way added latency; loss/reorder/dup as noted):
#   baseline  clear (no impairment)
#   wan       40ms ± 15ms jitter (25% corr), 0.5% loss            — a good broadband path
#   lossy     60ms ± 30ms jitter, 2% loss, 1% reorder             — a mediocre path
#   wifi      30ms ± 40ms bursty jitter, 1% loss (30% burst corr) — flaky wifi
#   harsh     80ms ± 50ms jitter, 5% loss, 2% reorder, 0.2% dup   — worst-case mobile/DSL
set -euo pipefail

PROFILE="${1:-}"
IFACE="${2:-$(ip route 2>/dev/null | awk '/default/{print $5; exit}')}"
PORT="${3:-24454}"

if [[ -z "$PROFILE" ]]; then
  sed -n '2,40p' "$0"; exit 1
fi
if [[ -z "$IFACE" ]]; then
  echo "!! could not auto-detect interface; pass it explicitly: netem-soak.sh $PROFILE <iface>" >&2
  exit 1
fi
if [[ $EUID -ne 0 ]]; then
  echo "!! tc needs root — re-run with sudo" >&2; exit 1
fi

clear_qdisc() { tc qdisc del dev "$IFACE" root 2>/dev/null || true; }

case "$PROFILE" in
  clear|baseline)
    clear_qdisc
    echo "cleared netem on $IFACE (SVC audio egress back to normal)"; exit 0 ;;
  status)
    echo "=== qdisc on $IFACE ==="; tc qdisc show dev "$IFACE"
    echo "=== filters ==="; tc filter show dev "$IFACE" 2>/dev/null; exit 0 ;;
  wan)   NETEM="delay 40ms 15ms 25% loss 0.5%" ;;
  lossy) NETEM="delay 60ms 30ms loss 2% reorder 1% 50%" ;;
  wifi)  NETEM="delay 30ms 40ms loss 1% 30%" ;;
  harsh) NETEM="delay 80ms 50ms loss 5% reorder 2% 50% duplicate 0.2%" ;;
  *) echo "!! unknown profile '$PROFILE'"; sed -n '30,36p' "$0"; exit 1 ;;
esac

clear_qdisc
# prio root; priomap forces ALL normal traffic to band 1:1 (no netem). Only the
# explicit filter below diverts SVC audio (UDP sport $PORT) into band 1:3 (netem),
# so nothing else on the box is impaired.
tc qdisc add dev "$IFACE" root handle 1: prio bands 3 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
tc qdisc add dev "$IFACE" parent 1:3 handle 30: netem $NETEM
# match UDP (proto 17) with source port $PORT on both IPv4 and IPv6 egress
tc filter add dev "$IFACE" protocol ip   parent 1:0 prio 1 u32 \
   match ip protocol 17 0xff  match ip sport "$PORT" 0xffff  flowid 1:3
tc filter add dev "$IFACE" protocol ipv6 parent 1:0 prio 2 u32 \
   match ip6 protocol 17 0xff match ip6 sport "$PORT" 0xffff flowid 1:3 2>/dev/null || true

echo "applied '$PROFILE' to SVC audio egress on $IFACE (UDP sport $PORT): $NETEM"
echo "everything else on $IFACE is UNAFFECTED. clear with: sudo tools/netem-soak.sh clear $IFACE"
