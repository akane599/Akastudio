#!/usr/bin/env bash
# peak-rss.sh <serial> reset|read [package]
# Per-phase peak RSS for a debuggable build, from the host:
#   reset  writes 5 to /proc/<pid>/clear_refs (via run-as), which resets VmHWM to the current RSS
#   read   prints VmHWM (peak since the last reset) and VmRSS
# Call `reset` right before a phase and `read` right after it.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
[ $# -ge 2 ] || { echo "usage: $0 <serial> reset|read [package]" >&2; exit 2; }
serial=$1 action=$2 pkg=${3:-com.akane.akastudio}
case $pkg in com.akane.akastudio | com.akane.akastudio.*) ;; *) echo "Only AkaStudio packages may be probed." >&2; exit 2 ;; esac
acquire_device "$serial" "$here/peak-rss.sh" "$@"
pid=$(adb -s "$serial" shell pidof -s "$pkg" | tr -d '\r')
[ -n "$pid" ] || { echo "$pkg is not running on $serial." >&2; exit 1; }
case $action in
  reset)
    adb -s "$serial" shell "run-as $pkg sh -c 'echo 5 > /proc/$pid/clear_refs'" || exit 1
    adb -s "$serial" shell "run-as $pkg grep -E '^(VmHWM|VmRSS)' /proc/$pid/status" | tr -d '\r' | sed "s/^/pid $pid after reset: /"
    ;;
  read)
    adb -s "$serial" shell "run-as $pkg grep -E '^(VmHWM|VmRSS)' /proc/$pid/status" | tr -d '\r' | sed "s/^/pid $pid: /"
    ;;
  *) echo "action must be reset or read" >&2; exit 2 ;;
esac
