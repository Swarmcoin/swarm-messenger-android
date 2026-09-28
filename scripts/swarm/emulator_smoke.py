#!/usr/bin/env python3
# Copyright 2026 SWARM
# SPDX-License-Identifier: AGPL-3.0-only
"""SWARM Messenger for Android: the emulator smoke test.

Installs the APK on a running emulator, creates a disposable SWARM wallet account against chat.swarm.green
(welcome -> permissions "Not now" -> "Create a new wallet" -> "I have written these 24 words down" -> Continue),
names the profile, and checks that the conversation list appears. It drives the real UI through UI Automator
(`uiautomator dump` + `input tap`), so what it proves is what a person would see.

On the way it checks what a SWARM account must never be shown: the +888 account identifier on any screen, and the
rows that are about a phone number (the profile screen's "Who can find me by number?", Privacy > "Phone number",
Account > "Change phone number"), as well as upstream's donation and MobileCoin payments rows.

With --peer-username it then messages another SWARM account, normally a desktop instance: New chat -> Find by
username -> the peer's username -> a text. It waits for the peer's reply (a message containing the word "desktop"),
shows it was received, and answers once more, so both directions are exercised in one run. Someone has to reply
from the peer's side while the run waits (--reply-wait seconds).

Evidence goes to --out: screenshots at each step (the wallet screens are FLAG_SECURE, so they capture black by
design, and no screenshot ever shows a recovery phrase), UI dumps (any dump taken while a wallet screen is up has
every text blanked, so the words cannot end up in a file), and the app's own log lines. The app never logs the
phrase or keys.

Usage: python3 scripts/swarm/emulator_smoke.py --apk path/to/x86_64.apk --out smoke-out [--peer-username name.42]
Exit status 0 only if every check passed.
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

# Rows that are about a phone number. A SWARM wallet account has none (its +888 identifier is not a phone number),
# so none of these may be offered to it.
PHONE_NUMBER_ROWS = {
    "profile": ["Who can find me by number?"],
    "privacy": ["Phone number", "Who can find me by number", "Who can see my number"],
    "account": ["Change phone number", "Change number"],
}

# The wallet screens hold the recovery phrase. Anything taken from the screen while one of these is present is blanked.
WALLET_SCREEN_IDS = ("swarm_wallet_words", "swarm_wallet_phrase_input", "swarm_wallet_written_down")


def adb(*args, check=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
    return result.stdout


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def input_text(text):
    """Types plain text (letters, digits, dots, spaces) into the focused field."""
    if not re.fullmatch(r"[A-Za-z0-9 .@_-]+", text):
        raise ValueError(f"refusing to type {text!r}: only letters, digits, spaces and . @ _ - are typed")
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(1)


class Ui:
    def __init__(self, out):
        self.out = out
        self.step = 0
        # Every screen the test sees is checked for the account identifier: a SWARM account's +888 number must never
        # be shown (docs/WALLET-SIGN-IN.md).
        self.identifier_seen = []

    @staticmethod
    def wallet_screen(root):
        return any(node.get("resource-id", "").endswith(WALLET_SCREEN_IDS) for node in root.iter("node"))

    def dump(self):
        for attempt in range(5):
            try:
                adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
                xml = adb("shell", "cat", "/sdcard/ui.xml", timeout=30).decode("utf-8", errors="replace")
                adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
                start = xml.find("<?xml")
                root = ET.fromstring(xml[start:] if start >= 0 else xml)
                if not self.wallet_screen(root):
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
    def matches(node, text=None, rid=None, desc=None, contains=None, cls=None):
        if text is not None and node.get("text", "").strip().lower() != text.lower():
            return False
        if contains is not None and contains.lower() not in node.get("text", "").lower():
            return False
        if rid is not None and not node.get("resource-id", "").endswith(rid):
            return False
        if desc is not None and desc.lower() not in node.get("content-desc", "").lower():
            return False
        if cls is not None and node.get("class", "") != cls:
            return False
        return True

    def find(self, nodes=None, **query):
        for node in nodes if nodes is not None else self.nodes():
            if self.matches(node, **query):
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

    def screen_size(self):
        size = adb("shell", "wm", "size", check=False).decode(errors="replace")
        match = re.search(r"(\d+)x(\d+)", size)
        return (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)

    def swipe_up(self):
        width, height = self.screen_size()
        x = width // 2
        log("scroll up")
        adb("shell", "input", "swipe", str(x), str(height * 7 // 10), str(x), str(height * 3 // 10), "400", check=False)
        time.sleep(1)

    def back(self):
        adb("shell", "input", "keyevent", "KEYCODE_BACK", check=False)
        time.sleep(1.5)

    def dismiss_system_dialogs(self):
        """Android's own permission and battery dialogs: decline, SWARM Messenger works without them."""
        for node in self.nodes():
            rid = node.get("resource-id", "")
            if rid.endswith("permission_deny_button") or rid.endswith("permission_deny_and_dont_ask_again_button"):
                self.tap(node, "system dialog: deny")
                return True
        return False

    def screen_texts(self, scrolls=0):
        """Every text and content description on the screen, scrolling down `scrolls` times to see a long page."""
        seen = []
        for index in range(scrolls + 1):
            for node in self.nodes():
                for attribute in ("text", "content-desc"):
                    value = node.get(attribute, "").strip()
                    if value and value not in seen:
                        seen.append(value)
            if index < scrolls:
                self.swipe_up()
        return seen

    def screenshot(self, name):
        self.step += 1
        path = os.path.join(self.out, f"{self.step:02d}-{name}.png")
        with open(path, "wb") as handle:
            handle.write(adb("exec-out", "screencap", "-p", timeout=60))
        log(f"screenshot {path}")

    def save_dump(self, name):
        try:
            root = self.dump()
            if self.wallet_screen(root):
                # Never write a wallet screen's words to a file: blank every text before saving.
                for node in root.iter("node"):
                    for attribute in ("text", "content-desc"):
                        if node.get(attribute):
                            node.set(attribute, "[blanked: wallet screen]")
            xml = ET.tostring(root, encoding="unicode")
            with open(os.path.join(self.out, f"ui-{name}.xml"), "w", encoding="utf-8") as handle:
                handle.write(xml)
        except Exception as error:  # noqa: BLE001
            log(f"could not save ui dump: {error}")


