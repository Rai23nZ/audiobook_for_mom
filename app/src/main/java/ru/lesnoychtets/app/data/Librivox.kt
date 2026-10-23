package ru.lesnoychtets.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Книга из бесплатного каталога LibriVox (общественное достояние, хранится в Internet Archive). */
data class CatalogBook(
    val id: String,
    val author: String,
    val title: String,
    val year: Int?,
) {
    val key: String get() = FilenameParser.bookKey(author, year, title)
}

/**
 * Каталог русскоязычных записей LibriVox. Список берётся из поиска Internet Archive
 * (коллекция librivoxaudio, язык — русский). В приложение вшит снимок каталога,
 * поэтому он открывается и без интернета; при наличии сети список обновляется.
 */
object LibrivoxCatalog {
    private const val SEARCH_URL = "https://archive.org/advancedsearch.php" +
        "?q=collection%3Alibrivoxaudio+AND+language%3A%28rus+OR+Russian%29" +
        "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=1000&output=json"
    private const val ASSET = "librivox_ru.json"

    private val state = MutableStateFlow<List<CatalogBook>>(emptyList())
    val books: StateFlow<List<CatalogBook>> = state.asStateFlow()
    val loading = MutableStateFlow(false)

    private lateinit var cacheFile: File

    fun init(context: Context) {
        if (::cacheFile.isInitialized) return
        cacheFile = File(context.filesDir, "librivox_catalog.json")
        val raw = runCatching { cacheFile.readText() }.getOrNull()
            ?: runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        state.value = raw?.let { runCatching { parseSearchResponse(it) }.getOrNull() }.orEmpty()
    }

    /** Обновляет список из интернета. Возвращает false, если сеть недоступна. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        loading.value = true
        try {
            val raw = Http.getText(SEARCH_URL)
            val parsed = parseSearchResponse(raw)
            if (parsed.isNotEmpty()) {
                state.value = parsed
                runCatching { cacheFile.writeText(raw) }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            loading.value = false
        }
    }

    fun parseSearchResponse(raw: String): List<CatalogBook> {
        val docs = JSONObject(raw).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.getString("identifier")
            val creator = when (val c = d.opt("creator")) {
                is JSONArray -> (0 until c.length()).map { c.getString(it) }
                is String -> listOf(c)
                else -> emptyList()
            }
            CatalogBook(
                id = id,
                author = RussianNames.author(creator),
                title = RussianNames.title(id, d.optString("title")),
                year = RussianNames.YEARS[id],
            )
        }.sortedWith(compareBy<CatalogBook> { it.author }.thenBy { it.year ?: 9999 }.thenBy { it.title })
    }
}

/** Скачивание книги LibriVox: список MP3 берётся из метаданных Internet Archive. */
object LibrivoxDownloader {
    const val META_FILE = "book.json"

    data class RemoteFile(val name: String, val size: Long)

    fun bookDir(context: Context, id: String) = File(LibraryRepository.librivoxDir(context), Http.safeFileName(id))

    fun isDownloaded(context: Context, id: String) = File(bookDir(context, id), META_FILE).exists()

    /** Из всех форматов выбираем стандартный для LibriVox «64Kbps MP3» — он компактный и годится для речи. */
    fun pickMp3(filesJson: String): List<RemoteFile> {
        val arr = JSONObject(filesJson).getJSONArray("result")
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        fun select(pred: (String) -> Boolean) = all.filter { pred(it.optString("format")) }
            .map { RemoteFile(it.getString("name"), it.optString("size").toLongOrNull() ?: 0L) }
            .filter { FilenameParser.isAudio(it.name) && '/' !in it.name }
        val files = select { it == "64Kbps MP3" }.ifEmpty { select { it.contains("MP3", ignoreCase = true) } }
        return files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    fun download(context: Context, book: CatalogBook, onProgress: (done: Int, total: Int) -> Unit) {
        val files = pickMp3(Http.getText("https://archive.org/metadata/${book.id}/files"))
        require(files.isNotEmpty()) { "В записи нет MP3-файлов" }
        val dir = bookDir(context, book.id)
        dir.mkdirs()
        files.forEachIndexed { i, f ->
            onProgress(i, files.size)
            val target = File(dir, Http.safeFileName(f.name))
            if (target.exists() && (f.size == 0L || target.length() == f.size)) return@forEachIndexed
            val url = "https://archive.org/download/${book.id}/" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            Http.download(url, target)
        }
        onProgress(files.size, files.size)
        // Описание пишется последним: пока его нет, недокачанная книга не появляется в библиотеке.
        File(dir, META_FILE).writeText(
            JSONObject().apply {
                put("id", book.id)
                put("author", book.author)
                put("title", book.title)
                book.year?.let { put("year", it) }
                put("source", "LibriVox / Internet Archive (Public Domain)")
            }.toString(),
        )
    }

    fun delete(context: Context, id: String) {
        bookDir(context, id).deleteRecursively()
    }
}

/** Русские имена авторов и названия для записей, у которых в архиве они латиницей. */
object RussianNames {
    private val CYRILLIC = Regex("[А-Яа-яЁё]")

