#!/usr/bin/env python3
# Copyright 2026 SWARM
# SPDX-License-Identifier: AGPL-3.0-only
"""SWARM endpoint scan for the Android build.

Fails when the configuration that ships in the SWARM Messenger APK names a Signal host or carries a
Firebase / FCM setting. "The configuration that ships" means, for the built variant:

  1. the generated BuildConfig.java - every server URL, host, trust root and key the app is compiled
     with comes from here;
  2. the merged AndroidManifest.xml - intent filters, services, receivers, providers, meta-data and
     permissions. An https intent filter with android:autoVerify makes *Android* fetch
     https://<host>/.well-known/assetlinks.json at install time, so an https filter on a Signal host
     is a contact with Signal even though the app never opens a socket itself;
  3. the merged default-locale resources (res/values) - strings such as URLs and the Firebase
     project settings (google_app_id, gcm_defaultSenderId, google_api_key, ...);
  4. the APK's assets that are text.

What it refuses (FAIL):
  - any host under signal.org, signal.art, signalcaptchas.org, signaldonations.org, whispersystems.org
    (this covers chat., storage., cdn*., cdsi., svr2., sfu.voip., updates., updates2., uptime.,
    contentproxy. and the rest), and the word "whispersystems" outside Java package names;
  - an http(s) intent filter for signal.me, signal.group, signal.link or signal.tube;
  - Firebase / FCM: a manifest component, meta-data or permission naming firebase or c2dm, or a
    non-empty Firebase project setting in the resources.

What it only reports (WARN), because they are identifiers or the deep-link vocabulary other Signal-
protocol clients share and nothing is fetched from them: the sgnl:// scheme with signal.me /
signal.group / signal.link / signal.tube hosts, Google Play services components pulled in by
dependencies, and how many times Signal hosts still appear inside the compiled code (dex), which is
code that is not configuration and is tracked in docs/SWARM-CHANGES.md.

Usage (after ./gradlew :Signal-Android:assembleWebsiteProdDebug):
  python3 scripts/swarm/endpoint_scan.py [--mode enforce|report] [--variant websiteProdDebug]
Exit status: 0 clean (or report mode), 1 findings in enforce mode, 2 the build outputs were not found.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Hosts SWARM Messenger must never be configured with, at any depth of subdomain.
FORBIDDEN_DOMAINS = [
    "signal.org",
    "signal.art",
    "signalcaptchas.org",
    "signaldonations.org",
    "whispersystems.org",
]
FORBIDDEN_HOST_RE = re.compile(
    r"(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]+\.)*(?:" + "|".join(re.escape(d) for d in FORBIDDEN_DOMAINS) + r")(?![A-Za-z0-9-])",
    re.IGNORECASE,
)
# The word itself, but not the Java package org.whispersystems.* (identifiers, not hosts).
WHISPERSYSTEMS_WORD_RE = re.compile(r"(?<!org\.)whispersystems(?!\.org)", re.IGNORECASE)

# Deep-link domains: forbidden as http(s) intent filters, reported as sgnl:// hosts.
DEEP_LINK_DOMAINS = {"signal.me", "signal.group", "signal.link", "signal.tube"}

# Firebase project settings (the google-services plugin's resource names).
FIREBASE_RESOURCE_NAMES = {
    "google_app_id",
    "gcm_defaultSenderId",
    "google_api_key",
    "google_crash_reporting_api_key",
    "firebase_database_url",
    "google_storage_bucket",
    "default_web_client_id",
    "project_id",
}
FIREBASE_WORD_RE = re.compile(r"firebase|c2dm", re.IGNORECASE)
PLAY_SERVICES_RE = re.compile(r"com\.google\.android\.gms", re.IGNORECASE)

TEXT_ASSET_SUFFIXES = (".json", ".txt", ".xml", ".html", ".htm", ".properties", ".md", ".cfg", ".conf", ".yml", ".yaml")


class Report:
    def __init__(self):
        self.failures = []
        self.warnings = []

    def fail(self, where, what):
        self.failures.append(f"{where}: {what}")

    def warn(self, where, what):
        self.warnings.append(f"{where}: {what}")


def find_one(patterns, description):
    for pattern in patterns:
        matches = sorted(glob.glob(pattern, recursive=True), key=len)
        if matches:
            return matches[0]
    print(f"endpoint_scan: could not find {description} (looked for: {', '.join(patterns)})", file=sys.stderr)
    return None


def variant_dirs(variant):
    """websiteProdDebug -> ('websiteProd', 'debug')."""
    match = re.match(r"^(.*?)(Debug|Release|Spinner|Perf|Benchmark|Canary|Quickstart|Mocked)$", variant)
    if not match:
        return variant, ""
    return match.group(1), match.group(2).lower()


def scan_text(report, where, text):
    for match in FORBIDDEN_HOST_RE.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        report.fail(f"{where}:{line}", f"Signal host {match.group(0)!r}")
    for match in WHISPERSYSTEMS_WORD_RE.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        report.fail(f"{where}:{line}", "the word 'whispersystems'")


def scan_buildconfig(report, path):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    scan_text(report, os.path.relpath(path), text)
    for number, line in enumerate(text.splitlines(), start=1):
        if FIREBASE_WORD_RE.search(line) and "public static final" in line:
            report.fail(f"{os.path.relpath(path)}:{number}", "a Firebase/FCM setting in BuildConfig")


def scan_manifest(report, path):
    where = os.path.relpath(path)
    tree = ET.parse(path)
    root = tree.getroot()

    with open(path, encoding="utf-8") as handle:
        raw = handle.read()
    # Hosts anywhere in the manifest (meta-data values, provider authorities, filters...) except the
    # deep-link hosts, which are judged per intent filter below.
    scan_text(report, where, raw)

    for element in root.iter():
        name = element.get(ANDROID_NS + "name") or ""
        if element.tag in ("service", "receiver", "provider", "activity", "meta-data", "uses-permission", "permission"):
            if FIREBASE_WORD_RE.search(name):
                report.fail(where, f"<{element.tag} android:name={name!r}> (Firebase/FCM)")
            elif PLAY_SERVICES_RE.search(name):
                report.warn(where, f"<{element.tag} android:name={name!r}> (Google Play services, from a dependency)")
        if element.tag == "intent-filter":
            schemes = set()
            hosts = set()
            for data in element.iter("data"):
                if data.get(ANDROID_NS + "scheme"):
                    schemes.add(data.get(ANDROID_NS + "scheme").lower())
                if data.get(ANDROID_NS + "host"):
                    hosts.add(data.get(ANDROID_NS + "host").lower())
            for host in sorted(hosts):
                if host in DEEP_LINK_DOMAINS or host.endswith(tuple("." + d for d in DEEP_LINK_DOMAINS)):
                    web = sorted(schemes & {"http", "https"})
                    if web:
                        verify = element.get(ANDROID_NS + "autoVerify") == "true"
                        report.fail(where, f"{'/'.join(web)} intent filter for {host}{' with autoVerify' if verify else ''}")
                    else:
                        report.warn(where, f"deep link {'/'.join(sorted(schemes)) or '(no scheme)'}://{host} (not fetched)")


def scan_values(report, path):
    where = os.path.relpath(path)
    with open(path, encoding="utf-8") as handle:
        raw = handle.read()
    scan_text(report, where, raw)
    root = ET.fromstring(raw)
    for element in root:
        name = element.get("name")
        if name in FIREBASE_RESOURCE_NAMES and (element.text or "").strip():
            if name == "project_id" and not re.search(r"^api-project-|firebase", element.text or "", re.IGNORECASE):
                continue
            report.fail(where, f"Firebase project setting <{element.tag} name={name!r}>")


def scan_apk(report, apk):
    where = os.path.relpath(apk)
    dex_counts = {}
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            name = info.filename
            if name.startswith("assets/") and name.lower().endswith(TEXT_ASSET_SUFFIXES):
                try:
                    text = archive.read(info).decode("utf-8", errors="replace")
                except Exception as error:  # noqa: BLE001 - a corrupt entry is itself worth reporting
                    report.warn(f"{where}!/{name}", f"could not read ({error})")
                    continue
                scan_text(report, f"{where}!/{name}", text)
            elif name.endswith(".dex"):
                data = archive.read(info)
                for domain in FORBIDDEN_DOMAINS + sorted(DEEP_LINK_DOMAINS):
                    # A whole host name, not a prefix of a Java package ("signal.me" in "signal.messages").
                    pattern = re.compile(rb"(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]+\.)*" + re.escape(domain.encode("ascii")) + rb"(?![A-Za-z0-9_-])")
                    count = len(pattern.findall(data))
                    if count:
                        dex_counts[domain] = dex_counts.get(domain, 0) + count
    for domain, count in sorted(dex_counts.items()):
        report.warn(f"{where} (code)", f"{count} occurrence(s) of {domain!r} in compiled code")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--mode", choices=("enforce", "report"), default=os.environ.get("SWARM_SCAN_MODE", "enforce"))
    parser.add_argument("--variant", default="websiteProdDebug")
    parser.add_argument("--app-dir", default="app")
    args = parser.parse_args()

    flavor, build_type = variant_dirs(args.variant)
    app = args.app_dir
    report = Report()

    buildconfig = find_one(
        [
            f"{app}/build/generated/source/buildConfig/{flavor}/{build_type}/**/BuildConfig.java",
            f"{app}/build/generated/source/buildConfig/{args.variant}/**/BuildConfig.java",
            f"{app}/build/generated/**/buildConfig/**/{args.variant}/**/BuildConfig.java",
        ],
        "the generated BuildConfig.java",
    )
    manifest = find_one(
        [
            f"{app}/build/intermediates/packaged_manifests/{args.variant}/**/AndroidManifest.xml",
            f"{app}/build/intermediates/merged_manifest/{args.variant}/**/AndroidManifest.xml",
            f"{app}/build/intermediates/merged_manifests/{args.variant}/**/AndroidManifest.xml",
        ],
        "the merged AndroidManifest.xml",
    )
    values = find_one(
        [
            f"{app}/build/intermediates/incremental/{args.variant}/**/merged.dir/values/values.xml",
            f"{app}/build/intermediates/incremental/**/{args.variant}*/**/merged.dir/values/values.xml",
        ],
        "the merged default-locale values.xml",
    )
    apks = sorted(glob.glob(f"{app}/build/outputs/apk/{flavor}/{build_type}/*.apk"))
    universal = [apk for apk in apks if "universal" in os.path.basename(apk)] or apks

    missing = [name for name, value in (("BuildConfig", buildconfig), ("manifest", manifest), ("values", values)) if value is None]
    if missing or not universal:
        print(f"endpoint_scan: build outputs missing ({', '.join(missing + ([] if universal else ['APK']))}); build {args.variant} first", file=sys.stderr)
        return 2

    scan_buildconfig(report, buildconfig)
    scan_manifest(report, manifest)
    scan_values(report, values)
    scan_apk(report, universal[0])

    print(f"SWARM endpoint scan, variant {args.variant}, mode {args.mode}")
    print(f"  BuildConfig : {buildconfig}")
    print(f"  manifest    : {manifest}")
    print(f"  values      : {values}")
    print(f"  apk         : {universal[0]}")
    for line in report.warnings:
        print(f"WARN {line}")
    for line in report.failures:
        print(f"FAIL {line}")
    print(f"{len(report.failures)} failure(s), {len(report.warnings)} warning(s)")

    if report.failures and args.mode == "enforce":
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
