#!/usr/bin/env python3
# Copyright 2026 SWARM
# SPDX-License-Identifier: AGPL-3.0-only
"""SWARM Messenger for Android: the emulator smoke test.

Installs the APK on a running emulator, creates a disposable SWARM wallet account against chat.swarm.green
(welcome -> permissions "Not now" -> "Create a new wallet" -> "I have written these 24 words down" -> Continue),
names the profile, and checks that the conversation list appears. It drives the real UI through UI Automator
(`uiautomator dump` + `input tap`), so what it proves is what a person would see.

Evidence goes to --out: screenshots at each step (the wallet screens are FLAG_SECURE, so they capture black by
design, and no screenshot ever shows a recovery phrase), the UI dumps, and the app's own log lines for the sign-in
(challenge, verify, registration). The log is filtered to the app's tags; the app never logs the phrase or keys.

Usage: python3 scripts/swarm/emulator_smoke.py --apk path/to/x86_64.apk --out smoke-out
Exit status 0 only if the conversation list was reached after a successful registration.
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "green.swarm.messenger"
LAUNCH = f"{PACKAGE}/org.thoughtcrime.securesms.RoutingActivity"
PROFILE_NAME = "M5 Smoke"


def adb(*args, check=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
    return result.stdout


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


class Ui:
    def __init__(self, out):
        self.out = out
        self.step = 0
        # Every screen the test sees is checked for the account identifier: a SWARM account's +888 number must never
        # be shown (docs/WALLET-SIGN-IN.md).
        self.identifier_seen = []

    def dump(self):
        for attempt in range(5):
            try:
                adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
                xml = adb("shell", "cat", "/sdcard/ui.xml", timeout=30).decode("utf-8", errors="replace")
                start = xml.find("<?xml")
                root = ET.fromstring(xml[start:] if start >= 0 else xml)
                for node in root.iter("node"):
                    for attribute in ("text", "content-desc"):
                        value = node.get(attribute, "")
                        if re.search(r"\+\s*888", value) and value not in self.identifier_seen:
                            log(f"the account identifier is on screen: {value!r}")
                            self.identifier_seen.append(value)
                return root
            except Exception as error:  # noqa: BLE001 - uiautomator is flaky while the screen changes
                log(f"ui dump failed ({error}); retrying")
                time.sleep(2)
        raise RuntimeError("uiautomator dump kept failing")

    def nodes(self):
        return list(self.dump().iter("node"))

    @staticmethod
    def matches(node, text=None, rid=None, desc=None):
        if text is not None and node.get("text", "").strip().lower() != text.lower():
            return False
        if rid is not None and not node.get("resource-id", "").endswith(rid):
            return False
        if desc is not None and desc.lower() not in node.get("content-desc", "").lower():
            return False
        return True

    def find(self, text=None, rid=None, desc=None):
        for node in self.nodes():
            if self.matches(node, text, rid, desc):
                return node
        return None

    def wait_for(self, what, timeout=90, scroll=False, **query):
        """Waits for a node. With scroll=True, scrolls the screen up between looks: UI Automator only sees what
        is on screen, and a long screen (the 24 words) can push a control below the fold on a small display."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(**query)
            if node is not None:
                return node
            if not self.dismiss_system_dialogs() and scroll:
                self.swipe_up()
            time.sleep(2)
        self.save_dump(f"timeout-{what}")
        self.screenshot(f"timeout-{what}")
        raise RuntimeError(f"timed out waiting for {what} ({query})")

    def tap(self, node, what):
        bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds"))]
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        log(f"tap {what} at {x},{y}")
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)

    def tap_when_present(self, what, timeout=90, scroll=False, **query):
        self.tap(self.wait_for(what, timeout=timeout, scroll=scroll, **query), what)

    def swipe_up(self):
        size = adb("shell", "wm", "size", check=False).decode(errors="replace")
        match = re.search(r"(\d+)x(\d+)", size)
        width, height = (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)
        x = width // 2
        log("scroll up")
        adb("shell", "input", "swipe", str(x), str(height * 7 // 10), str(x), str(height * 3 // 10), "400", check=False)
        time.sleep(1)

    def dismiss_system_dialogs(self):
        """Android's own permission and battery dialogs: decline, SWARM Messenger works without them."""
        for node in self.nodes():
            rid = node.get("resource-id", "")
            if rid.endswith("permission_deny_button") or rid.endswith("permission_deny_and_dont_ask_again_button"):
                self.tap(node, "system dialog: deny")
                return True
        return False

    def screenshot(self, name):
        self.step += 1
        path = os.path.join(self.out, f"{self.step:02d}-{name}.png")
        with open(path, "wb") as handle:
            handle.write(adb("exec-out", "screencap", "-p", timeout=60))
        log(f"screenshot {path}")

    def save_dump(self, name):
        try:
            xml = ET.tostring(self.dump(), encoding="unicode")
            with open(os.path.join(self.out, f"ui-{name}.xml"), "w", encoding="utf-8") as handle:
                handle.write(xml)
        except Exception as error:  # noqa: BLE001
            log(f"could not save ui dump: {error}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)

    log("waiting for the emulator")
    adb("wait-for-device", timeout=600)
    adb("shell", "settings", "put", "global", "window_animation_scale", "0", check=False)
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0", check=False)
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0", check=False)

    log(f"installing {args.apk}")
    adb("install", "-r", "-g", args.apk, timeout=600)
    adb("logcat", "-c", check=False)

    log("launching")
    adb("shell", "am", "start", "-n", LAUNCH)
    time.sleep(8)
    ui.screenshot("welcome")

    ui.tap_when_present("welcome Continue", timeout=180, text="Continue")
    # The permissions screen, unless everything was granted at install (-g grants runtime permissions).
    node = ui.find(text="Not now")
    if node is not None:
        ui.screenshot("permissions")
        ui.tap(node, "permissions Not now")

    ui.wait_for("wallet choice", timeout=90, rid="swarm_wallet_create")
    ui.screenshot("wallet-choice-secure-window")
    ui.tap_when_present("Create a new wallet", rid="swarm_wallet_create")

    # The 24 words are on screen now (FLAG_SECURE: not captured). Confirm and continue; the phrase is never read.
    ui.tap_when_present("written-down checkbox", scroll=True, rid="swarm_wallet_written_down")
    ui.tap_when_present("Continue", scroll=True, rid="swarm_wallet_continue")

    log("waiting for registration and the profile screen")
    deadline = time.time() + 240
    reached_profile = False
    while time.time() < deadline:
        nodes = ui.nodes()
        if any(Ui.matches(n, rid=":id/given_name") for n in nodes):
            reached_profile = True
            break
        if any(Ui.matches(n, text="Could not sign in") for n in nodes):
            ui.save_dump("sign-in-error")
            ui.screenshot("sign-in-error")
            message = " / ".join(n.get("text", "") for n in nodes if n.get("text"))
            raise RuntimeError(f"sign-in failed on screen: {message}")
        ui.dismiss_system_dialogs()
        time.sleep(3)
    if not reached_profile:
        ui.save_dump("no-profile-screen")
        ui.screenshot("no-profile-screen")
        raise RuntimeError("the profile screen never appeared after sign-in")

    ui.screenshot("registered-profile-name")
    ui.tap_when_present("given name field", rid=":id/given_name")
    adb("shell", "input", "text", PROFILE_NAME.replace(" ", "%s"))
    time.sleep(1)
    ui.screenshot("profile-name-entered")
    ui.tap_when_present("profile Next", rid=":id/finish_button")

    log("waiting for the conversation list")
    deadline = time.time() + 180
    while time.time() < deadline:
        nodes = ui.nodes()
        texts = " | ".join(n.get("text", "") for n in nodes if n.get("text"))
        descs = " | ".join(n.get("content-desc", "") for n in nodes if n.get("content-desc"))
        if re.search(r"\bChats\b", texts + " | " + descs) and not any(Ui.matches(n, rid=":id/given_name") for n in nodes):
            ui.screenshot("conversation-list")
            ui.save_dump("conversation-list")
            log("reached the conversation list")
            break
        ui.dismiss_system_dialogs()
        time.sleep(3)
    else:
        ui.save_dump("no-conversation-list")
        ui.screenshot("no-conversation-list")
        raise RuntimeError("the conversation list never appeared")

    time.sleep(10)
    ui.screenshot("conversation-list-settled")

    # SWARM Messenger never uses FCM. If the app believed FCM was on, it would tell the server it does not fetch its
    # messages and drop its connection in the background, and the new account could not receive anything.
    raw = adb("logcat", "-d", "-v", "time", timeout=120).decode("utf-8", errors="replace")
    fcm_states = re.findall(r"IncomingMessageObserver.*FCM: (true|false)", raw)
    log(f"FCM as the message observer saw it: {sorted(set(fcm_states)) or 'not logged'}")
    if "true" in fcm_states:
        raise RuntimeError("the app reported FCM: true; SWARM Messenger must never rely on FCM")

    # The settings page names the account under the profile name; for a SWARM account that must read "SWARM account".
    try:
        ui.tap_when_present("Settings shortcut", timeout=30, desc="Settings shortcut")
        time.sleep(3)
        ui.screenshot("settings")
        ui.save_dump("settings")
    except Exception as error:  # noqa: BLE001 - the settings page is extra evidence, not the proof itself
        log(f"could not open the settings page: {error}")

    if ui.identifier_seen:
        raise RuntimeError(f"the +888 account identifier was shown on screen: {ui.identifier_seen}")
    log("the +888 account identifier never appeared on screen")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        try:
            out = next((sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--out"), "smoke-out")
            raw = subprocess.run(["adb", "logcat", "-d", "-v", "time"], capture_output=True, timeout=120).stdout.decode("utf-8", errors="replace")
            keep = re.compile(r"WalletSignIn|RegistrationRepository|SwarmWallet|SwarmNetwork|RegistrationViewModel|LibSignalChatConnection|SignalWebSocket|IncomingMessageObserver|RefreshAttributes|ProfileUpload|AndroidRuntime|FATAL|PUT /v1|POST /v1|GET /v1")
            with open(os.path.join(out, "app-log.txt"), "w", encoding="utf-8") as handle:
                handle.write("\n".join(line for line in raw.splitlines() if keep.search(line)))
        except Exception as error:  # noqa: BLE001
            print(f"could not save the log: {error}")
