"""Offline checks for accompanying notices; no build/device/network operations."""

import hashlib
import json
import struct
import zipfile
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


root = Path(__file__).resolve().parents[2]
folder = root / "docs/licenses"
evidence = json.loads((folder / "evidence.json").read_text(encoding="utf-8"))
assert digest(root / evidence["apk"]["path"]) == evidence["apk"]["sha256"], "APK changed"
assert digest(root / evidence["dependency_report"]["path"]) == evidence["dependency_report"]["sha256"], "Dependency report changed"
for name, expected in evidence["material_hashes"].items():
    assert digest(folder / name) == expected, f"License material changed: {name}"
coords = {item["coordinate"] for item in evidence["coordinates"]}
assert len(coords) == len(evidence["coordinates"]), "Duplicate coordinates"
for name in ("commonmark", "commonmark-ext-autolink", "commonmark-ext-gfm-tables"):
    item = next(item for item in evidence["coordinates"] if item["coordinate"] == f"org.commonmark:{name}:0.24.0")
    assert any(entry["material"] == "COMMONMARK-BSD-2-Clause.txt" for entry in item["license_entries"]), "CommonMark notice missing"
assert "AUTOLINK-MIT.txt" in evidence["material_hashes"], "Autolink notice missing"
assert "PUBLIC_SUFFIX_LIST.dat" in evidence["material_hashes"], "MPL source missing"
assert evidence["public_suffix_list"]["regenerated_apk_asset_match"], "MPL source mismatch"
rules, exceptions = set(), set()
for line in (folder / "PUBLIC_SUFFIX_LIST.dat").read_bytes().splitlines():
    if not line.strip() or line.startswith(b"//"):
        continue
    if line.startswith(b"!"):
        exceptions.add(line[1:])
    else:
        rules.add(line)
rules = b"".join(line + b"\n" for line in sorted(rules))
exceptions = b"".join(line + b"\n" for line in sorted(exceptions))
regenerated = struct.pack(">I", len(rules)) + rules + struct.pack(">I", len(exceptions)) + exceptions
with zipfile.ZipFile(root / evidence["apk"]["path"]) as apk:
    assert regenerated == apk.read("assets/PublicSuffixDatabase.list"), "MPL source does not regenerate packaged asset"
assert not any("jlatexmath" in coordinate.lower() for coordinate in coords), "Debug math entered release graph"
print(f"PASS: {len(coords)} coordinates, {len(evidence['material_hashes'])} exact material hashes; APK unchanged")
