package com.natan.nexa

import java.net.HttpURLConnection
import java.net.URL

/**
 * Server pribadi NEXA (Cloudflare Workers). Bila aktif, semua panggilan Gemini, ElevenLabs,
 * Fish Audio, Live, dan TikTok dialihkan ke server — kunci API tidak pernah ada di HP.
 * Kunci "SERVER" adalah penanda: artinya "pakai kunci yang tersimpan di server".
 */
object Route {
    const val SERVER = "SERVER"
    @Volatile var server = ""
    @Volatile var token = ""
    /** Login & Premium: dikirim ke server di setiap permintaan. */
    @Volatile var session = ""
    @Volatile var code = ""
    @Volatile var device = ""

    private const val G = "https://generativelanguage.googleapis.com"
    private const val O = "https://api.elevenlabs.io"
    private const val F = "https://api.fish.audio"

    val active: Boolean get() = server.isNotBlank()

    fun apply(url: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
        val viaServer = headers["x-goog-api-key"] == SERVER || headers["Authorization"] == "Bearer $SERVER" ||
            headers["xi-api-key"] == SERVER
        if (!viaServer || !active) return url to headers
        val h = headers.filterKeys { it != "x-goog-api-key" && it != "Authorization" && it != "xi-api-key" }.toMutableMap()
        h.putAll(authHeaders())
        val u = when {
            url.startsWith(G) -> "$server/gemini" + url.removePrefix(G)
            url.startsWith(O) -> "$server/eleven" + url.removePrefix(O)
            url.startsWith(F) -> "$server/fish" + url.removePrefix(F)
            else -> url
        }
        return u to h
    }

    /** Buka koneksi HTTP dengan header; otomatis lewat server NEXA bila kuncinya "SERVER". */
    fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val (u, h) = apply(url, headers)
        return (URL(u).openConnection() as HttpURLConnection).apply {
            h.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }

    /** Header pengenal aplikasi + login + Premium untuk server NEXA. */
    fun authHeaders(): Map<String, String> = mapOf(
        "x-nexa-app" to token, "x-nexa-session" to session, "x-nexa-device" to device, "x-nexa-code" to code)

    /** Koneksi langsung ke endpoint server (akun, premium, saran). */
    fun openServer(path: String): HttpURLConnection =
        (URL(server + path).openConnection() as HttpURLConnection).apply {
            authHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /** Alamat WebSocket server (wss://…). */
    fun wsBase(): String = server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
}
