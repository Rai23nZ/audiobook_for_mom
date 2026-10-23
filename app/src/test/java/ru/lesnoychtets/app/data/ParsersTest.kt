package ru.lesnoychtets.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilenameParserTest {
    @Test
    fun fullPattern() {
        val p = FilenameParser.parseFile("Пушкин А.С. - 1836 - Капитанская дочка.mp3", null)
        assertEquals(FilenameParser.Parsed("Пушкин А.С.", 1836, "Капитанская дочка"), p)
    }

    @Test
    fun hyphenatedAuthorAndDashes() {
        val p = FilenameParser.parseFile("Мамин-Сибиряк Д.Н. — 1894 — Алёнушкины сказки.m4b", "Разное")
        assertEquals(FilenameParser.Parsed("Мамин-Сибиряк Д.Н.", 1894, "Алёнушкины сказки"), p)
    }

    @Test
    fun yearTitleInsideAuthorFolder() {
        val p = FilenameParser.parseFile("1924 - Письмо матери.mp3", "Есенин С.А.")
        assertEquals(FilenameParser.Parsed("Есенин С.А.", 1924, "Письмо матери"), p)
    }

    @Test
    fun plainTitleInsideAuthorFolder() {
        val p = FilenameParser.parseFile("Белая_береза.ogg", "Есенин С.А.")
        assertEquals(FilenameParser.Parsed("Есенин С.А.", null, "Белая береза"), p)
    }

    @Test
    fun authorTitleAtRoot() {
        val p = FilenameParser.parseFile("Лермонтов - Бородино.mp3", null)
        assertEquals(FilenameParser.Parsed("Лермонтов", null, "Бородино"), p)
    }

    @Test
    fun unknownAtRoot() {
        val p = FilenameParser.parseFile("track01.mp3", null)
        assertEquals(FilenameParser.UNKNOWN_AUTHOR, p.author)
    }

    @Test
    fun audioDetection() {
        assertTrue(FilenameParser.isAudio("a.MP3"))
        assertTrue(FilenameParser.isAudio("book.m4b"))
        assertFalse(FilenameParser.isAudio("cover.jpg"))
        assertFalse(FilenameParser.isAudio(".hidden.mp3"))
    }

    @Test
    fun bookFolders() {
        assertEquals(
            FilenameParser.Parsed("Толстой Л.Н.", 1869, "Война и мир"),
            FilenameParser.parseBookFolder("Толстой Л.Н. - 1869 - Война и мир", null),
        )
        assertEquals(
            FilenameParser.Parsed("Пушкин", 1833, "Евгений Онегин"),
            FilenameParser.parseBookFolder("1833 - Евгений Онегин", "Пушкин"),
        )
        assertNull(FilenameParser.parseBookFolder("Пушкин", null))
    }
}

class LibraryBuilderTest {
    @Test
    fun groupsChaptersAndSingles() {
        val files = listOf(
            ScannedFile("u10", listOf("Толстой Л.Н. - 1869 - Война и мир"), "10.mp3"),
            ScannedFile("u2", listOf("Толстой Л.Н. - 1869 - Война и мир"), "2.mp3"),
            ScannedFile("u1", listOf("Толстой Л.Н. - 1869 - Война и мир"), "1.mp3"),
            ScannedFile("p", listOf("Пушкин А.С."), "1836 - Капитанская дочка.mp3"),
            ScannedFile("e", emptyList(), "Есенин С.А. - 1924 - Письмо матери.mp3"),
            ScannedFile("e2", listOf("Копия"), "Есенин С.А. - 1924 - Письмо матери.mp3"),
            ScannedFile("img", emptyList(), "cover.jpg"),
        )
        val books = LibraryBuilder.build(files).values.toList()
        assertEquals(3, books.size)
        val war = books.first { it.title == "Война и мир" }
        assertEquals(listOf("u1", "u2", "u10"), war.tracks.map { it.uri })
        assertEquals(1869, war.year)
        val captain = books.first { it.title == "Капитанская дочка" }
        assertEquals("Пушкин А.С.", captain.author)
        assertEquals("e", books.first { it.title == "Письмо матери" }.tracks.single().uri)
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("Глава 10.mp3", "Глава 2.mp3", "Глава 1.mp3").sortedWith(NaturalOrder)
        assertEquals(listOf("Глава 1.mp3", "Глава 2.mp3", "Глава 10.mp3"), sorted)
    }

