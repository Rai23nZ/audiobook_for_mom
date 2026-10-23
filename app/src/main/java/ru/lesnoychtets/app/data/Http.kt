package ru.lesnoychtets.app.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val USER_AGENT = "Vslukh/1.0 (Android audiobook player)"

    private fun open(url: String): HttpURLConnection {
        var current = url
        // HttpURLConnection не переходит по редиректам между http и https — делаем это сами.
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                current = URL(URL(current), location).toString()
                c.disconnect()
            } else {
                if (code !in 200..299) {
                    c.disconnect()
                    throw IOException("HTTP $code для $current")
                }
                return c
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    fun getText(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Скачивает файл во временный «.part» и переименовывает только после успеха,
     * чтобы оборванная загрузка не выглядела как готовая книга.
     */
    fun download(url: String, target: File, rejectHtml: Boolean = true, onProgress: (Long) -> Unit = {}) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val c = open(url)
        try {
            if (rejectHtml && c.contentType?.startsWith("text/html") == true) {
                throw IOException("Сервер вернул страницу вместо файла (возможно, нет доступа)")
            }
            c.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total - lastReport > 512 * 1024) {
                            lastReport = total
                            onProgress(total)
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trimStart('.').ifEmpty { "file" }.take(150)
}
