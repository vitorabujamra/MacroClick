package com.vitor.macroclick

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class EditActivity : Activity() {

    private lateinit var macro: Macro
    private lateinit var stepsBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", 0L)
        val m = Storage.get(this, id)
        if (m == null) { finish(); return }
        macro = m
        title = "Editar macro"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        root.addView(label("Nome"))
        root.addView(EditText(this).apply {
            setText(macro.name)
            setSingleLine()
            onChange { macro.name = it }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(numField("Repetições (0 = ∞)", macro.loops.toLong()) { macro.loops = it.toInt() })
        row.addView(numField("Pausa entre ciclos (ms)", macro.loopDelay) { macro.loopDelay = it })
        root.addView(row)

        val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var bulkDelay = 500L
        bulk.addView(numField("Atraso p/ todos (ms)", bulkDelay) { bulkDelay = it })
        bulk.addView(Button(this).apply {
            text = "Aplicar a todos"
            setOnClickListener {
                macro.steps.forEachIndexed { i, s -> if (i > 0) s.delay = bulkDelay }
                renderSteps()
            }
        })
        root.addView(bulk)

        root.addView(Button(this).apply {
            text = "💾 Salvar alterações"
            setOnClickListener { save() }
        })

        root.addView(TextView(this).apply {
            text = "Passos"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Atraso = espera antes do passo. Repetir = quantas vezes tocar no mesmo ponto (cliques múltiplos)."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stepsBox)
        renderSteps()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        Storage.upsert(this, macro)
        ClickService.instance?.let { if (it.current.id == macro.id && !it.playing) it.loadMacro(macro) }
        Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun renderSteps() {
        stepsBox.removeAllViews()
        macro.steps.forEachIndexed { i, s -> stepsBox.addView(stepCard(i, s)) }
        if (macro.steps.isEmpty()) {
            stepsBox.addView(TextView(this).apply { text = "Sem passos."; setTextColor(Color.GRAY) })
        }
    }

    private fun stepCard(i: Int, s: Step): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFF1F3F6.toInt())
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6))
            layoutParams = lp
        }
        val p = s.points.firstOrNull()
        val pos = if (p != null) "(${p[0].toInt()}, ${p[1].toInt()})" else ""
        val end = if (s.type == StepType.SWIPE && s.points.size > 1) {
            val l = s.points.last(); " → (${l[0].toInt()}, ${l[1].toInt()})"
        } else ""

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "#${i + 1}  ${s.type.label}  $pos$end"
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(small("↑") { if (i > 0) { swap(i, i - 1) } })
        header.addView(small("↓") { if (i < macro.steps.size - 1) { swap(i, i + 1) } })
        header.addView(small("✕") { macro.steps.removeAt(i); renderSteps() })
        box.addView(header)

        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(numField("Atraso (ms)", s.delay) { s.delay = it })
        r1.addView(numField("Duração (ms)", s.duration) { s.duration = it.coerceAtLeast(1) })
        box.addView(r1)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(numField("Repetir", s.repeat.toLong()) { s.repeat = it.toInt().coerceAtLeast(1) })
        r2.addView(numField("Intervalo rep. (ms)", s.repeatInterval) { s.repeatInterval = it })
        box.addView(r2)
        return box
    }

    private fun swap(a: Int, b: Int) {
        val t = macro.steps[a]
        macro.steps[a] = macro.steps[b]
        macro.steps[b] = t
        renderSteps()
    }

    private fun small(t: String, action: () -> Unit) = Button(this).apply {
        text = t
        minWidth = 0
        minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.DKGRAY)
    }

    private fun numField(title: String, value: Long, onValue: (Long) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(8), 0)
        }
        col.addView(label(title))
        col.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSingleLine()
            onChange { txt -> txt.toLongOrNull()?.let { onValue(it) } }
        })
        return col
    }

    private fun EditText.onChange(cb: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { cb(s?.toString() ?: "") }
        })
    }
}
