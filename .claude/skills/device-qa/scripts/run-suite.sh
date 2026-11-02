#!/usr/bin/env bash
# run-suite.sh <serial> <module> [--class <FQCN>[#method]] [--stop-mobilecli]
# Runs :<module>:connectedDebugAndroidTest under the device lock and prints the verdict
# from the fresh TEST-*.xml. On an ADB-over-TCP serial the Gradle exit code is ignored
# (AGP 9.4.1 bug, docs/stabilization/PHONE_QA_20261102.md); the XML decides.
# Exit: 0 pass, 1 test failures, 2 usage/no results, 3 lock busy, 4 device missing.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"
orig=("$@")
root=$(git -C "$here" rev-parse --show-toplevel) || exit 2
cd "$root" || exit 2

usage() { echo "usage: $0 <serial> <module e.g. core:export-pipeline> [--class FQCN[#method]] [--stop-mobilecli]" >&2; exit 2; }
[ $# -ge 2 ] || usage
serial=$1 module=${2#:}
shift 2
class="" stop_mcli=0
while [ $# -gt 0 ]; do
  case $1 in
    --class) [ $# -ge 2 ] || usage; class=$2; shift 2 ;;
    --stop-mobilecli) stop_mcli=1; shift ;;
    *) usage ;;
  esac
done
moddir=${module//://}
[ -d "$moddir/src/androidTest" ] || { echo "No $moddir/src/androidTest; nothing to run." >&2; exit 2; }

workers=$(python3 .claude/kit/resource-policy.py 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["gradle_workers_per_command"])' 2>/dev/null) || workers=2
[ -n "$workers" ] || workers=2

acquire_device "$serial" "$here/run-suite.sh" "${orig[@]}"
if [ "$stop_mcli" = 1 ]; then
  # mobile-mcp's device agent holds Android's single UiAutomation connection.
  mobilecli daemon stop >/dev/null 2>&1 || true
fi

marker=$(mktemp)
trap 'rm -f "$marker"' EXIT
sleep 1 # keep XML mtimes strictly newer than the marker
args=(":$module:connectedDebugAndroidTest" "--max-workers=$workers")
[ -n "$class" ] && args+=("-Pandroid.testInstrumentationRunnerArguments.class=$class")
echo "Running ${args[*]} on $serial (lock held: $AKASTUDIO_LOCK_HELD)"
bash .claude/kit/gradle-check.sh "${args[@]}"
grc=$?

python3 -I "$here/xml-verdict.py" "$moddir/build/outputs/androidTest-results" "$marker"
xrc=$?

if is_tcp_serial "$serial"; then
  [ "$grc" -ne 0 ] && echo "Note: Gradle exit $grc ignored on TCP serial $serial; verdict comes from the XML."
  verdict=$xrc
else
  verdict=$xrc
  if [ "$grc" -ne 0 ] && [ "$xrc" -eq 0 ]; then
    echo "Gradle failed (exit $grc) although the XML is green; treating as FAIL on $serial."
    verdict=1
  fi
fi
case $verdict in
  0) echo "VERDICT: PASS ($module on $serial)" ;;
  1) echo "VERDICT: FAIL ($module on $serial)" ;;
  *) echo "VERDICT: NO RESULTS ($module on $serial); the build or install likely failed before tests ran." ;;
esac
exit "$verdict"
