# Heidi Mobile — port tidak resmi HeidiSQL ke Android 7+

Klien database untuk Android (minSdk 24 = Android 7.0) yang meniru alur kerja
[HeidiSQL](https://github.com/HeidiSQL/HeidiSQL): session manager, pohon database, tab data,
struktur tabel, kode CREATE, dan editor query. **Bukan produk resmi HeidiSQL.**

Lisensi: **GPL v2**, mengikuti HeidiSQL (lihat `LICENSE`). Jika Anda mendistribusikan APK-nya,
source-nya juga wajib tersedia di bawah GPL v2.

## Kenapa bukan "port langsung"

HeidiSQL adalah aplikasi Delphi/VCL (sekitar 73 ribu baris Pascal, bergantung pada komponen
Windows). Tidak ada cara mengompilasinya untuk Android. Yang dilakukan di sini adalah
*re-implementasi* di Kotlin + Jetpack Compose, dengan logika dan query metadata diambil dari
source HeidiSQL.

| HeidiSQL (`source/`) | Port ini |
|---|---|
| `dbconnection.pas` — `TDBConnection` abstrak | `db/DbDriver.kt` |
| `TMySQLConnection`, `TPgConnection` | `db/JdbcDriver.kt` |
| `TSQLiteConnection` | `db/SqliteDriver.kt` |
| `TDBObject`, `TTableColumn`, `TTableKey`, `TDBQuery` | `db/Models.kt` |
| `QuoteIdent`, `EscapeString`, limit clause, `dbstructures.*.pas` | `db/Dialect.kt` |
| Pemecah batch SQL (`DELIMITER`, komentar, string) | `db/SqlSplitter.kt` |
| `connections.pas` (session manager) | `ui/SessionsScreen.kt`, `db/SessionStore.kt` |
| Pohon database, tab Data/Structure/Code, tab Query | `ui/BrowserScreen.kt`, `ui/TableScreen.kt` |
| `exportgrid.pas` (CSV) | `db/Csv.kt` |
| SSH tunnel via `plink.exe` | `db/SshTunnel.kt` (JSch) |

## Fitur

- MySQL / MariaDB, PostgreSQL, SQLite (file lokal)
- Session manager; password disimpan terenkripsi (AES-256-GCM, Android Keystore)
- SSH tunnel (eksperimental), host key di-pin saat koneksi pertama (trust on first use)
- Pohon database: tabel, view, procedure, function, trigger; buat/hapus database, kosongkan/hapus tabel
- Data tab: paging 1000 baris, sort lewat header, filter `WHERE`, insert/edit/hapus baris (butuh primary key)
- Structure, Indexes, dan kode CREATE (`SHOW CREATE` / `pg_get_*` / `sqlite_master`)
- Editor query: multi-statement, syntax highlight, riwayat, batal, ekspor CSV

## Perbedaan yang disengaja dari HeidiSQL

- **PostgreSQL**: seperti HeidiSQL, daftar "database" sebenarnya daftar *schema*.
- **SQLite**: Android 7 membawa SQLite 3.9, jadi memakai `PRAGMA table_info` dan `PRAGMA database_list`,
  bukan `pragma_table_xinfo` (butuh ≥ 3.16). Kolom generated/hidden tidak terlihat.
- **SQLite & scoped storage**: aplikasi tidak bisa membuka file di storage bersama secara langsung.
  File yang dipilih *disalin* ke penyimpanan aplikasi; gunakan menu "Export database file" untuk
  mengambilnya kembali.
- **Tidak ada**: MSSQL, Interbase/Firebird, ClickHouse, export SQL dump, import, user manager,
  heatmap, dan fitur desktop lain.
- Edit baris hanya untuk tabel ber-primary key. Saat insert, kolom kosong memakai nilai default kolom.

## Build

Android Studio (Koala atau lebih baru, JDK 17): buka folder ini, tunggu sync, lalu *Run*.

Command line:

```
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Atau push ke GitHub: workflow `.github/workflows/build.yml` menjalankan tes dan mengunggah APK debug
sebagai artifact.

## Status verifikasi (jujur)

Diuji di lingkungan pembuatan:

- 23 unit test (pemecah SQL, dialek, tokenizer, CSV, format BLOB) lulus.
- `JdbcDriver` diuji ke **MariaDB 10.11** (connector 2.7.6) dan **PostgreSQL 16** sungguhan:
  daftar database/schema, tabel/view/routine/trigger, kolom, index, kode CREATE, paging,
  INSERT/UPDATE/DELETE berparameter (termasuk NULL), deteksi hasil terpotong, error.
- Query SQLite diuji dengan CLI `sqlite3`.
- `SqliteDriver`, `SessionStore`/`Vault`, `SshTunnel` berhasil dikompilasi terhadap `android.jar` API 34.

**Belum terverifikasi** (tidak ada Android SDK/Maven di lingkungan pembuatan):

- Kompilasi lapisan Jetpack Compose (`ui/`, `AppViewModel.kt`, `MainActivity.kt`) dan build APK.
  Kalau ada error kompilasi, kemungkinan kecil dan lokal; kirim log-nya.
- Menjalankan app di perangkat/emulator Android 7, termasuk kompatibilitas
  MariaDB Connector/J 2.7.12 dan pgjdbc 42.2.27.jre7 di runtime Android.
- SSH tunnel dengan JSch 0.2.18 (hanya dikompilasi terhadap JSch 0.1.54).
- Autentikasi `caching_sha2_password` (MySQL 8) lewat koneksi tanpa SSL mungkin gagal; aktifkan SSL
  atau gunakan user `mysql_native_password`.

## Catatan keamanan

- Opsi SSL menerima sertifikat server apa adanya (tanpa verifikasi) agar mudah dipakai dengan sertifikat
  self-signed. Untuk jaringan tidak tepercaya, gunakan SSH tunnel.
- `usesCleartextTraffic="true"` diperlukan karena koneksi database memakai TCP biasa.
