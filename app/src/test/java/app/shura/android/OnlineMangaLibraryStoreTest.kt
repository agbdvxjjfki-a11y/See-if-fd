package app.shura.android

import kotlin.test.Test
import kotlin.test.assertEquals

class OnlineMangaLibraryStoreTest {
    @Test
    fun onlineLibraryPersistsStableSourceIdsAndDeduplicatesEntries() {
        val id = "11111111-1111-1111-1111-111111111111"
        val entries = listOf(
            SavedOnlineManga(id, "Example"),
            SavedOnlineManga(id, "Duplicate"),
        )

        assertEquals(listOf(SavedOnlineManga(id, "Example")), decodeOnlineLibrary(encodeOnlineLibrary(entries)))
    }

    @Test
    fun onlineReadingProgressPersistsChapterIdentityAndPageIndex() {
        val progress = OnlineReadingProgress(
            mangaId = "11111111-1111-1111-1111-111111111111",
            chapterId = "22222222-2222-2222-2222-222222222222",
            pageIndex = 12,
        )

        assertEquals(
            mapOf(progress.mangaId to progress),
            decodeOnlineReadingProgress(encodeOnlineReadingProgress(mapOf(progress.mangaId to progress))),
        )
    }

    @Test
    fun invalidSavedIdentifiersAndMalformedDataAreNotRestored() {
        assertEquals(emptyList(), decodeOnlineLibrary("""[{"id":"invalid","title":"Example"}]"""))
        assertEquals(emptyMap(), decodeOnlineReadingProgress("""[{"mangaId":"invalid"}]"""))
        assertEquals(emptyList(), decodeOnlineLibrary("not json"))
    }
}
