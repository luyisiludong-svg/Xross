package dev.xross.uireader

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.StringWriter
import java.util.concurrent.TimeoutException
import org.xmlpull.v1.XmlSerializer

/**
 * A screen reader for the adb-driven device tests (tools/device-tests/lib/harness.sh).
 *
 * `uiautomator dump` starts a new process for every read and then waits for 1 s without
 * accessibility events, about 2 s a read. This instrumentation opens UiAutomation once and
 * answers plain HTTP/1.0 on the abstract socket [SOCKET] (`adb forward tcp:0 localabstract:…`).
 * Start it with `am instrument -w` (run in the background on the device): without -w there is no
 * UiAutomation. Only one UiAutomation client can be connected at a time, so `uiautomator dump`
 * fails while the reader runs.
 *
 * - `GET /ping`: `ok`
 * - `GET /dump?idle=250&timeout=2000`: waits up to `timeout` ms for `idle` ms without events
 *   (dumps anyway when the screen never settles), then the active window as `uiautomator dump`
 *   writes it: same attributes in the same order, invisible children skipped, bounds clipped to
 *   the display. 503 when there is no active window yet.
 * - `GET /quit`: ends the instrumentation.
 */
class UiReader : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val automation = uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            // What `uiautomator dump` (not --compressed) reports: every view, with its id.
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val server = try {
            LocalServerSocket(SOCKET)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "socket $SOCKET is taken: another reader is running", e)
            finish(1, Bundle())
            return
        }
        Log.i(TAG, "listening on @$SOCKET")
        while (true) {
            val client = try { server.accept() } catch (e: java.io.IOException) { break }
            val quit = try { serve(client, automation) } catch (e: Exception) {
                Log.w(TAG, "request failed", e); false
            } finally {
                runCatching { client.close() }
            }
            if (quit) break
        }
        runCatching { server.close() }
        finish(0, Bundle())
    }

    /** Answers one request; true when it asked the reader to quit. */
    private fun serve(client: LocalSocket, automation: UiAutomation): Boolean {
        val line = client.inputStream.bufferedReader().readLine() ?: return false
        val target = line.split(' ').getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to v
        }.toMap()
        val (status, body) = when (path) {
            "/ping" -> 200 to "ok\n"
            "/quit" -> 200 to "bye\n"
            "/dump" -> dump(
                automation,
                idleMs = query["idle"]?.toLongOrNull() ?: 250,
                timeoutMs = query["timeout"]?.toLongOrNull() ?: 2000,
            )?.let { 200 to it } ?: (503 to "no active window\n")
            else -> 404 to "unknown path $path\n"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Service Unavailable" }
        client.outputStream.apply {
            write("HTTP/1.0 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        return path == "/quit"
    }

    private fun dump(automation: UiAutomation, idleMs: Long, timeoutMs: Long): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        if (idleMs > 0) {
            try { automation.waitForIdle(idleMs, timeoutMs) } catch (_: TimeoutException) { /* dump what is there */ }
        }
        while (true) {
            // A fresh `uiautomator dump` process has an empty node cache; so does every read here.
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root != null) return serialize(root)
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(50)
        }
    }

    private fun serialize(root: AccessibilityNodeInfo): String {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val out = StringWriter()
        Xml.newSerializer().apply {
            setOutput(out)
            startDocument("UTF-8", true)
            startTag("", "hierarchy")
            attribute("", "rotation", display.rotation.toString())
            node(root, 0, size.x, size.y)
            endTag("", "hierarchy")
            endDocument()
        }
        return out.toString()
    }

    // Mirrors AccessibilityNodeInfoDumper in AOSP's uiautomator command.
    private fun XmlSerializer.node(n: AccessibilityNodeInfo, index: Int, width: Int, height: Int) {
        startTag("", "node")
        if (!nafExcluded(n) && !nafCheck(n)) attribute("", "NAF", "true")
        attribute("", "index", index.toString())
        attribute("", "text", safe(n.text))
        attribute("", "resource-id", safe(n.viewIdResourceName))
        attribute("", "class", safe(n.className))
        attribute("", "package", safe(n.packageName))
        attribute("", "content-desc", safe(n.contentDescription))
        attribute("", "checkable", n.isCheckable.toString())
        attribute("", "checked", n.isChecked.toString())
        attribute("", "clickable", n.isClickable.toString())
        attribute("", "enabled", n.isEnabled.toString())
        attribute("", "focusable", n.isFocusable.toString())
        attribute("", "focused", n.isFocused.toString())
        attribute("", "scrollable", n.isScrollable.toString())
        attribute("", "long-clickable", n.isLongClickable.toString())
        attribute("", "password", n.isPassword.toString())
        attribute("", "selected", n.isSelected.toString())
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        if (!bounds.intersect(0, 0, width, height)) bounds.setEmpty()
        attribute("", "bounds", bounds.toShortString())
        attribute("", "drawing-order", n.drawingOrder.toString())
        attribute("", "hint", safe(n.hintText))
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            if (child.isVisibleToUser) node(child, i, width, height)
        }
        endTag("", "node")
    }

    private fun nafExcluded(n: AccessibilityNodeInfo): Boolean {
        val cls = safe(n.className)
        return NAF_EXCLUDED.any { cls.endsWith(it) }
    }

    /** "Not accessibility friendly": an enabled, clickable node with no label of its own or below. */
    private fun nafCheck(n: AccessibilityNodeInfo): Boolean {
        val naf = n.isClickable && n.isEnabled && safe(n.contentDescription).isEmpty() && safe(n.text).isEmpty()
        return !naf || childLabelled(n)
    }

    // Like AOSP's childNafCheck, this looks at every child, visible to the user or not.
    private fun childLabelled(n: AccessibilityNodeInfo): Boolean {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (safe(c.contentDescription).isNotEmpty() || safe(c.text).isNotEmpty() || childLabelled(c)) return true
        }
        return false
    }

    private fun safe(cs: CharSequence?): String {
        if (cs == null) return ""
        val sb = StringBuilder(cs.length)
        for (ch in cs) {
            val c = ch.code
            val invalid = c in 0x1..0x8 || c in 0xB..0xC || c in 0xE..0x1F || c in 0x7F..0x84 ||
                c in 0x86..0x9F || c in 0xFDD0..0xFDDF
            sb.append(if (invalid) '.' else ch)
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "XrossUiReader"
        const val SOCKET = "xross-uireader"
        val NAF_EXCLUDED = listOf(".GridView", ".GridLayout", ".ListView", ".TableLayout")
    }
}
