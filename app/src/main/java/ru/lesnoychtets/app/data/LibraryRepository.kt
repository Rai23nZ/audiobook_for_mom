package ru.lesnoychtets.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Библиотека собирается из двух мест:
 *  1. семейная папка Google Drive (список её файлов хранится в манифесте, поэтому
 *     книга остаётся в списке и после удаления прослушанных файлов с телефона);
 *  2. книги, скачанные из каталога LibriVox.
 */
object LibraryRepository {
    private const val MANIFEST = ".manifest.json"

    private val state = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = state.asStateFlow()

    private val mutex = Mutex()
    private lateinit var cacheFile: File

    fun init(context: Context) {
        if (::cacheFile.isInitialized) return
        cacheFile = File(context.filesDir, "library.json")
        state.value = runCatching { fromJson(cacheFile.readText()) }.getOrDefault(emptyList())
    }

    fun storageRoot(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
    fun driveDir(context: Context): File = File(storageRoot(context), "google_drive")
    fun librivoxDir(context: Context): File = File(storageRoot(context), "librivox")

    fun find(key: String?): Book? = key?.let { k -> state.value.firstOrNull { it.key == k } }

    /** Файлы семейной папки с последней синхронизации: относительный путь -> id файла в Drive. */
    fun readManifest(context: Context): Map<String, String> = runCatching {
        val o = JSONObject(File(driveDir(context), MANIFEST).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun writeManifest(context: Context, files: Map<String, String>) {
        val dir = driveDir(context)
        dir.mkdirs()
        File(dir, MANIFEST).writeText(JSONObject(files).toString())
    }

    /** Книги семейной папки по списку путей (скачанные и нет). */
    fun driveBooks(context: Context, paths: Collection<String>): List<Book> {
        val root = driveDir(context)
        val files = paths.map { rel ->
            val parts = rel.split('/')
            ScannedFile(Uri.fromFile(File(root, rel)).toString(), parts.dropLast(1), parts.last())
        }
        return LibraryBuilder.build(files).values.toList()
    }

    suspend fun rescan(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = LinkedHashMap<String, Book>()
            val root = driveDir(context)
            val paths = (readManifest(context).keys + localDriveFiles(root)).toSortedSet()
            driveBooks(context, paths).forEach { b ->
                result.putIfAbsent(b.key, b.copy(downloaded = b.tracks.all { fileOf(it).let { f -> f.isFile && f.length() > 0 } }))
            }
            scanLibrivox(librivoxDir(context), result)
            val list = result.values.toList()
            state.value = list
            runCatching { cacheFile.writeText(toJson(list)) }
        }
    }

    /**
     * Удаляет из телефона файлы книг, дослушанных до конца не сегодня.
     * Сама книга остаётся в списке с меткой «ПРОСЛУШАНО».
     */
    suspend fun removeListened(context: Context) = withContext(Dispatchers.IO) {
        val today = ProgressStore.todayStart()
        var removed = false
        state.value.filter { it.downloaded && ProgressStore.get(it.key)?.finishedBefore(today) == true }.forEach { book ->
            book.tracks.forEach { t -> if (fileOf(t).delete()) removed = true }
        }
        if (removed) {
            driveDir(context).walkBottomUp().forEach { f ->
                if (f.isDirectory && f != driveDir(context) && f.list()?.isEmpty() == true) f.delete()
            }
            rescan(context)
        }
    }

    private fun fileOf(t: Track): File = File(Uri.parse(t.uri).path ?: "")

    private fun localDriveFiles(root: File): List<String> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown()
            .filter { it.isFile && FilenameParser.isAudio(it.name) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

    /** Каждая подпапка LibriVox — одна книга, её описание лежит в book.json. */
    private fun scanLibrivox(root: File, into: MutableMap<String, Book>) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val meta = runCatching { JSONObject(File(dir, LibrivoxDownloader.META_FILE).readText()) }.getOrNull()
                ?: return@forEach
            val tracks = dir.listFiles()
                ?.filter { FilenameParser.isAudio(it.name) }
                ?.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                ?.map { Track(Uri.fromFile(it).toString(), it.name) }
                .orEmpty()
            val author = meta.optString("author")
            val title = meta.optString("title")
            val year = if (meta.has("year")) meta.optInt("year") else null
            val key = FilenameParser.bookKey(author, year, title)
            val complete = tracks.isNotEmpty() && meta.optBoolean("complete", true)
            into.putIfAbsent(key, Book(key, author, year, title, if (complete) tracks else emptyList(), complete, meta.optString("id")))
        }
    }

    private fun toJson(books: List<Book>): String = JSONArray().apply {
        books.forEach { b ->
            put(JSONObject().apply {
                put("key", b.key)
                put("author", b.author)
                b.year?.let { put("year", it) }
                put("title", b.title)
                put("downloaded", b.downloaded)
                b.librivoxId?.let { put("librivox", it) }
                put("tracks", JSONArray().apply {
                    b.tracks.forEach { t -> put(JSONObject().put("uri", t.uri).put("name", t.name)) }
                })
            })
        }
    }.toString()

    private fun fromJson(s: String): List<Book> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tracks = o.getJSONArray("tracks")
            Book(
                key = o.getString("key"),
                author = o.getString("author"),
                year = if (o.has("year")) o.getInt("year") else null,
                title = o.getString("title"),
                tracks = (0 until tracks.length()).map { j ->
                    val t = tracks.getJSONObject(j)
                    Track(t.getString("uri"), t.getString("name"))
                },
                downloaded = o.optBoolean("downloaded", true),
                librivoxId = o.optString("librivox").ifEmpty { null },
            )
        }
    }
}
