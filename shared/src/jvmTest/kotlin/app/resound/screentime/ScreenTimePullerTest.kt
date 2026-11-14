package app.resound.screentime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 内存假库；无事务，用「先校验后落盘」模拟 commitPull 的原子性，语义与 Room @Transaction 对齐。 */
private class FakeDao : ScreenTimeDao {
    val rows = mutableListOf<ScreenEventRow>()
    val names = mutableMapOf<String, ScreenAppNameRow>()
    val states = mutableMapOf<String, ScreenPullStateRow>()
    val gaps = mutableListOf<ScreenCoverageGapRow>()
    var failNames = false
    override suspend fun events(from: Long, until: Long) = rows.filter { it.at >= from && it.at < until }.sortedWith(compareBy({ it.at }, { it.id }))
    override suspend fun names() = names.values.sortedBy { it.pkg }
    override suspend fun state(source: String) = states[source]
    override suspend fun gaps(source: String, from: Long, until: Long) = gaps.filter { it.source == source && it.fromAt < until && it.untilAt > from }.sortedBy { it.fromAt }
    override suspend fun insertEvents(events: List<ScreenEventRow>) {
        for (e in events) if (rows.none { it.at == e.at && it.pkg == e.pkg && it.type == e.type && it.cls == e.cls }) rows += e
    }
    override suspend fun upsertNames(names: List<ScreenAppNameRow>) {
        if (failNames) throw IllegalStateException("names failed")
        names.forEach { this.names[it.pkg] = it }
    }
    override suspend fun insertStateRow(row: ScreenPullStateRow) = if (states.containsKey(row.source)) -1L else { states[row.source] = row; 1L }
    override suspend fun updateCoveredUntil(source: String, value: Long) { states[source]?.let { states[source] = it.copy(coveredUntil = maxOf(it.coveredUntil ?: 0L, value)) } }
    override suspend fun raiseFloor(source: String, value: Long) { states[source]?.let { states[source] = it.copy(floor = maxOf(it.floor, value)) } }
    override suspend fun recordGap(gap: ScreenCoverageGapRow) { gaps += gap }
    override suspend fun deleteEvents(from: Long, until: Long) { rows.removeAll { it.at >= from && it.at < until } }
    override suspend fun lastEventAt() = rows.maxOfOrNull { it.at }
    override suspend fun commitPull(events: List<ScreenEventRow>, names: List<ScreenAppNameRow>, coveredUntil: Long) {
        if (failNames && names.isNotEmpty()) throw IllegalStateException("names failed")
        if (events.isNotEmpty()) insertEvents(events)
        if (names.isNotEmpty()) upsertNames(names)
        if (insertStateRow(ScreenPullStateRow(SCREEN_SOURCE, coveredUntil, 0)) == -1L) updateCoveredUntil(SCREEN_SOURCE, coveredUntil)
    }
}

/** 断言被调用即失败的严格事件源；验证开关关闭时不碰任何方法。 */
private class StrictSource : ScreenEventSource {
    override fun permitted() = error("开关关闭时不得调用 permitted")
    override suspend fun since(from: Long, until: Long) = error("开关关闭时不得调用 since")
    override suspend fun appNames(pkgs: Set<String>) = error("开关关闭时不得调用 appNames")
}

private class FakeSource(var permitted: Boolean = true, var events: List<RawScreenEvent> = emptyList(),
    var names: Map<String, String> = emptyMap()) : ScreenEventSource {
    val calls = mutableListOf<Pair<Long, Long>>()
    override fun permitted() = permitted
    override suspend fun since(from: Long, until: Long): List<RawScreenEvent> { calls += from to until; return events }
    override suspend fun appNames(pkgs: Set<String>) = names.filterKeys { it in pkgs }
}

/** 固定时钟与内存开关的测试环境；clock 默认 1_000_000_000_000。 */
private class FakeSettings(var on: Boolean = true) {
    var clock = 1_000_000_000_000L
    var lastPull = 0L
    val gapCalls = mutableListOf<Int>()
    val settings = ScreenTimeSettings(
        readBoolean = { _, _ -> on }, saveBoolean = { _, value -> on = value; true },
        readLong = { _, _ -> lastPull }, saveLong = { _, value -> lastPull = value; true },
        onReenabled = { gapCalls += gapCalls.size + 1 })
    fun puller(source: ScreenEventSource, dao: ScreenTimeDao, denied: (Throwable) -> Boolean = { false }) =
        ScreenTimePuller(settings, source, dao, now = { clock }, denied = denied)
}

private fun resumed(at: Long, pkg: String = "com.example.app", cls: String? = "com.example.MainActivity") = RawScreenEvent(at, pkg, cls, 1)

class ScreenTimePullerTest {
    @Test fun disabledSkipsSourceEntirely() = runBlocking {
        val dao = FakeDao()
        val result = FakeSettings(on = false).puller(StrictSource(), dao).pull()
        assertEquals(PullResult.Disabled, result)
        assertEquals(0, dao.rows.size); assertNull(dao.state(SCREEN_SOURCE)); assertEquals(0, dao.gaps.size)
    }

