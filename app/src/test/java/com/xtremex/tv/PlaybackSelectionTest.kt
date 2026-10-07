package com.xtremex.tv
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private fun channel(id: String, url: String) = TvChannel(id, id, "TV", listOf(url))
    @Test fun unchangedAndReorderedPlaylistPreservesCurrentAndPreviousIdentity() {
        val result = reconcileSelection(PlaybackSelection("b", "a", "https://b"), listOf(channel("b", "https://b"), channel("a", "https://a")))
        assertEquals("b", result.currentId); assertEquals("a", result.previousId); assertFalse(result.retune)
    }
    @Test fun sourceChangeAndRemovalRetuneSafely() {
        val old = PlaybackSelection("b", "a", "https://b")
        val changed = reconcileSelection(old, listOf(channel("a", "https://a"), channel("b", "https://new")))
        assertTrue(changed.retune); assertEquals("https://new", changed.source)
        val removed = reconcileSelection(old, listOf(channel("a", "https://a")))
        assertTrue(removed.retune); assertEquals("a", removed.currentId)
    }
    @Test fun delayedRetryCannotSurviveANewTune() {
        val guard = TuneGuard(); val retry = guard.next(); assertTrue(guard.valid(retry))
        guard.next(); assertFalse(guard.valid(retry))
    }
}