    private val AUTHORS = listOf(
        "pushkin" to "А. С. Пушкин",
        "пушкин" to "А. С. Пушкин",
        "tolstoy" to "Л. Н. Толстой",
        "dostoevsky" to "Ф. М. Достоевский",
        "chekhov" to "А. П. Чехов",
        "turgenev" to "И. С. Тургенев",
        "goncharov" to "И. А. Гончаров",
        "gumilyov" to "Н. С. Гумилёв",
        "mandelstam" to "О. Э. Мандельштам",
        "akhmatova" to "А. А. Ахматова",
        "severyanin" to "Игорь Северянин",
        "zhukovsky" to "В. А. Жуковский",
        "yershov" to "П. П. Ершов",
        "griboedov" to "А. С. Грибоедов",
        "leskov" to "Н. С. Лесков",
        "garshin" to "В. М. Гаршин",
        "saltykov" to "М. Е. Салтыков-Щедрин",
        "afanasyev" to "А. Н. Афанасьев",
        "baratynsky" to "Е. А. Баратынский",
        "venevitinov" to "Д. В. Веневитинов",
        "annensky" to "И. Ф. Анненский",
        "khodasevich" to "В. Ф. Ходасевич",
        "kuzmin" to "М. А. Кузмин",
        "remizov" to "А. М. Ремизов",
        "zaytsev" to "Б. К. Зайцев",
        "georgy ivanov" to "Г. В. Иванов",
        "vyacheslav ivanov" to "Вяч. И. Иванов",
        "teffi" to "Тэффи",
        "lokhvitskaya" to "М. А. Лохвицкая",
        "danilevsky" to "Г. П. Данилевский",
        "hoffmann" to "Э. Т. А. Гофман",
        "aesop" to "Эзоп",
        "longfellow" to "Г. Лонгфелло",
        "ehrenburg" to "И. Г. Эренбург",
        "eikhenbaum" to "Б. М. Эйхенбаум",
        "aykhenvald" to "Ю. И. Айхенвальд",
        "chirikov" to "Е. Н. Чириков",
        "shervinsky" to "С. В. Шервинский",
        "shengeli" to "Г. А. Шенгели",
        "verlaine" to "П. Верлен",
        "jabotinsky" to "В. Е. Жаботинский",
        "lvov-rogachevsky" to "В. Л. Львов-Рогачевский",
        "vetlugin" to "А. Ветлугин",
        "fyodorov-davydov" to "А. А. Федоров-Давыдов",
        "levanda" to "Л. О. Леванда",
        "mirbeau" to "О. Мирбо",
        "roman gul" to "Р. Б. Гуль",
        "aleichem" to "Шолом-Алейхем",
        "struve" to "Сборник авторов",
        "various" to "Разные авторы",
    )

