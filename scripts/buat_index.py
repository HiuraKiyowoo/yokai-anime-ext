#!/usr/bin/env python3
"""Membuat index.min.json yang dibaca Yokai (GET <repo>/index.min.json).

Nilai tiap ekstensi dibaca dari extensions.json; URL APK menunjuk ke aset
GitHub Release dengan tag v<version>. Jadi menambah provider = tambah entri di
extensions.json, tidak perlu mengubah skrip ini.
"""
import json
import os

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.environ.get("GITHUB_REPOSITORY", "HiuraKiyowoo/yokai-anime-ext")

with open(os.path.join(AKAR, "extensions.json"), encoding="utf-8") as f:
    daftar = json.load(f)

hasil = []
for e in daftar:
    tag = "v" + e["version"]
    hasil.append({
        "name": e["name"],
        "pkg": e["pkg"],
        "apk": f"https://github.com/{REPO}/releases/download/{tag}/{e['module']}-release.apk",
        "lang": e["lang"],
        "code": e["code"],
        "version": e["version"],
        "nsfw": e.get("nsfw", 0),
        "hasReadme": 0,
        "hasChangelog": 0,
    })

keluar = os.path.join(AKAR, "index.min.json")
with open(keluar, "w", encoding="utf-8") as f:
    f.write(json.dumps(hasil, separators=(",", ":")))
print(f"index.min.json dibuat: {len(hasil)} ekstensi")
for x in hasil:
    print(" ", x["name"], x["pkg"], x["apk"])