def check_rows_absent(ui, where, texts, problems):
    """Fails the run (at the end) if any phone-number row is offered on this page."""
    offered = [row for row in PHONE_NUMBER_ROWS[where] if any(t.strip().lower() == row.lower() for t in texts)]
    if offered:
        log(f"{where}: phone-number rows ARE offered: {offered}")
        problems.append(f"{where} offers phone-number rows {offered}")
    else:
        log(f"{where}: no phone-number rows ({', '.join(PHONE_NUMBER_ROWS[where])} all absent)")


def register(ui, profile_name, problems):
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

    time.sleep(2)
    ui.screenshot("registered-profile-name")
    ui.save_dump("profile")
    check_rows_absent(ui, "profile", ui.screen_texts(), problems)

    ui.tap_when_present("given name field", rid=":id/given_name")
    input_text(profile_name)
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
            return
        ui.dismiss_system_dialogs()
        time.sleep(3)
    ui.save_dump("no-conversation-list")
    ui.screenshot("no-conversation-list")
    raise RuntimeError("the conversation list never appeared")


def check_settings(ui, problems):
    """Settings: the account reads "SWARM account", no donations, no MobileCoin, no phone-number rows."""
    try:
        ui.tap_when_present("Settings shortcut", timeout=30, desc="Settings shortcut")
        time.sleep(3)
        ui.screenshot("settings")
        ui.save_dump("settings")
    except Exception as error:  # noqa: BLE001 - the settings page is extra evidence, not the proof itself
        problems.append(f"could not open the settings page: {error}")
        return

    settings = ui.nodes()
    donate = [n.get("text") for n in settings if "donate" in n.get("text", "").lower()]
    if donate:
        problems.append(f"the settings page offers donations: {donate}")
    # Upstream's Payments row is a MobileCoin wallet; SWARM Messenger offers none (SwarmBuild.MOBILECOIN_PAYMENTS).
    if any(n.get("text", "").strip().lower() == "payments" for n in settings):
        problems.append("the settings page offers MobileCoin payments")

    # Account is the first row and Privacy further down; both are on screen without scrolling on a Pixel 6.
    for page, row in (("account", "Account"), ("privacy", "Privacy")):
        try:
            ui.tap_when_present(f"Settings > {row}", timeout=30, scroll=True, text=row)
            time.sleep(2.5)
            ui.screenshot(f"settings-{page}")
            ui.save_dump(f"settings-{page}")
            check_rows_absent(ui, page, ui.screen_texts(scrolls=2), problems)
        except Exception as error:  # noqa: BLE001
            problems.append(f"could not check Settings > {row}: {error}")
        ui.back()

    # Back to the conversation list.
    for _ in range(3):
        if ui.find(desc="New chat") is not None:
            break
        ui.back()


