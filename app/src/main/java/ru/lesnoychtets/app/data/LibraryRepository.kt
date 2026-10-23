package ru.lesnoychtets.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
 * Библиотека собирается из трёх мест:
 *  1. папка на телефоне, выбранная родственником (через системный выбор папки);
 *  2. копия семейной папки Google Drive;
 *  3. книги, скачанные из каталога LibriVox.
 */
object LibraryRepository {
    private const val TAG = "LibraryRepository"

    private val state = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = state.asStateFlow()
    val scanning = MutableStateFlow(false)

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

    suspend fun rescan(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            scanning.value = true
            try {
                val result = LinkedHashMap<String, Book>()
                AppPrefs.treeUri?.let { LibraryBuilder.build(scanTree(context, Uri.parse(it)), result) }
                LibraryBuilder.build(scanDir(driveDir(context)), result)
                scanLibrivox(librivoxDir(context), result)
                val list = result.values.toList()
                state.value = list
                runCatching { cacheFile.writeText(toJson(list)) }
            } finally {
                scanning.value = false
            }
        }
    }

    private fun scanTree(context: Context, treeUri: Uri): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        val resolver = context.contentResolver
        fun walk(docId: String, dirs: List<String>) {
            if (dirs.size > 6) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val subdirs = mutableListOf<Pair<String, String>>()
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subdirs += id to name
                    } else if (FilenameParser.isAudio(name)) {
                        out += ScannedFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), dirs, name)
                    }
                }
            }
            subdirs.forEach { (id, name) -> walk(id, dirs + name) }
        }
        try {
            walk(DocumentsContract.getTreeDocumentId(treeUri), emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать папку $treeUri", e)
        }
        return out
    }

    private fun scanDir(root: File): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        fun walk(dir: File, dirs: List<String>) {
            if (dirs.size > 6) return
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) walk(f, dirs + f.name)
                else if (FilenameParser.isAudio(f.name)) out += ScannedFile(Uri.fromFile(f).toString(), dirs, f.name)
            }
        }
        if (root.isDirectory) walk(root, emptyList())
        return out
    }

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
            if (tracks.isEmpty()) return@forEach
            val author = meta.optString("author")
            val title = meta.optString("title")
            val year = if (meta.has("year")) meta.optInt("year") else null
            val key = FilenameParser.bookKey(author, year, title)
            if (key !in into) into[key] = Book(key, author, year, title, tracks)
        }
    }

    private fun toJson(books: List<Book>): String = JSONArray().apply {
        books.forEach { b ->
            put(JSONObject().apply {
                put("key", b.key)
                put("author", b.author)
                b.year?.let { put("year", it) }
                put("title", b.title)
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
            )
        }
    }
}
