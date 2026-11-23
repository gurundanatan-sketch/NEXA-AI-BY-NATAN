# NEXA AI BY NATAN

Asisten AI super-agent untuk Android. NEXA bisa **ngobrol real-time dua arah (dan disela)**, **dipanggil dengan menyebut namanya**, **mengendalikan HP**, **membaca & membalas chat**, **mengurus telepon**, **membantu live TikTok**, serta **membuat gambar & video panjang dengan suara kloning atau suara asli**.

> ⚠️ **Status:** kode aplikasi Android dibangun otomatis oleh GitHub Actions (lihat tab **Actions**). Server media dan TikTok bridge sudah diuji dengan data tiruan. Fitur yang bergantung pada API pihak ketiga (Gemini, OpenAI, MiniMax, TikTok) perlu dicoba dengan kunci API Anda sendiri.

---

## 🆕 Versi 6.0

- **Akun:** login/daftar dengan email atau nomor telepon, foto profil, nama panggilan, username, bio, beralih akun, keluar.
- **Premium Rp50.000/bulan:** transfer ke BNI 2117806403 a.n. Natanel Liaha, kirim bukti ke WhatsApp 085176894962,
  lalu aktifkan dengan kode. Premium hanya mengunci fitur asisten AI (Live, chat, panggil suara, Studio AI, kloning suara,
  TikTok LIVE), dan kuncinya diterapkan di server. Login, pengaturan, pusat kendali, aksi cepat HP, alarm, catatan, dan galeri tetap gratis.
- **Suara Live OpenAI:** selain suara Gemini, Live bisa memakai suara OpenAI Realtime (Marin, Cedar, Coral, dan lainnya).
- **Kode aktivasi dari Termux:** `bash tools/buat_kode.sh`. Pilihan: 1) Premium 30 hari, 2) hadiah/gratis (jumlah hari bebas
  atau selamanya), 3) pembuat aplikasi (selamanya, termasuk kotak saran). Semua kode dicatat di `~/nexa_kode_premium.txt`.
- **Saran & masukan:** pengguna mengirim saran. Pembuat aplikasi melihat dan membalasnya lewat ikon 🔔 di Beranda.
- **Live:** gaya anak muda Jaksel yang ekspresif dan berempati (bisa bercanda, curhat, dan gombal), selalu aktif tanpa batas
  waktu (sambung ulang otomatis), mode diam ("diem dulu" → aktif lagi saat dipanggil), jawaban berdasarkan Google Search
  beserta sumbernya, dan suara otomatis lewat headset kabel/USB/Bluetooth.
- **Pencipta:** Nexa menjelaskan bahwa ia diciptakan oleh Natanel Liaha ("Baginda Natan"), seorang tunanetra, untuk membantu
  para tunanetra mengendalikan HP, tablet, laptop, dan komputer. Nexa juga bisa menjelaskan semua fitur aplikasi.
- **Suara:** 30 suara Google Gemini + 13 suara OpenAI + suara kloning dalam satu daftar. Kloning suara bisa dari audio atau
  dari video di galeri. Video bisa memakai banyak tokoh dengan suara dan gaya bicara berbeda.
- **Pengaturan berkategori:** Umum, Suara, Bahasa (27 bahasa), Aksesibilitas, Izin tampil di atas aplikasi lain,
  Izin setelan terbatas (panduan), Izin & sistem, Server, Tentang.
- **Panggil dari luar aplikasi:** sebut nama Nexa kapan saja. Dengan izin "Tampil di atas aplikasi lain", Nexa membuka dirinya
  sebentar untuk menyalakan mikrofon, lalu kembali ke aplikasi sebelumnya.

### Langkah pembuat aplikasi setelah update
1. `bash tools/setup_server.sh`: memasang server baru, database akun, dan rahasia Premium. Kunci lama tetap dipakai, cukup Enter.
2. Pasang APK baru, daftar/login, lalu salin **ID perangkat** di Akun → Langganan Premium.
3. `bash tools/buat_kode.sh`, pilih **3 (pembuat aplikasi)**, lalu tempel kodenya di aplikasi dan tekan Aktifkan.

## 🆕 Versi 5.0

- **Server pribadi NEXA (Cloudflare Workers, gratis).** Semua kunci API (Gemini, OpenAI, Fish Audio, TikTok) disimpan di server dan diatur dari Termux dengan `bash tools/setup_server.sh`. Siapa pun yang memasang APK langsung bisa memakai semua fitur tanpa mengisi kunci, dan kunci tidak ada di dalam APK. Ganti kunci kapan saja dengan menjalankan skrip yang sama, tanpa update aplikasi.
- **Suara Gemini otomatis.** Satu saklar ON/OFF untuk semua suara (perintah, Live, video), 30 suara Google, 4 model TTS Google dengan cadangan otomatis, dan 7 gaya bicara.
- **Cek koneksi semua API** dengan satu tombol.
- **Fungsi baru:** jadwal Kalender, kontrol musik, navigasi peta, salin/bagikan teks, baca papan klip. Nexa juga tahu tanggal dan jam sekarang.
- **Tanya cepat** di Beranda dan Chat.

