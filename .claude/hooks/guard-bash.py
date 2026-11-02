#!/usr/bin/env python3
"""PreToolUse(Bash) guard for the shared host, the shared devices and large files.

Blocks (exit 2, reason on stderr) commands that break .claude/rules/android-build.md
or .claude/rules/device-testing.md:
  - stopping/killing Gradle, Java or adb (`gradlew --stop`, `adb kill-server`, pkill java ...)
  - adb or connected*AndroidTest without the device lock or an explicit serial
  - adb commands that mutate packages other than com.akane.akastudio[.*]
  - adb commands that change global device state (settings put, setprop, reboot ...)
  - `git add` of files larger than 5 MB

Scripts under .claude/skills/device-qa/scripts take the lock themselves, so they are
called without a flock prefix and are not inspected here. Any internal error fails open.
"""
import json
import os
import re
import shlex
import subprocess
import sys

LOCK_DIR_HINT = "$HOME/.cache"
SERIAL_LOCKS = {
    "emulator-5554": "akastudio-emulator-5554.lock",
    "100.70.96.59:5555": "akastudio-akadroid.lock",
    "R5CYA0WKL6Y": "akastudio-akadroid.lock",
}
OWN_PACKAGE = re.compile(r"^com\.akane\.akastudio(\.[A-Za-z0-9_.]+)?$")
PACKAGE_TOKEN = re.compile(r"^[a-z][a-z0-9_]*(\.[A-Za-z0-9_]+)+$")
ADB_READ_ONLY = {"devices", "version", "help", "get-state", "get-serialno", "wait-for-device"}
MUTATING_SHELL = re.compile(
    r"\b(pm\s+(clear|uninstall|disable|disable-user|enable|suspend|unsuspend|grant|revoke|hide|unhide|reset-permissions)"
    r"|am\s+(force-stop|kill|kill-all|stop-app|crash)|cmd\s+package\s+(uninstall|clear|disable|suspend)"
    r"|monkey\b)"
)
GLOBAL_STATE = re.compile(
    r"\b(settings\s+(put|delete|reset)|setprop|svc\s|cmd\s+(overlay|display|wifi|bluetooth_manager|connectivity)"
    r"|wm\s+(size|density|overscan)|locksettings|reboot)\b"
)
ADB_GLOBAL_VERBS = {"reboot", "root", "unroot", "remount", "disable-verity", "enable-verity", "kill-server", "tcpip", "usb", "sideload"}
WRAPPERS = {"rtk", "env", "nice", "nohup", "time", "command", "exec", "stdbuf"}
MAX_STAGED_BYTES = 5 * 1024 * 1024


class Block(Exception):
    pass


