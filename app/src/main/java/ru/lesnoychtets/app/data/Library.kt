package ru.lesnoychtets.app.data

/** Один аудиофайл книги (глава или вся книга целиком). */
data class Track(val uri: String, val name: String)

/** Книга или стихотворение: один файл либо папка с главами. */
data class Book(
    val key: String,
    val author: String,
    val year: Int?,
    val title: String,
    val tracks: List<Track>,
)

/** Найденный при сканировании аудиофайл и путь папок до него от корня источника. */
data class ScannedFile(val uri: String, val dirs: List<String>, val name: String)

object FilenameParser {
    const val UNKNOWN_AUTHOR = "Разное"

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "ogg", "oga", "opus", "aac", "wav", "flac", "awb")

    private const val SEP = """\s*[-—–]\s*"""
    private const val YEAR = """(1[0-9]\d{2}|20\d{2})"""
    private val FULL = Regex("""^(.+?)$SEP$YEAR$SEP(.+)$""")
    private val YEAR_TITLE = Regex("""^$YEAR$SEP(.+)$""")
    private val AUTHOR_TITLE = Regex("""^(.+?)\s+[-—–]\s+(.+)$""")
    private val ANY_YEAR = Regex("""(?<!\d)$YEAR(?!\d)""")
    private val SPACES = Regex("""\s+""")

    data class Parsed(val author: String, val year: Int?, val title: String)

    fun isAudio(fileName: String): Boolean =
        !fileName.startsWith(".") && fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    fun stripExtension(fileName: String): String =
        if ('.' in fileName) fileName.substringBeforeLast('.') else fileName

    fun clean(s: String): String = s.replace('_', ' ').replace(SPACES, " ").trim()

    private fun findYear(s: String): Int? = ANY_YEAR.find(s)?.groupValues?.get(1)?.toInt()

    /**
     * Разбирает имя аудиофайла. Главный шаблон — «Писатель - Год - Название.mp3».
     * Если файл лежит в папке писателя, подходит и «Год - Название.mp3» или просто «Название.mp3».
     */
    fun parseFile(fileName: String, parentDir: String?): Parsed {
        val name = clean(stripExtension(fileName))
        FULL.matchEntire(name)?.let { m ->
            return Parsed(clean(m.groupValues[1]), m.groupValues[2].toInt(), clean(m.groupValues[3]))
        }
        if (parentDir != null) {
            val author = clean(parentDir)
            YEAR_TITLE.matchEntire(name)?.let { m ->
                return Parsed(author, m.groupValues[1].toInt(), clean(m.groupValues[2]))
            }
            return Parsed(author, findYear(name), name)
        }
        YEAR_TITLE.matchEntire(name)?.let { m ->
            return Parsed(UNKNOWN_AUTHOR, m.groupValues[1].toInt(), clean(m.groupValues[2]))
        }
        AUTHOR_TITLE.matchEntire(name)?.let { m ->
            val title = clean(m.groupValues[2])
            return Parsed(clean(m.groupValues[1]), findYear(title), title)
        }
        return Parsed(UNKNOWN_AUTHOR, findYear(name), name)
    }

    /**
     * Папка-книга с главами: «Писатель - Год - Название/» или «Писатель/Год - Название/».
     * Возвращает null, если имя папки не похоже на книгу.
     */
    fun parseBookFolder(dirName: String, parentDir: String?): Parsed? {
        val name = clean(dirName)
        FULL.matchEntire(name)?.let { m ->
            return Parsed(clean(m.groupValues[1]), m.groupValues[2].toInt(), clean(m.groupValues[3]))
        }
        if (parentDir != null) {
            YEAR_TITLE.matchEntire(name)?.let { m ->
                return Parsed(clean(parentDir), m.groupValues[1].toInt(), clean(m.groupValues[2]))
            }
        }
        return null
    }

    fun bookKey(author: String, year: Int?, title: String): String =
        "${author.lowercase()}|${year ?: ""}|${title.lowercase()}"
}

/** Сравнение «по-человечески»: «2.mp3» раньше «10.mp3». */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val ca = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val cb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (r != 0) return r
        }
        return ca.size - cb.size
    }
}

object LibraryBuilder {
    /** Собирает книги из найденных файлов. Повторы (одна книга в двух источниках) отбрасываются. */
    fun build(files: List<ScannedFile>, into: MutableMap<String, Book> = LinkedHashMap()): MutableMap<String, Book> {
        files.filter { FilenameParser.isAudio(it.name) }
            .groupBy { it.dirs }
            .forEach { (dirs, group) ->
                val dirName = dirs.lastOrNull()
                val parent = dirs.getOrNull(dirs.size - 2)
                val folderBook = dirName?.let { FilenameParser.parseBookFolder(it, parent) }
                if (folderBook != null && group.size > 1) {
                    add(into, folderBook, group.sortedWith { x, y -> NaturalOrder.compare(x.name, y.name) })
                } else if (folderBook != null) {
                    add(into, folderBook, group)
                } else {
                    group.forEach { f -> add(into, FilenameParser.parseFile(f.name, dirName), listOf(f)) }
                }
            }
        return into
    }

    private fun add(into: MutableMap<String, Book>, p: FilenameParser.Parsed, files: List<ScannedFile>) {
        val key = FilenameParser.bookKey(p.author, p.year, p.title)
        if (key in into) return
        into[key] = Book(key, p.author, p.year, p.title, files.map { Track(it.uri, it.name) })
    }

    /** Порядок книг писателя: по году, книги без года — в конце. */
    val byYear: Comparator<Book> = compareBy<Book>({ it.year == null }, { it.year ?: 0 })
        .thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) }
}