    @Test fun noPermissionLeavesEverythingUntouched() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(permitted = false, events = listOf(resumed(500)))
        val env = FakeSettings()
        assertEquals(PullResult.NoPermission, env.puller(source, dao).pull())
        assertEquals(0, source.calls.size); assertEquals(0, dao.rows.size); assertEquals(0, dao.gaps.size)
        assertEquals(0L, env.lastPull)
    }

    @Test fun failedNameWriteLeavesNoSideEffects() = runBlocking {
        val dao = FakeDao().apply { failNames = true }
        val source = FakeSource(events = listOf(resumed(500), RawScreenEvent(600, "sys", null, 16)),
            names = mapOf("com.example.app" to "示例"))
        val env = FakeSettings()
        val result = env.puller(source, dao).pull()
        assertEquals("IllegalStateException", (result as PullResult.Failed).type)
        assertEquals(0, dao.rows.size); assertNull(dao.state(SCREEN_SOURCE)); assertEquals(0, dao.gaps.size)
        assertEquals(0L, env.lastPull)
    }

    @Test fun emptyPullStillAdvancesCoveredUntil() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val clock = env.clock
        assertEquals(PullResult.Pulled(0), env.puller(FakeSource(), dao).pull())
        assertEquals(clock, dao.state(SCREEN_SOURCE)?.coveredUntil)
    }

    @Test fun duplicatesAndUnknownTypesAreDropped() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource(events = listOf(resumed(500), resumed(500),
            resumed(501, cls = null), RawScreenEvent(502, "sys", "X", 11)))
        assertEquals(PullResult.Pulled(3), env.puller(source, dao).pull()) // 过滤后 3 条，落库 2 行
        assertEquals(listOf(500L, 501L), dao.rows.map { it.at })
        assertEquals("", dao.rows.single { it.at == 501L }.cls) // 无类名事件存空串
        env.clock += 31_000
        assertEquals(PullResult.Pulled(3), env.puller(source, dao).pull()) // 同一批重拉不产生重复行
        assertEquals(listOf(500L, 501L), dao.rows.map { it.at })
    }

    @Test fun firstPullStartsAtTenDaysBack() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource()
        env.puller(source, dao).pull()
        assertEquals(env.clock - ScreenTimePuller.HORIZON_MILLIS to env.clock, source.calls.single())
    }

    @Test fun staleCoverageBeyondHorizonRecordsPullFailedGap() = runBlocking {
        val dao = FakeDao()
        val covered = 1_000_000_000_000L - 12 * 24 * 3_600_000L
        dao.advanceCoveredUntil(SCREEN_SOURCE, covered)
        val env = FakeSettings()
        val source = FakeSource()
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        val gap = dao.gaps.single()
        assertEquals("pull_failed", gap.code)
        assertEquals(covered, gap.fromAt)
        assertEquals(1_000_000_000_000L - ScreenTimePuller.HORIZON_MILLIS, gap.untilAt)
        assertEquals(1_000_000_000_000L - ScreenTimePuller.HORIZON_MILLIS, source.calls.single().first) // 起点收到保留期下限
    }

    @Test fun coveredUntilNeverGoesBackwards() = runBlocking {
        val dao = FakeDao()
        dao.advanceCoveredUntil(SCREEN_SOURCE, 1_000)
        dao.advanceCoveredUntil(SCREEN_SOURCE, 500)
        assertEquals(1_000L, dao.state(SCREEN_SOURCE)?.coveredUntil)
    }

    @Test fun secondPullWithinThrottleIsRejected() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val puller = env.puller(FakeSource(), dao)
        assertEquals(PullResult.Pulled(0), puller.pull())
        env.clock += 10_000
        assertEquals(PullResult.Throttled, puller.pull())
        env.clock += 21_000 // 距上次成功已超 30 秒
        assertEquals(PullResult.Pulled(0), puller.pull())
    }

    @Test fun repullStartsAtPreviousCoveredUntil() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val source = FakeSource()
        env.puller(source, dao).pull()
        val first = source.calls.single()
        env.clock += 31_000
        assertEquals(PullResult.Pulled(0), env.puller(source, dao).pull())
        assertEquals(first.second, source.calls.last().first) // 起点接续上次覆盖终点（含）
    }

    @Test fun securityExceptionBecomesNoPermissionAndOtherErrorsBecomeFailed() = runBlocking {
        val dao = FakeDao()
        val env = FakeSettings()
        val denied = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw SecurityException("denied")
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        assertEquals(PullResult.NoPermission, env.puller(denied, dao, denied = { it is SecurityException }).pull())
        assertNull(dao.state(SCREEN_SOURCE))
        val broken = object : ScreenEventSource {
            override fun permitted() = true
            override suspend fun since(from: Long, until: Long): List<RawScreenEvent> = throw IllegalArgumentException("boom")
            override suspend fun appNames(pkgs: Set<String>) = emptyMap<String, String>()
        }
        assertEquals("IllegalArgumentException", (env.puller(broken, dao).pull() as PullResult.Failed).type)
    }
}

class ScreenTimeSettingsTest {
    @Test fun reenablingNotifiesOnlyWhenSwitchingFromOff() {
        val env = FakeSettings(on = false)
        env.settings.setEnabled(true)
        assertEquals(1, env.gapCalls.size) // 关→开通知补缺口
        env.settings.setEnabled(true)
        assertEquals(1, env.gapCalls.size) // 已开再开不重复通知
        env.settings.setEnabled(false)
        env.settings.setEnabled(true)
        assertEquals(2, env.gapCalls.size)
        assertTrue(env.settings.enabled.value)
    }

    @Test fun defaultIsOff() {
        assertFalse(FakeSettings(on = false).settings.enabled.value)
    }

    @Test fun statePublishesOnlyAfterSaveCommits() {
        val settings = ScreenTimeSettings(
            readBoolean = { _, def -> def }, saveBoolean = { _, _ -> false },
            readLong = { _, def -> def }, saveLong = { _, _ -> true },
            onReenabled = { error("提交失败不得通知") })
        try { settings.setEnabled(true) } catch (_: IllegalStateException) {}
        assertFalse(settings.enabled.value) // 提交失败不发布状态
    }
}
