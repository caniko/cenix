#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/dist/sbom.cdx.json}"
apk="${CENIX_SBOM_APK:-$root/android/app/build/outputs/apk/release/app-release-unsigned.apk}"
[[ -f "$apk" ]] || { echo "unsigned release APK required: $apk" >&2; exit 1; }
for file in Cargo.lock android/app/gradle.lockfile android/fixture/gradle.lockfile android/gradle/verification-metadata.xml; do
  [[ -s "$root/$file" ]] || { echo "missing SBOM input: $file" >&2; exit 1; }
done
mkdir -p "$(dirname "$out")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cargo metadata --manifest-path "$root/Cargo.toml" --format-version 1 --locked --offline >"$tmp/cargo.json"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$root" show -s --format=%ct HEAD)}" \
  python3 - "$root" "$apk" "$tmp/cargo.json" "$out" <<'PY'
import datetime, hashlib, json, os, pathlib, re, sys, tomllib, urllib.parse, uuid, zipfile
import xml.etree.ElementTree as ET

root, apk, cargo_json, out = map(pathlib.Path, sys.argv[1:])
epoch = int(os.environ["SOURCE_DATE_EPOCH"])
commit = os.environ.get("CENIX_GIT_COMMIT") or __import__("subprocess").check_output(
    ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
).strip()

def digest(data): return hashlib.sha256(data).hexdigest()
def file_digest(path): return digest(path.read_bytes())
def license_entry(value):
    if not value or value == "NOASSERTION": return [{"license": {"name": "NOASSERTION"}}]
    return [{"expression": value}]
def component(kind, name, version, purl, licenses="NOASSERTION", hashes=(), props=()):
    value = {"type": kind, "name": name, "version": version, "bom-ref": purl, "purl": purl,
             "licenses": license_entry(licenses)}
    unique = sorted(set(hashes))
    if unique: value["hashes"] = [{"alg": "SHA-256", "content": h} for h in unique]
    if props: value["properties"] = [{"name": k, "value": v} for k, v in sorted(props)]
    return value

lock = tomllib.loads((root / "Cargo.lock").read_text())
checksums = {(p["name"], p["version"]): p.get("checksum") for p in lock["package"]}
metadata = json.loads(cargo_json.read_text())
components = []
for package in metadata["packages"]:
    name, version = package["name"], package["version"]
    purl = f"pkg:cargo/{urllib.parse.quote(name)}@{urllib.parse.quote(version)}"
    hashes = [checksums[(name, version)]] if checksums.get((name, version)) else []
    components.append(component("library", name, version, purl, package.get("license"), hashes))

locked = set()
for lockfile in (root / "android/app/gradle.lockfile", root / "android/fixture/gradle.lockfile"):
    for line in lockfile.read_text().splitlines():
        coord = line.split("=", 1)[0]
        if line.startswith("#") or coord == "empty" or coord.count(":") != 2: continue
        locked.add(tuple(coord.split(":")))

ns = {"v": "https://schema.gradle.org/dependency-verification"}
verification = ET.parse(root / "android/gradle/verification-metadata.xml")
artifact_hashes = {}
for node in verification.findall(".//v:component", ns):
    key = (node.attrib["group"], node.attrib["name"], node.attrib["version"])
    artifact_hashes[key] = [h.attrib["value"] for h in node.findall(".//v:sha256", ns)]

def gradle_license(group, name):
    if group.startswith(("androidx.", "com.android.", "org.jetbrains.kotlin", "com.google.devtools.ksp")):
        return "Apache-2.0"
    return {
        ("net.java.dev.jna", "jna"): "Apache-2.0 OR LGPL-2.1-or-later",
        ("junit", "junit"): "EPL-1.0",
        ("org.hamcrest", "hamcrest-core"): "BSD-3-Clause",
        ("org.robolectric", "robolectric"): "MIT",
    }.get((group, name), "NOASSERTION")

for group, name, version in sorted(locked):
    purl = f"pkg:maven/{urllib.parse.quote(group)}/{urllib.parse.quote(name)}@{urllib.parse.quote(version)}"
    components.append(component("library", f"{group}:{name}", version, purl,
                                gradle_license(group, name), artifact_hashes.get((group, name, version), [])))

with zipfile.ZipFile(apk) as archive:
    entries = (i for i in archive.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so"))
    for entry in sorted(entries, key=lambda value: value.filename):
        _, abi, filename = entry.filename.split("/")
        data = archive.read(entry)
        purl = f"pkg:generic/{urllib.parse.quote(filename)}@0.1.0?arch={urllib.parse.quote(abi)}"
        components.append(component("library", filename, "0.1.0", purl, "Apache-2.0", [digest(data)], [("android:abi", abi)]))

tools = [
    ("gradle", "8.12.1"), ("android-gradle-plugin", "8.8.2"), ("kotlin", "2.1.10"),
    ("android-sdk", "35"), ("android-build-tools", "35.0.0"), ("android-ndk", "29.0.14206865"),
    ("openjdk", "21"), ("uniffi", "0.29.5"), ("rust", "nightly-2026-02-28"),
]
for name, version in tools:
    purl = f"pkg:generic/{name}@{version}"
    components.append(component("framework", name, version, purl))

components.sort(key=lambda c: c["bom-ref"])
refs = [c["bom-ref"] for c in components]
if len(refs) != len(set(refs)):
    raise SystemExit("duplicate SBOM component reference")
apk_hash = file_digest(apk)
application_ref = "pkg:apk/com.caniko.cenix@0.1.0"
timestamp = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).isoformat().replace("+00:00", "Z")
sbom = {
    "$schema": "https://cyclonedx.org/schema/bom-1.5.schema.json",
    "bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
    "serialNumber": f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, commit + apk_hash)}",
    "metadata": {
        "timestamp": timestamp,
        "component": {"type": "application", "name": "Cenix", "group": "com.caniko",
                      "version": "0.1.0", "bom-ref": application_ref, "purl": application_ref,
                      "hashes": [{"alg": "SHA-256", "content": apk_hash}],
                      "licenses": license_entry("Apache-2.0"),
                      "properties": [{"name": "vcs:commit", "value": commit}]},
    },
    "components": components,
}
out.write_text(json.dumps(sbom, indent=2, sort_keys=True) + "\n")

# Structural and cross-report validation stays local and deterministic.
loaded = json.loads(out.read_text())
assert loaded["bomFormat"] == "CycloneDX" and loaded["specVersion"] == "1.5"
assert all(re.fullmatch(r"[0-9a-f]{64}", h["content"])
           for c in [loaded["metadata"]["component"], *loaded["components"]] for h in c.get("hashes", []))
required = {
    "pkg:maven/androidx.appcompat/appcompat@1.7.0",
    "pkg:maven/androidx.core/core-ktx@1.15.0",
    "pkg:maven/androidx.room/room-runtime@2.6.1",
    "pkg:maven/net.java.dev.jna/jna@5.19.1",
}
missing = required - set(refs)
if missing: raise SystemExit(f"runtime components absent from SBOM: {sorted(missing)}")
license_report = out.with_name("licenses.json")
license_report.write_text(json.dumps({c["bom-ref"]: c["licenses"] for c in components}, indent=2, sort_keys=True) + "\n")
assert set(json.loads(license_report.read_text())) == set(refs)
print(f"validated {out} ({len(components)} components, apk sha256 {apk_hash})")
PY
