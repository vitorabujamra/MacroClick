package com.vitor.macroclick

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnService: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        root.addView(status)

        btnService = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(btnService)

        root.addView(Button(this).apply {
            text = "Mostrar painel flutuante"
            setOnClickListener {
                val s = ClickService.instance
                if (s == null) toast("Ative o serviço de acessibilidade primeiro")
                else { s.showPanel(); moveTaskToBack(true) }
            }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Exportar"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportAll() }
        })
        row.addView(Button(this).apply {
            text = "Importar"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { importDialog() }
        })
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "Meus macros"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(20), 0, 0)
            text = "Como usar o painel:\n" +
                "▶ executar / ■ parar\n" +
                "● gravar seus toques, toques longos e deslizes (■ para parar)\n" +
                "＋ adicionar um alvo de toque (arraste o círculo para posicionar)\n" +
                "1x  número de repetições (toque para alternar até ∞)\n" +
                "👁 mostrar/ocultar alvos   💾 salvar\n" +
                "🗑 segure para limpar   ☰ abrir o app   ✕ fechar painel\n\n" +
                "Arraste o painel pelo ⠿. Atrasos, duração e repetições de cada passo podem ser ajustados em Editar."
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = ClickService.instance != null
        status.text = if (on) "✅ Serviço ativo" else "⚠️ Serviço de acessibilidade desativado"
        btnService.text = if (on) "Configurações de acessibilidade" else "Ativar serviço (Acessibilidade → MacroClick)"

        listBox.removeAllViews()
        val macros = Storage.loadAll(this)
        if (macros.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Nenhum macro salvo ainda. Abra o painel, grave ou adicione alvos e toque em 💾."
                setTextColor(Color.GRAY)
            })
        }
        for (m in macros.sortedByDescending { it.id }) listBox.addView(card(m))
    }

    private fun card(m: Macro): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF1F3F6.toInt())
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
        }
        box.addView(TextView(this).apply {
            text = m.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "${m.steps.size} passos • repetir ${if (m.loops == 0) "infinito" else "${m.loops}x"}"
            setTextColor(Color.DKGRAY)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun b(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
        row.addView(b("Usar") {
            val s = ClickService.instance
            if (s == null) toast("Ative o serviço de acessibilidade primeiro")
            else { s.loadMacro(m); toast("Carregado no painel. Abra o app alvo e toque ▶"); moveTaskToBack(true) }
        })
        row.addView(b("Editar") {
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", m.id))
        })
        row.addView(b("Duplicar") {
            val c = m.copy()
            c.id = System.currentTimeMillis()
            c.name = m.name + " (cópia)"
            Storage.upsert(this, c)
            refresh()
        })
        row.addView(b("Excluir") {
            AlertDialog.Builder(this)
                .setTitle("Excluir macro?")
                .setMessage(m.name)
                .setPositiveButton("Excluir") { _, _ -> Storage.delete(this, m.id); refresh() }
                .setNegativeButton("Cancelar", null)
                .show()
        })
        box.addView(row)
        return box
    }

    private fun exportAll() {
        val json = Storage.toJsonString(Storage.loadAll(this))
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("macros", json))
        toast("Macros copiados para a área de transferência")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json)
        try { startActivity(Intent.createChooser(send, "Compartilhar backup")) } catch (_: Exception) { }
    }

    private fun importDialog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val input = EditText(this).apply {
            setText(clip)
            hint = "Cole aqui o JSON exportado"
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Importar macros")
            .setView(input)
            .setPositiveButton("Importar") { _, _ ->
                val imported = Storage.parseList(input.text.toString())
                if (imported.isEmpty()) { toast("Nada válido para importar"); return@setPositiveButton }
                val list = Storage.loadAll(this)
                var base = System.currentTimeMillis()
                for (m in imported) {
                    if (list.any { it.id == m.id } || m.id == 0L) m.id = base++
                    list.add(m)
                }
                Storage.saveAll(this, list)
                toast("${imported.size} macro(s) importado(s)")
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
