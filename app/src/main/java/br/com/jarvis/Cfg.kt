package br.com.jarvis

import android.content.Context
import org.json.JSONArray

/** Configurações e memória do Jarvis (guardadas só no seu celular). */
object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun get(c: Context, k: String): String = p(c).getString(k, "") ?: ""
    fun set(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }

    fun memorias(c: Context): List<String> {
        val arr = JSONArray(get(c, "memoria").ifEmpty { "[]" })
        return List(arr.length()) { arr.getString(it) }
    }

    fun addMemoria(c: Context, texto: String) {
        val lista = (memorias(c) + texto).takeLast(40)
        set(c, "memoria", JSONArray(lista).toString())
    }
}
