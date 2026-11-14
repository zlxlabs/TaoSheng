package app.resound.digest
import app.resound.data.*
import app.resound.context.SituationLookup
import java.time.ZoneId
internal class SituationPrompt(enabled: Boolean, lookup: SituationLookup?, playback: Map<Long, Long> = emptyMap()) {
    private val core = app.resound.digest.core.SituationPrompt(enabled, lookup, playback)
    fun system(original: String) = core.system(original)
    suspend fun transcript(lines: List<TranscriptLineEntity>, unseparated: Set<Long> = emptySet(), zone: ZoneId = ZoneId.systemDefault()) =
        core.transcript(lines.map { it.snapshot() }, unseparated, zone.sharedZone()).first to lines
    suspend fun merge(events: List<MemoryEventEntity>, zone: ZoneId = ZoneId.systemDefault()) = core.merge(events.map { it.snapshot() }, zone.sharedZone())
    companion object {
        const val RULE = app.resound.digest.core.SituationPrompt.RULE
        const val PLAYBACK_RULE = app.resound.digest.core.SituationPrompt.PLAYBACK_RULE
        const val IN_USE_RULE = app.resound.digest.core.SituationPrompt.IN_USE_RULE
    }
}
