package com.natan.nexa.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Mata dan tangan" Nexa.
 * - dumpScreen(): membaca pohon UI layar aktif → teks ringkas bernomor untuk Gemini
 * - click/type/scroll/global: menjalankan aksi di elemen bernomor itu
 *
 * Diaktifkan pengguna lewat: Pengaturan → Aksesibilitas → Nexa – Kontrol Layar.
 */
class NexaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NexaAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
        private const val MAX_ELEMENTS = 120
        private const val MAX_TEXT = 60
    }

    /** Elemen hasil dump terakhir; index = nomor yang dilihat Gemini. */
    private val lastElements = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* tidak dipakai */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // MEMBACA LAYAR
    // ---------------------------------------------------------------------

    /**
     * Contoh hasil:
     *   APP: com.google.android.youtube
     *   [0] button "Cari" (540,120)
     *   [1] edit "Telusuri YouTube" (400,120) editable
     */
    fun dumpScreen(): String {
        lastElements.clear()
        val root = rootInActiveWindow ?: return "LAYAR: tidak terbaca (coba lagi sebentar)"
        val sb = StringBuilder()
        sb.append("APP: ").append(root.packageName ?: "?").append('\n')
        walk(root, sb)
        if (lastElements.isEmpty()) sb.append("(tidak ada elemen yang bisa dipakai)\n")
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null || lastElements.size >= MAX_ELEMENTS) return
        if (!node.isVisibleToUser) return

        val label = labelOf(node)
        val useful = node.isClickable || node.isEditable || node.isScrollable ||
            node.isCheckable || label.isNotEmpty()

        if (useful) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) {
                val idx = lastElements.size
                lastElements.add(node)
                sb.append('[').append(idx).append("] ")
                    .append(kindOf(node))
                if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
                sb.append(" (").append(r.centerX()).append(',').append(r.centerY()).append(')')
                if (node.isEditable) sb.append(" editable")
                if (node.isScrollable) sb.append(" scrollable")
                if (node.isCheckable) sb.append(if (node.isChecked) " checked" else " unchecked")
                if (!node.isEnabled) sb.append(" disabled")
                sb.append('\n')
            }
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), sb)
    }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val t = (n.text ?: n.contentDescription ?: n.hintText ?: "").toString()
            .replace('\n', ' ').trim()
        return if (t.length > MAX_TEXT) t.take(MAX_TEXT) + "…" else t
    }

    private fun kindOf(n: AccessibilityNodeInfo): String {
        val c = n.className?.toString() ?: ""
        return when {
            n.isEditable -> "edit"
            c.endsWith("Button") || c.endsWith("ImageButton") -> "button"
            c.endsWith("CheckBox") || c.endsWith("Switch") -> "toggle"
            n.isScrollable -> "list"
            n.isClickable -> "clickable"
            else -> "text"
        }
    }

    // ---------------------------------------------------------------------
    // AKSI
    // ---------------------------------------------------------------------

    private fun element(index: Int): AccessibilityNodeInfo? = lastElements.getOrNull(index)

    suspend fun click(index: Int): String {
        val node = element(index) ?: return "GAGAL: elemen [$index] tidak ada, baca layar ulang"
        // 1) klik node itu atau induk terdekat yang bisa diklik
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "OK: klik [$index]"
        }
        // 2) cadangan: ketuk koordinat tengah
        val r = Rect().also { node.getBoundsInScreen(it) }
        return if (tap(r.centerX().toFloat(), r.centerY().toFloat())) "OK: ketuk [$index]"
        else "GAGAL: tidak bisa klik [$index]"
    }

    fun typeText(index: Int, text: String): String {
        val node = element(index) ?: return "GAGAL: elemen [$index] tidak ada"
        if (!node.isEditable) return "GAGAL: [$index] bukan kolom input"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            "OK: ketik di [$index]" else "GAGAL: tidak bisa mengetik di [$index]"
    }

    /** Tekan Enter / tombol kirim di keyboard (Android 11+). */
    fun pressEnter(index: Int): String {
        val node = element(index) ?: return "GAGAL: elemen [$index] tidak ada"
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val ok = node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            return if (ok) "OK: enter di [$index]" else "GAGAL: enter tidak didukung"
        }
        return "GAGAL: enter butuh Android 11+, klik tombol cari/kirim saja"
    }

    suspend fun scroll(direction: String): String {
        val forward = direction == "down" || direction == "right"
        val scrollable = lastElements.firstOrNull { it.isScrollable }
        if (scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) return "OK: scroll $direction"
        }
        // cadangan: gesture geser
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val ok = when (direction) {
            "down" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "up" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
        return if (ok) "OK: geser $direction" else "GAGAL: scroll"
    }

    fun pressKey(key: String): String {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "GAGAL: tombol '$key' tidak dikenal"
        }
        return if (performGlobalAction(action)) "OK: tekan $key" else "GAGAL: tekan $key"
    }

    // ---------------------------------------------------------------------
    // GESTURE
    // ---------------------------------------------------------------------

    private suspend fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(p, 0, 60))
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(GestureDescription.StrokeDescription(p, 0, 350))
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val sent = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
}
