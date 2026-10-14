package br.com.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val campos = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        setContentView(ScrollView(this).apply { addView(raiz) })

        fun texto(t: String, tam: Float = 14f) {
            raiz.addView(TextView(this).apply { text = t; textSize = tam; setPadding(0, 20, 0, 6) })
        }
        fun campo(chave: String, dica: String, segredo: Boolean = false) {
            val e = EditText(this).apply {
                hint = dica
                setText(Cfg.get(this@MainActivity, chave))
                inputType = if (segredo) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
            }
            campos[chave] = e
            raiz.addView(e)
        }
        fun botao(t: String, acao: () -> Unit) {
            raiz.addView(Button(this).apply { text = t; setOnClickListener { acao() } })
        }

        texto("J.A.R.V.I.S", 28f)
        texto("Chave da API da Anthropic (console.anthropic.com)")
        campo("claude_key", "sk-ant-...", true)
        texto("Chave do Picovoice (console.picovoice.ai, grátis). É ela que detecta a palavra \"Jarvis\".")
        campo("pico_key", "AccessKey", true)
        texto("Como devo te chamar?")
        campo("nome", "senhor")
        texto("Opcional: Home Assistant (casa inteligente)")
        campo("ha_url", "http://192.168.0.10:8123")
        campo("ha_token", "Token de acesso de longa duração", true)

        texto("Passo a passo:")
        botao("1) Permitir abrir apps por voz") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        botao("2) Tirar o Jarvis da economia de bateria") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        botao("3) Salvar e ativar o Jarvis") { salvarEAtivar() }
        botao("Desligar o Jarvis") { stopService(Intent(this, JarvisService::class.java)) }
    }

    private fun salvarEAtivar() {
        campos.forEach { (k, v) -> Cfg.set(this, k, v.text.toString().trim()) }
        if (Cfg.get(this, "claude_key").isEmpty() || Cfg.get(this, "pico_key").isEmpty()) {
            Toast.makeText(this, "Preencha as duas chaves primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val faltando = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            faltando.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) faltando.add(Manifest.permission.POST_NOTIFICATIONS)

        if (faltando.isEmpty()) iniciar() else requestPermissions(faltando.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) iniciar()
        else Toast.makeText(this, "Sem o microfone o Jarvis não funciona.", Toast.LENGTH_LONG).show()
    }

    private fun iniciar() {
        startForegroundService(Intent(this, JarvisService::class.java))
        Toast.makeText(this, "Jarvis ativo! Diga: Jarvis", Toast.LENGTH_LONG).show()
    }
}
