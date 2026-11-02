#!/usr/bin/env python3
"""xml-verdict.py <results-dir> [<marker-file>]

Summarize JUnit TEST-*.xml under <results-dir>. With a marker file, only XML newer than
the marker counts, so results left over from earlier runs can't produce a verdict.
Exit: 0 all passed (at least one test), 1 failures or errors, 2 no fresh results.
"""
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    root_dir = sys.argv[1]
    since = os.path.getmtime(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    files = []
    for dirpath, _, names in os.walk(root_dir):
        for n in names:
            p = os.path.join(dirpath, n)
            if n.startswith("TEST-") and n.endswith(".xml") and os.path.getmtime(p) > since:
                files.append(p)
    if not files:
        print(f"No fresh TEST-*.xml under {root_dir}.")
        return 2
    total = failed = skipped = 0
    failures = []
    for p in sorted(files):
        try:
            tree = ET.parse(p)
        except ET.ParseError as e:
            print(f"Unreadable {p}: {e}")
            return 2
        suites = [tree.getroot()] if tree.getroot().tag == "testsuite" else tree.getroot().iter("testsuite")
        for s in suites:
            total += int(s.get("tests", 0))
            skipped += int(s.get("skipped", 0))
            failed += int(s.get("failures", 0)) + int(s.get("errors", 0))
            for case in s.iter("testcase"):
                for bad in list(case.findall("failure")) + list(case.findall("error")):
                    msg = (bad.get("message") or (bad.text or "")).strip().splitlines()
                    failures.append(f"{case.get('classname')}#{case.get('name')}: {msg[0][:200] if msg else ''}")
        print(f"{os.path.relpath(p)}")
    print(f"tests={total} failed={failed} skipped={skipped}")
    for f in failures[:30]:
        print(f"  FAILED {f}")
    if failed:
        return 1
    return 0 if total > 0 else 2


if __name__ == "__main__":
    sys.exit(main())
