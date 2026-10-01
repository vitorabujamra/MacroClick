package com.vitor.macroclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

class ClickService : AccessibilityService() {

    companion object {
        var instance: ClickService? = null
            private set
        private const val MAX_MARKERS = 60
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    var current: Macro = Macro()
        private set

    // Painel
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var btnPlay: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnLoop: TextView
    private lateinit var txtInfo: TextView

    // Marcadores
    private val markers = mutableListOf<View>()
    private var markersVisible = true

    // Gravação
    private var capture: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var recording = false
    private var replaying = false
    private val recPts = mutableListOf<FloatArray>()
    private var recDownTime = 0L
    private var lastUpTime = 0L

    // Execução
    var playing = false
        private set
    private var playIdx = 0
    private var playRep = 0
    private var playLoop = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        removeCapture()
        clearMarkers()
        panel?.let { safeRemove(it) }
        panel = null
        instance = null
    }

    // ---------- utilidades ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (e: Exception) { }
    }

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ---------- painel flutuante ----------

    private fun makeBtn(label: String, color: Int = Color.WHITE, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(color)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            background = bg
            val lp = LinearLayout.LayoutParams(dp(44), dp(44))
            lp.setMargins(dp(4), dp(3), dp(4), dp(3))
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (!::wm.isInitialized) return
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(4), dp(2), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE0202530.toInt())
            }
        }

        val handle = TextView(this).apply {
            text = "⠿"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(26))
        }
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var px = 0; var py = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = panelParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = p.x; py = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = px + (e.rawX - sx).toInt()
                        p.y = py + (e.rawY - sy).toInt()
                        panel?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) { } }
                    }
                }
                return true
            }
        })

        txtInfo = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        btnPlay = makeBtn("▶", 0xFF66BB6A.toInt()) { if (playing) stopPlay() else startPlay() }
        btnRec = makeBtn("●", 0xFFEF5350.toInt()) { if (recording) stopRecording() else startRecording() }
        val btnAdd = makeBtn("＋") { addTarget() }
        btnLoop = makeBtn("1x") { cycleLoops() }.apply { textSize = 14f }
        val btnEye = makeBtn("👁") {
            markersVisible = !markersVisible
            refreshMarkers()
            toast(if (markersVisible) "Alvos visíveis" else "Alvos ocultos")
        }
        val btnSave = makeBtn("💾") { saveCurrent() }
        val btnClear = makeBtn("🗑") { toast("Segure para limpar o macro atual") }
        btnClear.setOnLongClickListener {
            stopPlay(); if (recording) stopRecording()
            current = Macro()
            refreshMarkers(); updateInfo()
            toast("Macro limpo")
            true
        }
        val btnApp = makeBtn("☰") {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        }
        val btnClose = makeBtn("✕", Color.LTGRAY) { hidePanel() }

        root.addView(handle)
        root.addView(txtInfo)
        listOf(btnPlay, btnRec, btnAdd, btnLoop, btnEye, btnSave, btnClear, btnApp, btnClose)
            .forEach { root.addView(it) }

        val p = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = 0
        p.y = dp(120)
        panel = root
        panelParams = p
        wm.addView(root, p)
        updateInfo()
        refreshMarkers()
    }

    fun hidePanel() {
        stopPlay()
        if (recording) stopRecording()
        clearMarkers()
        panel?.let { safeRemove(it) }
        panel = null
    }

    private fun bringPanelToFront() {
        val p = panel ?: return
        val lp = panelParams ?: return
        safeRemove(p)
        try { wm.addView(p, lp) } catch (_: Exception) { }
    }

    private fun updateInfo() {
        if (panel == null) return
        val name = if (current.name.isBlank()) "novo" else current.name
        txtInfo.text = if (playing) {
            val tot = if (current.loops == 0) "∞" else current.loops.toString()
            "${playIdx + 1}/${current.steps.size}\nL${playLoop + 1}/$tot"
        } else {
            "${current.steps.size} pass.\n${name.take(8)}"
        }
        btnPlay.text = if (playing) "■" else "▶"
        btnRec.text = if (recording) "■" else "●"
        btnLoop.text = current.loopsLabel()
    }

    private fun cycleLoops() {
        val options = intArrayOf(1, 3, 5, 10, 50, 100, 0)
        val idx = options.indexOf(current.loops)
        current.loops = options[(idx + 1) % options.size]
        updateInfo()
        toast("Repetições: ${if (current.loops == 0) "infinito" else current.loops}")
    }

    // ---------- macros ----------

    fun loadMacro(m: Macro) {
        stopPlay()
        if (recording) stopRecording()
        current = m.copy()
        if (panel == null) showPanel()
        refreshMarkers()
        updateInfo()
    }

    private fun saveCurrent() {
        if (current.steps.isEmpty()) { toast("Nada para salvar"); return }
        if (current.id == 0L) {
            current.id = System.currentTimeMillis()
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            current.name = "Macro ${fmt.format(Date())}"
        }
        Storage.upsert(this, current)
        updateInfo()
        toast("Salvo: ${current.name}")
    }

    // ---------- alvos (marcadores arrastáveis) ----------

    private fun clearMarkers() {
        markers.forEach { safeRemove(it) }
        markers.clear()
    }

    private fun refreshMarkers() {
        clearMarkers()
        if (panel == null || !markersVisible || recording || playing) return
        if (current.steps.size > MAX_MARKERS) return
        current.steps.forEachIndexed { i, s -> addMarker(i, s) }
        bringPanelToFront()
    }

    private fun addTarget() {
        if (recording) { toast("Pare a gravação primeiro"); return }
        stopPlay()
        val (w, h) = screenSize()
        val off = (current.steps.size % 6) * dp(24)
        val step = Step(
            StepType.TAP,
            mutableListOf(floatArrayOf(w / 2f + off, h / 2f + off)),
            duration = 50,
            delay = if (current.steps.isEmpty()) 0 else 500
        )
        current.steps.add(step)
        if (!markersVisible) markersVisible = true
        refreshMarkers()
        updateInfo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addMarker(index: Int, step: Step) {
        val first = step.points.firstOrNull() ?: return
        val size = dp(46)
        val color = when (step.type) {
            StepType.TAP -> 0xAAE53935.toInt()
            StepType.LONG -> 0xAA8E24AA.toInt()
            StepType.SWIPE -> 0xAA1E88E5.toInt()
        }
        val tv = TextView(this).apply {
            text = (index + 1).toString()
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(2), Color.WHITE)
            }
        }
        val p = baseParams(size, size)
        p.x = (first[0] - size / 2f).toInt()
        p.y = (first[1] - size / 2f).toInt()

        tv.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var px = 0; var py = 0
            val loc0 = IntArray(2)
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; px = p.x; py = p.y
                        v.getLocationOnScreen(loc0)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = px + (e.rawX - sx).toInt()
                        p.y = py + (e.rawY - sy).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.post {
                            val loc1 = IntArray(2)
                            v.getLocationOnScreen(loc1)
                            val dx = (loc1[0] - loc0[0]).toFloat()
                            val dy = (loc1[1] - loc0[1]).toFloat()
                            for (pt in step.points) {
                                pt[0] = (pt[0] + dx).coerceAtLeast(0f)
                                pt[1] = (pt[1] + dy).coerceAtLeast(0f)
                            }
                        }
                    }
                }
                return true
            }
        })
        wm.addView(tv, p)
        markers.add(tv)
    }

    // ---------- gravação ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun startRecording() {
        stopPlay()
        recording = true
        lastUpTime = 0L
        replaying = false
        clearMarkers()
        val v = View(this)
        v.setBackgroundColor(0x1A00E676)
        v.setOnTouchListener { _, e -> onCaptureTouch(e); true }
        val p = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        capture = v
        captureParams = p
        wm.addView(v, p)
        bringPanelToFront()
        updateInfo()
        toast("Gravando… use a tela normalmente. ■ para parar")
    }

    private fun stopRecording() {
        recording = false
        removeCapture()
        updateInfo()
        refreshMarkers()
        toast("${current.steps.size} passos. Toque 💾 para salvar")
    }

    private fun removeCapture() {
        capture?.let { safeRemove(it) }
        capture = null
        captureParams = null
    }

    private fun onCaptureTouch(e: MotionEvent) {
        if (replaying) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recPts.clear()
                recPts.add(floatArrayOf(e.rawX, e.rawY))
                recDownTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (recPts.isEmpty()) return
                val last = recPts.last()
                if (hypot(e.rawX - last[0], e.rawY - last[1]) > dp(4)) {
                    recPts.add(floatArrayOf(e.rawX, e.rawY))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (recPts.isEmpty()) return
                val now = SystemClock.uptimeMillis()
                recPts.add(floatArrayOf(e.rawX, e.rawY))
                val dur = (now - recDownTime).coerceAtLeast(1)
                val a = recPts.first()
                val b = recPts.last()
                val dist = hypot(b[0] - a[0], b[1] - a[1])

                val step = when {
                    dist > dp(20) -> Step(StepType.SWIPE, simplify(recPts), dur.coerceIn(50, 10000))
                    dur > 450 -> Step(StepType.LONG, mutableListOf(a.copyOf()), dur.coerceAtMost(30000))
                    else -> Step(StepType.TAP, mutableListOf(a.copyOf()), 50)
                }
                step.delay = if (lastUpTime == 0L) {
                    if (current.steps.isEmpty()) 0 else 500
                } else {
                    (recDownTime - lastUpTime).coerceAtLeast(0)
                }
                current.steps.add(step)
                lastUpTime = now
                updateInfo()
                passThrough(step)
            }
            MotionEvent.ACTION_CANCEL -> recPts.clear()
        }
    }

    private fun simplify(src: List<FloatArray>): MutableList<FloatArray> {
        val max = 30
        if (src.size <= max) return src.map { it.copyOf() }.toMutableList()
        val out = mutableListOf<FloatArray>()
        for (i in 0 until max) {
            val idx = (i.toFloat() * (src.size - 1) / (max - 1)).toInt()
            out.add(src[idx].copyOf())
        }
        return out
    }

    /** Repassa o toque gravado para o app que está por baixo. */
    private fun passThrough(step: Step) {
        val v = capture ?: return
        val p = captureParams ?: return
        replaying = true
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
        handler.postDelayed({
            val restore = {
                handler.postDelayed({
                    val cv = capture
                    val cp = captureParams
                    if (cv != null && cp != null) {
                        cp.flags = cp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        try { wm.updateViewLayout(cv, cp) } catch (_: Exception) { }
                    }
                    replaying = false
                }, 60)
            }
            val g = buildGesture(step)
            if (g == null) { restore(); return@postDelayed }
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { restore() }
                override fun onCancelled(gestureDescription: GestureDescription?) { restore() }
            }, null)
            if (!ok) restore()
        }, 80)
    }

    // ---------- execução ----------

    private fun buildGesture(s: Step): GestureDescription? {
        val pts = s.points
        if (pts.isEmpty()) return null
        val path = Path()
        path.moveTo(pts[0][0].coerceAtLeast(0f), pts[0][1].coerceAtLeast(0f))
        if (s.type == StepType.SWIPE && pts.size > 1) {
            for (i in 1 until pts.size) {
                path.lineTo(pts[i][0].coerceAtLeast(0f), pts[i][1].coerceAtLeast(0f))
            }
        }
        val maxD = GestureDescription.getMaxGestureDuration()
        val d = s.duration.coerceIn(1L, maxD)
        return try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, d))
                .build()
        } catch (e: Exception) { null }
    }

    fun startPlay(startDelay: Long = 400) {
        if (current.steps.isEmpty()) { toast("Macro vazio: grave (●) ou adicione alvos (＋)"); return }
        if (recording) stopRecording()
        playing = true
        playIdx = 0; playRep = 0; playLoop = 0
        refreshMarkers()
        updateInfo()
        handler.postDelayed({ runStep() }, startDelay)
    }

    fun stopPlay() {
        if (!playing) return
        playing = false
        handler.removeCallbacksAndMessages(null)
        updateInfo()
        refreshMarkers()
    }

    private fun runStep() {
        if (!playing) return
        if (playIdx >= current.steps.size) {
            playLoop++
            if (current.loops in 1..playLoop) {
                stopPlay()
                toast("Macro concluído")
                return
            }
            playIdx = 0; playRep = 0
            updateInfo()
            handler.postDelayed({ runStep() }, current.loopDelay.coerceAtLeast(0))
            return
        }
        val s = current.steps[playIdx]
        val wait = if (playRep == 0) s.delay else s.repeatInterval
        handler.postDelayed({ fire(s) }, wait.coerceAtLeast(0))
    }

    private fun fire(s: Step) {
        if (!playing) return
        val next = {
            if (playing) {
                playRep++
                if (playRep >= s.repeat.coerceAtLeast(1)) { playRep = 0; playIdx++ }
                updateInfo()
                runStep()
            }
            Unit
        }
        val g = buildGesture(s)
        if (g == null) { next(); return }
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { handler.post { next() } }
            override fun onCancelled(gestureDescription: GestureDescription?) { handler.post { next() } }
        }, null)
        if (!ok) handler.postDelayed({ next() }, 100)
    }
}