    /** Названия, которые в архиве записаны только латиницей или требуют уточнения. */
    private val TITLES = mapOf(
        "krasavitse-librivox" to "Красавице, которая нюхала табак",
        "Poezdka_Polesye_librivox" to "Поездка в Полесье",
        "predlozhenie_1404_librivox" to "Предложение",
        "notes_underground_russian" to "Записки из подполья",
        "childhood_russian_librivox" to "Детство",
        "childhood_2312_librivox" to "Детство (вторая запись)",
        "yaponskienarodnyeskazki_1607_librivox" to "Японские народные сказки",
        "ocherkiproshlagorazskazy_1806_librivox" to "Очерки прошлого: рассказы",
        "icemarch_1507_librivox" to "Ледяной поход",
        "early_short_stories_jabotinsky_1410_librivox" to "Ранние рассказы",
        "portraitsrussianpoets_1612_librivox" to "Портреты русских поэтов",
        "song_of_hiawatha_2207_librivox" to "Песнь о Гайавате",
        "aesops_fables_russian_0905_librivox" to "Басни Эзопа",
        "shortstories_childrenadults_1801_librivox" to "Рассказы для детей и взрослых",
        "poemsvalerybryusov_2007_librivox" to "Стихотворения в переводе В. Брюсова",
        "vekhi_2011_librivox" to "Вехи. Сборник статей о русской интеллигенции",
        "poetry_1902_librivox" to "Стихи и переводы",
        "russianfairytales1_2007_librivox" to "Народные русские сказки. Выпуск 1",
        "russianfairytales2_2103_librivox" to "Народные русские сказки. Выпуск 2",
        "russianfairytales3_2412_librivox" to "Народные русские сказки. Выпуск 3",
    )

    /** Годы написания или первой публикации — по ним книги сортируются в библиотеке. */
    val YEARS = mapOf(
        "krasavitse-librivox" to 1814,
        "belkintales_1406_librivox" to 1831,
        "eugene_onegin_1801_librivox" to 1833,
        "dubrovsky_1411_librivox" to 1841,
        "konekgorbunok_1603_librivox" to 1834,
        "woe_from_wit_ig_librivox" to 1825,
        "selkuncikimysinyjcar_2210_librivox" to 1816,
        "obyknovennayaistorya_1912_librivox" to 1847,
        "white_nights_librivox" to 1848,
        "childhood_russian_librivox" to 1852,
        "childhood_2312_librivox" to 1852,
        "zapiskiohotnika_2409_librivox" to 1852,
        "Poezdka_Polesye_librivox" to 1857,
        "dvoryanskoegnezdo_1906_librivox" to 1859,
        "notes_underground_russian" to 1864,
        "obryv_1906_librivox" to 1869,
        "veshnie_vody_1607_librivox" to 1872,
        "on_the_edge_of_the_world_1902_librivox" to 1875,
        "skazki_2110_librivox" to 1886,
        "yuletide_stories_1812_librivox" to 1886,
        "predlozhenie_1404_librivox" to 1889,
        "chelovek_2103_librivox" to 1898,
        "transparency_2112_librivox" to 1904,
        "teachingsofchrist_1204_librivox" to 1908,
        "agrafena_2007_librivox" to 1908,
        "beforethesunset_2007_librivox" to 1908,
        "poetry_gumilyov_1602_librivox" to 1908,
        "vekhi_2011_librivox" to 1909,
        "junipercoffret_2302_librivox" to 1910,
        "chortova_kukla_2211_librivox" to 1911,
        "kamen_1501_librivox" to 1913,
        "carousel_2511_librivox" to 1913,
        "ananasi_2005_librivox" to 1915,
        "belayastaya1_2406_librivox" to 1917,
        "belayastaya2_2408_librivox" to 1917,
        "belayastaya3_2506_librivox" to 1917,
        "otherwherenights_2008_librivox" to 1921,
        "ognennaya_rossiya_1507_librivox" to 1921,
        "lampada_1810_librivox" to 1922,
        "bluestar_1903_librivox" to 1923,
    )

    fun author(creators: List<String>): String {
        val joined = creators.joinToString(" ").lowercase()
        AUTHORS.firstOrNull { (k, _) -> k in joined }?.let { return it.second }
        creators.firstOrNull { CYRILLIC.containsMatchIn(it) }?.let { return FilenameParser.clean(it) }
        return creators.firstOrNull()?.let(FilenameParser::clean) ?: FilenameParser.UNKNOWN_AUTHOR
    }

    fun title(id: String, raw: String): String {
        TITLES[id]?.let { return it }
        if (CYRILLIC.containsMatchIn(raw)) {
            val parts = raw.split(Regex("""\s*(?:/|::|\s-\s|\(|\))\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
            parts.firstOrNull { CYRILLIC.containsMatchIn(it) }?.let { return it.trimEnd(',', '.', ':') }
        }
        return raw.trim()
    }
}
