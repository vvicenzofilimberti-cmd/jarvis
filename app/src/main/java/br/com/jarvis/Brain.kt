package br.com.jarvis

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

const val MODELO = "claude-haiku-5-5" // rápido, ideal para voz. Troque por claude-sonnet-5-5 se quiser mais "inteligência".

/** O cérebro: manda o que você falou para o Claude e executa as ferramentas que ele pedir. */
class Brain(private val ctx: Context) {
    private val actions = Actions(ctx)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val historico = mutableListOf<JSONObject>()

    fun ask(texto: String): String {
        val msgs = JSONArray()
        historico.forEach { msgs.put(it) }
        msgs.put(JSONObject().put("role", "user").put("content", texto))
        var resposta = ""

        for (rodada in 0 until 8) {
            val corpo = JSONObject()
                .put("model", MODELO)
                .put("max_tokens", 600)
                .put("system", sistema())
                .put("tools", tools)
                .put("messages", msgs)
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", Cfg.get(ctx, "claude_key").trim())
                .header("anthropic-version", "2023-06-01")
                .post(corpo.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val bruto = http.newCall(req).execute().use { it.body?.string() ?: "" }
            val j = JSONObject(bruto)

            if (j.has("error")) {
                val e = j.getJSONObject("error")
                return if (e.optString("type") == "authentication_error")
                    "A chave da API parece inválida. Confira nas configurações."
                else "Erro na API: " + e.optString("message")
            }

            val conteudo = j.getJSONArray("content")
            msgs.put(JSONObject().put("role", "assistant").put("content", conteudo))

            when (j.optString("stop_reason")) {
                "tool_use" -> {
                    val resultados = JSONArray()
                    for (k in 0 until conteudo.length()) {
                        val b = conteudo.getJSONObject(k)
                        if (b.getString("type") == "tool_use") {
                            val saida = actions.executar(b.getString("name"), b.getJSONObject("input"))
                            resultados.put(
                                JSONObject().put("type", "tool_result")
                                    .put("tool_use_id", b.getString("id")).put("content", saida)
                            )
                        }
                    }
                    msgs.put(JSONObject().put("role", "user").put("content", resultados))
                }
                "pause_turn" -> { /* pesquisa na web em andamento: continua */ }
                else -> {
                    val sb = StringBuilder()
                    for (k in 0 until conteudo.length()) {
                        val b = conteudo.getJSONObject(k)
                        if (b.getString("type") == "text") sb.append(b.getString("text"))
                    }
                    resposta = sb.toString().trim()
                    break
                }
            }
        }

        if (resposta.isEmpty()) resposta = "Pronto."
        historico.add(JSONObject().put("role", "user").put("content", texto))
        historico.add(JSONObject().put("role", "assistant").put("content", resposta))
        while (historico.size > 20) historico.removeAt(0)
        return resposta
    }

    private fun sistema(): String {
        val nome = Cfg.get(ctx, "nome").ifEmpty { "senhor" }
        val memoria = Cfg.memorias(ctx).joinToString("\n") { "- $it" }.ifEmpty { "(nada ainda)" }
        val agora = SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
        return """Você é o JARVIS, o assistente de IA inspirado no do Homem de Ferro, rodando no celular Android de $nome.
Personalidade: educado, eficiente, humor sutil e sofisticado. Chame o usuário de "$nome". Pode discordar com respeito e avisar sobre riscos.
Suas respostas serão FALADAS em voz alta: português do Brasil, no máximo 2 ou 3 frases curtas, sem markdown, listas, emojis ou links.
Aja em vez de explicar: se o usuário pedir algo que uma ferramenta resolve, use a ferramenta e confirme em uma frase.
Você pode usar várias ferramentas em sequência para cumprir um objetivo (por exemplo, abrir um app e ajustar o volume).
Para pausar ou continuar YouTube, Spotify e outros, use controlar_midia.
Para fatos atuais (notícias, clima, placar, preços) use a pesquisa na web.
Se o usuário contar algo que vale lembrar (preferências, nomes, rotinas), use salvar_memoria.
Se uma ação for arriscada ou irreversível, peça confirmação antes.
Se algo falhar, diga com honestidade o que aconteceu.

Data e hora agora: $agora.
O que você já sabe sobre o usuário:
$memoria"""
    }

    // ---------- definição das ferramentas que o Claude pode usar ----------
    private fun s(desc: String, vararg opcoes: String): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
        if (opcoes.isNotEmpty()) o.put("enum", JSONArray(opcoes.toList()))
        return o
    }
    private fun n(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun b(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun tool(nome: String, desc: String, props: JSONObject, obrig: List<String>) =
        JSONObject().put("name", nome).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(obrig))
        )

    private val tools: JSONArray = JSONArray().apply {
        put(tool("controlar_midia", "Controla a mídia que está tocando no celular (YouTube, Spotify, etc.).",
            JSONObject().put("acao", s("Ação", "pausar", "continuar", "alternar", "proxima", "anterior")), listOf("acao")))
        put(tool("volume", "Ajusta o volume de mídia do celular.",
            JSONObject().put("acao", s("Ação", "aumentar", "diminuir", "mudo", "definir"))
                .put("nivel", n("0 a 100, apenas para 'definir'")), listOf("acao")))
        put(tool("abrir_app", "Abre um aplicativo instalado pelo nome (ex: YouTube, WhatsApp, Câmera).",
            JSONObject().put("nome", s("Nome do app")), listOf("nome")))
        put(tool("abrir_link", "Abre um link, busca, rota de mapa ou ligação. Aceita https:, geo:, tel:, mailto:, sms:. " +
            "Ex: https://www.youtube.com/results?search_query=termo ; https://www.google.com/maps/search/?api=1&query=padaria ; tel:+5549999999999",
            JSONObject().put("url", s("Endereço")), listOf("url")))
        put(tool("lanterna", "Liga ou desliga a lanterna.",
            JSONObject().put("ligar", b("true para ligar, false para desligar")), listOf("ligar")))
        put(tool("alarme", "Cria um alarme no relógio do celular.",
            JSONObject().put("hora", n("0 a 23")).put("minuto", n("0 a 59")).put("rotulo", s("Nome do alarme")),
            listOf("hora", "minuto")))
        put(tool("timer", "Inicia um cronômetro regressivo.",
            JSONObject().put("segundos", n("Duração em segundos")), listOf("segundos")))
        put(tool("status_celular", "Informa bateria, volume e se há mídia tocando.", JSONObject(), listOf()))
        put(tool("salvar_memoria", "Guarda uma informação útil sobre o usuário para lembrar em conversas futuras.",
            JSONObject().put("texto", s("O que lembrar")), listOf("texto")))
        put(tool("casa_inteligente", "Controla um dispositivo da casa via Home Assistant (luz, tomada, ar-condicionado...). " +
            "Ex: dominio=light, servico=turn_on, entidade=light.sala",
            JSONObject().put("dominio", s("Domínio, ex: light, switch, climate"))
                .put("servico", s("Serviço, ex: turn_on, turn_off, toggle"))
                .put("entidade", s("entity_id, ex: light.sala")), listOf("dominio", "servico", "entidade")))
        // Pesquisa na web feita pela própria API da Anthropic
        put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
    }
}
