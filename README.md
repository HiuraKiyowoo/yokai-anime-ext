# yokai-anime-ext

Repo ekstensi anime untuk [Yokai](https://github.com/null2264/yokai). Satu modul
per provider; aplikasi tidak perlu dibangun ulang saat provider ditambah.

## Menambahkan sebagai repo ekstensi di aplikasi

Di Yokai: **Browse → Extension Repo → tambah URL**

```
https://raw.githubusercontent.com/HiuraKiyowoo/yokai-anime-ext/main
```

Aplikasi meminta `<base>/repo.json` dulu (metadata + `signingKeyFingerprint`),
lalu membaca `index.min.json`. Tanpa `repo.json` yang sah, aplikasi menolak
dengan pesan **"URL repo tidak valid"** — itu bukan soal jaringan.

Aplikasi akan menambah `/index.min.json` sendiri. Semua ekstensi di repo ini
lalu muncul dan bisa dipasang/di-update dari dalam aplikasi.

## Isi

| Modul | Provider | Sumber data |
|---|---|---|
| `wibutime` | Wibutime | Supabase aplikasi Wibutime (katalog, episode, tautan video) |

## Cara build

CI membangun tiap modul, menandatanganinya, mengunggah APK ke GitHub Releases,
lalu memperbarui `index.min.json`. Tidak ada langkah manual.

Lokal:

```bash
git clone https://github.com/HiuraKiyowoo/yokai.git yokai      # pustaka API
./gradlew :wibutime:assembleRelease
```

`settings.gradle.kts` menyertakan direktori `yokai/` sebagai build komposit dan
mengganti dependensi `yokai:source-api` dengan proyek `:source:api` asli — jadi
tidak perlu mempublikasikan pustaka ke Maven.

## Menambah provider

1. Buat modul baru, mis. `provider2/`, ikuti pola `wibutime/`.
2. Daftarkan di `settings.gradle.kts` (`include(":provider2")`).
3. Tambah entri di `extensions.json` (nama, pkg, modul, versi, versionCode).
4. Push — CI membangun, merilis, dan memperbarui indeks.

Tiap ekstensi wajib punya di manifest:

```xml
<uses-feature android:name="tachiyomi.extension" android:required="false" />
<meta-data android:name="tachiyomi.extension.factory" android:value="...Factory" />
<meta-data android:name="tachiyomix.extensionLib" android:value="1.5" />
```

`tachiyomix.extensionLib` harus di antara 1.3 dan 1.6, kalau tidak aplikasi
menolak memuatnya.

## Penandatanganan

Keystore tetap disimpan sebagai secret repo (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`). Sidik jari SHA-256 sertifikatnya ditulis di
`repo.json` → `meta.signingKeyFingerprint`.

Kalau keystore diganti, `repo.json` **wajib** diperbarui, kalau tidak ekstensi
akan berstatus untrusted dan harus di-trust manual setiap update:

```bash
keytool -exportcert -keystore keystore.jks -alias <alias> -file cert.der
python3 -c "import hashlib;print(hashlib.sha256(open('cert.der','rb').read()).hexdigest())"
```
