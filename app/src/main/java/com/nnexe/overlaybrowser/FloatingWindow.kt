package com.nnexe.overlaybrowser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating Frida-Gum agent console: an overlay window with a toolbar, scrolling monospace output,
 * command input bar, and a minimized floating bubble.
 */
@SuppressLint("ClickableViewAccessibility", "InflateParams")
class FloatingWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    initialCommand: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onCloseRequested()
        fun onMinimizedChanged(minimized: Boolean)
        fun onOpenSettings()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val minWidth = dp(240)
    private val minHeight = dp(160)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val statusBarHeight = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        .let { if (it != 0) ctx.resources.getDimensionPixelSize(it) else 0 }
    private val main = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(InputMethodManager::class.java)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val root = LayoutInflater.from(ctx).inflate(R.layout.overlay_window, null) as OverlayRootView
    private val toolbar: View = root.findViewById(R.id.toolbar)
    private val dragHandle: ImageView = root.findViewById(R.id.drag_handle)
    private val btnBack: ImageButton = root.findViewById(R.id.btn_back)
    private val btnReload: ImageButton = root.findViewById(R.id.btn_reload)
    private val statusField: EditText = root.findViewById(R.id.url)
    private val btnFit: ImageButton = root.findViewById(R.id.btn_fit)
    private val btnMenu: ImageButton = root.findViewById(R.id.btn_menu)
    private val btnMute: ImageButton = root.findViewById(R.id.btn_mute)
    private val btnLock: ImageButton = root.findViewById(R.id.btn_lock)
    private val btnMinimize: ImageButton = root.findViewById(R.id.btn_minimize)
    private val btnClose: ImageButton = root.findViewById(R.id.btn_close)
    private val progress: View = root.findViewById(R.id.progress)
    private val contentHolder: FrameLayout = root.findViewById(R.id.web_holder)
    private val resizeLeft: View = root.findViewById(R.id.resize_bl)
    private val resizeRight: View = root.findViewById(R.id.resize_br)

    private val bubble = LayoutInflater.from(ctx).inflate(R.layout.overlay_bubble, null)
    private val bubbleIcon: View = bubble.findViewById(R.id.bubble_icon)

    private val bounds = Rect()
    private val params = overlayParams(0, 0)
    private val bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
    private var rootAdded = false
    private var bubbleAdded = false
    private var interacting = false
    private var keyboardTop = 0

    private var locked = prefs.locked
    private var outputPaused = prefs.pausedOutput
    var isMinimized = false
        private set
    private var typing = false

    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var cmdInput: EditText
    private lateinit var btnSend: TextView

    private val logLines = ArrayDeque<String>()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val client = AgentClient(
        host = prefs.host,
        port = prefs.port,
        onLine = { line -> main.post { appendLine("<< $line") } },
        onStatus = { connected, status ->
            main.post {
                statusField.setText(status)
                statusField.setTextColor(if (connected) Color.parseColor("#6EE7B7") else Color.parseColor("#FCA5A5"))
                appendLine("[sys] $status")
            }
        },
    )

    init {
        loadBounds()
        buildConsoleView()
        wireToolbar()
        resizeLeft.setOnTouchListener(resizeListener(fromLeft = true))
        resizeRight.setOnTouchListener(resizeListener(fromLeft = false))
        bubbleIcon.setOnTouchListener(bubbleListener())

        root.isFocusableInTouchMode = true
        root.onOutsideTouch = { stopTyping() }
        root.onBack = { handleBack() }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) main.post { fitToolbar() }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            trackKeyboard(insets)
            main.post { avoidSystemBars() }
            insets
        }
        if (Build.VERSION.SDK_INT >= 29) {
            for (handle in listOf(dragHandle, resizeLeft, resizeRight)) {
                handle.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
        }

        updatePauseUi()
        updateLockUi()
        applyWindowLayout()
        wm.addView(root, params)
        rootAdded = true

        client.start()
        if (!initialCommand.isNullOrBlank()) {
            sendCommand(initialCommand)
        }
    }

    // region Console UI

    private fun buildConsoleView() {
        contentHolder.removeAllViews()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0D1117"))
        }

        logText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        logScroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                logText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val inputBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6161B22"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val prompt = TextView(ctx).apply {
            text = ">"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(dp(4), 0, dp(6), 0)
        }

        cmdInput = EditText(ctx).apply {
            hint = "Enter agent command..."
            setHintTextColor(Color.parseColor("#6E7681"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(2), dp(6), dp(6), dp(6))

            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    startTyping()
                    main.postDelayed({ if (typing && hasFocus()) imm.showSoftInput(this, 0) }, 200)
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    main.postDelayed({ if (!cmdInput.hasFocus()) stopTyping() }, 250)
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                    submitInput()
                    true
                } else {
                    false
                }
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
                } else {
                    false
                }
            }
        }

        btnSend = TextView(ctx).apply {
            text = "SEND"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#238636"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { submitInput() }
        }

        inputBar.addView(prompt)
        inputBar.addView(
            cmdInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        inputBar.addView(btnSend)

        container.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        container.addView(
            inputBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        contentHolder.addView(
            container,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    private fun submitInput() {
        val raw = cmdInput.text.toString().trim()
        if (raw.isEmpty()) return
        cmdInput.setText("")
        sendCommand(raw)
    }

    private fun stepHistory(delta: Int) {
        if (commandHistory.isEmpty()) return
        if (historyIndex == -1 && delta < 0) {
            historyIndex = commandHistory.size - 1
        } else {
            historyIndex = (historyIndex + delta).coerceIn(-1, commandHistory.size - 1)
        }
        if (historyIndex >= 0) {
            cmdInput.setText(commandHistory[historyIndex])
            cmdInput.setSelection(cmdInput.text.length)
        } else {
            cmdInput.setText("")
        }
    }

    private fun appendLine(text: String) {
        val stamp = timeFmt.format(Date())
        logLines.addLast("[$stamp] $text")
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        if (!outputPaused) {
            renderLog()
        }
    }

    private fun renderLog() {
        logText.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearLog() {
        logLines.clear()
        logText.text = ""
    }

    private fun copyLogToClipboard() {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("FridaGum Console", logLines.joinToString("\n")))
        Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region Public controls

    fun load(commandOrText: String) {
        val trimmed = commandOrText.trim()
        if (trimmed.isNotEmpty()) {
            sendCommand(trimmed)
        }
    }

    fun sendCommand(command: String) {
        if (commandHistory.lastOrNull() != command) {
            commandHistory.add(command)
            if (commandHistory.size > MAX_HISTORY) commandHistory.removeAt(0)
        }
        historyIndex = -1
        appendLine(">> $command")
        client.send(command)
    }

    fun minimize() {
        if (isMinimized) return
        stopTyping()
        isMinimized = true
        applyWindowLayout()
        showBubble()
        listener.onMinimizedChanged(true)
    }

    fun restore() {
        if (!isMinimized) return
        isMinimized = false
        hideBubble()
        applyWindowLayout()
        listener.onMinimizedChanged(false)
    }

    fun refreshAppearance() {
        applyWindowLayout()
    }

    fun onScreenChanged() {
        main.post {
            clampBounds()
            applyWindowLayout()
            if (bubbleAdded) {
                clampBubble()
                wm.updateViewLayout(bubble, bubbleParams)
            }
        }
    }

    fun destroy() {
        client.stop()
        hideBubble()
        if (rootAdded) {
            wm.removeView(root)
            rootAdded = false
        }
    }

    // endregion

    // region Window placement

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun screen(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Rect(0, 0, b.width(), b.height())
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun loadBounds() {
        val s = screen()
        var w = prefs.width
        var h = prefs.height
        if (w <= 0 || h <= 0) {
            w = (s.width() * 0.55f).roundToInt()
            h = (s.height() * 0.65f).roundToInt()
        }
        var x = prefs.x
        var y = prefs.y
        if (x < 0 || y < 0) {
            x = s.width() - w - dp(12)
            y = dp(12)
        }
        bounds.set(x, y, x + w, y + h)
        clampBounds()
    }

    private fun clampBounds() {
        val s = screen()
        val w = bounds.width().clamp(minWidth, s.width())
        val h = bounds.height().clamp(minHeight, s.height())
        val x = bounds.left.clamp(0, s.width() - w)
        val y = bounds.top.clamp(0, s.height() - h)
        bounds.set(x, y, x + w, y + h)
    }

    private fun saveBounds() {
        prefs.x = bounds.left
        prefs.y = bounds.top
        prefs.width = bounds.width()
        prefs.height = bounds.height()
    }

    private fun applyWindowLayout() {
        val touchFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (isMinimized) {
            params.x = bounds.left
            params.y = bounds.top
            params.width = 1
            params.height = 1
            params.alpha = 0f
            params.flags = params.flags or touchFlag
        } else {
            var top = bounds.top
            var height = bounds.height()
            if (typing && keyboardTop > 0 && bounds.bottom > keyboardTop) {
                top = (keyboardTop - height).coerceAtLeast(statusBarHeight)
                height = (keyboardTop - top).coerceAtLeast(dp(110))
            }
            params.x = bounds.left
            params.y = top
            params.width = bounds.width()
            params.height = height
            params.alpha = prefs.opacity
            params.flags = params.flags and touchFlag.inv()
        }

        val focusFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val outsideFlag = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (typing && !isMinimized) {
            params.flags = (params.flags and focusFlag.inv()) or outsideFlag
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            params.flags = (params.flags or focusFlag) and outsideFlag.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (rootAdded) wm.updateViewLayout(root, params)
    }

    private fun moveListener() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (locked || isMinimized) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interacting = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = bounds.left
                    startY = bounds.top
                }
                MotionEvent.ACTION_MOVE -> {
                    bounds.offsetTo(startX + (e.rawX - downX).roundToInt(), startY + (e.rawY - downY).roundToInt())
                    clampBounds()
                    applyWindowLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endInteraction()
            }
            return true
        }
    }

    private fun resizeListener(fromLeft: Boolean) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private val start = Rect()

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (locked || isMinimized) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interacting = true
                    downX = e.rawX
                    downY = e.rawY
                    start.set(bounds)
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = screen()
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    val bottom = (start.bottom + dy).clamp(start.top + minHeight, s.height())
                    if (fromLeft) {
                        val left = (start.left + dx).clamp(0, start.right - minWidth)
                        bounds.set(left, start.top, start.right, bottom)
                    } else {
                        val right = (start.right + dx).clamp(start.left + minWidth, s.width())
                        bounds.set(start.left, start.top, right, bottom)
                    }
                    applyWindowLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endInteraction()
            }
            return true
        }
    }

    private fun endInteraction() {
        interacting = false
        saveBounds()
        main.postDelayed({ avoidSystemBars() }, 150)
    }

    private fun avoidSystemBars() {
        if (interacting || isMinimized || keyboardTop > 0 || !rootAdded) return
        val insets = root.rootWindowInsets ?: return
        val coveredTop: Int
        val coveredBottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            coveredTop = i.top
            coveredBottom = i.bottom
        } else {
            @Suppress("DEPRECATION")
            coveredTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            coveredBottom = insets.systemWindowInsetBottom
        }
        if (coveredTop <= 0 && coveredBottom <= 0) return
        val s = screen()
        var top = bounds.top
        var bottom = bounds.bottom
        if (coveredTop > 0 && top < coveredTop) {
            val shift = (coveredTop - top).coerceAtMost((s.height() - coveredBottom - bottom).coerceAtLeast(0))
            top += shift
            bottom += shift
        }
        if (coveredBottom > 0 && bottom > s.height() - coveredBottom) {
            bottom = (s.height() - coveredBottom).coerceAtLeast(top + minHeight)
        }
        if (top == bounds.top && bottom == bounds.bottom) return
        val prev = Rect(bounds)
        bounds.set(bounds.left, top, bounds.right, bottom)
        clampBounds()
        if (bounds != prev) applyWindowLayout()
    }

    private fun trackKeyboard(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        val top = if (!typing || !insets.isVisible(WindowInsets.Type.ime())) {
            0
        } else {
            val covered = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (covered > 0) {
                val frame = IntArray(2)
                root.getLocationOnScreen(frame)
                frame[1] + root.height - covered
            } else {
                keyboardTop
            }
        }
        if (top != keyboardTop) {
            keyboardTop = top
            main.post { applyWindowLayout() }
        }
    }

    // endregion

    // region Bubble

    private fun showBubble() {
        if (bubbleAdded) return
        val s = screen()
        val size = dp(58)
        bubbleParams.x = if (prefs.bubbleX >= 0) prefs.bubbleX else s.width() - size - dp(8)
        bubbleParams.y = if (prefs.bubbleY >= 0) prefs.bubbleY else s.height() * 3 / 5
        clampBubble()
        wm.addView(bubble, bubbleParams)
        bubbleAdded = true
    }

    private fun hideBubble() {
        if (!bubbleAdded) return
        wm.removeView(bubble)
        bubbleAdded = false
    }

    private fun clampBubble() {
        val s = screen()
        val size = dp(58)
        bubbleParams.x = bubbleParams.x.clamp(0, s.width() - size)
        bubbleParams.y = bubbleParams.y.clamp(0, s.height() - size)
    }

    private fun bubbleListener() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            showBubbleMenu()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    longPressed = false
                    v.isPressed = true
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.roundToInt()
                        bubbleParams.y = startY + dy.roundToInt()
                        clampBubble()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        prefs.bubbleX = bubbleParams.x
                        prefs.bubbleY = bubbleParams.y
                    } else if (!longPressed) {
                        restore()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                }
            }
            return true
        }
    }

    private fun showBubbleMenu() {
        val popup = PopupMenu(ctx, bubbleIcon)
        popup.menu.add(0, 1, 0, R.string.restore)
        popup.menu.add(0, 2, 1, "Reconnect")
        popup.menu.add(0, 3, 2, R.string.close)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> restore()
                2 -> client.reconnect()
                3 -> listener.onCloseRequested()
            }
            true
        }
        popup.show()
    }

    // endregion

    // region Toolbar & Focus

    private fun wireToolbar() {
        val mover = moveListener()
        dragHandle.setOnTouchListener(mover)
        toolbar.setOnTouchListener(mover)

        // Make the top bar field a read-only connection status banner
        statusField.isFocusable = false
        statusField.isFocusableInTouchMode = false
        statusField.isCursorVisible = false
        statusField.keyListener = null
        statusField.ellipsize = TextUtils.TruncateAt.END
        statusField.typeface = Typeface.MONOSPACE
        statusField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        statusField.setText("Connecting to ${prefs.host}:${prefs.port}...")
        statusField.setOnTouchListener(mover)

        progress.visibility = View.GONE
        btnFit.visibility = View.GONE
        btnBack.visibility = View.GONE

        // Reuse reload button to reconnect TCP socket
        btnReload.contentDescription = "Reconnect"
        btnReload.setOnClickListener {
            appendLine("[sys] Reconnecting...")
            client.reconnect()
        }

        btnMenu.setOnClickListener { showMenu() }
        btnMute.setOnClickListener { togglePauseOutput() }
        btnLock.setOnClickListener { toggleLock() }
        btnMinimize.setOnClickListener { minimize() }
        btnClose.setOnClickListener { listener.onCloseRequested() }
    }

    private fun fitToolbar() {
        val widthDp = root.width / density
        btnReload.visibility = if (widthDp >= 300) View.VISIBLE else View.GONE
    }

    private fun togglePauseOutput() {
        outputPaused = !outputPaused
        prefs.pausedOutput = outputPaused
        updatePauseUi()
        if (!outputPaused) {
            renderLog()
        }
        Toast.makeText(ctx, if (outputPaused) "Output paused" else "Output resumed", Toast.LENGTH_SHORT).show()
    }

    private fun updatePauseUi() {
        btnMute.setImageResource(if (outputPaused) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        btnMute.imageTintList = ColorStateList.valueOf(ctx.getColor(if (outputPaused) R.color.accent else R.color.text))
        btnMute.contentDescription = if (outputPaused) "Resume output" else "Pause output"
    }

    private fun toggleLock() {
        locked = !locked
        prefs.locked = locked
        updateLockUi()
        Toast.makeText(ctx, if (locked) R.string.locked else R.string.unlocked, Toast.LENGTH_SHORT).show()
    }

    private fun updateLockUi() {
        btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        btnLock.imageTintList = ColorStateList.valueOf(ctx.getColor(if (locked) R.color.accent else R.color.text))
        root.setBackgroundResource(if (locked) R.drawable.bg_window_locked else R.drawable.bg_window)
        dragHandle.alpha = if (locked) 0.3f else 1f
        val handles = if (locked) View.GONE else View.VISIBLE
        resizeLeft.visibility = handles
        resizeRight.visibility = handles
    }

    private fun showMenu() {
        val popup = PopupMenu(ctx, btnMenu)
        val menu = popup.menu
        menu.add(0, MENU_RECONNECT, 0, "Reconnect (${prefs.host}:${prefs.port})")
        menu.add(0, MENU_CLEAR, 1, "Clear Console")
        menu.add(0, MENU_COPY, 2, "Copy Output")
        menu.add(0, MENU_OPACITY, 3, ctx.getString(R.string.menu_opacity, (prefs.opacity * 100).roundToInt()))
        menu.add(0, MENU_SETTINGS, 4, R.string.menu_settings)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RECONNECT -> client.reconnect()
                MENU_CLEAR -> clearLog()
                MENU_COPY -> copyLogToClipboard()
                MENU_OPACITY -> showOpacityPanel()
                MENU_SETTINGS -> {
                    minimize()
                    listener.onOpenSettings()
                }
            }
            true
        }
        popup.show()
    }

    private fun showOpacityPanel() {
        val panel = LayoutInflater.from(ctx).inflate(R.layout.opacity_panel, null)
        val value: TextView = panel.findViewById(R.id.opacity_value)
        val seek: SeekBar = panel.findViewById(R.id.opacity_seek)
        val minPct = (Prefs.MIN_OPACITY * 100).roundToInt()

        fun setPercent(pct: Int) {
            val p = pct.coerceIn(minPct, 100)
            prefs.opacity = p / 100f
            value.text = ctx.getString(R.string.percent, p)
            if (seek.progress != p) seek.progress = p
            applyWindowLayout()
        }

        seek.min = minPct
        seek.max = 100
        seek.keyProgressIncrement = 5
        seek.progress = (prefs.opacity * 100).roundToInt()
        value.text = ctx.getString(R.string.percent, seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setPercent(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        panel.findViewById<View>(R.id.opacity_minus).setOnClickListener { setPercent((seek.progress - 1) / 5 * 5) }
        panel.findViewById<View>(R.id.opacity_plus).setOnClickListener { setPercent((seek.progress / 5 + 1) * 5) }
        for ((id, pct) in listOf(R.id.opacity_25 to 25, R.id.opacity_50 to 50, R.id.opacity_75 to 75, R.id.opacity_100 to 100)) {
            panel.findViewById<View>(id).setOnClickListener { setPercent(pct) }
        }

        val popup = PopupWindow(panel, dp(280), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ctx.getDrawable(R.drawable.bg_card) ?: ColorDrawable(Color.DKGRAY))
        popup.elevation = dp(8).toFloat()
        popup.isOutsideTouchable = true
        popup.showAsDropDown(btnMenu, 0, dp(4), Gravity.END)
    }

    private fun startTyping() {
        if (typing || isMinimized) return
        typing = true
        applyWindowLayout()
    }

    private fun stopTyping() {
        if (!typing) return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        if (cmdInput.hasFocus()) root.requestFocus()
        typing = false
        keyboardTop = 0
        applyWindowLayout()
    }

    private fun handleBack() {
        if (typing) stopTyping()
    }

    // endregion

    private fun Int.clamp(lo: Int, hi: Int) = if (hi < lo) lo else coerceIn(lo, hi)

    private companion object {
        const val MENU_RECONNECT = 1
        const val MENU_CLEAR = 2
        const val MENU_COPY = 3
        const val MENU_OPACITY = 4
        const val MENU_SETTINGS = 5
        const val MAX_LOG_LINES = 500
        const val MAX_HISTORY = 50
    }
}
