package ru.lesnoychtets.app.data

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Чтение публичной папки Google Drive («Доступ: все, у кого есть ссылка») без ключей и входа в аккаунт.
 * Список файлов берётся со встраиваемой страницы папки, файлы — по прямой ссылке скачивания.
 */
object GoogleDriveFolder {
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

    fun list(folderId: String): List<Entry> =
        parseListing(Http.getText("https://drive.google.com/embeddedfolderview?id=$folderId#list"))

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

    /** Результат синхронизации для сообщения родственнику. */
    data class SyncResult(val total: Int, val downloaded: Int, val removed: Int)

    /**
     * Повторяет содержимое папки Drive в памяти телефона: докачивает новые аудиофайлы
     * и удаляет те, что убрали из облака. Если список пуст (нет доступа, ошибка), ничего не удаляет.
     */
    fun sync(context: Context, link: String, onProgress: (String) -> Unit): SyncResult {
        val rootId = extractFolderId(link) ?: throw IOException("Не похоже на ссылку на папку Google Drive")
        val remote = LinkedHashMap<String, String>() // относительный путь -> id файла
        fun collect(folderId: String, prefix: String, depth: Int) {
            list(folderId).forEach { e ->
                val safe = Http.safeFileName(e.name)
                if (e.isFolder) {
                    if (depth < 4) collect(e.id, "$prefix$safe/", depth + 1)
                } else if (FilenameParser.isAudio(e.name)) {
                    remote["$prefix$safe"] = e.id
                }
            }
        }
        collect(rootId, "", 0)
        if (remote.isEmpty()) {
            throw IOException("В папке не найдено аудиофайлов. Проверьте, что доступ открыт «всем, у кого есть ссылка».")
        }

        val root = LibraryRepository.driveDir(context)
        var downloaded = 0
        remote.entries.forEachIndexed { i, (path, id) ->
            val target = File(root, path)
            if (target.exists() && target.length() > 0) return@forEachIndexed
            onProgress("Скачивание ${i + 1} из ${remote.size}: ${target.name}")
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