def bubbles(nodes):
    return [n.get("text", "") for n in nodes if Ui.matches(n, rid=":id/conversation_item_body") and n.get("text")]


def delivery_states(nodes):
    return [n.get("content-desc", "") for n in nodes if Ui.matches(n, rid=":id/conversation_item_delivery_status")]


def send_text(ui, text):
    ui.tap_when_present("message field", timeout=60, rid=":id/embedded_text_editor")
    input_text(text)
    ui.tap_when_present("send", timeout=30, rid=":id/send_button")
    deadline = time.time() + 60
    while time.time() < deadline:
        if any(text in b for b in bubbles(ui.nodes())):
            log(f"sent: {text!r}")
            return
        time.sleep(2)
    raise RuntimeError(f"the message {text!r} never appeared in the conversation")


def message_peer(ui, peer_username, run_id, reply_wait, problems):
    """New chat -> Find by username -> send -> wait for the peer's reply -> send again."""
    first = f"Hello from SWARM Android run {run_id}"
    second = f"Android received your reply run {run_id}"

    ui.tap_when_present("New chat", timeout=60, desc="New chat")
    ui.tap_when_present("Find by username", timeout=60, text="Find by username")
    ui.tap_when_present("username field", timeout=30, cls="android.widget.EditText")
    input_text(peer_username)
    ui.screenshot("find-by-username")
    ui.tap_when_present("Next", timeout=30, desc="Next")

    log(f"looking up {peer_username}")
    deadline = time.time() + 90
    while time.time() < deadline:
        nodes = ui.nodes()
        if any(Ui.matches(n, rid=":id/embedded_text_editor") for n in nodes):
            break
        texts = [n.get("text", "") for n in nodes if n.get("text")]
        refusal = [t for t in texts if "not found" in t.lower() or "network error" in t.lower() or "not a valid" in t.lower()]
        if refusal:
            ui.save_dump("find-by-username-refused")
            ui.screenshot("find-by-username-refused")
            raise RuntimeError(f"looking up {peer_username} failed on screen: {refusal}")
        time.sleep(2)
    else:
        ui.save_dump("no-conversation")
        ui.screenshot("no-conversation")
        raise RuntimeError(f"no conversation with {peer_username} opened")

    ui.screenshot("conversation-opened")
    send_text(ui, first)
    time.sleep(3)
    ui.screenshot("first-message-sent")

    log(f"waiting up to {reply_wait} s for the peer's reply")
    deadline = time.time() + reply_wait
    reply = None
    last_report = 0
    while time.time() < deadline:
        nodes = ui.nodes()
        received = [b for b in bubbles(nodes) if "desktop" in b.lower()]
        if received:
            reply = received[-1]
            break
        if time.time() - last_report > 60:
            log(f"no reply yet; delivery state of the first message: {delivery_states(nodes)}")
            last_report = time.time()
        time.sleep(5)
    if reply is None:
        ui.save_dump("no-reply")
        ui.screenshot("no-reply")
        problems.append(f"no reply from {peer_username} within {reply_wait} s")
        return

    log(f"RECEIVED the peer's reply: {reply!r}")
    time.sleep(2)
    ui.screenshot("reply-received")
    ui.save_dump("reply-received")

    send_text(ui, second)
    # Give the peer's delivery (and read) receipts a moment to come back.
    deadline = time.time() + 60
    states = []
    while time.time() < deadline:
        states = delivery_states(ui.nodes())
        if states and all(s in ("Delivered", "Message read") for s in states):
            break
        time.sleep(4)
    log(f"delivery state of Android's messages: {states}")
    ui.screenshot("second-message-sent")
    ui.save_dump("conversation-final")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--profile-name", default="M5 Smoke")
    parser.add_argument("--peer-username", default="", help="a SWARM username to message, e.g. a2desk.64")
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", str(int(time.time()))))
    parser.add_argument("--reply-wait", type=int, default=1200, help="seconds to wait for the peer's reply")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ui = Ui(args.out)
    problems = []

    log("waiting for the emulator")
    adb("wait-for-device", timeout=600)
    adb("shell", "settings", "put", "global", "window_animation_scale", "0", check=False)
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0", check=False)
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0", check=False)

    log(f"installing {args.apk}")
    adb("install", "-r", "-g", args.apk, timeout=600)
    adb("logcat", "-c", check=False)
    adb("logcat", "-G", "16M", check=False)

    log("launching")
    adb("shell", "am", "start", "-n", LAUNCH)
    time.sleep(8)
    ui.screenshot("welcome")

    register(ui, args.profile_name, problems)

    time.sleep(10)
    ui.screenshot("conversation-list-settled")

    # SWARM Messenger never uses FCM. If the app believed FCM was on, it would tell the server it does not fetch its
    # messages and drop its connection in the background, and the new account could not receive anything.
    raw = adb("logcat", "-d", "-v", "time", timeout=120).decode("utf-8", errors="replace")
    fcm_states = re.findall(r"IncomingMessageObserver.*FCM: (true|false)", raw)
    log(f"FCM as the message observer saw it: {sorted(set(fcm_states)) or 'not logged'}")
    if "true" in fcm_states:
        raise RuntimeError("the app reported FCM: true; SWARM Messenger must never rely on FCM")
    connected = re.findall(r"LibSignalChatConnection.*\[libsignal-auth:\d+\] Connected", raw)
    log(f"authenticated chat connection opened {len(connected)} time(s)")
    if not connected:
        problems.append("the authenticated chat connection never opened")

    check_settings(ui, problems)

    if args.peer_username:
        try:
            message_peer(ui, args.peer_username, args.run_id, args.reply_wait, problems)
        except Exception as error:  # noqa: BLE001 - reported with the rest
            problems.append(f"messaging {args.peer_username}: {error}")

    if ui.identifier_seen:
        problems.append(f"the +888 account identifier was shown on screen: {ui.identifier_seen}")
    else:
        log("the +888 account identifier never appeared on screen")

    if problems:
        for problem in problems:
            log(f"PROBLEM: {problem}")
        raise RuntimeError(f"{len(problems)} problem(s): {problems}")
    log("all checks passed")
    return 0


