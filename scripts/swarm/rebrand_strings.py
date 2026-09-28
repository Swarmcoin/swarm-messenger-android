#!/usr/bin/env python3
# Copyright 2026 SWARM
# SPDX-License-Identifier: AGPL-3.0-only
"""Sweep user-visible "Signal" wording and Signal links out of the English strings.

Runs over every module's default-locale res/values/strings.xml (demo apps excluded). Only element
text is touched - never resource names, attributes or XML comments - so the diff is exactly the
wording. Idempotent: run it again after an upstream merge and it re-sweeps the new strings.

The rules follow the desktop fork's scripts/swarm-rebrand-locale.mjs (Swarm-Official/swarm-messenger)
so both apps say the same thing. What is deliberately kept:
  - "Signal Protocol": the name of the cryptographic protocol SWARM Messenger really uses;
  - "Signal Technology Foundation": who that is (donation text, unreachable in SWARM builds);
  - the copyright line in the About screen ("Copyright Signal Messenger, LLC"), which is a legal
    notice, not branding. The "based on Signal" attribution lives in swarm_strings.xml.
Translated locales (values-xx) are not swept: re-translation is an owner decision. Android falls back
to these English strings only for keys a locale lacks, so a French phone still reads "Signal" in
already-translated sentences.

Usage: python3 scripts/swarm/rebrand_strings.py [--check]
  --check  change nothing; exit 1 if a sweep would change something (for CI).
"""

import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

SUPPORT = "https://swarm.green/support"

# Ordered: most specific first. Applied to text only.
REPLACEMENTS = [
    # Links. Signal's support articles have no SWARM equivalent yet, so every one goes to the
    # support page rather than to a guessed path.
    (re.compile(r"https?://support\.signal\.org[^\s\"<]*"), SUPPORT),
    (re.compile(r"support\.signal\.org[^\s\"<]*"), "swarm.green/support"),
    (re.compile(r"support@signal\.org"), "support@swarm.green"),
    (re.compile(r"https?://(?:www\.)?signal\.org/legal[^\s\"<]*"), "https://swarm.green/legal"),
    (re.compile(r"https?://(?:www\.)?signal\.org[^\s\"<]*"), "https://swarm.green"),
    (re.compile(r"(?<![\w.-])signal\.org\b"), "swarm.green"),
    # Names that stay.
    (re.compile(r"Signal Protocol"), "\u0000PROTOCOL\u0000"),
    (re.compile(r"Signal Technology Foundation"), "\u0000FOUNDATION\u0000"),
    # Product wording, as the desktop fork has it.
    (re.compile(r"Signal Android"), "SWARM Messenger"),
    (re.compile(r"Signal Messenger, LLC"), "SWARM"),
    (re.compile(r"Signal Messenger"), "SWARM Messenger"),
    (re.compile(r"Signal Desktop"), "SWARM Messenger Desktop"),
    (re.compile(r"Signal PIN"), "SWARM PIN"),
    (re.compile(r"Signal Backups"), "SWARM Backups"),
    (re.compile(r"Signal Backup"), "SWARM Backup"),
    (re.compile(r"Signal [Pp]rofile"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Aa]ccount"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Gg]roup"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Cc]all"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Mm]essage"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Cc]onnection"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    (re.compile(r"Signal [Uu]sername"), lambda m: "SWARM " + m.group(0).split(" ")[1]),
    # The bare product name, last.
    (re.compile(r"\bSignal\b"), "SWARM Messenger"),
    (re.compile(r"\u0000PROTOCOL\u0000"), "Signal Protocol"),
    (re.compile(r"\u0000FOUNDATION\u0000"), "Signal Technology Foundation"),
]

