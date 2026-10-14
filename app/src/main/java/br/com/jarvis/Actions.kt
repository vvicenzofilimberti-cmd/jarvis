package br.com.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.view.KeyEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Tudo o que o Jarvis consegue FAZER no celular. Cada função vira uma "ferramenta" do Claude. */
class Actions(private val ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val http = OkHttpClient()

    fun executar(nome: String, a: JSONObject): String = try {
        when (nome) {
            "controlar_midia" -> midia(a.getString("acao"))
            "volume" -> volume(a.getString("acao"), a.optInt("nivel", 50))
            "abrir_app" -> abrirApp(a.getString("nome"))
            "abrir_link" -> abrirLink(a.getString("url"))
            "lanterna" -> lanterna(a.getBoolean("ligar"))
            "alarme" -> alarme(a.getInt("hora"), a.getInt("minuto"), a.optString("rotulo", "Jarvis"))
            "timer" -> timer(a.getInt("segundos"))
            "status_celular" -> status()
            "salvar_memoria" -> { Cfg.addMemoria(ctx, a.getString("texto")); "Anotado." }
            "casa_inteligente" -> casa(a.getString("dominio"), a.getString("servico"), a.getString("entidade"))
            else -> "Ferramenta desconhecida."
        }
    } catch (e: Exception) {
        "Erro: ${e.message}"
    }

    // Pausar, continuar, próxima... funciona com YouTube, Spotify, etc.
    private fun midia(acao: String): String {
        val code = when (acao) {
            "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "continuar" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "alternar" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "proxima" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "Ação de mídia inválida."
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Comando de mídia enviado."
    }

    private fun volume(acao: String, nivel: Int): String {
        val s = AudioManager.STREAM_MUSIC
        val ui = AudioManager.FLAG_SHOW_UI
        val max = audio.getStreamMaxVolume(s)
        when (acao) {
            "aumentar" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui)
            "diminuir" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui)
            "mudo" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, ui)
            "definir" -> audio.setStreamVolume(s, nivel.coerceIn(0, 100) * max / 100, ui)
            else -> return "Ação de volume inválida."
        }
        return "Volume ajustado."
    }

    private fun abrirApp(nome: String): String {
        val pm = ctx.packageManager
        val filtro = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(filtro, 0)
        val alvo = apps.firstOrNull { it.loadLabel(pm).toString().equals(nome, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(nome, true) }
            ?: return "Não encontrei o app $nome."
        val i = pm.getLaunchIntentForPackage(alvo.activityInfo.packageName)
            ?: return "Não consegui abrir $nome."
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "$nome aberto."
    }

    private fun abrirLink(url: String): String {
        if (!Regex("^(https?|geo|tel|mailto|sms):", RegexOption.IGNORE_CASE).containsMatchIn(url))
            return "Endereço não permitido."
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Aberto."
    }

    private fun lanterna(ligar: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este aparelho não tem lanterna."
        cm.setTorchMode(id, ligar)
        return if (ligar) "Lanterna ligada." else "Lanterna desligada."
    }

    private fun alarme(hora: Int, minuto: Int, rotulo: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hora)
            .putExtra(AlarmClock.EXTRA_MINUTES, minuto)
            .putExtra(AlarmClock.EXTRA_MESSAGE, rotulo)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Alarme criado para %02d:%02d.".format(hora, minuto)
    }

    private fun timer(segundos: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, segundos)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Timer de $segundos segundos iniciado."
    }

    private fun status(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val carga = if (bm.isCharging) "carregando" else "na bateria"
        val midia = if (audio.isMusicActive) "Há mídia tocando." else "Nenhuma mídia tocando."
        return "Bateria $nivel%, $carga. Volume $vol%. $midia"
    }

    // Casa inteligente via Home Assistant (opcional)
    private fun casa(dominio: String, servico: String, entidade: String): String {
        val url = Cfg.get(ctx, "ha_url").trimEnd('/')
        val token = Cfg.get(ctx, "ha_token")
        if (url.isEmpty() || token.isEmpty()) return "O Home Assistant não está configurado."
        val ok = Regex("[a-z_]+")
        if (!ok.matches(dominio) || !ok.matches(servico)) return "Nome de serviço inválido."
        val corpo = JSONObject().put("entity_id", entidade).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$url/api/services/$dominio/$servico")
            .header("Authorization", "Bearer $token").post(corpo).build()
        return http.newCall(req).execute().use { r ->
            if (r.isSuccessful) "Feito." else "Falhou (código ${r.code})."
        }
    }
}
