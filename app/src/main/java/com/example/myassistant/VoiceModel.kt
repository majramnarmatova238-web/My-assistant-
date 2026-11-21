package com.example.myassistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Офлайн-модель русской речи (Vosk). Скачивается один раз, потом работает без интернета. */
object VoiceModel {
    private const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val DIR_NAME = "vosk-model-small-ru-0.22"

    fun dir(c: Context): File = File(c.filesDir, DIR_NAME)

    fun isReady(c: Context): Boolean = File(dir(c), ".ok").exists()

    fun download(c: Context, onProgress: (String) -> Unit) {
        val tmp = File(c.cacheDir, "model.zip")
        val conn = URL(ZIP_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        val total = conn.contentLength
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress("Скачивание: $pct%")
                        }
                    }
                }
            }
        }
        conn.disconnect()

        onProgress("Распаковка...")
        val root = c.filesDir
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(rootPath)) throw Exception("плохой архив")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        tmp.delete()
        File(dir(c), ".ok").writeText("ok")
    }
}
