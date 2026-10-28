package com.familypornhd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cloudflare bypass settings, shown from Extensions -> FamilyPornHD -> settings (gear). */
object FamilyPornHDSettings {
    enum class Mode(val label: String, val help: String) {
        AUTO("Auto (recommended)", "Hidden browser first; if Cloudflare wants a click, the solver window opens for you."),
        SOLVER("Always use solver window", "Skip the hidden browser and open the visible solver window whenever blocked."),
        CLOUDSTREAM("Cloudstream built-in bypass", "Use Cloudstream's own CloudflareKiller (works for non-interactive checks)."),
        HIDDEN("Hidden browser only", "Never open a window; only the hidden browser is tried."),
        COOKIE("Manual cookie only", "Only use the cf_clearance cookie + user agent you paste below."),
    }

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val DEFAULT_URL = "https://familypornhd.com"

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("familypornhd_settings", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = prefs?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean) = prefs?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    var mode: Mode
        get() = try { Mode.valueOf(str("mode", Mode.AUTO.name)) } catch (_: Throwable) { Mode.AUTO }
        set(v) = put { putString("mode", v.name) }

    var autoPrompt: Boolean
        get() = bool("auto_prompt", true)
        set(v) = put { putBoolean("auto_prompt", v) }

    var stripWv: Boolean
        get() = bool("strip_wv", true)
        set(v) = put { putBoolean("strip_wv", v) }

    var lightweight: Boolean
        get() = bool("lightweight", false)
        set(v) = put { putBoolean("lightweight", v) }

    var customUa: String
        get() = str("custom_ua").trim()
        set(v) = put { putString("custom_ua", v.trim()) }

    /** UA that was active when cf_clearance was obtained - requests must reuse it. */
    var solvedUa: String
        get() = str("solved_ua").trim()
        set(v) = put { putString("solved_ua", v.trim()) }

    var manualCookie: String
        get() = str("manual_cookie").trim()
        set(v) = put { putString("manual_cookie", v.trim()) }

    var timeoutSec: Int
        get() = str("timeout", "35").toIntOrNull()?.coerceIn(5, 180) ?: 35
        set(v) = put { putString("timeout", v.toString()) }

    var siteUrl: String
        get() = str("site_url", DEFAULT_URL).trim().trimEnd('/').ifBlank { DEFAULT_URL }
        set(v) = put { putString("site_url", v.trim().trimEnd('/')) }

    @Volatile
    var lastLinkReport: String = "Nothing played yet."

    fun currentActivity(ctx: Context? = null): Activity? =
        (ctx as? Activity) ?: try { CommonActivity.activity } catch (_: Throwable) { null }

    fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            try {
                CommonActivity.showToast(msg, Toast.LENGTH_LONG)
            } catch (_: Throwable) {
            }
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(context: Context) {
        val act = currentActivity(context) ?: return
        init(act)
        val pad = dp(act, 16)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun header(t: String) = TextView(act).apply {
            text = t
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            setPadding(0, dp(act, 14), 0, dp(act, 4))
        }

        fun note(t: String) = TextView(act).apply {
            text = t
            textSize = 12f
            alpha = 0.75f
        }

        fun field(hint: String, value: String, multi: Boolean = false) = EditText(act).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (multi) { minLines = 2; maxLines = 5 } else isSingleLine = true
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val status = TextView(act).apply {
            textSize = 13f
            setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        fun refreshStatus() {
            val has = FamilyPornHDBrowser.hasClearance()
            status.text = if (has) "Status: cf_clearance cookie saved" else "Status: no Cloudflare cookie yet - tap \"Solve Cloudflare now\""
            status.setTextColor(if (has) Color.rgb(76, 175, 80) else Color.rgb(239, 154, 0))
        }
        refreshStatus()

        root.addView(TextView(act).apply {
            text = "FamilyPornHD - Cloudflare bypass"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(status)

        root.addView(button("Solve Cloudflare now (opens the site, tick the box)") {
            scope.launch {
                val ok = FamilyPornHDBrowser.solveInteractive(act)
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    Toast.makeText(act, if (ok) "Cloudflare passed" else "Not solved", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(button("Test connection") {
            Toast.makeText(act, "Testing...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val msg = FamilyPornHDBrowser.test()
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    AlertDialog.Builder(act).setTitle("FamilyPornHD test").setMessage(msg).setPositiveButton("OK", null).show()
                }
            }
        })
        root.addView(button("Show last link report") {
            AlertDialog.Builder(act).setTitle("Last link report").setMessage(lastLinkReport).setPositiveButton("OK", null).show()
        })
        root.addView(button("Clear Cloudflare cookies") {
            scope.launch {
                FamilyPornHDBrowser.reset(clearCookies = true)
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    Toast.makeText(act, "Cookies cleared", Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(header("Bypass mode"))
        val modeHelp = note(mode.help)
        val group = RadioGroup(act)
        Mode.values().forEach { m ->
            group.addView(RadioButton(act).apply {
                id = View.generateViewId()
                text = m.label
                tag = m
                isChecked = m == mode
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            (g.findViewById<RadioButton>(id)?.tag as? Mode)?.let { modeHelp.text = it.help }
        }
        root.addView(group)
        root.addView(modeHelp)

        root.addView(header("Options"))
        val cbPrompt = CheckBox(act).apply { text = "Open solver window automatically when needed"; isChecked = autoPrompt }
        val cbWv = CheckBox(act).apply { text = "Hide WebView marker in user agent (\"; wv\")"; isChecked = stripWv }
        val cbLight = CheckBox(act).apply { text = "Lightweight hidden browser (no images)"; isChecked = lightweight }
        root.addView(cbPrompt); root.addView(cbWv); root.addView(cbLight)

        root.addView(header("Hidden browser timeout (seconds)"))
        val etTimeout = field("35", timeoutSec.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(etTimeout)

        root.addView(header("Custom user agent"))
        root.addView(note("Leave empty to use this device's browser user agent. If you paste a cookie from another browser, paste that browser's exact user agent here."))
        val etUa = field("Mozilla/5.0 ...", customUa, multi = true)
        root.addView(etUa)

        root.addView(header("Manual cookie"))
        root.addView(note("Optional. e.g. cf_clearance=xxxx  (from a browser on the SAME network, with the same user agent as above)."))
        val etCookie = field("cf_clearance=...", manualCookie, multi = true)
        root.addView(etCookie)

        root.addView(header("Site URL (mirror)"))
        val etUrl = field(DEFAULT_URL, siteUrl)
        root.addView(etUrl)

        root.addView(note("Current user agent:\n" + FamilyPornHDBrowser.userAgent()).apply { setPadding(0, dp(act, 12), 0, 0) })

        val scroll = ScrollView(act).apply { addView(root) }
        AlertDialog.Builder(act)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                (group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Mode)?.let { mode = it }
                autoPrompt = cbPrompt.isChecked
                val uaChanged = stripWv != cbWv.isChecked || customUa != etUa.text.toString().trim()
                stripWv = cbWv.isChecked
                lightweight = cbLight.isChecked
                timeoutSec = etTimeout.text.toString().toIntOrNull() ?: 35
                customUa = etUa.text.toString()
                manualCookie = etCookie.text.toString()
                siteUrl = etUrl.text.toString()
                scope.launch { FamilyPornHDBrowser.reset(clearCookies = false, uaChanged = uaChanged) }
                Toast.makeText(act, "Saved - restart the app if the site URL changed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
