package app.resound.digest

import app.resound.context.PlaceAt
import app.resound.context.SituationLookup
import app.resound.context.SituationStore
import app.resound.data.MemoryEventEntity
import app.resound.data.TranscriptLineEntity
import app.resound.envsound.EnvSoundRow
import app.resound.screentime.NamedAppSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SituationPrompt130Test {
    private val zone = ZoneId.of("UTC")
    private val lines = listOf(
        TranscriptLineEntity(1, 1, 0, 1000, "第一句", null),
        TranscriptLineEntity(2, 1, 1000, 2000, "第二句", null),
        TranscriptLineEntity(3, 2, 3000, 4000, "第三句", null),
        TranscriptLineEntity(4, 3, 5000, 6000, "第四句", null),
    )
    private fun event(id: Long, start: Long, end: Long) = MemoryEventEntity(
        id = id, startAt = start, endAt = end, scene = "工作", title = "记录$id", summary = "摘要", pointsJson = "[]",
        peopleJson = "[]", place = null, emotion = null, intensity = null, important = false, createdAt = 0, model = "test")
    private val events = listOf(event(1, 0, 2000), event(2, 3000, 4000), event(3, 5000, 6000))

    @Test fun disabledKeepsBothPromptsByteForByteAndNeverQueries() = runBlocking {
        val forbidden = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? = error("关闭不应查询")
            override suspend fun envDuring(start: Long, end: Long, limit: Int): List<String> = error("关闭不应查询")
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        }
        val prompt = SituationPrompt(false, forbidden)
        val today = LocalDate.of(2026, 11, 10)
        assertEquals(DigestPrompt.system("原模板", today, "涛声"), prompt.system(DigestPrompt.system("原模板", today, "涛声")))
        assertEquals(MergePrompt.system("合并模板", today, "涛声"), prompt.system(MergePrompt.system("合并模板", today, "涛声")))
        assertEquals(DigestPrompt.transcript(lines, zone, setOf(1)), prompt.transcript(lines, setOf(1), zone))
        assertEquals(MergePrompt.input(events, zone), prompt.merge(events, zone))
    }

    @Test fun enabledAddsOneHeadingPerSegmentOrMergedEventAndOmitsEmptyParts() = runBlocking {
        val queried = mutableListOf<Pair<Long, Long>>()
        val lookup = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? {
                queried += start to end
                return if (start == 0L) PlaceAt("公司", false, start, end) else null
            }
            override suspend fun envDuring(start: Long, end: Long, limit: Int) = when (start) {
                0L -> listOf("键盘声", "附近有车流")
                3000L -> listOf("雨")
                else -> emptyList()
            }
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        }
        val prompt = SituationPrompt(true, lookup)
        val (text, indexed) = prompt.transcript(lines, zone = zone)
        assertEquals(lines, indexed)
        assertEquals(listOf(0L to 2000L, 3000L to 4000L, 5000L to 6000L), queried)
        val headings = listOf("【当时在：公司｜周围：键盘声、附近有车流】", "【周围：雨】")
        assertEquals(headings, text.lines().filter { it.startsWith("【") })
        assertEquals(DigestPrompt.transcript(lines, zone).first, text.lines().filterNot { it.startsWith("【") }.joinToString("\n"))
        val merge = prompt.merge(events, zone)
        assertEquals(headings, merge.lines().filter { it.startsWith("【当时") || it.startsWith("【周围") })
        assertTrue(merge.startsWith(headings.first() + "\n【记录 1】"))
        assertTrue(prompt.system("模板").contains("不要把情境行当成原话"))
        assertTrue(prompt.system("模板").contains("以说话内容为准"))
    }

    // ---------- 卡 184：外放提示（playbackMillis ≥ 3000 的 mic 片段） ----------

    private fun lookup() = object : SituationLookup {
        override suspend fun placeDuring(start: Long, end: Long) = if (start == 0L) PlaceAt("公司", false, start, end) else null
        override suspend fun envDuring(start: Long, end: Long, limit: Int) = when (start) {
            0L -> listOf("键盘声", "附近有车流")
            3000L -> listOf("雨")
            else -> emptyList()
        }
        override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
    }

    @Test fun disabledMarksOnlySegmentsAtOrAboveThresholdAndNeverQueries() = runBlocking {
        val forbidden = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? = error("关闭不应查询")
            override suspend fun envDuring(start: Long, end: Long, limit: Int): List<String> = error("关闭不应查询")
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        }
        val prompt = SituationPrompt(false, forbidden, mapOf(1L to 3000L, 3L to 2999L))
        val (text, _) = prompt.transcript(lines, zone = zone)
        assertEquals(listOf("【外放：有】"), text.lines().filter { it.startsWith("【") })
        assertTrue(text.contains("【外放：有】\n[1] "))
        assertEquals("模板\n\n${SituationPrompt.PLAYBACK_RULE}", prompt.system("模板"))
    }

    @Test fun disabledWithoutLookupStillMarksPlaybackAndAppendsRuleWithoutBaseRule() = runBlocking {
        val prompt = SituationPrompt(false, null, mapOf(1L to 5000L))
        val (text, _) = prompt.transcript(lines, zone = zone)
        assertEquals(listOf("【外放：有】"), text.lines().filter { it.startsWith("【") })
        assertEquals("模板\n\n${SituationPrompt.PLAYBACK_RULE}", prompt.system("模板"))
    }

    @Test fun enabledAppendsPlaybackMarkerAfterExistingPartsAndRuleAfterBaseRule() = runBlocking {
        val prompt = SituationPrompt(true, lookup(), mapOf(1L to 5000L, 3L to 5000L))
        val (text, _) = prompt.transcript(lines, zone = zone)
        assertEquals(listOf("【当时在：公司｜周围：键盘声、附近有车流｜外放：来源未知】", "【周围：雨】", "【外放：来源未知】"),
            text.lines().filter { it.startsWith("【") })
        val system = prompt.system("模板")
        assertTrue(system.contains(SituationPrompt.RULE))
        assertTrue(system.endsWith(SituationPrompt.PLAYBACK_RULE))
    }

    @Test fun enabledWithZeroPlaybackKeepsAllPromptsByteForByte() = runBlocking {
        val source = lookup()
        val baseline = SituationPrompt(true, source)
        val zero = SituationPrompt(true, source, mapOf(1L to 0L, 3L to 0L))
        assertEquals(baseline.system("模板"), zero.system("模板"))
        assertEquals(baseline.transcript(lines, zone = zone), zero.transcript(lines, zone = zone))
        assertEquals(baseline.merge(events, zone), zero.merge(events, zone))
        assertEquals("模板\n\n${SituationPrompt.RULE}", zero.system("模板"))
    }

    @Test fun mergeNeverMarksPlaybackEvenWhenProvided() = runBlocking {
        assertFalse(SituationPrompt(true, lookup(), mapOf(1L to 9000L)).merge(events, zone).contains("外放"))
        assertFalse(SituationPrompt(false, null, mapOf(1L to 9000L)).merge(events, zone).contains("外放"))
    }

    // ---------- 卡 186c：情境行「在用」 ----------

    private class CountingLookup(
        private val home: Boolean = false,
        private val sessions: List<NamedAppSession> = emptyList(),
    ) : SituationLookup {
        val calls = mutableListOf<Pair<Long, Long>>()
        override suspend fun placeDuring(start: Long, end: Long): PlaceAt? =
            if (home) PlaceAt("家", true, start, end) else null
        override suspend fun envDuring(start: Long, end: Long, limit: Int) = emptyList<String>()
        override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        override suspend fun appSessions(from: Long, until: Long): List<NamedAppSession> {
            calls += from to until
            return sessions
        }
    }

    /** 必测 1：mic 片段逐字出「当时在：家｜在用：微信｜外放：来源未知」；非 mic 片段无「在用」；整批只查一次。 */
    @Test fun inUseItemAppearsForMicSegmentsAndQueriesOncePerBatch() = runBlocking {
        val lookup = CountingLookup(home = true,
            sessions = listOf(NamedAppSession("com.tencent.mm", "微信", 0, 6000)))
        val prompt = SituationPrompt(true, lookup, mapOf(1L to 5000L, 2L to 0L))
        val (text, _) = prompt.transcript(lines, zone = zone)
        assertEquals(
            listOf("【当时在：家｜在用：微信｜外放：来源未知】", "【当时在：家｜在用：微信】", "【当时在：家】"),
            text.lines().filter { it.startsWith("【") },
        )
        assertEquals(listOf(0L to 6000L), lookup.calls) // 批内最早 0、最晚 6000，恰好一次
    }

    /** 必测 2：片段同时跨微信 1 分钟、抖音 3 分钟，取重叠最长的抖音。 */
    @Test fun longestOverlappingAppWins() = runBlocking {
        val lookup = CountingLookup(sessions = listOf(
            NamedAppSession("com.tencent.mm", "微信", 0, 60_000),
            NamedAppSession("com.ss.android.ugc.aweme", "抖音", 60_000, 240_000)))
        val longLine = listOf(TranscriptLineEntity(1, 1, 0, 240_000, "一句长话", null))
        val (text, _) = SituationPrompt(true, lookup, mapOf(1L to 5000L)).transcript(longLine, zone = zone)
        assertEquals(listOf("【在用：抖音｜外放：来源未知】"), text.lines().filter { it.startsWith("【") })
        assertEquals(listOf(0L to 240_000L), lookup.calls)
    }

    /** 必测 3：打开、无外放、appSessions 为空时与 184 输出逐字相同；关闭时连 appSessions 都不调。 */
    @Test fun emptyAppSessionsKeepsCard184OutputByteForByte() = runBlocking {
        val (text, _) = SituationPrompt(true, lookup(), emptyMap()).transcript(lines, zone = zone)
        assertEquals(listOf("【当时在：公司｜周围：键盘声、附近有车流】", "【周围：雨】"), text.lines().filter { it.startsWith("【") })
        assertEquals("模板\n\n${SituationPrompt.RULE}", SituationPrompt(true, lookup(), emptyMap()).system("模板"))
        val forbidden = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? = error("关闭不应查询")
            override suspend fun envDuring(start: Long, end: Long, limit: Int): List<String> = error("关闭不应查询")
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
            override suspend fun appSessions(from: Long, until: Long): List<NamedAppSession> = error("关闭不应查询")
        }
        val (offText, _) = SituationPrompt(false, forbidden, mapOf(1L to 3000L)).transcript(lines, zone = zone)
        assertEquals(listOf("【外放：有】"), offText.lines().filter { it.startsWith("【") })
        assertEquals("模板\n\n${SituationPrompt.PLAYBACK_RULE}", SituationPrompt(false, forbidden, mapOf(1L to 3000L)).system("模板"))
    }

    /** 必测 4：本批出现「在用」时 system 在 RULE 之后、外放规则之前追加 IN_USE_RULE；无在用逐字不变。 */
    @Test fun systemAppendsInUseRuleBetweenBaseRuleAndPlaybackRule() = runBlocking {
        val prompt = SituationPrompt(true, CountingLookup(sessions = listOf(NamedAppSession("com.tencent.mm", "微信", 0, 6000))),
            mapOf(1L to 5000L, 3L to 5000L))
        prompt.transcript(lines, zone = zone)
        val system = prompt.system("模板")
        assertTrue(system.contains(SituationPrompt.IN_USE_RULE))
        val base = system.indexOf(SituationPrompt.RULE)
        val inUse = system.indexOf(SituationPrompt.IN_USE_RULE)
        val playback = system.indexOf(SituationPrompt.PLAYBACK_RULE)
        assertTrue("$base < $inUse < $playback", base < inUse && inUse < playback)
        // 无在用的批次：system 与 184 逐字相同
        val quiet = SituationPrompt(true, lookup(), emptyMap())
        quiet.transcript(lines, zone = zone)
        assertEquals("模板\n\n${SituationPrompt.RULE}", quiet.system("模板"))
    }

    /** E2E：真实 SituationStore 注入假 appSessions；mic 片段带「在用」、非 mic 不带；整批恰好查询一次。 */
    @Test fun realSituationStoreQueriesAppSessionsOncePerBatch() = runBlocking {
        var calls = 0
        val store = SituationStore(
            available = { true },
            points = { _, _ -> emptyList() },
            cachedPlace = { _, _ -> null },
            sounds = { _, _ -> listOf(EnvSoundRow(segmentId = 4, startAt = 5000, endAt = 6000, path = "", verdict = "",
                reason = null, tags = "Rain", sounds = "", scores = "", skipped = false, markMedia = false)) },
            zone = app.resound.platform.ZoneProvider { "UTC" },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            permissionDenied = { false },
            radians = Math::toRadians,
            appSessions = { _, _ -> calls++; listOf(NamedAppSession("com.tencent.mm", "微信", 0, 6000)) },
        )
        val (text, _) = SituationPrompt(true, store, mapOf(1L to 5000L, 2L to 0L)).transcript(lines, zone = zone)
        assertEquals(listOf("【在用：微信｜外放：来源未知】", "【在用：微信】", "【周围：雨】"), text.lines().filter { it.startsWith("【") })
        assertEquals(1, calls)
    }
}
