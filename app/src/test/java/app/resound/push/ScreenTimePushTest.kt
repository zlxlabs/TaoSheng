package app.resound.push

import android.app.Application
import android.content.Context
import app.resound.data.MemoryEventEntity
import app.resound.diagnostics.FailureNotes
import app.resound.screentime.CoverageGap
import app.resound.screentime.DevicePlayback
import app.resound.screentime.GapCode
import app.resound.screentime.NamedAppSession
import app.resound.screentime.PLAYBACK_SOURCE
import app.resound.screentime.SCREEN_SOURCE
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ScreenTimePushTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 14)
    private val at = 1_794_657_600_000L
    private fun t(hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime.of(2026, 11, 14, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    private val transport = PushTransport(now = { at })
    private fun Request.text() = Buffer().also { body!!.writeTo(it) }.readUtf8()

    private fun sampleResult() = ScreenTimeResult(
        enabled = true,
        sessions = listOf(NamedAppSession("tv.danmaku.bili", "哔哩哔哩", t(17, 11, 46), t(20, 23, 46))),
        coverageUntil = t(22, 0),
        gaps = listOf(
            CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, t(21, 0), t(22, 0), "等待下一次采集"),
            CoverageGap(GapCode.NOTIFICATION_ACCESS_MISSING, PLAYBACK_SOURCE, t(0, 0), t(22, 0), "设置 → 通知使用权 → 回响 开启"),
        ),
        devicePlayback = listOf(
            DevicePlayback("app.podcast.cosmos", "小宇宙", "271.两眼一抹黑，试试也不亏", "…", t(8, 2, 10), t(8, 47, 55)),
        ),
    )

    @Test fun webhookJsonMatchesSampleBytesAndHeaders() = runBlocking {
        val message = buildScreenTimeMessage(date, at, zone, ScreenTimeReader { _, _ -> sampleResult() })!!
        val request = transport.request(PushKind.Webhook, PushFormat.Json, mapOf("url" to "https://example.com/hook"), message)
        val expected = """{"title":"今日屏幕时间 3 小时 12 分","body":"哔哩哔哩 192 分\n设备播放记录：\n小宇宙《271.两眼一抹黑，试试也不亏》 46 分\n部分时段没有数据：等待下一次采集；设置 → 通知使用权 → 回响 开启","topic":"ScreenTime","deepLink":null,"at":1794657600000,"screenTime":{"schemaVersion":1,"date":"2026-11-14","coverageUntil":"2026-11-14T22:00:00+08:00","totalMinutes":192,"apps":[{"package":"tv.danmaku.bili","name":"哔哩哔哩","minutes":192}],"timeline":[{"package":"tv.danmaku.bili","name":"哔哩哔哩","start":"2026-11-14T17:11:46+08:00","end":"2026-11-14T20:23:46+08:00"}],"devicePlayback":[{"package":"app.podcast.cosmos","name":"小宇宙","title":"271.两眼一抹黑，试试也不亏","subtitle":"…","start":"2026-11-14T08:02:10+08:00","end":"2026-11-14T08:47:55+08:00"}],"gaps":[{"code":"coverage_lag","source":"screen","from":"2026-11-14T21:00:00+08:00","until":"2026-11-14T22:00:00+08:00","fix":"等待下一次采集"},{"code":"notification_access_missing","source":"playback","from":"2026-11-14T00:00:00+08:00","until":"2026-11-14T22:00:00+08:00","fix":"设置 → 通知使用权 → 回响 开启"}]},"type":"screen_time","version":1,"app":"resound"}"""
        assertEquals(expected, request.text())
        assertEquals("screen_time", request.header("X-Resound-Type"))
        assertEquals("2026-11-14", request.header("X-Resound-Date"))
        val markdown = transport.request(PushKind.Webhook, PushFormat.Markdown, mapOf("url" to "https://example.com/hook"), message).text()
        assertFalse(markdown.contains("screenTime"))
        assertTrue(markdown.contains("今日屏幕时间 3 小时 12 分"))
    }

    @Test fun snapshotRoundTripKeepsScreenTimeAndOldSnapshotOmitsIt() = runBlocking {
        val message = buildScreenTimeMessage(date, at, zone, ScreenTimeReader { _, _ -> sampleResult() })!!
        val restored = PushMessage.from(message.json())
        assertEquals(message.screenTime!!.toString(), restored.screenTime!!.toString())
        assertEquals("2026-11-14", restored.screenTime!!.getString("date"))
        val request = transport.request(PushKind.Webhook, PushFormat.Json, mapOf("url" to "https://example.com/hook"), restored)
        assertEquals("2026-11-14", request.header("X-Resound-Date"))
        assertEquals("screen_time", request.header("X-Resound-Type"))
        val old = PushMessage("标题", "正文", PushTopic.DailyReview, null, at)
        val parsed = PushMessage.from(old.json())
        assertNull(parsed.screenTime)
        assertFalse(old.json().has("screenTime"))
    }

    @Test fun dispatchSkipsWhenReaderNullOrDisabledAndDoesNotMarkSent() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val repo = repo(server)
            val id = repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val after = t(22, 1)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, null) }
            assertEquals(0, server.requestCount)
            repo.dispatchScreenTime(after, zone)
            assertEquals(0, server.requestCount)
            assertNull(prefs.getString("screen-time-sent-$id", null))
            assertTrue("跳过原因要可见：${FailureNotes.dump()}", FailureNotes.dump().contains("push/screen-time-skip"))
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ -> ScreenTimeResult.DISABLED }) }
            repo.dispatchScreenTime(after, zone)
            assertEquals(0, server.requestCount)
            assertNull(prefs.getString("screen-time-sent-$id", null))
            assertTrue("采集关闭跳过也要可见：${FailureNotes.dump()}", FailureNotes.dump().contains("push/screen-time-skip"))
        }
    }

    @Test fun unauthorizedStillSendsUsageAccessMissingGap() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val repo = repo(server)
            repo.saveWithSubscription(null, PushKind.Webhook, "屏幕时间", mapOf("url" to "https://example.com/hook"),
                PushSubscription(summaries = false, screenTime = true, screenTimeAt = 22 * 60), PushFormat.Json, false).getOrThrow()
            val unauthorized = ScreenTimeResult(
                enabled = true, sessions = emptyList(), coverageUntil = null,
                gaps = listOf(CoverageGap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, t(0, 0), t(22, 0), "设置 → 使用情况访问 → 回响 开启")),
            )
            val after = t(22, 1)
            repo.screenTimeMessage = { d -> buildScreenTimeMessage(d, after, zone, ScreenTimeReader { _, _ -> unauthorized }) }
            server.enqueue(MockResponse().setResponseCode(200))
            repo.dispatchScreenTime(after, zone)
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("screen_time", body.getString("type"))
            val gaps = body.getJSONObject("screenTime").getJSONArray("gaps")
            assertEquals(1, gaps.length())
            assertEquals("usage_access_missing", gaps.getJSONObject(0).getString("code"))
            assertEquals(0, body.getJSONObject("screenTime").getJSONArray("apps").length())
            assertTrue(body.getJSONObject("screenTime").isNull("coverageUntil"))
        }
    }

    /** 推送时刻为 0 点（或更早）时当天无数据，直接返回 null 且不查询 reader。 */
    @Test fun midnightPushReturnsNullWithoutQueryingReader() = runBlocking {
        val midnight = t(0, 0)
        val forbidden = ScreenTimeReader { _, _ -> error("0 点推送不得查询 reader") }
        assertNull(loadScreenTimeReport(date, midnight, zone, forbidden))
        assertNull(loadScreenTimeReport(date, midnight - 1, zone, forbidden)) // 0 点前同理：until 不晚于 from
    }

    @Test fun subscriptionDocumentAddsAndRemovesScreenTimeSection() = runBlocking {
        val opened = replaceScreenTimeSection("# 当天\n\n正文\n", "今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分")
        assertTrue(opened.contains("<!-- resound:screen-time -->"))
        assertTrue(opened.endsWith("## 屏幕时间\n今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分\n") || opened.contains("## 屏幕时间\n今日共 3 小时 12 分"))
        val closed = replaceScreenTimeSection(opened, null)
        assertFalse(closed.contains("屏幕时间"))
        assertFalse(closed.contains("resound:screen-time"))
        assertTrue(closed.contains("# 当天"))
        MockWebServer().apply { start() }.use { server ->
            val hook = mapOf("url" to "https://example.com/hook", "signSecret" to "fake-sign")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
            }.build()
            var ch = PushChannel(1, PushKind.Webhook, "测试", true, Subscriptions.topics(openSub), PushFormat.Json, "", subscription = openSub)
            val runtime = SubscriptionRuntime(context.getSharedPreferences(UUID.randomUUID().toString(), 0), temp.newFolder(),
                { ch }, { ch to hook }, PushTransport(client), { _, _, _ -> }, { _, _, _ -> }, { t(10, 0) }, { zone })
            runtime.source = { _, _ -> PushSnapshot(events = listOf(event())) }
            runtime.screenTimeMarkdown = { "今日共 3 小时 12 分\n\n- 哔哩哔哩 192 分" }
            server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1).getOrThrow()
            val first = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getString("markdown")
            assertTrue(first.contains("<!-- resound:screen-time -->"))
            assertTrue(first.contains("## 屏幕时间"))
            assertTrue(first.contains("哔哩哔哩 192 分"))
            ch = ch.copy(subscription = openSub.copy(screenTime = false), topics = Subscriptions.topics(openSub.copy(screenTime = false)))
            server.enqueue(MockResponse().setResponseCode(204))
            runtime.run(1).getOrThrow()
            val second = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getString("markdown")
            assertFalse(second.contains("## 屏幕时间"))
            assertFalse(second.contains("resound:screen-time"))
        }
    }

    @Test fun oldSubscriptionJsonDefaultsScreenTimeOffAt2200() {
        val s = PushSubscription(screenTime = true, screenTimeAt = 21 * 60)
        assertEquals(s, Subscriptions.read(Subscriptions.json(s)))
        assertTrue(PushTopic.ScreenTime in Subscriptions.topics(s))
        // 真实旧格式字节：屏幕时间字段加入之前的订阅 JSON（键序与编码与旧版一致），读回应为「未订阅」+ 默认 22 点。
        val legacy = """{"summaries":true,"reminders":false,"records":["Timeline"],"lines":"Off","trigger":{"type":"schedule","times":[1320],"weekdays":[]},"incremental":false,"fullCheckAt":null,"layout":"ByCategory","rangeDays":null,"excludeMedia":true,"backfillDays":null,"lineContext":true,"audio":"Off","audioWithText":true,"audioWifiOnly":true,"footprint":false,"footprintAt":1320}"""
        val read = Subscriptions.read(JSONObject(legacy))
        assertFalse(read.screenTime)
        assertEquals(22 * 60, read.screenTimeAt)
        val old = Subscriptions.json(PushSubscription()).apply { remove("screenTime"); remove("screenTimeAt") }
        val parsed = Subscriptions.read(old)
        assertFalse(parsed.screenTime)
        assertEquals(22 * 60, parsed.screenTimeAt)
    }

    private val openSub = PushSubscription(summaries = false, records = setOf(RecordKind.Timeline),
        incremental = false, screenTime = true, fullCheckAt = null, backfillDays = null)

    private fun event() = MemoryEventEntity(1, t(10, 0), t(10, 1), "工作", "讨论", "摘要", "[\"要点\"]", "[\"我\"]",
        null, null, null, true, false, t(10, 0), "test")

    private fun repo(server: MockWebServer): AndroidPushChannels {
        val secrets = object : PushSecrets {
            val map = HashMap<String, String>()
            override fun read(ref: String) = map.getValue(ref)
            override fun write(ref: String, value: String) { map[ref] = value }
            override fun remove(ref: String) { map.remove(ref) }
        }
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
        }.build())
        return AndroidPushChannels(context.getSharedPreferences("push", Context.MODE_PRIVATE),
            context.getSharedPreferences("push-retries", Context.MODE_PRIVATE), secrets, transport, {})
    }
}
