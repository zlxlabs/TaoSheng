package app.resound.ask

import app.resound.ask.core.*
import app.resound.net.Arr as JSONArray
import app.resound.net.Obj as JSONObject
import app.resound.screentime.CoverageGap
import app.resound.screentime.GapCode
import app.resound.screentime.NamedAppSession
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class AskScreenToolsTest {
    private val zone = TimeZone.of("Asia/Shanghai")
    private val disabledJson =
        """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":false,"notice":"屏幕时间未开启，没有 App 使用数据。"}"""

    @Test fun overnightQueryKeepsIntervalAndFieldOrder() = runBlocking {
        val from = AskTime.timestamp("2026-11-13 23:00", false, zone)
        val to = AskTime.timestamp("2026-11-14 01:00", true, zone)
        val wxFrom = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val wxTo = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val biliFrom = AskTime.timestamp("2026-11-14 00:10", false, zone)
        val biliTo = AskTime.timestamp("2026-11-14 00:40", false, zone)
        val covered = AskTime.timestamp("2026-11-14 00:50", false, zone)
        val gapUntil = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val reader = RecordingReader(ScreenTimeResult(
            enabled = true,
            sessions = listOf(
                NamedAppSession("tv.danmaku.bili", "哔哩哔哩", biliFrom, biliTo),
                NamedAppSession("com.tencent.mm", "微信", wxFrom, wxTo),
            ),
            coverageUntil = covered,
            gaps = listOf(CoverageGap("coverage_lag", "screen", from, gapUntil, "等待下一次采集")),
        ))
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources)
        assertEquals(1, reader.calls)
        assertEquals(from, reader.from)
        assertEquals(to, reader.until)
        assertEquals(
            """{"totals":[{"ref":1,"name":"微信","package":"com.tencent.mm","minutes":45},{"ref":2,"name":"哔哩哔哩","package":"tv.danmaku.bili","minutes":30}],"sessions":[{"ref":1,"name":"微信","package":"com.tencent.mm","start":"2026-11-13 23:10","end":"2026-11-13 23:55"},{"ref":2,"name":"哔哩哔哩","package":"tv.danmaku.bili","start":"2026-11-14 00:10","end":"2026-11-14 00:40"}],"devicePlayback":[],"coverageUntil":"2026-11-14 00:50","gaps":[{"code":"coverage_lag","source":"screen","from":"2026-11-13 23:00","until":"2026-11-13 23:10","fix":"等待下一次采集"}],"truncated":false}""",
            out,
        )
        assertEquals(
            listOf(
                AskSource("screen", wxFrom, wxFrom, "微信 使用", "23:10–23:55 微信"),
                AskSource("screen", biliFrom, biliFrom, "哔哩哔哩 使用", "00:10–00:40 哔哩哔哩"),
            ),
            sources,
        )
    }

    @Test fun invalidRangeIsRejectedBeforeReader() {
        var called = 0
        val core = AskScreenToolsCore({ called++; error("不应调用") }, zone)
        assertThrows(AskDateException::class.java) {
            runBlocking { core.execute("""{"from":"2026-11-14 01:00","to":"2026-11-13 23:00"}""", mutableListOf()) }
        }
        assertThrows(AskDateException::class.java) {
            runBlocking { core.execute("""{"from":"2026-11-01 00:00","to":"2026-11-11 23:59"}""", mutableListOf()) }
        }
        assertEquals(0, called)
    }

    @Test fun disabledOrMissingReaderReturnsNoticeOnly() = runBlocking {
        val sources = mutableListOf(AskSource("line", 1, 0, "旧", "原话"))
        assertEquals(disabledJson, AskScreenToolsCore({ null }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources))
        assertEquals(disabledJson, AskScreenToolsCore({ RecordingReader(ScreenTimeResult.DISABLED) }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", sources))
        assertEquals(1, sources.size)
    }

    @Test fun usageAccessMissingReturnsEmptyListsWithGap() = runBlocking {
        val from = AskTime.timestamp("2026-11-13 23:00", false, zone)
        val to = AskTime.timestamp("2026-11-14 01:00", true, zone)
        val reader = RecordingReader(ScreenTimeResult(
            enabled = true,
            sessions = emptyList(),
            coverageUntil = null,
            gaps = listOf(CoverageGap(GapCode.USAGE_ACCESS_MISSING, "screen", from, to, "设置 → 使用情况访问 → 回响 开启")),
        ))
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""", mutableListOf())
        assertEquals(1, reader.calls)
        assertEquals(
            """{"totals":[],"sessions":[],"devicePlayback":[],"coverageUntil":null,"gaps":[{"code":"usage_access_missing","source":"screen","from":"2026-11-13 23:00","until":"2026-11-14 01:01","fix":"设置 → 使用情况访问 → 回响 开启"}],"truncated":false}""",
            out,
        )
    }

    @Test fun thousandSessionsTruncatesSessionsButKeepsTotals() = runBlocking {
        val start0 = AskTime.timestamp("2026-11-13 00:00", false, zone)
        val sessions = (0 until 1000).map { i ->
            val start = start0 + i * 60_000L
            NamedAppSession("a.a", "A", start, start + 60_000L)
        }
        val reader = RecordingReader(ScreenTimeResult(true, sessions, null, emptyList()))
        val sources = mutableListOf<AskSource>()
        val out = AskScreenToolsCore({ reader }, zone)
            .execute("""{"from":"2026-11-13 00:00","to":"2026-11-13 17:00"}""", sources)
        assertTrue(out.length <= 6000)
        assertTrue(out.startsWith("""{"totals":[{"ref":1,"name":"A","package":"a.a","minutes":1000}],"sessions":["""))
        assertTrue(out.endsWith("""],"devicePlayback":[],"coverageUntil":null,"gaps":[],"truncated":true}"""))
        val json = JSONObject(out)
        val kept = json.getJSONArray("sessions")
        assertTrue(kept.length() in 1 until 1000)
        assertEquals(1, json.getJSONArray("totals").length())
        assertEquals(1000, json.getJSONArray("totals").getJSONObject(0).getInt("minutes"))
        assertEquals(kept.length(), sources.size)
        assertEquals(start0, sources.first().id)
        assertEquals(start0 + (kept.length() - 1) * 60_000L, sources.last().id)
    }

    @Test fun engineCitesAppsDuringScreenSource() = runBlocking {
        val start = AskTime.timestamp("2026-11-13 23:10", false, zone)
        val end = AskTime.timestamp("2026-11-13 23:55", false, zone)
        val tools = AskScreenToolsCore({
            ScreenTimeReader { _, _ -> ScreenTimeResult(true, listOf(NamedAppSession("com.tencent.mm", "微信", start, end)), end, emptyList()) }
        }, zone)
        var round = 0
        var result: AskEngineCore.Update? = null
        val updates = mutableListOf<AskEngineCore.Update>()
        engine(TestStream { _, _, _, _ ->
            if (round++ == 0) reply("", listOf(tool("a", "apps_during", """{"from":"2026-11-13 23:00","to":"2026-11-14 01:00"}""")))
            else reply("晚上在用微信[^1]", emptyList())
        }, JSONArray(AskDefinitions.json()), { name, args, sources ->
            assertEquals("apps_during", name)
            JSONObject(tools.execute(args.toString(), sources))
        }, { _, _ -> }).answer("", emptyList(), "晚上用了什么 App") { result = it; updates += it }
        assertNull(result!!.error)
        assertEquals("screen", result!!.sources.single().kind)
        assertEquals("微信 使用", result!!.sources.single().title)
        assertTrue(updates.any { it.progress == "正在查手机使用记录…" })
    }

    private class RecordingReader(private val result: ScreenTimeResult) : ScreenTimeReader {
        var calls = 0
        var from = 0L
        var until = 0L
        override suspend fun sessions(from: Long, until: Long): ScreenTimeResult {
            calls++; this.from = from; this.until = until
            return result
        }
    }
}