# Strings a mechanical sweep cannot get right, by resource name. Value is the exact new text.
OVERRIDES = {
    "app_name": "SWARM Messenger",
    # A legal notice: Signal Messenger, LLC holds the copyright of the code this app is built on.
    "HelpFragment__copyright_signal_messenger": "Copyright Signal Messenger, LLC",
    # Upstream: "Signal is a 501c3 nonprofit". False for SWARM. The About screen shows the
    # attribution instead (swarm_strings.xml); the welcome screen keeps only the link text.
    "HelpSettingsFragment__signal_is_a_501c3": "SWARM Messenger is based on Signal by Signal Messenger, LLC",
    "RegistrationActivity_terms_and_privacy": "Terms &amp; Privacy Policy",
    # The banner every SWARM Messenger user sees until battery optimisation is off. Upstream shows it only on
    # phones without Google Play services and says the device lacks them; SWARM Messenger shows it everywhere,
    # because it never uses them.
    "DozeReminder_optimize_for_missing_play_services": "Keep messages arriving",
    "DozeReminder_this_device_does_not_support_play_services_tap_to_disable_system_battery": "SWARM Messenger does not use Google push messaging. Tap to turn off battery optimization for it, so it can keep its own connection and receive messages while inactive.",
}

TOKEN_RE = re.compile(r"(<!--.*?-->|<!\[CDATA\[.*?\]\]>|<[^>]*>)", re.DOTALL)
NAME_RE = re.compile(r'<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"')


def sweep_text(text):
    for pattern, value in REPLACEMENTS:
        text = pattern.sub(value, text)
    return text


def sweep_file(path):
    with open(path, encoding="utf-8") as handle:
        original = handle.read()

    parts = TOKEN_RE.split(original)
    changed_names = set()
    current_name = None
    out = []
    for part in parts:
        if not part:
            continue
        if part.startswith("<!--"):
            out.append(part)
            continue
        if part.startswith("<![CDATA["):
            inner = part[len("<![CDATA["):-len("]]>")]
            new_inner = sweep_text(inner)
            if new_inner != inner and current_name:
                changed_names.add(current_name)
            out.append("<![CDATA[" + new_inner + "]]>")
            continue
        if part.startswith("<"):
            match = NAME_RE.match(part)
            if match:
                current_name = match.group(2)
            elif re.match(r"</(string|plurals|string-array)>", part):
                current_name = None
            out.append(part)
            continue
        if current_name is None:
            out.append(part)
            continue
        new_part = sweep_text(part)
        if new_part != part:
            changed_names.add(current_name)
        out.append(new_part)

    swept = "".join(out)

    for name, value in OVERRIDES.items():
        pattern = re.compile(r'(<string\b[^>]*\bname="' + re.escape(name) + r'"[^>]*>)(.*?)(</string>)', re.DOTALL)
        swept, count = pattern.subn(lambda m: m.group(1) + value + m.group(3), swept)
        if count:
            changed_names.add(name)

    return original, swept, changed_names


def leftovers(text):
    names = []
    for match in re.finditer(r'<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"[^>]*>(.*?)</\1>', text, re.DOTALL):
        body = re.sub(r"<!--.*?-->", "", match.group(3), flags=re.DOTALL)
        if re.search(r"\bSignal\b|signal\.org", body):
            names.append(match.group(2))
    return names


def main():
    check = "--check" in sys.argv
    files = sorted(
        path for path in glob.glob(os.path.join(ROOT, "**", "src", "main", "res", "values", "strings.xml"), recursive=True)
        if os.sep + "demo" + os.sep not in path and os.sep + "build" + os.sep not in path
    )
    total_changed = 0
    would_change = False
    all_left = []
    for path in files:
        original, swept, names = sweep_file(path)
        rel = os.path.relpath(path, ROOT)
        if swept != original:
            would_change = True
            if not check:
                with open(path, "w", encoding="utf-8", newline="\n") as handle:
                    handle.write(swept)
        total_changed += len(names)
        left = leftovers(swept)
        all_left += [f"{rel}:{name}" for name in left]
        print(f"{rel}: {len(names)} string(s) {'would change' if check else 'swept'}")

    print(f"rebrand_strings: {total_changed} string(s) {'would change' if check else 'swept'} in {len(files)} file(s).")
    print(f"rebrand_strings: {len(all_left)} string(s) still say Signal (expected: Signal Protocol, Signal Technology Foundation, the copyright notice, the attribution):")
    for item in all_left:
        print(f"  {item}")

    locales = sorted({os.path.basename(os.path.dirname(p)) for p in glob.glob(os.path.join(ROOT, "app", "src", "main", "res", "values-*", "strings.xml"))})
    print(f"rebrand_strings: {len(locales)} translated locale(s) in app/ left unswept (owner decision): {' '.join(l[len('values-'):] for l in locales)}")

    if check and would_change:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
