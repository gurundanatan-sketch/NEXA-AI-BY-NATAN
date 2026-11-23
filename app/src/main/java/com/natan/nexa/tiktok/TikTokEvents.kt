package com.natan.nexa.tiktok

import org.json.JSONArray
import org.json.JSONObject

/**
 * Menyeragamkan pesan WebSocket TikTok LIVE dari berbagai penyedia
 * (Euler Stream, TikTool, atau format TikTok-Live-Connector) menjadi:
 *   follow, like, gift, share, guest_request, join, chat, viewers, stream_end
 * Struktur data penyedia bisa berubah, jadi pembacaan dibuat toleran (mencari kunci secara rekursif).
 */
object TikTokEvents {

    fun normalize(raw: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val list = raw.optJSONArray("messages") ?: raw.optJSONArray("events")
        if (list != null) {
            for (i in 0 until list.length()) {
                val m = list.optJSONObject(i) ?: continue
                one(m.optString("type").ifBlank { m.optString("event") }, m.optJSONObject("data") ?: m)?.let { out += it }
            }
        } else {
            val type = raw.optString("event").ifBlank { raw.optString("type") }
            one(type, raw.optJSONObject("data") ?: raw)?.let { out += it }
        }
        return out
    }

    /** "WebcastGiftMessage" → "gift", "WebcastRoomUserSeqMessage" → "roomuserseq", "follow" → "follow". */
    private fun kind(type: String) = type.lowercase().removePrefix("webcast").removeSuffix("message")

    private fun one(type: String, d: JSONObject): JSONObject? {
        val k = kind(type)
        val user = user(d)
        val ev = JSONObject().put("user", user)
        return when {
            k == "follow" -> ev.put("type", "follow")
            k == "share" -> ev.put("type", "share")
            k == "social" -> {
                val s = d.toString().lowercase()
                when {
                    "follow" in s -> ev.put("type", "follow")
                    "share" in s -> ev.put("type", "share")
                    else -> null
                }
            }
            k == "like" -> ev.put("type", "like")
                .put("likeCount", int(d, "likeCount", "count").coerceAtLeast(1))
                .apply { int(d, "totalLikeCount", "totalLikes", "total").takeIf { it > 0 }?.let { put("totalLikes", it) } }
            k == "gift" -> {
                val giftType = int(d, "giftType")
                val repeatEnd = bool(d, "repeatEnd")
                val count = int(d, "repeatCount", "comboCount", "count").coerceAtLeast(1)
                val diamonds = int(d, "diamondCount", "diamonds", "coins")
                val name = str(d, "giftName", "name").ifBlank { "hadiah" }
                ev.put("type", "gift").put("gift", JSONObject()
                    .put("name", name).put("count", count).put("diamonds", diamonds)
                    .put("totalCoins", int(d, "totalValue", "totalCoins").takeIf { it > 0 } ?: (diamonds * count))
                    .put("inCombo", giftType == 1 && !repeatEnd))
            }
            k == "member" || k == "join" -> ev.put("type", "join")
            k == "chat" || k == "comment" -> ev.put("type", "chat").put("comment", str(d, "comment", "content", "text"))
            k == "roomuserseq" || k == "roomuser" || k == "viewers" || k == "viewercount" ->
                JSONObject().put("type", "viewers").put("viewers", int(d, "viewerCount", "viewers", "total"))
            k.startsWith("linkmic") || k == "link" || k.contains("guest") || k.contains("cohost") -> {
                val s = d.toString().lowercase()
                if (listOf("apply", "request", "invite", "join").any { it in s } && user.optString("nickname").isNotBlank())
                    ev.put("type", "guest_request") else null
            }
            k == "streamend" || k == "liveend" || (k == "control" && d.toString().contains("3")) ->
                JSONObject().put("type", "stream_end")
            else -> null
        }
    }

    // ------------------------------------------------------------------ pencarian toleran
    private fun user(d: JSONObject): JSONObject {
        val u = (d.optJSONObject("user") ?: find(d) { it.has("uniqueId") || it.has("nickname") }) ?: d
        val avatar = u.optJSONObject("profilePicture")?.optJSONArray("url")?.optString(0)
            ?: u.optString("profilePictureUrl")
        return JSONObject()
            .put("uniqueId", u.optString("uniqueId").ifBlank { u.optString("displayId") })
            .put("nickname", u.optString("nickname").ifBlank { u.optString("uniqueId", "seseorang") })
            .put("avatar", avatar ?: "")
    }

    private fun find(o: Any?, depth: Int = 0, pred: (JSONObject) -> Boolean): JSONObject? {
        if (depth > 5) return null
        when (o) {
            is JSONObject -> {
                if (pred(o)) return o
                for (key in o.keys()) find(o.opt(key), depth + 1, pred)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) find(o.opt(i), depth + 1, pred)?.let { return it }
        }
        return null
    }

    private fun value(d: JSONObject, vararg keys: String): Any? {
        for (k in keys) {
            val hit = find(d) { it.has(k) && !it.isNull(k) }
            if (hit != null) return hit.opt(k)
        }
        return null
    }

    private fun int(d: JSONObject, vararg keys: String): Int = when (val v = value(d, *keys)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun str(d: JSONObject, vararg keys: String): String = (value(d, *keys) as? String).orEmpty()

    private fun bool(d: JSONObject, vararg keys: String): Boolean = when (val v = value(d, *keys)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "true" || v == "1"
        else -> false
    }
}
