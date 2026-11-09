package dev.wave.stub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File

class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = describe(intent)
        report.lines().forEach { Log.i(TAG, it) }
        setContentView(TextView(this).apply { text = report; textSize = 14f; setPadding(32, 32, 32, 32) })
    }

    private fun describe(intent: Intent): String = buildString {
        appendLine("package=$packageName")
        appendLine("component=${intent.component?.className}")
        appendLine("action=${intent.action}")
        appendLine("data=${intent.data}")
        appendLine("type=${intent.type}")
        appendLine("grantRead=${intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0}")
        intent.extras?.keySet()?.sorted()?.forEach { appendLine("extra.$it=${intent.extras?.get(it)}") }
        intent.data?.let { uri ->
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().size } }
            appendLine("uriRead=" + bytes.fold({ "ok ($it bytes)" }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" }))
        }
        listOf("ROM", "bootPath", "AutoStartFile").firstNotNullOfOrNull { intent.getStringExtra(it) }?.let {
            appendLine("pathReadableWithoutPermission=${File(it).canRead()}")
        }
    }

    private companion object {
        const val TAG = "WaveStub"
    }
}
