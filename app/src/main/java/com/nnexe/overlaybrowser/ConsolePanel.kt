
package com.nnexe.overlaybrowser

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

class ConsolePanel(
    private val ctx: Context,
    private val host: FrameLayout,
    private val client: AgentClient,
    private val onStartTyping: () -> Unit,
    private val onStopTyping: () -> Unit,
) {
    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_SUCCESS = 1
        const val TYPE_WARNING = 2
        const val TYPE_ERROR = 3
        const val TYPE_INFO = 4
        const val TYPE_COMMAND = 5

        private const val MAX_LOG_LINES = 500
        private const val MAX_HISTORY = 50

        private val C_BG = Color.rgb(11, 16, 24)
        private val C_INPUT_BG = Color.rgb(17, 25, 37)
        private val C_DIVIDER = Color.rgb(38, 51, 67)
        private val C_TEXT = Color.rgb(215, 224, 234)
        private val C_MUTED = Color.rgb(105, 121, 140)
        private val C_PURPLE = Color.rgb(192, 132, 252)
        private val C_AMBER = Color.rgb(251, 191, 36)
        private val C_GREEN = Color.rgb(85, 214, 162)
        private val C_GREEN_DARK = Color.rgb(23, 107, 80)
        private val C_RED = Color.rgb(255, 107, 107)
        private val C_CYAN = Color.rgb(92, 214, 238)
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(InputMethodManager::class.java)

    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var cmdInput: EditText

    private val logLines = ArrayDeque<LogEntry>()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var fileReceiverRegistered = false
    private var offlineNoticeShown = false

    var outputPaused = false
        set(value) {
            field = value
            if (!value) renderLog()
        }

    private data class LogEntry(val text: String, val type: Int)

    private val filePickerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rawUri = intent.getStringExtra(MainActivity.EXTRA_FILE_URI)
            if (rawUri.isNullOrBlank()) {
                appendLine("File selection cancelled.", TYPE_WARNING)
                return
            }
            loadSelectedFile(Uri.parse(rawUri))
        }
    }

    init {
        build()
        registerFilePickerReceiver()
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidth: Int = 0,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }

    private fun SpannableStringBuilder.appendStyled(
        value: String,
        color: Int,
        bold: Boolean = false,
    ) {
        val start = length
        append(value)
        setSpan(
            ForegroundColorSpan(color),
            start,
            length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (bold) {
            setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun build() {
        host.removeAllViews()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        logText = TextView(ctx).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(C_TEXT)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
            text = SpannableStringBuilder().apply {
                appendStyled("AIZ3N CORE", C_PURPLE, true)
                append("\n")
                appendStyled("Secure agent console initialized.", C_MUTED)
                append("\n\n")
                appendStyled("Waiting for agent connection...", C_AMBER)
            }
        }

        logScroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                logText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val divider = View(ctx).apply {
            setBackgroundColor(C_DIVIDER)
        }

        val inputBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C_INPUT_BG)
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }

        val prompt = TextView(ctx).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(3), 0, dp(8), 0)
            text = SpannableStringBuilder().apply {
                appendStyled("[ AIZ3N CORE ] ", C_PURPLE, true)
                appendStyled("::", C_AMBER, true)
                appendStyled(" _", Color.WHITE, true)
            }
        }

        cmdInput = EditText(ctx).apply {
            hint = "Enter command..."
            setHintTextColor(C_MUTED)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(3), dp(7), dp(5), dp(7))

            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartTyping()
                    main.postDelayed({
                        if (hasFocus()) imm.showSoftInput(this, 0)
                    }, 200)
                }
                false
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    main.postDelayed({
                        if (!cmdInput.hasFocus()) onStopTyping()
                    }, 250)
                }
            }

            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN

                if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                    submitInput()
                    true
                } else false
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            stepHistory(-1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            stepHistory(1)
                            true
                        }
                        else -> false
                    }
                } else false
            }
        }

        val attach = TextView(ctx).apply {
            text = "ATTACH"
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            background = roundedBackground(C_INPUT_BG, 8, C_PURPLE, 1)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { openFilePicker() }
        }

        val execute = TextView(ctx).apply {
            text = "EXECUTE"
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            background = roundedBackground(C_GREEN_DARK, 8, C_GREEN, 1)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            setOnClickListener { submitInput() }
        }

        inputBar.addView(prompt)
        inputBar.addView(
            cmdInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        inputBar.addView(attach)
        inputBar.addView(execute)

        container.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )
        container.addView(
            inputBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        host.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun registerFilePickerReceiver() {
        if (fileReceiverRegistered) return

        val filter = IntentFilter(MainActivity.ACTION_FILE_PICKED)

        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(
                filePickerReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(filePickerReceiver, filter)
        }

        fileReceiverRegistered = true
    }

    private fun openFilePicker() {
        onStopTyping()
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_PICK_FILE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            appendLine(
                "Unable to open file picker: ${e.message ?: "unknown error"}",
                TYPE_ERROR
            )
        }
    }

    private fun loadSelectedFile(uri: Uri) {
        if (!client.isConnected) {
            showOfflineNotice()
            return
        }

        try {
            var fileName = ""

            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        fileName = cursor.getString(index).orEmpty()
                    }
                }
            }

            if (fileName.isBlank()) {
                fileName = Uri.decode(uri.lastPathSegment.orEmpty())
                    .substringAfterLast('/')
                    .substringAfterLast(':')
            }

            fileName = fileName.trim()
            val lowerName = fileName.lowercase()

            if (!lowerName.endsWith(".ts") && !lowerName.endsWith(".js")) {
                appendLine("Unsupported file: $fileName", TYPE_ERROR)
                appendLine("Select a .ts or .js script file.", TYPE_WARNING)
                return
            }

            val contents = ctx.contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            } ?: throw IllegalStateException("Unable to read selected file")

            appendLine("Loading $fileName...", TYPE_INFO)
            client.sendLoadFile(contents)
        } catch (e: Exception) {
            appendLine(
                "File load failed: ${e.message ?: "unable to read file"}",
                TYPE_ERROR
            )
        }
    }

    private fun submitInput() {
        val raw = cmdInput.text.toString().trim()
        if (raw.isEmpty()) return

        if (raw.equals(".load", true)) {
            cmdInput.setText("")
            openFilePicker()
            return
        }

        if (!client.isConnected) {
            showOfflineNotice()
            return
        }

        cmdInput.setText("")
        sendCommand(raw)
    }

    private fun stepHistory(delta: Int) {
        if (commandHistory.isEmpty()) return

        if (historyIndex == -1 && delta < 0) {
            historyIndex = commandHistory.size - 1
        } else {
            historyIndex = (historyIndex + delta)
                .coerceIn(-1, commandHistory.size - 1)
        }

        if (historyIndex >= 0) {
            cmdInput.setText(commandHistory[historyIndex])
            cmdInput.setSelection(cmdInput.text.length)
        } else {
            cmdInput.setText("")
        }
    }

    fun appendLine(text: String, type: Int = TYPE_NORMAL) {
        if (text.isBlank()) return

        logLines.addLast(LogEntry(text, type))
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()

        if (!outputPaused) renderLog()
    }

    fun appendAgentLine(raw: String) {
        var line = raw.trim()
        if (line.isEmpty()) return

        val prefixes = listOf("[AIZ3N]==>", "[Response]==>")
        var changed: Boolean

        do {
            changed = false
            for (prefix in prefixes) {
                if (line.startsWith(prefix, true)) {
                    line = line.removePrefix(prefix).trim()
                    changed = true
                }
            }
        } while (changed)

        if (line.isBlank()) return
        if (line.equals("GumJS: Ready", true)) return
        if (line.equals("AIZ3N TERMINAL", true)) return
        if (line.all { it == '=' } || line.all { it == '-' }) return

        when {
            line.startsWith("[Error]", true) ||
                line.startsWith("Error:", true) ->
                appendLine(line, TYPE_ERROR)

            line.startsWith("PID:", true) ||
                line.startsWith("PWD:", true) ->
                appendLine(line, TYPE_INFO)

            line.startsWith("AIZ3N agent connected", true) ||
                line.startsWith("Connected", true) -> Unit

            else -> appendLine(line, TYPE_NORMAL)
        }
    }

    private fun renderLog() {
        val output = SpannableStringBuilder()

        logLines.forEach { entry ->
            val color = when (entry.type) {
                TYPE_SUCCESS -> C_GREEN
                TYPE_WARNING -> C_AMBER
                TYPE_ERROR -> C_RED
                TYPE_INFO -> C_CYAN
                TYPE_COMMAND -> C_PURPLE
                else -> C_TEXT
            }

            if (entry.type == TYPE_COMMAND) {
                output.appendStyled("[ AIZ3N CORE ] ", C_PURPLE, true)
                output.appendStyled("::", C_AMBER, true)
                output.appendStyled(" ${entry.text}", Color.WHITE)
            } else {
                output.appendStyled(
                    entry.text,
                    color,
                    entry.type == TYPE_SUCCESS || entry.type == TYPE_ERROR
                )
            }

            output.append("\n")
        }

        logText.text = output
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun clearLog() {
        logLines.clear()
        logText.text = ""
    }

    fun copyLogToClipboard() {
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "AIZ3N Console",
                logLines.joinToString("\n") { it.text }
            )
        )
        Toast.makeText(ctx, "Output copied", Toast.LENGTH_SHORT).show()
    }

    fun showOfflineNotice() {
        if (offlineNoticeShown) return
        offlineNoticeShown = true
        appendLine(
            "Not connected. Launch the game with AIZ3N enabled.",
            TYPE_WARNING
        )
    }

    fun setConnectionState(connected: Boolean) {
        if (connected) offlineNoticeShown = false
    }

    fun load(commandOrText: String) {
        val value = commandOrText.trim()
        if (value.isNotEmpty()) sendCommand(value)
    }

    fun sendCommand(command: String) {
        val value = command.trim()
        if (value.isEmpty()) return

        if (!client.isConnected) {
            showOfflineNotice()
            return
        }

        if (commandHistory.lastOrNull() != value) {
            commandHistory.add(value)
            if (commandHistory.size > MAX_HISTORY) {
                commandHistory.removeAt(0)
            }
        }

        historyIndex = -1
        appendLine(value, TYPE_COMMAND)
        client.send(value)
    }

    fun destroy() {
        if (fileReceiverRegistered) {
            try {
                ctx.unregisterReceiver(filePickerReceiver)
            } catch (_: Exception) {
            }
            fileReceiverRegistered = false
        }
    }
}
