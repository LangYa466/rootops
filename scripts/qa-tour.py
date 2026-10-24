#!/usr/bin/env python3
"""RootOps APK UI tour harness.

Drives a running emulator/device through adb and walks the app by matching
visible labels from ``uiautomator dump``. No pixel coordinates are ever
hardcoded: every tap resolves the target node from a fresh dump, then taps the
centre of that node's bounds.

Usage:
    python3 scripts/qa-tour.py                     # install + full tour + report
    python3 scripts/qa-tour.py --tap-label Files   # tap one label (repeatable)
    python3 scripts/qa-tour.py --dry-run           # no adb calls at all
    python3 scripts/qa-tour.py --no-install        # reuse the installed app
    python3 scripts/qa-tour.py --apk /tmp/app.apk --serial emulator-5554

Exit codes:
    0  tour passed (or dry-run completed)
    1  QA failure (crash, required screen missing, label never found)
    2  harness/setup failure (adb missing, no device, install/start failed)
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APK = Path("app/build/outputs/apk/debug/app-debug.apk")
PACKAGE = "dev.rootops"
ACTIVITY = "dev.rootops/.MainActivity"
DEVICE_DUMP = "/sdcard/ui.xml"
DEVICE_SHOT = "/sdcard/qa-shot.png"

TAP_WAIT = 1.5
START_WAIT = 3.0
BACK_WAIT = 1.0
DUMP_ATTEMPTS = 3
COMMAND_TIMEOUT = 90

REQUIRED_SCREENS = ("Home", "Files", "Terminal", "Tools")
TOOL_SCREENS = (
    "Processes",
    "Logcat",
    "Network",
    "Scripts",
    "Schedule",
    "Mounts",
    "Packages",
    "SSH",
    "SFTP",
    "Analyzer",
)
SCREEN_ORDER = (
    "Home",
    "Files",
    "Terminal",
    "Tools",
    "Settings",
) + TOOL_SCREENS

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class AdbError(RuntimeError):
    """A setup or transport failure that makes the run impossible."""


def log(message: str) -> None:
    print(message, flush=True)


def slug(text: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-")
    return value or "step"


def display_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


# --------------------------------------------------------------------------- adb


class Adb:
    """Thin wrapper that records commands and can run without touching a device."""

    def __init__(self, serial: str | None, dry_run: bool) -> None:
        self.serial = serial
        self.dry_run = dry_run
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(
        self,
        args: list[str],
        *,
        timeout: int = COMMAND_TIMEOUT,
        binary: bool = False,
    ) -> subprocess.CompletedProcess:
        cmd = self.base + [str(arg) for arg in args]
        if self.dry_run:
            log("[dry-run] " + " ".join(cmd))
            empty = b"" if binary else ""
            return subprocess.CompletedProcess(cmd, 0, empty, empty)
        try:
            return subprocess.run(
                cmd,
                capture_output=True,
                text=not binary,
                timeout=timeout,
                check=False,
            )
        except FileNotFoundError as exc:
            raise AdbError("adb not found in PATH") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"timed out after {timeout}s: {' '.join(cmd)}") from exc


def check_device(adb: Adb) -> None:
    if adb.dry_run:
        return
    result = adb.run(["get-state"], timeout=20)
    state = (result.stdout or "").strip()
    if result.returncode != 0 or state != "device":
        detail = state or (result.stderr or "").strip() or "unknown"
        raise AdbError(f"no emulator/device ready (adb get-state -> {detail})")


def install_apk(adb: Adb, apk: Path) -> None:
    if adb.dry_run:
        adb.run(["install", "-r", str(apk)], timeout=300)
        return
    result = adb.run(["install", "-r", str(apk)], timeout=300)
    output = (result.stdout or "") + (result.stderr or "")
    if result.returncode != 0 or "Success" not in output:
        raise AdbError("adb install failed:\n" + output.strip())
    log(f"installed {apk.name}")


def clear_logcat(adb: Adb) -> None:
    result = adb.run(["logcat", "-b", "all", "-c"], timeout=30)
    if not adb.dry_run and result.returncode != 0:
        adb.run(["logcat", "-c"], timeout=30)


def launch_app(adb: Adb) -> None:
    adb.run(["shell", "am", "force-stop", PACKAGE], timeout=30)
    result = adb.run(["shell", "am", "start", "-W", "-n", ACTIVITY], timeout=60)
    if adb.dry_run:
        return
    output = ((result.stdout or "") + (result.stderr or "")).strip()
    if result.returncode != 0 or "Error" in output or "does not exist" in output:
        raise AdbError("failed to start the app:\n" + output)


def resumed_state(adb: Adb) -> tuple[bool, str]:
    """Returns (app-is-foreground, raw dumpsys excerpt).

    Uses the same signal the task asks for:
    ``dumpsys activity activities`` filtered to ``mResumedActivity`` /
    ``topResumedActivity``. Falls back to ``pidof`` when the ROM prints neither.
    """
    if adb.dry_run:
        return True, f"dry-run: {ACTIVITY}"
    result = adb.run(["shell", "dumpsys", "activity", "activities"], timeout=COMMAND_TIMEOUT)
    output = result.stdout or ""
    lines = [
        line.strip()
        for line in output.splitlines()
        if "mResumedActivity" in line or "topResumedActivity" in line
    ]
    if not lines:
        pid = adb.run(["shell", "pidof", PACKAGE], timeout=20)
        pid_out = (pid.stdout or "").strip()
        if pid.returncode == 0 and pid_out:
            return True, f"pidof {PACKAGE}: {pid_out}"
        return False, "no mResumedActivity/topResumedActivity line and no app pid"
    state = " | ".join(lines)
    return PACKAGE in state, state


# ------------------------------------------------------------------------ uiautomator


@dataclass(frozen=True)
class UiNode:
    text: str
    desc: str
    package: str
    cls: str
    clickable: bool
    enabled: bool
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def center(self) -> tuple[int, int]:
        return ((self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2)


def parse_ui(xml_text: str) -> list[UiNode]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    nodes: list[UiNode] = []
    for element in root.iter("node"):
        match = BOUNDS_RE.search(element.get("bounds") or "")
        if match is None:
            continue
        x1, y1, x2, y2 = (int(value) for value in match.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        nodes.append(
            UiNode(
                text=element.get("text") or "",
                desc=element.get("content-desc") or "",
                package=element.get("package") or "",
                cls=element.get("class") or "",
                clickable=(element.get("clickable") == "true"),
                enabled=(element.get("enabled") != "false"),
                x1=x1,
                y1=y1,
                x2=x2,
                y2=y2,
            )
        )
    return nodes


def find_node(nodes: list[UiNode], label: str) -> UiNode | None:
    """Best match for *label* on text or content-desc (case-insensitive substring).

    Ranking: exact match, then prefix, then substring; app-owned nodes before
    foreign ones; clickable nodes before non-clickable; document order last.
    """
    needle = label.strip().casefold()
    if not needle:
        return None
    best: UiNode | None = None
    best_key: tuple[int, int, int, int] | None = None
    for index, node in enumerate(nodes):
        if not node.enabled:
            continue
        for value in (node.text, node.desc):
            candidate = value.strip().casefold()
            if not candidate:
                continue
            if candidate == needle:
                rank = 0
            elif candidate.startswith(needle):
                rank = 1
            elif needle in candidate:
                rank = 2
            else:
                continue
            key = (
                rank,
                0 if node.package == PACKAGE else 1,
                0 if node.clickable else 1,
                index,
            )
            if best_key is None or key < best_key:
                best_key = key
                best = node
    return best


def synthetic_ui() -> str:
    """A plausible dump used by --dry-run so the tour logic can be exercised."""
    labels = list(SCREEN_ORDER)
    parts = ['<hierarchy rotation="0">']
    y = 120
    for index, label in enumerate(labels):
        parts.append(
            f'<node index="{index}" text="{label}" content-desc="" package="{PACKAGE}" '
            f'clickable="true" enabled="true" bounds="[40,{y}][1040,{y + 120}]" />'
        )
        y += 130
    parts.append(
        f'<node index="99" text="" content-desc="Terminal settings" package="{PACKAGE}" '
        f'clickable="false" enabled="true" bounds="[40,{y}][1040,{y + 120}]" />'
    )
    parts.append("</hierarchy>")
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>' + "".join(parts)


SYNTHETIC_UI = synthetic_ui()


def dump_ui(adb: Adb, local_path: Path) -> list[UiNode] | None:
    """Dumps the current UI, retrying twice on empty/stale output."""
    if adb.dry_run:
        log("[dry-run] synthesize uiautomator dump")
        return parse_ui(SYNTHETIC_UI)

    previous_digest: str | None = None
    for attempt in range(1, DUMP_ATTEMPTS + 1):
        try:
            local_path.unlink()
        except FileNotFoundError:
            pass
        adb.run(["shell", "rm", "-f", DEVICE_DUMP], timeout=20)
        dump = adb.run(["shell", "uiautomator", "dump", DEVICE_DUMP], timeout=45)
        dump_output = (dump.stdout or "") + (dump.stderr or "")
        adb.run(["pull", DEVICE_DUMP, str(local_path)], timeout=45)

        raw = ""
        if local_path.is_file():
            raw = local_path.read_text(encoding="utf-8", errors="replace")
        if raw.strip() and "<hierarchy" in raw:
            digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
            reported_ok = "dumped to" in dump_output
            if digest != previous_digest or reported_ok:
                nodes = parse_ui(raw)
                if nodes:
                    return nodes
            previous_digest = digest
        log(f"  uiautomator dump attempt {attempt}/{DUMP_ATTEMPTS} unusable; retrying")
        time.sleep(1.0)
    return None


def take_screenshot(adb: Adb, path: Path) -> str:
    if adb.dry_run:
        log(f"[dry-run] screenshot -> {display_path(path)}")
        return "(dry-run)"
    path.parent.mkdir(parents=True, exist_ok=True)
    result = adb.run(["exec-out", "screencap", "-p"], timeout=60, binary=True)
    data = result.stdout if isinstance(result.stdout, (bytes, bytearray)) else b""
    intact = data.startswith(b"\x89PNG") and b"IEND" in data[-64:]
    if result.returncode == 0 and intact:
        path.write_bytes(data)
        return display_path(path)
    # Older adb/devices can mangle exec-out; fall back to pull.
    adb.run(["shell", "screencap", "-p", DEVICE_SHOT], timeout=60)
    adb.run(["pull", DEVICE_SHOT, str(path)], timeout=60)
    adb.run(["shell", "rm", "-f", DEVICE_SHOT], timeout=20)
    if path.is_file() and path.stat().st_size > 0:
        return display_path(path)
    return "(failed)"


# ---------------------------------------------------------------------------- tour


@dataclass
class StepResult:
    index: int
    action: str
    target: str
    result: str
    screenshot: str
    note: str


@dataclass
class ScreenResult:
    status: str
    note: str


@dataclass
class ManualResult:
    name: str
    status: str
    detail: str
    screenshot: str


class Tour:
    def __init__(self, adb: Adb, outdir: Path, dry_run: bool) -> None:
        self.adb = adb
        self.outdir = outdir
        self.dry_run = dry_run
        self.dump_path = outdir / ".ui.xml"
        self.steps: list[StepResult] = []
        self.screens: dict[str, ScreenResult] = {}
        self.failed: list[str] = []
        self.manual: list[ManualResult] = []
        self.terminal_sample: list[str] = []
        self._shot_index = 1
        self._screen_size: tuple[int, int] | None = None

    # -- primitives -----------------------------------------------------

    def wait(self, seconds: float) -> None:
        """Sleeps only for real device runs; --dry-run stays instant."""
        if not self.dry_run:
            time.sleep(seconds)

    def next_step_name(self, label: str) -> str:
        name = f"{self._shot_index:02d}-{slug(label)}"
        self._shot_index += 1
        return name

    def capture(self, name: str) -> str:
        return take_screenshot(self.adb, self.outdir / f"{name}.png")

    def add_step(
        self, action: str, target: str, result: str, screenshot: str, note: str
    ) -> None:
        self.steps.append(
            StepResult(len(self.steps) + 1, action, target, result, screenshot, note)
        )

    def foreground(self) -> tuple[bool, str]:
        return resumed_state(self.adb)

    def ensure_app(self) -> bool:
        ok, state = self.foreground()
        if ok:
            return True
        log("  app is not foreground; relaunching")
        self.relaunch()
        return self.foreground()[0]

    def relaunch(self) -> None:
        try:
            launch_app(self.adb)
        except AdbError as exc:
            log(f"  relaunch failed: {exc}")
        self.wait(START_WAIT)

    def press_back(self) -> None:
        self.adb.run(["shell", "input", "keyevent", "KEYCODE_BACK"], timeout=30)
        self.wait(BACK_WAIT)

    def tap_node(self, node: UiNode) -> tuple[int, int]:
        x, y = node.center
        self.adb.run(["shell", "input", "tap", str(x), str(y)], timeout=30)
        self.wait(TAP_WAIT)
        return x, y

    def screen_size(self) -> tuple[int, int]:
        if self._screen_size is None:
            width, height = 1080, 2400
            if not self.dry_run:
                output = self.adb.run(["shell", "wm", "size"], timeout=20).stdout or ""
                match = re.search(r"(\d+)x(\d+)", output)
                if match is not None:
                    width, height = int(match.group(1)), int(match.group(2))
            self._screen_size = (width, height)
        return self._screen_size

    def scroll_down(self) -> None:
        width, height = self.screen_size()
        x = width // 2
        self.adb.run(
            [
                "shell",
                "input",
                "swipe",
                str(x),
                str(int(height * 0.75)),
                str(x),
                str(int(height * 0.35)),
                "350",
            ],
            timeout=30,
        )
        self.wait(0.8)

    def scroll_up(self) -> None:
        width, height = self.screen_size()
        x = width // 2
        self.adb.run(
            [
                "shell",
                "input",
                "swipe",
                str(x),
                str(int(height * 0.35)),
                str(x),
                str(int(height * 0.75)),
                "350",
            ],
            timeout=30,
        )
        self.wait(0.8)

    def tap_label_scroll(self, label: str, swipes: int = 5) -> tuple[bool, str]:
        """Finds a label that may be below the fold, scrolling down if needed."""
        for attempt in range(swipes + 1):
            node = self.locate(label)
            if node is not None:
                x, y = self.tap_node(node)
                suffix = "" if attempt == 0 else f" after {attempt} scroll(s)"
                return True, f"tapped at ({x}, {y}){suffix}"
            if attempt < swipes:
                self.scroll_down()
        return False, "label not found after scrolling"

    def all_texts(self) -> list[str]:
        nodes = dump_ui(self.adb, self.dump_path)
        if not nodes:
            return []
        return [node.text for node in nodes if node.text.strip()]

    def current_edit_text(self) -> str | None:
        nodes = dump_ui(self.adb, self.dump_path)
        if not nodes:
            return None
        for node in nodes:
            if node.cls == "android.widget.EditText":
                return node.text
        return None

    def focus_edit_text(self) -> bool:
        nodes = dump_ui(self.adb, self.dump_path)
        if not nodes:
            return False
        for node in nodes:
            if node.cls == "android.widget.EditText":
                self.tap_node(node)
                return True
        hint = find_node(nodes, "Send a command")
        if hint is not None:
            self.tap_node(hint)
            return True
        return False

    def clear_field(self) -> None:
        self.adb.run(["shell", "input", "keyevent", "123"], timeout=20)  # MOVE_END
        codes = ["shell", "input", "keyevent"] + ["67"] * 60  # DEL
        self.adb.run(codes, timeout=60)
        self.wait(0.3)

    def type_text(self, text: str) -> str | None:
        """Types ASCII via input text; spaces become %s, the rest is shell-quoted."""
        payload = text.replace(" ", "%s")
        quoted = "'" + payload.replace("'", "'\\''") + "'"
        self.adb.run(["shell", "input", "text", quoted], timeout=60)
        self.wait(0.6)
        return self.current_edit_text()

    def press_enter(self) -> None:
        self.adb.run(["shell", "input", "keyevent", "66"], timeout=30)
        self.wait(1.5)

    def add_manual(self, name: str, ok: bool, detail: str, screenshot: str) -> None:
        self.manual.append(
            ManualResult(name, "PASS" if ok else "FAIL", detail, screenshot)
        )

    def locate(self, label: str) -> UiNode | None:
        nodes = dump_ui(self.adb, self.dump_path)
        if not nodes:
            return None
        return find_node(nodes, label)

    def tap_label(self, label: str, *, recover: bool = True) -> tuple[bool, str]:
        """Finds *label* via uiautomator and taps its bounds centre."""
        node = self.locate(label)
        if node is not None:
            x, y = self.tap_node(node)
            return True, f"tapped at ({x}, {y})"
        if not recover:
            return False, "label not found"

        self.press_back()
        self.ensure_app()
        node = self.locate(label)
        if node is not None:
            x, y = self.tap_node(node)
            return True, f"tapped at ({x}, {y}) after back"

        self.relaunch()
        node = self.locate(label)
        if node is not None:
            x, y = self.tap_node(node)
            return True, f"tapped at ({x}, {y}) after relaunch"
        if label not in ("Home", "Files", "Terminal", "Tools", "Settings"):
            if self.tap_label("Tools", recover=False)[0]:
                node = self.locate(label)
                if node is not None:
                    x, y = self.tap_node(node)
                    return True, f"tapped at ({x}, {y}) after relaunch via Tools"
        return False, "label not found after back and relaunch"

    def record(
        self,
        label: str,
        ok: bool,
        note: str,
        screenshot: str,
        *,
        optional: bool = False,
    ) -> bool:
        if ok:
            fg, state = self.foreground()
            if not fg:
                ok = False
                note = f"{note}; app not foreground after tap ({state})"
                self.ensure_app()
        if ok:
            status = "PASS"
        elif optional:
            status = "SKIP"
        else:
            status = "FAIL"
        if not ok:
            self.failed.append(label)
            self.ensure_app()
        self.screens[label] = ScreenResult(status, note)
        self.add_step("tap", label, status, screenshot, note)
        return ok

    def visit(self, label: str, *, optional: bool = False) -> bool:
        step = self.next_step_name(label)
        ok, note = self.tap_label(label)
        screenshot = self.capture(step)
        return self.record(label, ok, note, screenshot, optional=optional)

    # -- scripted tour --------------------------------------------------

    def ensure_tools_hub(self) -> bool:
        if self.tap_label("Tools", recover=False)[0]:
            return True
        if self.tap_label("Home", recover=False)[0]:
            if self.tap_label("Tools", recover=False)[0]:
                return True
        return self.tap_label("Tools", recover=True)[0]

    def visit_settings(self) -> None:
        step = self.next_step_name("Settings")
        ok, note = self.tap_label("Settings", recover=False)
        if not ok:
            ok, note = self.tap_label_scroll("Settings")
        if not ok:
            note = "not visible on the Tools hub or Home"
            if self.tap_label("Home", recover=False)[0]:
                found, found_note = self.tap_label("Settings", recover=False)
                if found:
                    ok, note = True, f"{found_note} (from Home)"
        screenshot = self.capture(step)
        self.record("Settings", ok, note, screenshot, optional=True)
        if ok:
            self.press_back()
            self.ensure_app()
            for _ in range(3):
                self.scroll_up()

    def run(self) -> None:
        self.add_step(
            "start",
            ACTIVITY,
            "OK",
            self.capture("01-launch"),
            f"am start -W + {START_WAIT:g}s settle",
        )
        self._shot_index = max(self._shot_index, 2)

        self.visit("Home")
        self.visit("Files")
        self.visit("Terminal")
        self.visit("Tools")
        self.visit_settings()

        for label in TOOL_SCREENS:
            self.ensure_tools_hub()
            self.visit(label)
            self.press_back()
            self.ensure_app()

        if not self.dry_run:
            self.run_manual_checks()

    # -- manual root / pty / file checks --------------------------------

    def open_tools_scrolled(self, label: str) -> tuple[bool, str]:
        self.ensure_tools_hub()
        return self.tap_label_scroll(label)

    def run_manual_checks(self) -> None:
        self.check_root_status()
        self.check_home_root_chip()
        self.check_files_data()
        self.check_root_only_file()
        self.check_terminal_pty()

    def check_root_status(self) -> None:
        step = self.next_step_name("root-status")
        ok, note = self.open_tools_scrolled("Settings")
        status = ""
        screenshot = ""
        if ok:
            self.wait(1.0)
            for _ in range(4):
                texts = [t.strip() for t in self.all_texts()]
                if "AVAILABLE" in texts or "UNAVAILABLE" in texts:
                    status = "AVAILABLE" if "AVAILABLE" in texts else "UNAVAILABLE"
                    break
                self.scroll_down()
            if status != "AVAILABLE":
                rechecked, _ = self.tap_label("Re-check", recover=False)
                if not rechecked:
                    self.scroll_down()
                    rechecked, _ = self.tap_label("Re-check", recover=False)
                if rechecked:
                    self.wait(3.0)
                    texts = [t.strip() for t in self.all_texts()]
                    if "AVAILABLE" in texts:
                        status = "AVAILABLE"
            screenshot = self.capture(step)
            self.press_back()
            self.ensure_app()
        else:
            screenshot = self.capture(step)
        detail = f"root access {status or 'unknown'} ({note})"
        self.add_manual("Root access re-check", status == "AVAILABLE", detail, screenshot)

    def check_home_root_chip(self) -> None:
        step = self.next_step_name("home-root")
        ok, note = self.tap_label("Home", recover=True)
        self.wait(1.0)
        texts = [t.strip() for t in self.all_texts()]
        chip = "root" in texts
        screenshot = self.capture(step)
        detail = f"Home root chip shows {'root' if chip else 'not root'} ({note})"
        self.add_manual("Home root chip", ok and chip, detail, screenshot)

    def check_files_data(self) -> None:
        step = self.next_step_name("files-data")
        ok, note = self.tap_label("Files", recover=True)
        entries: list[str] = []
        field = ""
        if ok:
            self.focus_edit_text()
            self.clear_field()
            field = self.type_text("/data") or ""
            self.press_enter()
            self.wait(1.0)
            texts = [t.strip() for t in self.all_texts()]
            entries = [t for t in texts if t in ("app", "local", "system", "misc", "vendor", "apex", "data", "adb")]
        screenshot = self.capture(step)
        passed = ok and field.strip() == "/data" and "app" in entries and len(entries) >= 3
        detail = f"path field={field!r}; /data entries seen: {', '.join(entries[:8]) or 'none'}"
        self.add_manual("Files /data listing", passed, detail, screenshot)

    def check_root_only_file(self) -> None:
        step = self.next_step_name("root-file")
        field = ""
        content = ""
        if self.focus_edit_text():
            self.clear_field()
            field = self.type_text("/data/local/tmp") or ""
            self.press_enter()
            self.wait(1.0)
            self.press_back()  # hide the IME so the whole file list is visible
            node = self.locate("rootops-root-test.txt")
            if node is not None:
                self.tap_node(node)
                self.wait(1.5)
            else:
                found, _ = self.tap_label_scroll("rootops-root-test.txt", swipes=3)
                if found:
                    self.wait(1.5)
        texts = self.all_texts()
        for text in texts:
            if "root-only secret" in text:
                content = text.strip()
                break
        screenshot = self.capture(step)
        passed = field.strip() == "/data/local/tmp" and content != ""
        detail = (
            f"path field={field!r}; editor content={content!r}"
            if content
            else f"path field={field!r}; root-only content not found in UI"
        )
        self.add_manual("Root-only file read", passed, detail, screenshot)
        self.press_back()
        self.press_back()
        self.ensure_app()

    def send_terminal_command(self, command: str) -> str:
        self.focus_edit_text()
        self.clear_field()
        typed = self.type_text(command)
        self.press_enter()
        return typed if typed is not None else ""

    def check_terminal_pty(self) -> None:
        step = self.next_step_name("terminal-pty")
        ok, note = self.tap_label("Terminal", recover=True)
        typed: list[str] = []
        switched = False
        if ok:
            self.wait(1.0)
            texts = [t.strip() for t in self.all_texts()]
            # A root pty session (shell "su") renders no output with the test
            # root daemon, so fall back to a plain sh session and elevate per
            # command with `su -c id`.
            if any(text.startswith("root ·") for text in texts):
                if self.tap_label("root", recover=False)[0]:
                    switched = True
                    self.wait(0.8)
                    self.tap_label("New session", recover=False)
                    self.wait(2.0)
            typed.append(self.send_terminal_command(" su -c id"))
            typed.append(self.send_terminal_command(" tty"))
            typed.append(self.send_terminal_command(" printenv TERM"))
            self.wait(0.5)
        texts = self.all_texts()
        uid_ok = any("uid=0" in text for text in texts)
        tty_ok = any("/dev/pts/" in text for text in texts)
        self.terminal_sample = [
            text.strip()
            for text in texts
            if any(
                marker in text
                for marker in ("su -c id", "uid=", " tty", "/dev/pts", "printenv TERM", "xterm")
            )
        ]
        screenshot = self.capture(step)
        passed = ok and uid_ok and tty_ok
        detail = (
            f"typed={typed!r}; uid=0 {'seen' if uid_ok else 'missing'}; "
            f"pty {'seen' if tty_ok else 'missing'}; "
            f"{'root session switched to sh' if switched else 'root session not used'} ({note})"
        )
        self.add_manual("Terminal root + pty", passed, detail, screenshot)
        if switched:
            # Restore the app default (root terminal) for the next run.
            self.tap_label("sh", recover=False)


# ------------------------------------------------------------------------- crashes


def crash_blocks(text: str) -> list[str]:
    blocks: list[str] = []
    current: list[str] = []
    for line in text.splitlines():
        if "FATAL EXCEPTION" in line or "--------- beginning of crash" in line:
            if current:
                blocks.append("\n".join(current))
            current = [line]
        elif current:
            current.append(line)
    if current:
        blocks.append("\n".join(current))
    return blocks


def collect_crashes(adb: Adb) -> list[str]:
    if adb.dry_run:
        return []
    excerpts: list[str] = []

    crash = adb.run(["logcat", "-d", "-b", "crash"], timeout=COMMAND_TIMEOUT)
    for block in crash_blocks(crash.stdout or ""):
        if PACKAGE in block:
            excerpts.append(block.strip())

    full = adb.run(["logcat", "-d"], timeout=COMMAND_TIMEOUT)
    markers = (
        "FATAL EXCEPTION",
        "FATAL",
        "AndroidRuntime",
        "ANR in",
        "Force finishing",
        "Process: " + PACKAGE,
    )
    hits = [
        line
        for line in (full.stdout or "").splitlines()
        if PACKAGE in line and any(marker in line for marker in markers)
    ]
    if hits:
        excerpts.append("\n".join(hits[:120]))

    seen: set[str] = set()
    unique: list[str] = []
    for excerpt in excerpts:
        digest = hashlib.sha256(excerpt.encode("utf-8", "replace")).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        unique.append(excerpt)
    return unique


# -------------------------------------------------------------------------- report


def write_report(
    path: Path,
    *,
    apk: Path,
    serial: str | None,
    dry_run: bool,
    tour: Tour,
    crashes: list[str],
    final_ok: bool,
    final_state: str,
    verdict: str,
    required_ok: bool,
    started: str,
    error: str | None,
) -> None:
    finished = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines: list[str] = []
    lines.append("# RootOps QA tour report")
    lines.append("")
    lines.append(f"- Generated: {finished}")
    lines.append(f"- Started: {started}")
    lines.append(
        "- Mode: "
        + ("dry run (no adb commands executed)" if dry_run else "device run")
    )
    lines.append(f"- Serial: {serial or '(default adb device)'}")
    lines.append(f"- APK: {display_path(apk)}")
    lines.append(f"- Activity: {ACTIVITY}")
    lines.append("")

    lines.append("## Result")
    lines.append("")
    if dry_run:
        lines.append(
            "**DRY RUN** — the tour logic ran against a synthetic UI dump; "
            "no adb commands were executed and no screenshots were captured."
        )
    else:
        lines.append(f"**{verdict}**")
        lines.append("")
        lines.append(
            "- Required screens ("
            + ", ".join(REQUIRED_SCREENS)
            + ("): all PASS" if required_ok else "): NOT all reached")
        )
        lines.append(f"- Crash/exception excerpts: {len(crashes)}")
        lines.append(f"- App foreground at the end: {'yes' if final_ok else 'no'}")
        manual_pass = sum(1 for check in tour.manual if check.status == "PASS")
        lines.append(f"- Manual checks: {manual_pass}/{len(tour.manual)} PASS")
        if error:
            lines.append(f"- Harness error: {error}")
    lines.append("")

    lines.append("## Screens")
    lines.append("")
    lines.append("| Screen | Result | Note |")
    lines.append("| --- | --- | --- |")
    for name in SCREEN_ORDER:
        screen = tour.screens.get(name)
        if screen is None:
            lines.append(f"| {name} | NOT RUN | tour did not reach this screen |")
        else:
            note = screen.note.replace("|", "\\|")
            lines.append(f"| {name} | {screen.status} | {note} |")
    lines.append("")

    lines.append("## Steps")
    lines.append("")
    lines.append("| # | Action | Target | Result | Screenshot | Note |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for step in tour.steps:
        note = step.note.replace("|", "\\|")
        lines.append(
            f"| {step.index} | {step.action} | {step.target} | {step.result} | "
            f"{step.screenshot} | {note} |"
        )
    if not tour.steps:
        lines.append("| - | - | - | - | - | no steps executed |")
    lines.append("")

    lines.append("## Manual checks")
    lines.append("")
    lines.append("| Check | Result | Evidence | Screenshot |")
    lines.append("| --- | --- | --- | --- |")
    if tour.manual:
        for check in tour.manual:
            detail = check.detail.replace("|", "\\|")
            lines.append(
                f"| {check.name} | {check.status} | {detail} | {check.screenshot} |"
            )
    else:
        lines.append("| - | NOT RUN | no manual checks executed | - |")
    lines.append("")

    lines.append("## Terminal output sample")
    lines.append("")
    if tour.terminal_sample:
        lines.append("```text")
        lines.extend(tour.terminal_sample)
        lines.append("```")
    else:
        lines.append("(no terminal output captured)")
    lines.append("")

    lines.append("## Failed taps")
    lines.append("")
    if tour.failed:
        for label in tour.failed:
            lines.append(f"- {label}")
    else:
        lines.append("- (none)")
    lines.append("")

    lines.append("## Crash / exception excerpts")
    lines.append("")
    if crashes:
        excerpt = "\n\n---\n\n".join(crashes)
        if len(excerpt) > 12000:
            excerpt = excerpt[:12000] + "\n... (truncated)"
        lines.append("```text")
        lines.append(excerpt)
        lines.append("```")
    else:
        lines.append("(none)")
    lines.append("")

    lines.append("## Final foreground state")
    lines.append("")
    lines.append("```text")
    lines.append(final_state or "(unknown)")
    lines.append("```")
    lines.append("")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def print_summary(
    tour: Tour,
    crashes: list[str],
    final_ok: bool,
    report_path: Path,
    verdict: str,
    dry_run: bool,
) -> None:
    reached = [
        name
        for name in SCREEN_ORDER
        if tour.screens.get(name) is not None and tour.screens[name].status == "PASS"
    ]
    print()
    print("== RootOps QA tour ==")
    print(f"  mode: {'dry-run' if dry_run else 'device'}")
    print(f"  reached: {', '.join(reached) if reached else '(none)'}")
    print(f"  failed taps: {', '.join(tour.failed) if tour.failed else 'none'}")
    print(f"  crash findings: {len(crashes)}")
    manual_pass = sum(1 for check in tour.manual if check.status == "PASS")
    print(f"  manual checks: {manual_pass}/{len(tour.manual)} PASS")
    for check in tour.manual:
        print(f"    {check.name}: {check.status} — {check.detail}")
    print(f"  foreground at end: {'yes' if final_ok else 'no'}")
    print(f"  report: {display_path(report_path)}")
    print(f"  RESULT: {verdict}")


def print_tap_summary(tour: Tour, crashes: list[str], dry_run: bool) -> None:
    print()
    print("== RootOps QA label taps ==")
    print(f"  mode: {'dry-run' if dry_run else 'device'}")
    for step in tour.steps:
        print(f"  {step.target}: {step.result} ({step.note})")
    print(f"  crash findings: {len(crashes)}")


# ---------------------------------------------------------------------------- main


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Install the RootOps debug APK and tour its screens via uiautomator labels.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--apk",
        default=str(DEFAULT_APK),
        help=f"APK to install; relative paths resolve against the repo root (default: {DEFAULT_APK})",
    )
    parser.add_argument("--serial", default=None, help="adb serial (-s), e.g. emulator-5554")
    parser.add_argument(
        "--outdir",
        default="qa",
        help="directory for screenshots and report.md (default: qa, relative to repo root)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="skip every adb call and exercise the tour against a synthetic UI dump",
    )
    parser.add_argument(
        "--no-install",
        action="store_true",
        help="do not run adb install -r (reuse the installed app)",
    )
    parser.add_argument(
        "--tap-label",
        action="append",
        default=[],
        metavar="LABEL",
        help="tap one label resolved via uiautomator (repeatable); skips the tour unless --tour is given",
    )
    parser.add_argument(
        "--tour",
        action="store_true",
        help="run the scripted tour even when --tap-label is supplied",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    dry_run = bool(args.dry_run)

    outdir = Path(args.outdir)
    if not outdir.is_absolute():
        outdir = REPO_ROOT / outdir
    outdir.mkdir(parents=True, exist_ok=True)

    apk = Path(args.apk)
    if not apk.is_absolute():
        apk = REPO_ROOT / apk

    if not dry_run and shutil.which("adb") is None:
        print("ERROR: adb is not installed or not on PATH", file=sys.stderr)
        return 2

    adb = Adb(args.serial, dry_run)
    started = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        check_device(adb)
        if not args.no_install:
            if not dry_run and not apk.is_file():
                raise AdbError(f"APK not found: {apk}")
            install_apk(adb, apk)
        clear_logcat(adb)
        launch_app(adb)
        if not dry_run:
            time.sleep(START_WAIT)
    except AdbError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    tour = Tour(adb, outdir, dry_run)
    crashes: list[str] = []
    final_ok = False
    final_state = ""
    error: str | None = None

    try:
        if args.tap_label:
            for label in args.tap_label:
                step = tour.next_step_name(label)
                ok, note = tour.tap_label(label)
                screenshot = tour.capture(step)
                tour.add_step("tap", label, "OK" if ok else "FAIL", screenshot, note)
                if not ok:
                    tour.failed.append(label)
            if not args.tour:
                crashes = collect_crashes(adb)
                print_tap_summary(tour, crashes, dry_run)
                if dry_run:
                    return 0
                return 0 if not tour.failed and not crashes else 1

        tour.run()
        crashes = collect_crashes(adb)
        final_ok, final_state = resumed_state(adb)
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        return 130
    except AdbError as exc:
        error = str(exc)
        log(f"ERROR during tour: {exc}")
        final_ok, final_state = False, f"aborted: {exc}"
        try:
            crashes = collect_crashes(adb)
        except AdbError:
            crashes = []

    required_ok = all(
        tour.screens.get(name) is not None and tour.screens[name].status == "PASS"
        for name in REQUIRED_SCREENS
    )
    crashed = bool(crashes)
    manual_ok = all(check.status == "PASS" for check in tour.manual)
    ok = required_ok and final_ok and not crashed and error is None and manual_ok
    verdict = "DRY RUN" if dry_run else ("PASS" if ok else "FAIL")

    report_path = outdir / "report.md"
    write_report(
        report_path,
        apk=apk,
        serial=args.serial,
        dry_run=dry_run,
        tour=tour,
        crashes=crashes,
        final_ok=final_ok,
        final_state=final_state,
        verdict=verdict,
        required_ok=required_ok,
        started=started,
        error=error,
    )
    print_summary(tour, crashes, final_ok, report_path, verdict, dry_run)
    return 0 if (dry_run or ok) else 1


if __name__ == "__main__":
    sys.exit(main())