def save_logs(out):
    raw = subprocess.run(["adb", "logcat", "-d", "-v", "time"], capture_output=True, timeout=120).stdout.decode("utf-8", errors="replace")
    keep = re.compile(
        r"WalletSignIn|RegistrationRepository|SwarmWallet|SwarmNetwork|RegistrationViewModel|LibSignalChatConnection|"
        r"SignalWebSocket|IncomingMessageObserver|RefreshAttributes|ProfileUpload|AndroidRuntime|FATAL|PUT /v1|POST /v1|GET /v1|"
        r"IndividualSendJob|PushSendJob|SignalServiceMessageSender|MessageDecryptor|MessageContentProcessor|"
        r"IncomingMessageHandler|PushProcessMessage|MessageRequest|UsernameRepository|FindByViewModel|RetrieveProfile|"
        r"SendDeliveryReceipt|SendReadReceipt|ReceiptJob|SealedSender|SignalServiceNetworkAccess|NewConversation"
    )
    with open(os.path.join(out, "app-log.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(line for line in raw.splitlines() if keep.search(line)))
    pid = subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True, timeout=30).stdout.decode().strip()
    if pid:
        full = subprocess.run(["adb", "logcat", "-d", "-v", "time", f"--pid={pid.split()[0]}"], capture_output=True, timeout=120)
        with open(os.path.join(out, "app-log-full.txt"), "w", encoding="utf-8") as handle:
            handle.write(full.stdout.decode("utf-8", errors="replace"))


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        try:
            out_dir = next((sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--out"), "smoke-out")
            save_logs(out_dir)
        except Exception as error:  # noqa: BLE001
            print(f"could not save the log: {error}")
