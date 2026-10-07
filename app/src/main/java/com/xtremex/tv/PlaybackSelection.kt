package com.xtremex.tv

data class PlaybackSelection(val currentId: String?, val previousId: String?, val source: String?)
data class SelectionResult(val currentId: String?, val previousId: String?, val source: String?, val retune: Boolean)
fun reconcileSelection(old: PlaybackSelection, channels: List<TvChannel>): SelectionResult {
    val current = channels.firstOrNull { it.id == old.currentId } ?: channels.firstOrNull()
    val previous = old.previousId?.takeIf { id -> channels.any { it.id == id } && id != current?.id }
    val source = old.source?.takeIf { current?.sources?.contains(it) == true } ?: current?.primarySource
    return SelectionResult(current?.id, previous, source, current?.id != old.currentId || source != old.source)
}
class TuneGuard {
    private var generation = 0L
    fun next(): Long = ++generation
    fun valid(value: Long): Boolean = generation == value
}
