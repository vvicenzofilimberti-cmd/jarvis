package br.com.jarvis

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Serviço que fica ligado em segundo plano:
 *  1) escuta SÓ a palavra "Jarvis" (offline, via Porcupine)
 *  2) apita e escuta o seu comando (reconhecimento de voz do Android)
 *  3) manda para o Claude, que pensa e executa as ações
 *  4) responde falando e volta a esperar a palavra "Jarvis"
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var porcupine: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var aoTerminar: (() -> Unit)? = null
    private var brain: Brain? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "PARAR") {
            stopSelf()
            return START_NOT_STICKY
        }
        primeiroPlano()
        if (brain == null) {
            brain = Brain(this)
            tts = TextToSpeech(this, this)
            iniciarPalavraChave()
        }
        return START_NOT_STICKY
    }

    private fun primeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(
            this, 0, Intent(this, JarvisService::class.java).setAction("PARAR"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis ativo")
            .setContentText("Diga \"Jarvis\" para me chamar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Desligar", parar).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // ---------- 1) palavra de ativação ----------
    private fun iniciarPalavraChave() {
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(Cfg.get(this, "pico_key"))
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(this, PorcupineManagerCallback { main.post { aoDetectar() } })
            porcupine?.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar a escuta: " + e.message, Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun aoDetectar() {
        try { porcupine?.stop() } catch (e: Exception) { }
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90).startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (e: Exception) { }
        main.postDelayed({ ouvir() }, 350)
    }

    private fun retomar() {
        try { porcupine?.start() } catch (e: Exception) { }
    }

    // ---------- 2) escutar o comando ----------
    private fun ouvir() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            falar("O reconhecimento de voz não está disponível neste aparelho.") { retomar() }
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { retomar() }
            override fun onResults(results: Bundle?) {
                val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (txt.isNullOrBlank()) retomar() else pensar(txt)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        r.startListening(i)
    }

    // ---------- 3) pensar e agir ----------
    private fun pensar(txt: String) {
        val t = txt.lowercase(Locale("pt", "BR"))
        if (listOf("tchau", "pode descansar", "desligar jarvis").any { t.contains(it) }) {
            falar("Até logo.") { stopSelf() }
            return
        }
        worker.execute {
            val r = try {
                brain?.ask(txt) ?: "Ainda estou iniciando."
            } catch (e: Exception) {
                "Tive um problema de conexão."
            }
            main.post { falar(r) { retomar() } }
        }
    }

    // ---------- 4) falar ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("pt", "BR")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { fim() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { fim() }
        })
        falar("Sistemas online. Diga Jarvis quando precisar.") { }
    }

    private fun fim() {
        main.post {
            val cb = aoTerminar
            aoTerminar = null
            cb?.invoke()
        }
    }

    private fun falar(texto: String, depois: () -> Unit) {
        val voz = tts
        if (voz == null) { depois(); return }
        aoTerminar = depois
        voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    override fun onDestroy() {
        try { porcupine?.stop(); porcupine?.delete() } catch (e: Exception) { }
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
