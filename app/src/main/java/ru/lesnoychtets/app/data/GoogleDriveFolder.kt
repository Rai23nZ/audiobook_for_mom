package ru.lesnoychtets.app.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Чтение публичной папки Google Drive («Доступ: все, у кого есть ссылка») без ключей и входа в аккаунт.
 * Список файлов берётся со встраиваемой страницы папки, файлы — по прямой ссылке скачивания.
 */
object GoogleDriveFolder {
    /** Семейная папка «КнигиДляМамы» — вшита в приложение, поменять её нельзя. */
    const val FAMILY_FOLDER_ID = "1v4YiyBAaBq5ptGEqFQty0S0RDrFY1m4a"

    data class Entry(val id: String, val name: String, val isFolder: Boolean)

    private val FOLDER_IN_LINK = Regex("""folders/([A-Za-z0-9_-]{10,})""")
    private val ID_PARAM = Regex("""[?&]id=([A-Za-z0-9_-]{10,})""")
    private val BARE_ID = Regex("""^[A-Za-z0-9_-]{10,}$""")
    private val ENTRY_ID = Regex("""id="entry-([A-Za-z0-9_-]+)"""")
    private val ENTRY_TITLE = Regex("""class="flip-entry-title"[^>]*>([^<]*)<""")

    fun extractFolderId(link: String): String? {
        val s = link.trim()
        FOLDER_IN_LINK.find(s)?.let { return it.groupValues[1] }
        ID_PARAM.find(s)?.let { return it.groupValues[1] }
        return if (BARE_ID.matches(s)) s else null
    }

    fun parseListing(html: String): List<Entry> =
        html.split("class=\"flip-entry\"").drop(1).mapNotNull { chunk ->
            val id = ENTRY_ID.find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = ENTRY_TITLE.find(chunk)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                ?: return@mapNotNull null
            Entry(id, name, chunk.contains("/folders/$id") || chunk.contains("/drive/folders/"))
        }

    /** Если вместо списка пришла другая страница (нет доступа, сбой), бросаем ошибку, а не «пустую папку». */
    fun list(folderId: String): List<Entry> {
        val html = Http.getText("https://drive.google.com/embeddedfolderview?id=$folderId")
        if (!html.contains("flip-entries")) throw IOException("Google Drive не отдал список папки")
        return parseListing(html)
    }

    fun downloadUrl(fileId: String) =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    private fun decodeEntities(s: String): String =
        s.replace(Regex("""&#(x?)([0-9A-Fa-f]+);""")) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toInt(16) else m.groupValues[2].toInt()
            String(Character.toChars(code))
        }
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    /**
     * Обновляет список книг семейной папки. Сами файлы не скачиваются — только по кнопке
     * (см. [downloadBook]). Файлы, убранные из облака, удаляются и с телефона.
     * Если список получить не удалось, бросается ошибка и ничего не удаляется.
     * Возвращает число аудиофайлов в папке.
     */
    fun refresh(context: Context, folderId: String = FAMILY_FOLDER_ID): Int {
        val remote = LinkedHashMap<String, String>() // относительный путь -> id файла
        fun collect(id: String, prefix: String, depth: Int) {
            list(id).forEach { e ->
                val safe = Http.safeFileName(e.name)
                if (e.isFolder) {
                    if (depth < 4) collect(e.id, "$prefix$safe/", depth + 1)
                } else if (FilenameParser.isAudio(e.name)) {
                    remote["$prefix$safe"] = e.id
                }
            }
        }
        collect(folderId, "", 0)
        LibraryRepository.writeManifest(context, remote)

        val root = LibraryRepository.driveDir(context)
        root.walkBottomUp().forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (f.isFile && (f.name.endsWith(".part") || (FilenameParser.isAudio(f.name) && rel !in remote))) {
                f.delete()
            } else if (f.isDirectory && f != root && f.list()?.isEmpty() == true) {
                f.delete()
            }
        }
        return remote.size
    }

    /** Скачивает в телефон все файлы одной книги из семейной папки. */
    fun downloadBook(context: Context, book: Book, onProgress: (done: Int, total: Int) -> Unit) {
        val root = LibraryRepository.driveDir(context)
        val manifest = LibraryRepository.readManifest(context)
        book.tracks.forEachIndexed { i, t ->
            onProgress(i, book.tracks.size)
            val target = File(Uri.parse(t.uri).path ?: return@forEachIndexed)
            if (target.exists() && target.length() > 0) return@forEachIndexed
            val id = manifest[target.relativeTo(root).invariantSeparatorsPath]
                ?: throw IOException("Файл ${target.name} больше нет в папке")
            Http.download(downloadUrl(id), target)
        }
        onProgress(book.tracks.size, book.tracks.size)
    }
}