> Semua pemakaian aplikasi memakai kuota kunci API Anda. Server membatasi 120 permintaan per menit per pengguna (ubah lewat variabel `RATE_PER_MIN` di Cloudflare).

---

## ✨ Fitur

| Menu | Isi |
|---|---|
| **Beranda** | Orb asisten (ketuk untuk Live), panggil dengan suara "Nexa", aksi cepat (senter, Wi-Fi, Bluetooth, volume, info HP, baca notifikasi, baca layar), cek kesiapan izin, log aktivitas |
| **Chat** | Perintah teks/dikte; agen menjalankan aksi di HP sampai selesai |
| **Live** (tombol tengah) | Ngobrol suara real-time dengan Gemini Live, bisa disela, menjalankan fungsi HP sambil bicara |
| **TikTok** | Membacakan follow, tap-tap layar, hadiah (nama, jumlah, koin), share, permintaan naik live, penonton masuk, dan komentar. Setiap event punya **kalimat kustom** (bisa banyak variasi), statistik live, top gifter, dan AI yang menjawab pertanyaan penonton |
| **Studio** | 🎬 Video dari **naskah**, **topik**, atau **suara asli** · 🖼️ Gambar realistis (prompt diperkaya otomatis) · 🧬 Kloning suara (MiniMax / Gemini) · 📁 Galeri hasil |
| **Pusat Kendali** 🎛️ | Volume, alarm, timer, buka aplikasi, catatan |
| **Pengaturan** ⚙️ | Kunci & model, nama asisten, wake word, suara, kunci API (Gemini, OpenAI, Fish Audio), izin sistem |

Fungsi yang bisa dipanggil AI (lewat suara atau teks): jadwal Kalender, kontrol musik, navigasi, salin/bagikan teks, papan klip, baca layar, klik, ketik, scroll, Back/Home, buka aplikasi, WhatsApp, dialer, senter, volume, panel Wi-Fi/Bluetooth, alarm, timer, catatan, info baterai, baca & balas notifikasi, angkat/tolak telepon, Google Search, buat gambar, buat video, kontrol TikTok Live, akhiri percakapan.

---

## 🧱 Arsitektur

