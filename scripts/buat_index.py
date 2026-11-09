#!/usr/bin/env python3
"""Membuat index.min.json yang dibaca Yokai (GET <base>/index.min.json).

Aturan yang tidak boleh dilanggar (dibaca dari ExtensionApi.kt):
  * `sources` WAJIB ada di tiap entri (boleh null). Nullable tapi tanpa nilai
    bawaan — kalau field ini hilang, seluruh indeks gagal di-parse dan daftar
    ekstensi tampil kosong tanpa pesan.
  * `apk` hanya nama berkas. Untuk indeks JSON aplikasi menyusun sendiri
    URL-nya: <base>/apk/<apk>. URL absolut di sini diabaikan.
  * libVersion diambil dari `version` (buang segmen terakhir): "1.5.1" -> 1.5,
    dan harus 1.3 - 1.6.
"""
import json
import os

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

with open(os.path.join(AKAR, "extensions.json"), encoding="utf-8") as f:
    daftar = json.load(f)

hasil = []
for e in daftar:
    hasil.append({
        "name": e["name"],
        "pkg": e["pkg"],
        "apk": f"{e['module']}-release.apk",
        "lang": e["lang"],
        "code": e["code"],
        "version": e["version"],
        "nsfw": e.get("nsfw", 0),
        "hasReadme": 0,
        "hasChangelog": 0,
        "sources": e.get("sources"),
    })

keluar = os.path.join(AKAR, "index.min.json")
with open(keluar, "w", encoding="utf-8") as f:
    f.write(json.dumps(hasil, separators=(",", ":")))
print(f"index.min.json: {len(hasil)} ekstensi")
for x in hasil:
    print(" ", x["name"], x["version"], "->", x["apk"])
