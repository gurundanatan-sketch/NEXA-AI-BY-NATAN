package com.natan.nexa.agent

import org.json.JSONArray
import org.json.JSONObject

/** Daftar fungsi yang boleh dipanggil Gemini (function declarations). */
object Tools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .apply {
                // Fungsi tanpa parameter: jangan kirim "parameters" kosong.
                if (props.length() > 0) put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", props)
                    .put("required", JSONArray(required)))
            }

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun choice(desc: String, vararg v: String) =
        str(desc).put("enum", JSONArray(v.toList()))

    /** forLive = true: tanpa ask_user/finish, karena di mode Live model langsung berbicara. */
    fun declarations(forLive: Boolean = false): JSONArray = JSONArray()
        .put(fn("open_app", "Buka aplikasi terpasang berdasarkan nama atau package.",
            JSONObject().put("name", str("Nama aplikasi, mis. YouTube")), listOf("name")))
        .put(fn("click", "Klik elemen layar bernomor.",
            JSONObject().put("index", int("Nomor elemen [n]")), listOf("index")))
        .put(fn("type_text", "Ketik teks ke kolom input bernomor.",
            JSONObject()
                .put("index", int("Nomor elemen edit"))
                .put("text", str("Teks yang diketik"))
                .put("press_enter", bool("Tekan enter/cari setelah mengetik")),
            listOf("index", "text")))
        .put(fn("scroll", "Gulir layar.",
            JSONObject().put("direction", choice("Arah", "down", "up", "left", "right")), listOf("direction")))
        .put(fn("press_key", "Tekan tombol sistem.",
            JSONObject().put("key", choice("Tombol", "back", "home", "recents", "notifications", "quick_settings")),
            listOf("key")))
        .put(fn("open_url", "Buka alamat web atau deep link.",
            JSONObject().put("url", str("URL")), listOf("url")))
        .put(fn("dial", "Buka dialer telepon dengan nomor terisi.",
            JSONObject().put("number", str("Nomor telepon")), listOf("number")))
        .put(fn("send_whatsapp", "Buka chat WhatsApp dengan pesan terisi. Setelah itu klik tombol kirim.",
            JSONObject()
                .put("phone", str("Nomor format internasional, mis. 6281234567890"))
                .put("message", str("Isi pesan")),
            listOf("phone", "message")))
        .put(fn("flashlight", "Nyalakan atau matikan senter.",
            JSONObject().put("on", bool("true = nyala")), listOf("on")))
        .put(fn("set_volume", "Atur volume media.",
            JSONObject().put("percent", int("0 sampai 100")), listOf("percent")))
        .put(fn("open_settings", "Buka panel pengaturan (Wi-Fi tidak bisa diubah langsung; buka panelnya lalu klik toggle).",
            JSONObject().put("panel", choice("Panel", "wifi", "internet", "volume", "bluetooth", "display", "accessibility", "main")),
            listOf("panel")))
        .put(fn("wait", "Tunggu layar selesai memuat.",
            JSONObject().put("seconds", int("1 sampai 10"))))
        .put(fn("read_screen", "Baca isi layar saat ini (juga untuk menjawab pertanyaan tentang layar)."))
        // --- notifikasi & chat ---
        .put(fn("read_notifications", "Baca notifikasi/chat terbaru (WhatsApp, Telegram, SMS, dll.).",
            JSONObject()
                .put("app", str("Filter nama aplikasi, mis. WhatsApp. Kosongkan untuk semua."))
                .put("limit", int("Jumlah maksimal, default 10"))))
        .put(fn("reply_notification", "Balas langsung chat dari notifikasi (Direct Reply). WAJIB konfirmasi isi pesan ke pengguna dulu.",
            JSONObject()
                .put("index", int("Nomor [n] dari read_notifications"))
                .put("message", str("Isi balasan"))
                .put("app", str("Filter aplikasi yang sama dengan saat membaca")),
            listOf("index", "message")))
        // --- telepon ---
        .put(fn("answer_call", "Angkat telepon yang sedang berdering."))
        .put(fn("reject_call", "Tolak/akhiri telepon."))
        // --- info & media ---
        .put(fn("google_search", "Cari informasi terkini/valid di Google.",
            JSONObject().put("query", str("Kata kunci pencarian")), listOf("query")))
        .put(fn("generate_image", "Buat gambar dari deskripsi dan simpan ke Galeri.",
            JSONObject().put("prompt", str("Deskripsi gambar yang detail")), listOf("prompt")))
        .put(fn("make_video", "Buat video dari naskah ATAU topik (diproses di HP, hasil ke Galeri). Bisa memakai suara kloning.",
            JSONObject()
                .put("script", str("Naskah lengkap (kosongkan jika hanya topik)"))
                .put("topic", str("Topik video bila tidak ada naskah"))
                .put("duration_min", int("Perkiraan durasi menit bila dari topik"))
                .put("voice", str("Suara: gemini:Kore, fish:<id kloning>. Kosong = default"))
                .put("mode", choice("images = gambar + efek kamera (hemat), clips = klip video AI (mahal)", "images", "clips"))
                .put("aspect", choice("Rasio", "9:16", "16:9", "1:1"))))
        // --- jam, catatan, perangkat ---
        .put(fn("set_alarm", "Pasang alarm.",
            JSONObject().put("hour", int("Jam 0-23")).put("minute", int("Menit")).put("label", str("Label")),
            listOf("hour", "minute")))
        .put(fn("set_timer", "Mulai timer.",
            JSONObject().put("seconds", int("Durasi detik")).put("label", str("Label")), listOf("seconds")))
        .put(fn("save_note", "Simpan catatan/pengingat teks.",
            JSONObject().put("text", str("Isi catatan")), listOf("text")))
        .put(fn("read_notes", "Baca catatan yang tersimpan."))
        .put(fn("device_info", "Info baterai, RAM, penyimpanan, dan tipe HP."))
        // --- TikTok Live ---
        .put(fn("tiktok_connect", "Sambungkan NEXA ke live TikTok (akun harus sedang live).",
            JSONObject().put("username", str("Username TikTok tanpa @")), listOf("username")))
        .put(fn("tiktok_disconnect", "Putuskan dari live TikTok."))
        .put(fn("tiktok_stats", "Statistik live TikTok saat ini (penonton, follow, like, koin, top gifter)."))
        .put(fn("tiktok_reading", "Nyalakan/jeda pembacaan otomatis event TikTok.",
            JSONObject().put("on", bool("true = baca, false = jeda")), listOf("on")))
        // --- fitur pendukung ---
        .put(fn("copy_to_clipboard", "Salin teks ke papan klip.",
            JSONObject().put("text", str("Teks yang disalin")), listOf("text")))
        .put(fn("read_clipboard", "Baca isi papan klip (mis. untuk diterjemahkan/diringkas)."))
        .put(fn("media_control", "Kendalikan musik/video yang sedang diputar.",
            JSONObject().put("action", choice("Aksi", "play_pause", "next", "previous", "stop")), listOf("action")))
        .put(fn("navigate", "Buka navigasi peta ke suatu tempat.",
            JSONObject().put("place", str("Tujuan, mis. Bandara Sultan Hasanuddin"))
                .put("mode", choice("Kendaraan", "drive", "motor", "walk")), listOf("place")))
        .put(fn("share_text", "Bagikan teks ke aplikasi lain lewat menu bagikan.",
            JSONObject().put("text", str("Teks yang dibagikan")), listOf("text")))
        .put(fn("add_calendar_event", "Tambah jadwal ke Kalender.",
            JSONObject().put("title", str("Judul acara"))
                .put("start", str("Waktu mulai, format yyyy-MM-dd HH:mm"))
                .put("duration_min", int("Durasi menit")), listOf("title", "start")))
        .apply {
            if (forLive) {
                put(fn("end_conversation", "Matikan Live HANYA bila pengguna jelas menyuruh menutup/mematikan Live (mis. 'matiin live', 'tutup live')."))
                put(fn("quiet_mode", "Mode diam: on=true saat pengguna menyuruh diam (sedang bicara dengan orang lain); " +
                    "on=false saat pengguna bilang sudah boleh ngobrol lagi.",
                    JSONObject().put("on", bool("true = diam, false = bicara lagi")), listOf("on")))
            }
            if (!forLive) {
                put(fn("ask_user", "Tanya atau minta konfirmasi pengguna. Menghentikan tugas sampai pengguna menjawab.",
                    JSONObject().put("question", str("Pertanyaan singkat")), listOf("question")))
                put(fn("finish", "Tugas selesai. Beri jawaban singkat untuk dibacakan.",
                    JSONObject().put("reply", str("Jawaban berbahasa Indonesia")), listOf("reply")))
            }
        }
}