```
┌──────────────────── HP Android (APK Kotlin + UI HTML/CSS di WebView) ───────────────────┐
│ WakeWordService  ── "Nexa!" ──►  LiveVoiceService ⇄ WebSocket ⇄ Gemini Live (audio 2 arah) │
│ Accessibility · NotificationListener · Telepon · Intent/System  ◄── function calling ──┘ │
│ TikTokLiveManager ◄── WebSocket ── server.js (TikTok bridge)                              │
│ Studio UI ── HTTP ──► nexa_server.py (Flask + FFmpeg)                                      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
          ▲ Termux di HP yang sama (atau PC / VPS / Colab + Cloudflare Tunnel)
┌─────────┴─────────────────────────────────────────────────────────────────────────────────┐
│ server/tiktok/server.js   TikTok LIVE → event (follow, like, gift, share, minta naik, …)   │
│ server/nexa_server.py     naskah/topik/suara asli → adegan → gambar/klip AI + efek kamera  │
│                           + suara (Gemini / MiniMax kloning / OpenAI / GPT-SoVITS)         │
│                           + subtitle per kata + musik latar auto-ducking → MP4             │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

## 📁 Struktur

```
app/src/main/
├── AndroidManifest.xml
├── assets/web/               UI futuristik: index.html, css/nexa.css, js/app.js
├── java/com/natan/nexa/
│   ├── MainActivity.kt       WebView + event ke UI
│   ├── Prefs.kt              semua pengaturan
│   ├── ui/NexaBridge.kt      jembatan JavaScript ⇄ Kotlin
│   ├── live/                 LiveSession (Gemini Live, barge-in, auto-tutup) + LiveVoiceService
│   ├── wake/                 WakeWordService ("Nexa"), notifikasi latar belakang
│   ├── agent/                GeminiAgent, ToolExecutor, Tools, Prompts, SystemActions, AgentRunner
│   ├── accessibility/        baca layar & gestur
│   ├── notify/               baca/balas chat, deteksi telepon
│   ├── phone/                angkat/tolak telepon
│   ├── tiktok/               TikTokLiveManager (template, antrean suara, statistik, AI jawab)
│   ├── media/                Google Search, gambar, video, simpan ke Galeri
│   ├── voice/                Gemini TTS, Server TTS, GPT-SoVITS, TTS Android
│   └── system/               BootReceiver, Quick Settings tile
└── res/                      ikon, tema, xml
server/
├── nexa_server.py            server media (Python)
├── tiktok/server.js          TikTok LIVE bridge (Node.js)
├── setup_termux.sh / start_nexa.sh / nexa_env.example
tools/                        skrip suara Gemini (SDK) & tes GPT-SoVITS
gradle/wrapper/               Gradle Wrapper resmi 8.14.3 (dibuat dengan perintah `gradle wrapper`)
.github/workflows/build-apk.yml
```

---

## 🚀 Versi 3 — aplikasi MANDIRI (tanpa Termux)

Semua yang dulu berjalan di server Termux sekarang berjalan **di dalam APK**:

| Fitur | Dulu | Sekarang |
|---|---|---|
| Video dari naskah/topik/suara asli | Python + FFmpeg di Termux | **Media3 Transformer** di HP (studio/VideoRenderer.kt) |
| Kloning suara | MiniMax via server | **Fish Audio** / Gemini langsung dari HP |
| Transkrip suara asli | Server | **OpenAI Whisper** → Fish ASR → Gemini (langsung) |
| TikTok LIVE | server.js (Node) | **WebSocket langsung** (Euler Stream / TikTool), Termux opsional |

Folder `server/` tetap disertakan sebagai opsi lama, tetapi **tidak lagi diperlukan**.

## 🔏 Tanda tangan pribadi (update tanpa uninstall)

Android hanya mengizinkan APK baru menimpa aplikasi lama jika **tanda tangannya sama** dan **nomor versinya lebih tinggi**.
- Nomor versi kini naik otomatis di setiap build (`versionCode = 100 + nomor build`).
- Tanda tangan diambil dari **GitHub Secrets**. Siapkan sekali saja di Termux:

```bash
cd ~/NEXA-AI-BY-NATAN
bash tools/setup_signing.sh                                   # buat kunci baru
# atau pakai kunci yang sudah Anda punya:
bash tools/setup_signing.sh ~/storage/downloads/natan-release-BACKUP.jks
```

Skrip ini menyimpan kunci ke Secrets (`NEXA_KEYSTORE_BASE64`, `NEXA_KEYSTORE_PASSWORD`, `NEXA_KEY_ALIAS`, `NEXA_KEY_PASSWORD`) dan membuat cadangan di **Download/NEXA-release-KEY-BACKUP.jks**.
**Simpan file kunci & password-nya baik-baik.** Kalau hilang, update berikutnya tidak bisa menimpa aplikasi lama.

> APK yang sudah terpasang dengan tanda tangan lain (misalnya build pertama) harus di-uninstall **sekali**. Setelah itu semua update bisa langsung menimpa.

## 📲 Dapatkan APK

```bash
gh run list -R gurundanatan-sketch/NEXA-AI-BY-NATAN          # tunggu ✓
gh run download -R gurundanatan-sketch/NEXA-AI-BY-NATAN -n NEXA-AI-BY-NATAN-apk -D ~/storage/downloads/NEXA
```
Buka **File → Download/NEXA**, lalu instal APK-nya.

## 🔐 Izin & kunci di aplikasi

1. **Beranda → Kesiapan sistem**: aktifkan mikrofon, Aksesibilitas, Akses notifikasi, telepon, dan baterai.
2. **⚙️ Pengaturan**:
   - **Gemini API key** (aistudio.google.com/apikey)
   - **Kunci API lain**: OpenAI (`sk-…`), Fish Audio
3. **TikTok → Pengaturan pembacaan**: pilih penyedia **Euler Stream** atau **TikTool**, lalu tempel API key gratis dari eulerstream.com atau tik.tools.

> Android 13+: kalau muncul *"Setelan terbatas"*, buka **Info aplikasi → ⋮ → Izinkan setelan terbatas**.

## 🗣️ Memanggil "Nexa" tanpa membuka aplikasi

- **Beranda → Panggil dengan suara** = aktif. NEXA mendengarkan di latar belakang (ada notifikasi tetap).
- Ucapkan **"Nexa"** → NEXA langsung menyahut "Ya, ada apa?". Bisa juga sekaligus: **"Nexa, buka YouTube"**.
- Percakapan ditutup otomatis bila diam (default 45 detik) atau saat Anda bilang "udah, makasih Nexa". Setelah itu NEXA kembali siaga.
- Alternatif: tombol **NEXA Live** di Pengaturan Cepat, atau jadikan NEXA aplikasi asisten default (Pengaturan → Asisten default).

**Batasan jujur:** pendengar memakai pengenal suara bawaan Android secara berulang. Ia tidak sehemat "Ok Google", dan di beberapa HP terdengar bunyi bip (bisa dibisukan di Pengaturan). Untuk deteksi yang sangat hemat baterai, ganti dengan Picovoice Porcupine (butuh AccessKey). Setelah HP restart di Android 14+, ketuk notifikasi NEXA sekali untuk mengaktifkan lagi, karena Android membatasi mikrofon dari boot.

## 📺 TikTok LIVE

1. Isi penyedia & API key di **TikTok → Pengaturan pembacaan** (sekali saja).
2. Mulai live di TikTok → di NEXA buka **TikTok** → isi username → **Sambung**.
3. Atur kalimat tiap event (satu kalimat per baris, dipilih acak) dengan placeholder:
   `{nama}` `{username}` `{hadiah}` `{jumlah}` `{koin}` `{total_koin}` `{like}` `{komentar}`.
4. Tekan **▶ Coba** untuk mendengar contoh tanpa sedang live.

Anti-spam: like digabung per orang, hadiah combo dibacakan setelah combo selesai, ada jeda per orang, batas antrean, dan minimal koin/like.

> ⚠️ Koneksi ke TikTok memakai pustaka **tidak resmi** `tiktok-live-connector` (TikTok tidak menyediakan API publik untuk event live). Pustaka ini bisa berhenti bekerja bila TikTok berubah. Jika koneksi sering ditolak, isi `EULER_API_KEY`. Event **permintaan naik live** mungkin hanya terlihat oleh akun host yang login, jadi isi `TT_SESSION_ID` untuk itu. Jaga kerahasiaan sessionid Anda.

## 🎬 Studio video & kloning suara

| Sumber | Alur |
|---|---|
| **Naskah** | naskah → adegan → gambar AI + gerak kamera (Ken Burns) / klip video AI → suara → subtitle → MP4 |
| **Topik** | Gemini menulis naskah sesuai durasi, lalu alurnya sama |
| **Suara asli** | rekam/unggah narasi Anda → transkrip berwaktu (OpenAI Whisper; cadangan Gemini) → visual per kalimat, subtitle sinkron dengan suara Anda |

Suara narator: `gemini:Kore` (bawaan), `fish:<id>` (**kloning Fish Audio**), `gemini:voice_…` (Gemini replication), `openai:coral`.

**Kloning suara** (Studio → Suara): rekam/unggah sampel jernih → centang izin → **Kloning sekarang** → pilih ★ untuk dijadikan default.
- Fish Audio: sampel jernih 10–30 detik tanpa musik memberi hasil terbaik.
- Gemini: wajib ada rekaman persetujuan dari orang yang sama (eksperimental; bila gagal, pakai `tools/buat_suara_gemini.py`).
- **Hanya kloning suara milik sendiri atau suara yang pemiliknya sudah memberi izin.**

Musik latar diputar pelan di bawah narasi. Subtitle ditanam ke video, 3–4 kata per baris. Gambar diberi gerak kamera (zoom/geser).

---

## 🛡️ Keamanan & batasan

- NEXA diinstruksikan untuk membacakan dan meminta konfirmasi sebelum mengirim pesan. Ia juga dilarang membayar, mentransfer, menghapus data, atau mengganti kata sandi tanpa izin. Instruksi ini bukan jaminan, jadi tetap awasi saat NEXA bekerja.
- API key Gemini tersimpan di HP. Untuk aplikasi publik, pindahkan semua panggilan ke server dan pakai token sementara untuk Live.
- **Google Play** mewajibkan deklarasi khusus untuk AccessibilityService, akses notifikasi, dan izin telepon. Untuk pemakaian pribadi, cukup instal APK langsung.
- HTTP tanpa enkripsi hanya diizinkan untuk server lokal (`127.0.0.1` / jaringan rumah).
- Biaya API (Gemini, OpenAI, MiniMax) dihitung per pemakaian. Pantau di dashboard masing-masing.
- OpenAI `whisper-1` dijadwalkan berhenti pada 26 Feb 2027. Setelah itu, setel `OPENAI_STT_MODEL` ke model pengganti atau biarkan server memakai Gemini.

## 🧰 Build lokal (opsional)

```bash
./gradlew assembleDebug        # butuh JDK 17 + Android SDK
```

Wrapper Gradle di repo ini adalah wrapper resmi (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`) yang dibuat dengan perintah `gradle wrapper --gradle-version 8.14.3`. GitHub Actions memvalidasi checksum-nya lewat `gradle/actions/setup-gradle`.
