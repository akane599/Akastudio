#!/usr/bin/env bash
# focus.sh <serial>
# Prints the focused window. Exit 0 only when AkaStudio is in front, so callers can gate
# key events: `focus.sh <serial> && <send keys>`. Exit 1 means a personal app (or the
# launcher) is in front: send nothing.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
[ $# -eq 1 ] || { echo "usage: $0 <serial>" >&2; exit 2; }
acquire_device "$1" "$here/focus.sh" "$@"
focus=$(adb -s "$1" shell dumpsys window | grep -m1 mCurrentFocus | sed 's/^ *//')
echo "${focus:-mCurrentFocus not found}"
case $focus in
  *com.akane.akastudio*) exit 0 ;;
  *) echo "AkaStudio is not in front; do not send key events." >&2; exit 1 ;;
esac
