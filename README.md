# NEXA AI BY NATAN

Asisten AI super-agent untuk Android. NEXA bisa **ngobrol real-time dua arah (dan disela)**, **dipanggil dengan menyebut namanya**, **mengendalikan HP**, **membaca & membalas chat**, **mengurus telepon**, **membantu live TikTok**, serta **membuat gambar & video panjang dengan suara kloning atau suara asli**.

> ⚠️ **Status:** kode aplikasi Android dibangun otomatis oleh GitHub Actions (lihat tab **Actions**). Server media dan TikTok bridge sudah diuji dengan data tiruan. Fitur yang bergantung pada API pihak ketiga (Gemini, OpenAI, MiniMax, TikTok) perlu dicoba dengan kunci API Anda sendiri.

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
| **Pengaturan** ⚙️ | Kunci & model, nama asisten, wake word, suara, server Termux, izin sistem |

Fungsi yang bisa dipanggil AI (lewat suara atau teks): baca layar, klik, ketik, scroll, Back/Home, buka aplikasi, WhatsApp, dialer, senter, volume, panel Wi-Fi/Bluetooth, alarm, timer, catatan, info baterai, baca & balas notifikasi, angkat/tolak telepon, Google Search, buat gambar, buat video, kontrol TikTok Live, akhiri percakapan.

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

## 🚀 Langkah 1 — Dapatkan APK

1. Buka tab **Actions** di repo ini → **Build NEXA AI APK** → jalankan terbaru (centang hijau).
2. Unduh artifact **NEXA-AI-BY-NATAN-apk**, ekstrak, instal `NEXA-AI-BY-NATAN-release.apk`.
3. Untuk rilis resmi: buat tag `v2.0.0` → APK otomatis muncul di **Releases**.

> APK rilis ditandatangani dengan kunci debug kecuali Anda menambahkan Secrets `NEXA_KEYSTORE_BASE64`, `NEXA_KEYSTORE_PASSWORD`, `NEXA_KEY_ALIAS`, `NEXA_KEY_PASSWORD`. Simpan keystore yang sama supaya update APK tidak perlu uninstall.

## 🔐 Langkah 2 — Izin di HP

Buka NEXA → **Beranda → Kesiapan sistem**, aktifkan satu per satu:
- **Gemini API key** (Pengaturan) dari https://aistudio.google.com/apikey
- **Mikrofon & telepon**, **Aksesibilitas** (*NEXA – Kontrol Layar*), **Akses notifikasi**, **Abaikan hemat baterai**

> Android 13+: kalau muncul *"Setelan terbatas"*, buka **Info aplikasi → ⋮ → Izinkan setelan terbatas**.

## 🖥️ Langkah 3 — Server di Termux (untuk video, kloning suara, TikTok)

```bash
pkg install -y git
git clone https://github.com/gurundanatan-sketch/NEXA-AI-BY-NATAN
cd NEXA-AI-BY-NATAN/server
bash setup_termux.sh          # sekali: python, nodejs, ffmpeg, modul
nano ~/.nexa_env              # isi NEXA_TOKEN, GEMINI_API_KEY, OPENAI_API_KEY, MINIMAX_API_KEY
bash start_nexa.sh            # setiap kali dipakai
```

Di aplikasi → **Pengaturan → Server NEXA**: URL `http://127.0.0.1:5000`, Token = `NEXA_TOKEN`, TikTok bridge `ws://127.0.0.1:8090` → **Tes koneksi server**.

Kunci API OpenAI & MiniMax **hanya disimpan di Termux** (`~/.nexa_env`), tidak di APK dan tidak di GitHub.

---

## 🗣️ Memanggil "Nexa" tanpa membuka aplikasi

- **Beranda → Panggil dengan suara** = aktif. NEXA mendengarkan di latar belakang (ada notifikasi tetap).
- Ucapkan **"Nexa"** → NEXA langsung menyahut "Ya, ada apa?". Bisa juga sekaligus: **"Nexa, buka YouTube"**.
- Percakapan ditutup otomatis bila diam (default 45 detik) atau saat Anda bilang "udah, makasih Nexa". Setelah itu NEXA kembali siaga.
- Alternatif: tombol **NEXA Live** di Pengaturan Cepat, atau jadikan NEXA aplikasi asisten default (Pengaturan → Asisten default).

**Batasan jujur:** pendengar memakai pengenal suara bawaan Android secara berulang. Ia tidak sehemat "Ok Google", dan di beberapa HP terdengar bunyi bip (bisa dibisukan di Pengaturan). Untuk deteksi yang sangat hemat baterai, ganti dengan Picovoice Porcupine (butuh AccessKey). Setelah HP restart di Android 14+, ketuk notifikasi NEXA sekali untuk mengaktifkan lagi, karena Android membatasi mikrofon dari boot.

## 📺 TikTok LIVE

1. Jalankan `start_nexa.sh` di Termux (TikTok bridge ikut berjalan).
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

Suara narator: `gemini:Kore` (bawaan), `minimax:<id>` (**kloning MiniMax Speech-02**), `gemini:voice_…` (Gemini replication), `openai:coral`, `sovits:default`.

**Kloning suara** (Studio → Suara): rekam/unggah sampel jernih → centang izin → **Kloning sekarang** → pilih ★ untuk dijadikan default.
- MiniMax: suara kloning yang tidak dipakai selama 7 hari dihapus otomatis, jadi pakai minimal sekali.
- Gemini: wajib ada rekaman persetujuan dari orang yang sama. Jalur REST di server bersifat eksperimental; bila gagal, pakai `tools/buat_suara_gemini.py` (SDK resmi).
- **Hanya kloning suara milik sendiri atau suara yang pemiliknya sudah memberi izin.**

Musik latar otomatis mengecil saat ada narasi (auto-ducking). Subtitle ditanam ke video, 3–4 kata per baris.

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
