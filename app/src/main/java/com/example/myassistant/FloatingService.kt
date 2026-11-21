package com.example.myassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

class FloatingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var strip: View? = null
    private var panel: View? = null
    private var chat: TextView? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var micButton: Button? = null
    private var voiceModel: Model? = null
    private var listening = false
    @Volatile private var stopRequested = false

    private val history = ArrayList<JSONObject>()
    private val transcript = StringBuilder()
    private var bubbleY = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubbleY = dp(300)
        startAsForeground()
        tts = TextToSpeech(this, this)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        safeRemove(bubble)
        safeRemove(strip)
        safeRemove(panel)
        bubble = null
        strip = null
        panel = null
        stopRequested = true
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("assistant", "Ассистент", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "assistant")
            .setContentTitle("Ассистент работает")
            .setContentText("Нажмите на синюю точку на экране")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n)
    }

    private fun safeRemove(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // уже удалено
        }
    }

    // ---------- Точка ----------

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val v = View(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#E62962FF"))
        bg.setStroke(dp(2), Color.WHITE)
        v.background = bg

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = bubbleY

        v.setOnTouchListener(object : View.OnTouchListener {
            var startY = 0f
            var startParamY = 0
            var moved = false
            var longPressed = false
            val longRunnable = Runnable {
                longPressed = true
                bubbleY = lp.y
                hideBubble()
            }

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = e.rawY
                        startParamY = lp.y
                        moved = false
                        longPressed = false
                        handler.postDelayed(longRunnable, 700)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - startY
                        if (abs(dy) > dp(8)) {
                            moved = true
                            handler.removeCallbacks(longRunnable)
                        }
                        if (moved) {
                            lp.y = startParamY + dy.toInt()
                            bubbleY = lp.y
                            try {
                                wm.updateViewLayout(view, lp)
                            } catch (ex: Exception) {
                                // ignore
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longRunnable)
                        if (!moved && !longPressed) openPanel()
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRunnable)
                }
                return true
            }
        })

        wm.addView(v, lp)
        bubble = v
    }

    private fun hideBubble() {
        safeRemove(bubble)
        bubble = null
        showStrip()
    }

    private fun showStrip() {
        if (strip != null) return
        val v = View(this)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#802962FF"))
        val r = dp(8).toFloat()
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        v.background = bg

        val lp = WindowManager.LayoutParams(
            dp(12),
            dp(110),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = bubbleY

        var startX = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> startX = e.rawX
                MotionEvent.ACTION_MOVE -> {
                    if (e.rawX - startX > dp(40)) restoreFromStrip()
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - startX) < dp(8)) restoreFromStrip()
                }
            }
            true
        }

        wm.addView(v, lp)
        strip = v
    }

    private fun restoreFromStrip() {
        safeRemove(strip)
        strip = null
        showBubble()
    }

    // ---------- Панель ----------

    private fun openPanel() {
        if (panel != null) return
        safeRemove(bubble)
        bubble = null

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(10), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2202124"))
        bg.cornerRadius = dp(16).toFloat()
        root.background = bg

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = Prefs.name(this)
        title.textSize = 18f
        title.setTextColor(Color.WHITE)
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val close = Button(this)
        close.text = "✕"
        close.setOnClickListener { closePanel() }
        header.addView(close)
        root.addView(header)

        val sv = ScrollView(this)
        val ct = TextView(this)
        ct.textSize = 15f
        ct.setTextColor(Color.WHITE)
        ct.setPadding(dp(4), dp(6), dp(4), dp(6))
        ct.text = transcript.toString()
        sv.addView(ct)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        chat = ct
        scroll = sv

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val et = EditText(this)
        et.hint = "Напишите или нажмите микрофон"
        et.setHintTextColor(Color.GRAY)
        et.setTextColor(Color.WHITE)
        et.setSingleLine()
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        input = et

        val mic = Button(this)
        mic.text = "🎤"
        mic.setOnClickListener { listen() }
        row.addView(mic)
        micButton = mic

        val send = Button(this)
        send.text = "➤"
        send.setOnClickListener {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                et.setText("")
                ask(t)
            }
        }
        row.addView(send)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            dp(380),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(40)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        wm.addView(root, lp)
        panel = root

        if (transcript.isEmpty()) {
            append(Prefs.name(this) + ": Привет! Я слушаю.")
        }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun closePanel() {
        tts?.stop()
        stopRequested = true
        recognizer?.cancel()
        safeRemove(panel)
        panel = null
        micButton = null
        chat = null
        scroll = null
        input = null
        showBubble()
    }

    private fun append(line: String) {
        transcript.append(line).append("\n\n")
        chat?.text = transcript.toString()
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Голос ----------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ru", "RU"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.8f)
        }
        if (!ttsReady) {
            append("(Голос не настроен: в настройках телефона установите синтез речи с русским языком.)")
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace("*", "").replace("#", "").replace("`", "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun listen() {
        tts?.stop()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            append("Дайте разрешение на микрофон: откройте приложение «My Assistant» и разрешите.")
            return
        }
        if (VoiceModel.isReady(this)) {
            listenOffline()
        } else {
            append("Голосовая модель не скачана: откройте приложение «My Assistant» и нажмите «Скачать голосовую модель». Пока пробую голос Google.")
            listenGoogle()
        }
    }

    // Офлайн-распознавание (Vosk): не зависит от Google и интернета.
    private fun listenOffline() {
        if (listening) {
            stopRequested = true
            return
        }
        listening = true
        stopRequested = false
        micButton?.text = "⏹"
        input?.hint = "Говорите..."
        val modelPath = VoiceModel.dir(this).absolutePath
        Thread {
            var text = ""
            var error: String? = null
            try {
                val m = voiceModel ?: Model(modelPath).also { voiceModel = it }
                val rec = Recognizer(m, 16000.0f)
                val minBuf = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 8000) * 2
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    rec.close()
                    throw Exception("микрофон недоступен")
                }
                ar.startRecording()
                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (!stopRequested && System.currentTimeMillis() - start < 15000) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (rec.acceptWaveForm(buf, n)) {
                        text = JSONObject(rec.result).optString("text")
                        if (text.isNotBlank()) break
                    } else {
                        val p = JSONObject(rec.partialResult).optString("partial")
                        if (p.isNotBlank()) {
                            handler.post { input?.setText(p) }
                        }
                    }
                }
                ar.stop()
                ar.release()
                if (text.isBlank()) {
                    text = JSONObject(rec.finalResult).optString("text")
                }
                rec.close()
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            handler.post {
                listening = false
                micButton?.text = "🎤"
                input?.setText("")
                input?.hint = "Напишите или нажмите микрофон"
                if (error != null) {
                    append("Ошибка голоса: $error")
                } else if (text.isBlank()) {
                    append("Не расслышал. Попробуйте ещё раз.")
                } else {
                    ask(text)
                }
            }
        }.start()
    }

    // Запасной вариант: голос Google (может не работать на этом телефоне).
    private fun listenGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            append("На телефоне нет службы распознавания речи. Пишите текстом.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                input?.hint = "Говорите..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                input?.hint = "Напишите или нажмите микрофон"
                append("Не расслышал (код $error). Попробуйте ещё раз.")
            }

            override fun onResults(results: Bundle?) {
                input?.hint = "Напишите или нажмите микрофон"
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (!text.isNullOrBlank()) ask(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        r.startListening(i)
    }

    // ---------- Запрос к FreeLLMAPI ----------

    private fun ask(text: String) {
        append("Вы: $text")
        history.add(JSONObject().put("role", "user").put("content", text))
        while (history.size > 12) history.removeAt(0)
        val snapshot = ArrayList(history)
        val assistantName = Prefs.name(this)
        Thread {
            try {
                val reply = callApi(snapshot, assistantName)
                handler.post {
                    history.add(JSONObject().put("role", "assistant").put("content", reply))
                    append("$assistantName: $reply")
                    speak(reply)
                }
            } catch (e: Exception) {
                handler.post {
                    append("Ошибка: ${e.message ?: "нет связи"}. Проверьте, что Termux с FreeLLMAPI запущен.")
                }
            }
        }.start()
    }

    private fun callApi(messages: List<JSONObject>, assistantName: String): String {
        val base = Prefs.base(this).trimEnd('/')
        val key = Prefs.key(this)

        val msgs = JSONArray()
        msgs.put(
            JSONObject().put("role", "system").put(
                "content",
                "Ты голосовой ассистент по имени $assistantName. Отвечай кратко, по-русски, " +
                    "простыми словами, без списков и без markdown."
            )
        )
        for (m in messages) msgs.put(m)

        val body = JSONObject()
        body.put("model", "auto")
        body.put("messages", msgs)

        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw Exception("сервер ответил $code: ${text.take(160)}")
            }
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } finally {
            conn.disconnect()
        }
    }
}
