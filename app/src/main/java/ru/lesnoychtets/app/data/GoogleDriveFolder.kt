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

    /** Результат синхронизации (для журнала). */
    data class SyncResult(val total: Int, val downloaded: Int, val removed: Int)

    /**
     * Повторяет содержимое семейной папки в памяти телефона: докачивает новые аудиофайлы
     * и удаляет те, что убрали из облака. Книги, дослушанные не сегодня, заново не скачиваются.
     * Если список получить не удалось, бросается ошибка и ничего не удаляется.
     */
    fun sync(context: Context, folderId: String = FAMILY_FOLDER_ID): SyncResult {
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
        LibraryRepository.writeManifest(context, remote.keys)

        val root = LibraryRepository.driveDir(context)
        val today = ProgressStore.todayStart()
        val skip = LibraryRepository.driveBooks(context, remote.keys)
            .filter { ProgressStore.get(it.key)?.finishedBefore(today) == true }
            .flatMap { b -> b.tracks.map { Uri.parse(it.uri).path } }
            .toSet()

        var downloaded = 0
        remote.forEach { (path, id) ->
            val target = File(root, path)
            if (target.path in skip || (target.exists() && target.length() > 0)) return@forEach
            Http.download(downloadUrl(id), target)
            downloaded++
        }

        var removed = 0
        root.walkBottomUp().forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (f.isFile && (f.name.endsWith(".part") || (FilenameParser.isAudio(f.name) && rel !in remote))) {
                if (f.delete() && !f.name.endsWith(".part")) removed++
            } else if (f.isDirectory && f != root && f.list()?.isEmpty() == true) {
                f.delete()
            }
        }
        return SyncResult(remote.size, downloaded, removed)
    }
}
