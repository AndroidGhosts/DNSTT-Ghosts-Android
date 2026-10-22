package com.androidghosts.dnsttghosts

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale

/** Simple log screen: original-style layout, colored events, Copy and Clear only. */
class LogActivity : AppCompatActivity() {
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private var currentLines: List<String> = emptyList()
    private val accent = Color.rgb(0, 238, 190)
    private val bg = Color.rgb(5, 12, 19)
    private val panel = Color.rgb(10, 23, 32)
    private val muted = Color.rgb(119, 150, 166)

    private val listener: (String) -> Unit = {
        runOnUiThread {
            currentLines = LogBuffer.all().lineSequence().filter(String::isNotBlank).toList()
            renderLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        buildUi()
        currentLines = LogBuffer.all().lineSequence().filter(String::isNotBlank).toList()
        renderLogs()
        LogBuffer.listen(listener)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "LOGS"
            setTitleTextColor(accent)
            setBackgroundColor(panel)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        setSupportActionBar(toolbar)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(206, 224, 232))
            setPadding(dp(12), dp(12), dp(12), dp(18))
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        scroll.addView(logText, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(panel)
        }

        val copyButton = TextView(this).apply {
            text = "COPY"
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(accent)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { copyLogs() }
        }
        val clearButton = TextView(this).apply {
            text = "CLEAR"
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(255, 105, 105))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { clearLogs() }
        }
        footer.addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(clearButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(footer)
        setContentView(root)
    }

    private fun category(line: String): String {
        val upper = line.uppercase(Locale.ROOT)
        return when {
            "ERROR" in upper || "FAILED" in upper || "EXCEPTION" in upper || "FATAL" in upper -> "ERROR"
            "WARN" in upper || "RETRY" in upper || "TIMEOUT" in upper || "DEGRADED" in upper -> "WARN"
            "NETWORK_PROBE" in upper || "PROBE" in upper || "RTT=" in upper || "LATENCY" in upper -> "PROBE"
            "SSH" in upper || "HOST KEY" in upper || "SSH-" in upper -> "SSH"
            "DNS" in upper || "RESOLVER" in upper || "DNSTT" in upper -> "DNS"
            "CONNECTION" in upper || "TUNNEL" in upper || "VPN" in upper || "SESSION" in upper -> "TUNNEL"
            "AUTH" in upper || "KEY" in upper || "LOGIN" in upper -> "AUTH"
            "NETWORK" in upper || "ROUTE" in upper || "DEVICE" in upper -> "NETWORK"
            else -> "INFO"
        }
    }

    private fun renderLogs() {
        if (!::logText.isInitialized) return
        val lines = currentLines
        if (lines.isEmpty()) {
            logText.text = "No logs yet."
            return
        }

        val builder = android.text.SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val color = when (category(line)) {
                "ERROR" -> Color.rgb(255, 92, 106)
                "WARN" -> Color.rgb(255, 190, 80)
                "AUTH", "SSH" -> Color.rgb(205, 130, 255)
                "DNS" -> Color.rgb(74, 205, 255)
                "NETWORK", "PROBE" -> Color.rgb(92, 224, 156)
                "TUNNEL" -> accent
                else -> Color.rgb(206, 224, 232)
            }
            builder.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append(if (index == lines.lastIndex) "" else "\n")
        }
        logText.text = builder
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Android_Ghosts logs", logText.text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        LogBuffer.clear()
        currentLines = emptyList()
        renderLogs()
    }

    override fun onDestroy() {
        LogBuffer.unlisten(listener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        super.onOptionsItemSelected(item)
}
