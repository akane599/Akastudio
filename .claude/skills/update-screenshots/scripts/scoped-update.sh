#!/usr/bin/env bash
# scoped-update.sh <module> <keep-pattern> [<keep-pattern>...]
# Runs :<module>:updateDebugScreenshotTest, then keeps only the reference changes whose path
# matches one of the patterns (bash glob against the path relative to the reference dir,
# e.g. '*StudioScreenBack*' or '*/StudioAppScreenshotsKt/StudioAppReady_*').
# Everything else is restored (modified/deleted refs) or removed (refs this run created).
set -uo pipefail
[ $# -ge 2 ] || { echo "usage: $0 <module e.g. feature:studio> <keep-pattern>..." >&2; exit 2; }
module=${1#:}
shift
here=$(cd "$(dirname "$0")" && pwd)
root=$(git -C "$here" rev-parse --show-toplevel) || exit 2
cd "$root" || exit 2
refdir="${module//://}/src/screenshotTestDebug/reference"
[ -d "$refdir" ] || { echo "No reference dir $refdir" >&2; exit 2; }

if [ -n "$(git status --porcelain -- "$refdir")" ]; then
  echo "Refusing to run: $refdir already has uncommitted changes (they would be mixed with this update):" >&2
  git status --short -- "$refdir" | head -20 >&2
  exit 2
fi

workers=$(python3 .claude/kit/resource-policy.py 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["gradle_workers_per_command"])' 2>/dev/null) || workers=2
[ -n "$workers" ] || workers=2
bash .claude/kit/gradle-check.sh ":$module:updateDebugScreenshotTest" "--max-workers=$workers" || exit $?

matches() {
  local rel=${1#"$refdir"/} p
  for p in "${patterns[@]}"; do
    # shellcheck disable=SC2053
    [[ $rel == $p ]] && return 0
  done
  return 1
}
patterns=("$@")
kept=() restored=0 removed=0
while IFS= read -r -d '' entry; do
  status=${entry:0:2} path=${entry:3}
  if matches "$path"; then
    kept+=("$status $path")
  elif [ "$status" = "??" ]; then
    rm -f -- "$path"; removed=$((removed + 1))
  else
    git restore --worktree -- "$path"; restored=$((restored + 1))
  fi
done < <(git status --porcelain -z --untracked-files=all -- "$refdir")

echo "Restored $restored and removed $removed out-of-scope reference(s)."
if [ ${#kept[@]} -eq 0 ]; then
  echo "No reference matched ${patterns[*]}; nothing changed in scope. Check the patterns."
  exit 1
fi
echo "Kept ${#kept[@]} in-scope change(s), review each image before committing:"
printf '  %s\n' "${kept[@]}"
