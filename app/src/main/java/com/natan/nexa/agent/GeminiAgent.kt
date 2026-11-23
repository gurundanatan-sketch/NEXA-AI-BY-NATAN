package com.natan.nexa.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mode PERINTAH: loop  baca layar → Gemini memilih aksi (function calling) → jalankan → ulangi.
 * Berhenti saat Gemini memanggil finish/ask_user, atau setelah MAX_STEPS.
 * (Untuk ngobrol real-time dua arah, lihat live/LiveSession.kt.)
 */
class GeminiAgent(
    private val apiKey: String,
    private val model: String,
    private val tools: ToolExecutor,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val MAX_STEPS = 25
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    }

    private val contents = JSONArray()

    /** true jika agen terakhir berhenti karena ask_user dan menunggu jawaban. */
    var waitingForAnswer = false
        private set

    /** Menjalankan satu perintah baru sampai selesai. Mengembalikan kalimat untuk diucapkan. */
    suspend fun run(command: String): String {
        contents.put(userText(
            "PERINTAH PENGGUNA: $command\n\nAPLIKASI TERPASANG: ${tools.installedApps}\n\nLAYAR SAAT INI:\n${tools.screen()}"
        ))
        return loop()
    }

    /** Melanjutkan tugas setelah pengguna menjawab pertanyaan ask_user. */
    suspend fun answer(reply: String): String {
        waitingForAnswer = false
        contents.put(functionResponse("ask_user",
            "JAWABAN PENGGUNA: $reply\n\nLAYAR SAAT INI:\n${tools.screen()}"))
        return loop()
    }

    private suspend fun loop(): String {
        repeat(MAX_STEPS) { step ->
            val modelContent = callGemini()
            contents.put(modelContent)

            val call = firstFunctionCall(modelContent)
                ?: return textOf(modelContent).ifBlank { "Maaf, saya tidak yakin harus melakukan apa." }

            val name = call.getString("name")
            val args = call.optJSONObject("args") ?: JSONObject()
            log("Langkah ${step + 1}: $name $args")

            when (name) {
                "finish" -> return args.optString("reply", "Selesai.")
                "ask_user" -> {
                    waitingForAnswer = true
                    return args.optString("question", "Bisa dijelaskan lagi?")
                }
            }
            contents.put(functionResponse(name, tools.execute(name, args)))
        }
        return "Saya berhenti setelah $MAX_STEPS langkah. Tugasnya mungkin belum selesai."
    }

    // ---------------------------------------------------------------------
    // PANGGILAN REST GEMINI
    // ---------------------------------------------------------------------

    private suspend fun callGemini(): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Prompts.agent(tools.assistantName)))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations())))
            .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "ANY")))

        val conn = (URL(ENDPOINT.format(model)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Gemini error $code: ${text.take(300)}")

        val candidate = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw RuntimeException("Gemini tidak memberi jawaban: ${text.take(300)}")
        // Simpan "content" apa adanya supaya thoughtSignature ikut dikirim balik.
        candidate.getJSONObject("content").put("role", "model")
    }

    private fun firstFunctionCall(content: JSONObject): JSONObject? {
        val parts = content.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.optJSONObject("functionCall")?.let { return it }
        }
        return null
    }

    private fun textOf(content: JSONObject): String {
        val parts = content.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).joinToString(" ") { parts.optJSONObject(it)?.optString("text", "") ?: "" }.trim()
    }

    private fun userText(t: String) = JSONObject()
        .put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun functionResponse(name: String, result: String) = JSONObject()
        .put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("functionResponse",
            JSONObject().put("name", name).put("response", JSONObject().put("result", result)))))
}
