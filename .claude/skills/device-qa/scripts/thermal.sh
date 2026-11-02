#!/usr/bin/env bash
# thermal.sh <serial>
# Prints the thermal status and the live "Current temperatures from HAL" block.
# The "Cached temperatures" block is stale and deliberately not printed.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
[ $# -eq 1 ] || { echo "usage: $0 <serial>" >&2; exit 2; }
acquire_device "$1" "$here/thermal.sh" "$@"
dump=$(adb -s "$1" shell dumpsys thermalservice) || exit 1
printf '%s\n' "$dump" | grep -m1 -i 'Thermal Status'
block=$(printf '%s\n' "$dump" | awk '/Current temperatures from HAL/ {f=1; print; next} f && !/^[ \t]/ {exit} f {print}')
if [ -z "$block" ]; then
  echo "No 'Current temperatures from HAL' block in dumpsys thermalservice." >&2
  exit 1
fi
printf '%s\n' "$block"
