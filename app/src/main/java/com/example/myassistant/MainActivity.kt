package com.example.myassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var nameField: EditText
    private lateinit var baseField: EditText
    private lateinit var keyField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askPermissions()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setPadding(0, dp(14), 0, dp(4))
        return t
    }

    private fun field(value: String): EditText {
        val e = EditText(this)
        e.setText(value)
        e.setSingleLine()
        return e
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(28))

        val title = TextView(this)
        title.text = "Мой ассистент"
        title.textSize = 24f
        root.addView(title)

        val help = TextView(this)
        help.text = "1) Вставьте ключ из панели FreeLLMAPI (страница Keys).\n" +
            "2) Нажмите «Запустить». При первом запуске разрешите показ поверх других приложений и микрофон.\n" +
            "3) Нажмите на синюю точку на экране и говорите.\n" +
            "Termux с FreeLLMAPI должен быть запущен."
        help.textSize = 14f
        help.setPadding(0, dp(8), 0, dp(8))
        root.addView(help)

        root.addView(label("Имя ассистента"))
        nameField = field(Prefs.name(this))
        root.addView(nameField)

        root.addView(label("Адрес сервера"))
        baseField = field(Prefs.base(this))
        root.addView(baseField)

        root.addView(label("Ключ API"))
        keyField = field(Prefs.key(this))
        root.addView(keyField)

        val save = Button(this)
        save.text = "Сохранить"
        save.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        root.addView(save)

        val start = Button(this)
        start.text = "Запустить"
        start.setOnClickListener { startAssistant() }
        root.addView(start)

        val stop = Button(this)
        stop.text = "Остановить"
        stop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
        }
        root.addView(stop)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun saveSettings() {
        Prefs.save(
            this,
            nameField.text.toString(),
            baseField.text.toString(),
            keyField.text.toString()
        )
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun startAssistant() {
        saveSettings()
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Включите «Поверх других приложений», вернитесь и нажмите «Запустить»",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        Toast.makeText(this, "Ассистент запущен: ищите синюю точку слева", Toast.LENGTH_LONG).show()
    }
}
