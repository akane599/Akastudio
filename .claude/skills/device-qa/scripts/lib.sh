# shellcheck shell=bash
# Shared helpers for the device-qa scripts. Source it; don't run it.
# Each script takes the device lock itself. Don't wrap these scripts in flock:
# a second flock on the same file from a child process waits for itself.

lock_for() {
  case "$1" in
    emulator-5554) printf '%s\n' "$HOME/.cache/akastudio-emulator-5554.lock" ;;
    100.70.96.59:5555 | R5CYA0WKL6Y) printf '%s\n' "$HOME/.cache/akastudio-akadroid.lock" ;;
    *) return 1 ;;
  esac
}

is_tcp_serial() { [[ "$1" == *:* ]]; }

# acquire_device <serial> <script-path> [original script args...]
# Re-runs the script under `flock -o` (the lock fd is closed in children, so an adb server or
# daemon started during the run can't keep the lock), with ANDROID_SERIAL exported.
acquire_device() {
  local serial=$1 script=$2 lock wait=${DEVICE_LOCK_WAIT:-900}
  shift 2
  lock=$(lock_for "$serial") || { echo "No lock mapping for serial '$serial' (known: emulator-5554, 100.70.96.59:5555)." >&2; exit 2; }
  export ANDROID_SERIAL=$serial
  if [ "${AKASTUDIO_LOCK_HELD:-}" != "$lock" ]; then
    export AKASTUDIO_LOCK_HELD=$lock
    flock -n "$lock" true || echo "Waiting up to ${wait}s for device lock $lock ..." >&2
    exec flock -o -E 3 -w "$wait" "$lock" bash "$script" "$@"
  fi
  if [ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]; then
    echo "Device $serial is not attached or not ready (check 'adb devices -l')." >&2
    exit 4
  fi
}