def strip_heredocs(cmd: str) -> str:
    out, lines, i = [], cmd.split("\n"), 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        m = re.search(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1", line)
        i += 1
        if m:
            delim = m.group(2)
            while i < len(lines) and lines[i].strip() != delim:
                i += 1
            i += 1
    return "\n".join(out)


def tokenize(cmd: str):
    lex = shlex.shlex(cmd.replace("\n", " ; "), posix=True, punctuation_chars=";&|")
    lex.whitespace_split = True
    return list(lex)


def segments(tokens):
    seg = []
    for t in tokens:
        if t and set(t) <= set(";&|"):
            if seg:
                yield seg
            seg = []
        else:
            seg.append(t)
    if seg:
        yield seg


def lock_name(path: str) -> str:
    return os.path.basename(path)


def unwrap(seg, ctx):
    """Strip env assignments and wrappers; record ANDROID_SERIAL and flock locks into ctx."""
    i = 0
    while i < len(seg):
        t = seg[i]
        if t == "export" and i + 1 < len(seg):
            i += 1
            continue
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", t)
        if m:
            if m.group(1) == "ANDROID_SERIAL":
                ctx["serial"] = m.group(2)
            i += 1
            continue
        base = os.path.basename(t)
        if base in WRAPPERS:
            i += 1
            if base == "rtk" and i < len(seg) and seg[i] == "bypass":
                i += 1
            continue
        if base == "timeout":
            i += 1
            while i < len(seg) and (seg[i].startswith("-") or re.match(r"^\d+[smhd]?$", seg[i])):
                i += 1
            continue
        if base == "flock":
            i += 1
            while i < len(seg) and seg[i].startswith("-"):
                if seg[i] in ("-w", "--timeout", "-E", "--conflict-exit-code"):
                    i += 1
                i += 1
            if i < len(seg):
                ctx["locks"] = ctx["locks"] | {lock_name(seg[i])}
                i += 1
            if i + 1 < len(seg) and seg[i] in ("-c", "--command"):
                return ["sh", "-c", seg[i + 1]]
            continue
        break
    return seg[i:]


def require_lock_and_serial(ctx, what):
    serial = ctx.get("serial")
    if not serial:
        raise Block(f"{what} needs an explicit device: set ANDROID_SERIAL=<serial> or pass adb -s <serial>.")
    lock = SERIAL_LOCKS.get(serial)
    if lock is None:
        raise Block(f"{what}: no lock mapping for serial {serial!r}; add it to SERIAL_LOCKS in .claude/hooks/guard-bash.py.")
    if lock not in ctx["locks"]:
        raise Block(
            f"{what} on {serial} must run under its device lock: "
            f"ANDROID_SERIAL={serial} flock {LOCK_DIR_HINT}/{lock} <command> "
            f"(or use the device-qa skill scripts, which lock for you)."
        )


def check_adb(args, ctx):
    rest, j = list(args), 0
    while j < len(rest) and rest[j].startswith("-"):
        if rest[j] == "-s" and j + 1 < len(rest):
            ctx["serial"] = rest[j + 1]
            j += 2
            continue
        if rest[j] in ("-t", "-H", "-P", "-L"):
            j += 2
            continue
        j += 1
    rest = rest[j:]
    verb = rest[0] if rest else ""
    if verb == "kill-server":
        raise Block("adb kill-server is forbidden: the adb server is shared with another session.")
    if verb in ADB_READ_ONLY or verb == "":
        return
    if verb in ADB_GLOBAL_VERBS:
        raise Block(f"adb {verb} changes shared device state and is forbidden.")
    require_lock_and_serial(ctx, f"adb {verb}")
    text = " ".join(rest)
    if verb == "uninstall" or (verb == "shell" and MUTATING_SHELL.search(text)):
        for tok in rest[1:]:
            cand = tok.split("/")[0]
            if PACKAGE_TOKEN.match(cand) and not OWN_PACKAGE.match(cand):
                raise Block(f"adb may only modify com.akane.akastudio and its .test packages, not {cand}.")
    if verb == "shell" and GLOBAL_STATE.search(text):
        raise Block("adb shell command changes global device settings/state; that is forbidden on shared devices.")
    if verb in ("install", "install-multiple"):
        apks = [t for t in rest[1:] if t.endswith(".apk")]
        for apk in apks:
            p = os.path.abspath(apk)
            if "akastudio" not in p.lower():
                raise Block(f"adb install of {apk}: only AkaStudio build outputs may be installed.")


def check_gradle(args, ctx):
    if "--stop" in args:
        raise Block("gradlew --stop is forbidden: Gradle daemons are shared with another session.")
    if any(re.search(r"connected\w*AndroidTest|connectedCheck|installDebug|installRelease|uninstallAll", a) for a in args):
        require_lock_and_serial(ctx, "Device-touching Gradle task")


def check_git_add(args, cwd):
    if not args or args[0] != "add":
        return
    paths = [a for a in args[1:] if not a.startswith("-")]
    if "--" in args[1:]:
        paths = args[args.index("--") + 1:]
    try:
        out = subprocess.run(
            ["git", "ls-files", "-z", "-m", "-o", "--exclude-standard", "--", *paths],
            cwd=cwd, capture_output=True, timeout=10,
        ).stdout.decode(errors="replace")
    except (OSError, subprocess.TimeoutExpired):
        return
    big = []
    for f in filter(None, out.split("\0")):
        fp = os.path.join(cwd, f)
        try:
            size = os.path.getsize(fp)
        except OSError:
            continue
        if size > MAX_STAGED_BYTES:
            big.append(f"{f} ({size / 1048576:.1f} MiB)")
    if big:
        raise Block("Refusing to stage files over 5 MB: " + ", ".join(big) + ". Add them to .gitignore or stage specific paths.")


def analyze(cmd, ctx, cwd, depth=0):
    if depth > 4:
        return
    for seg in segments(tokenize(strip_heredocs(cmd))):
        local = dict(ctx)
        words = unwrap(seg, local)
        if words and words[0] == "export":
            ctx.update({k: v for k, v in local.items() if k == "serial"})
        if not words:
            ctx.update({k: v for k, v in local.items() if k == "serial"})
            continue
        words = [words[0].lstrip("(")] + words[1:]
        base = os.path.basename(words[0])
        args = words[1:]
        if base in ("bash", "sh", "zsh") and "-c" in args:
            k = args.index("-c")
            if k + 1 < len(args):
                analyze(args[k + 1], local, cwd, depth + 1)
            continue
        if base in ("bash", "sh") and args and os.path.basename(args[0]) == "gradlew":
            base, args = "gradlew", args[1:]
        if base in ("bash", "sh") and args and args[0].endswith(".claude/kit/gradle-check.sh"):
            base, args = "gradlew", args[1:]
        if base in ("pkill", "killall") and re.search(r"java|gradle|adb|kotlin", " ".join(args), re.I):
            raise Block("Killing Gradle/Java/adb processes is forbidden: they are shared with another session.")
        if base == "adb":
            check_adb(args, local)
        elif base in ("gradlew", "gradle"):
            check_gradle(args, local)
        elif base == "git":
            check_git_add(args, cwd)


def main():
    data = json.load(sys.stdin)
    cmd = (data.get("tool_input") or {}).get("command") or ""
    cwd = data.get("cwd") or os.getcwd()
    if re.search(r"\bkill\b[^;&|]*\$\(\s*(pgrep|pidof)[^)]*(java|gradle|adb|kotlin)", cmd, re.I):
        raise Block("Killing Gradle/Java/adb processes is forbidden: they are shared with another session.")
    if re.search(r"\$\(\s*adb\s+(?!devices|version)", cmd) and "flock" not in cmd:
        raise Block("adb inside $(...) also needs the device lock; wrap the whole command in flock.")
    try:
        analyze(cmd, {"serial": os.environ.get("ANDROID_SERIAL"), "locks": frozenset()}, cwd)
    except ValueError:
        # Unparseable quoting: fall back to the coarse checks only.
        if re.search(r"gradlew[^;&|]*--stop|adb[^;&|]*kill-server", cmd):
            raise Block("Stopping shared Gradle daemons or the adb server is forbidden.")


if __name__ == "__main__":
    try:
        main()
    except Block as b:
        print(f"Blocked by .claude/hooks/guard-bash.py: {b}", file=sys.stderr)
        sys.exit(2)
    except Exception:  # fail open on hook bugs
        sys.exit(0)
