package com.natan.nexa

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Premium mode mandiri (tanpa server). Kode dibuat pembuat aplikasi di Termux (tools/buat_kode.sh):
 *   NX-<T><HARI>-<16 HEX>   T = O (pembuat, selamanya) | P (premium 30 hari) | G (hadiah)
 *   tanda tangan = HMAC-SHA256(PREMIUM_SECRET, "<ID_PERANGKAT>|<T>|<HARI>")
 * Premium mengunci fitur ASISTEN AI saja. Menu lain tetap gratis.
 */
object Premium {
    private val RE = Regex("^NX-([OPG])(\\d+)-([0-9A-F]{16})$")
    private fun today() = System.currentTimeMillis() / 86_400_000L

    /** null = kode tidak sah untuk perangkat ini. */
    fun info(code: String, device: String): JSONObject? {
        val secret = BuildConfig.PREMIUM_SECRET
        val m = RE.find(code.trim().uppercase()) ?: return null
        if (secret.isEmpty() || device.isBlank()) return null
        val (tier, exp, sig) = m.destructured
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val want = mac.doFinal("$device|$tier|$exp".toByteArray()).joinToString("") { "%02x".format(it) }
            .take(16).uppercase()
        if (want != sig) return null
        val e = exp.toLong()
        return JSONObject().put("tier", tier).put("exp", e).put("expired", e != 0L && e < today())
    }

    private fun current(p: Prefs): JSONObject? = info(p.raw("premium_code"), Route.device)

    /** Boleh memakai fitur asisten AI? (server aktif → server yang memeriksa) */
    fun allowed(p: Prefs): Boolean {
        if (Route.active) return true
        if (BuildConfig.PREMIUM_SECRET.isEmpty()) return true   // Premium belum diatur pembuat → bebas
        if (p.raw("session").isEmpty()) return false              // wajib login
        val i = current(p) ?: return false
        return !i.optBoolean("expired")
    }

    /** Khusus pembuat aplikasi (kode O). */
    fun isOwner(p: Prefs): Boolean {
        if (BuildConfig.PREMIUM_SECRET.isEmpty()) return false
        val i = current(p) ?: return false
        return i.optString("tier") == "O" && !i.optBoolean("expired") && p.raw("session").isNotEmpty()
    }

    const val LOCKED = "Fitur asisten AI ini khusus Premium 👑. Buka Akun → Langganan Premium."
}