    @Test
    fun byYearPutsUnknownLast() {
        val b = { y: Int?, t: String -> Book(t, "a", y, t, emptyList()) }
        val sorted = listOf(b(null, "x"), b(1841, "Дубровский"), b(1833, "Онегин")).sortedWith(LibraryBuilder.byYear)
        assertEquals(listOf("Онегин", "Дубровский", "x"), sorted.map { it.title })
    }
}

class GoogleDriveFolderTest {
    @Test
    fun extractsFolderId() {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        assertEquals(id, GoogleDriveFolder.extractFolderId("https://drive.google.com/drive/folders/$id?usp=sharing"))
        assertEquals(id, GoogleDriveFolder.extractFolderId("https://drive.google.com/drive/u/0/folders/$id"))
        assertEquals(id, GoogleDriveFolder.extractFolderId("https://drive.google.com/open?id=$id"))
        assertEquals(id, GoogleDriveFolder.extractFolderId(" $id "))
        assertNull(GoogleDriveFolder.extractFolderId("https://example.com/"))
    }

    @Test
    fun parsesEmbeddedListing() {
        val html = """
            <div class="flip-entries"><div class="flip-entry" id="entry-FOLDER123456" tabindex="0" role="link">
            <div class="flip-entry-info"><a href="https://drive.google.com/drive/folders/FOLDER123456" target="_blank">
            <div class="flip-entry-title">Толстой Л.Н. - 1869 - Война и мир</div></a></div></div>
            <div class="flip-entry" id="entry-FILE98765432" tabindex="0" role="link">
            <div class="flip-entry-info"><a href="https://drive.google.com/file/d/FILE98765432/view?usp=drive_web" target="_blank">
            <div class="flip-entry-thumb"></div><div class="flip-entry-title">Пушкин &amp; Co - 1836 - &quot;Дочка&quot;.mp3</div></a></div>
            <div class="flip-entry-last-modified"><div>12:00</div></div></div></div>
        """.trimIndent()
        val entries = GoogleDriveFolder.parseListing(html)
        assertEquals(2, entries.size)
        assertEquals(GoogleDriveFolder.Entry("FOLDER123456", "Толстой Л.Н. - 1869 - Война и мир", true), entries[0])
        assertEquals(GoogleDriveFolder.Entry("FILE98765432", "Пушкин & Co - 1836 - \"Дочка\".mp3", false), entries[1])
    }
}

class LibrivoxTest {
    private fun snapshot(): String {
        val f = listOf("src/main/assets/librivox_ru.json", "app/src/main/assets/librivox_ru.json").map(::File).first { it.exists() }
        return f.readText()
    }

    @Test
    fun catalogSnapshotParsesWithRussianNames() {
        val books = LibrivoxCatalog.parseSearchResponse(snapshot())
        assertTrue(books.size >= 70)
        assertEquals(books.size, books.map { it.key }.toSet().size)
        val onegin = books.first { it.id == "eugene_onegin_1801_librivox" }
        assertEquals("А. С. Пушкин", onegin.author)
        assertEquals("Евгений Онегин", onegin.title)
        assertEquals(1833, onegin.year)
        assertEquals("Горе от ума", books.first { it.id == "woe_from_wit_ig_librivox" }.title)
        assertEquals("Л. Н. Толстой", books.first { it.id == "childhood_russian_librivox" }.author)
    }

    @Test
    fun picksStandardMp3() {
        val json = """{"result":[
            {"name":"onegin_01.mp3","format":"128Kbps MP3","size":"900"},
            {"name":"onegin_10_64kb.mp3","format":"64Kbps MP3","size":"300"},
            {"name":"onegin_02_64kb.mp3","format":"64Kbps MP3","size":"200"},
            {"name":"onegin.png","format":"PNG","size":"5"}
        ]}"""
        val files = LibrivoxDownloader.pickMp3(json)
        assertEquals(listOf("onegin_02_64kb.mp3", "onegin_10_64kb.mp3"), files.map { it.name })
        assertEquals(200L, files[0].size)
    }
}

class ProgressTest {
    @Test
    fun encodesFinishedAt() {
        val p = Progress(track = 2, positionMs = 5000, durationMs = 90000, finished = true, finishedAt = 1_700_000_000_000)
        assertEquals(p, Progress.decode(p.encode()))
        assertEquals(Progress(1, 2, 3, false, 0), Progress.decode("1;2;3;0"))
    }

    @Test
    fun removedOnlyAfterTheDayOfListening() {
        val today = 1_700_000_000_000
        assertFalse(Progress(finished = true, finishedAt = today + 1000).finishedBefore(today))
        assertTrue(Progress(finished = true, finishedAt = today - 1000).finishedBefore(today))
        assertFalse(Progress(finished = false, finishedAt = 0).finishedBefore(today))
    }
}
