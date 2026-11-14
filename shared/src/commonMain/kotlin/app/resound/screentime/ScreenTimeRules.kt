@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.screentime

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 屏幕时间规则：重放系统使用事件得到使用段，再按包或按自然日聚合。全部为纯函数。 */
object ScreenTimeRules {
    /** 段的收尾原因；SWITCH 表示切换 App/页面（含暂停），其余是熄屏、锁屏、关机与覆盖截止。 */
    private enum class CloseReason { SWITCH, SCREEN_OFF, KEYGUARD, SHUTDOWN, STILL_OPEN }

    private data class Seg(val pkg: String, val from: Long, val until: Long, val reason: CloseReason)

    /**
     * 把 [events] 重放为使用段。调用方负责传入 `[from - config.lookbackMillis, until)` 的全部事件，
     * 函数从头重放建立「起点前已在前台」等初始状态，再裁剪到 `[from, until)`。
     * [coveredUntil] 是采集侧已覆盖的截止时刻，重放结束仍开着的段截止于 `min(until, coveredUntil)`。
     * [excluded] 中的包名不输出，但其 RESUMED 照样给上一段收尾。
     */
    fun sessions(
        events: List<ScreenEvent>,
        from: Long,
        until: Long,
        coveredUntil: Long,
        excluded: Set<String>,
        config: ScreenTimeConfig = ScreenTimeConfig(),
    ): List<AppSession> {
        var front: Pair<String, String>? = null // 当前前台页面（pkg, cls）= 最近 RESUMED 且还没离开的
        var screenOn = true
        var keyguard = false
        var openFrom: Long? = null // 当前开着的段开始时刻；开段必然对应 front
        val closed = mutableListOf<Seg>()

        fun close(at: Long, reason: CloseReason) {
            closed += Seg(front!!.first, openFrom!!, at, reason)
            openFrom = null
        }

        // 排序键（时间戳，自增主键）：同一毫秒的事件顺序稳定。
        for (e in events.sortedWith(compareBy({ it.at }, { it.id }))) {
            val page = e.pkg to e.cls
            when (e.type) {
                ScreenEventType.RESUMED -> {
                    if (front != null && front != page && openFrom != null) close(e.at, CloseReason.SWITCH)
                    front = page
                    // 同包同页重复 RESUMED 不重复开段；熄屏或锁屏中只更新前台不开段。
                    if (screenOn && !keyguard && openFrom == null) openFrom = e.at
                }
                ScreenEventType.PAUSED, ScreenEventType.STOPPED -> {
                    // 只在离开的页面正是当前前台页面时收尾；同 App 切页时旧页的 STOPPED 被忽略。
                    if (front == page) {
                        if (openFrom != null) close(e.at, CloseReason.SWITCH)
                        front = null
                    }
                }
                ScreenEventType.SCREEN_OFF -> {
                    if (openFrom != null) close(e.at, CloseReason.SCREEN_OFF)
                    screenOn = false
                }
                ScreenEventType.KEYGUARD_SHOWN -> {
                    if (openFrom != null) close(e.at, CloseReason.KEYGUARD)
                    keyguard = true
                }
                ScreenEventType.SHUTDOWN, ScreenEventType.STARTUP -> {
                    if (openFrom != null) close(e.at, CloseReason.SHUTDOWN)
                    front = null
                    screenOn = true
                    keyguard = false
                }
                ScreenEventType.SCREEN_ON -> {
                    screenOn = true
                    if (!keyguard && front != null && openFrom == null) openFrom = e.at
                }
                ScreenEventType.KEYGUARD_HIDDEN -> {
                    keyguard = false
                    if (front != null && openFrom == null) openFrom = e.at
                }
            }
        }
        // 重放结束仍开着的段：截止于 min(until, coveredUntil)，不截止于「此刻」。
        openFrom?.let { start ->
            val end = minOf(until, coveredUntil)
            if (end > start) closed += Seg(front!!.first, start, end, CloseReason.STILL_OPEN)
        }

        // 输出前：过滤、裁剪、合并抖动、丢零长度段。
        val merged = mutableListOf<Seg>()
        val lastIndexByPkg = HashMap<String, Int>()
        for (seg in closed
            .filter { it.pkg !in excluded }
            .map { s -> s.copy(from = maxOf(s.from, from), until = minOf(s.until, until)) }
            .filter { it.from < it.until }
        ) {
            val idx = lastIndexByPkg[seg.pkg]
            val prev = idx?.let { merged[it] }
            // 同包上一段因切换收尾且间隔在抖动窗口内才合并；合并段继承最后一段的收尾原因。
            if (prev != null && prev.reason == CloseReason.SWITCH && seg.from - prev.until <= config.mergeGapMillis) {
                merged[idx] = prev.copy(until = seg.until, reason = seg.reason)
            } else {
                lastIndexByPkg[seg.pkg] = merged.size
                merged += seg
            }
        }
        return merged.map { AppSession(it.pkg, it.from, it.until) }
    }

    /** 按包聚合总时长：时长降序，同时长按包名升序。 */
    fun totals(sessions: List<AppSession>): List<AppTotal> =
        sortTotals(sessions.groupBy { it.pkg }.map { (pkg, list) -> AppTotal(pkg, list.sumOf { it.until - it.from }) })

    /** 按时区的自然日聚合；跨零点的段拆到两天。每天的列表排序同 [totals]。 */
    fun dayTotals(sessions: List<AppSession>, zone: TimeZone): Map<LocalDate, List<AppTotal>> {
        val byDay = HashMap<LocalDate, HashMap<String, Long>>()
        for (session in sessions) {
            var cursor = session.from
            while (cursor < session.until) {
                val date = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(zone).date
                val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                val part = minOf(session.until, dayEnd) - cursor
                val day = byDay.getOrPut(date) { HashMap() }
                day[session.pkg] = (day[session.pkg] ?: 0L) + part
                cursor = minOf(session.until, dayEnd)
            }
        }
        return byDay.mapValues { (_, millis) -> sortTotals(millis.map { (pkg, ms) -> AppTotal(pkg, ms) }) }
    }

    private fun sortTotals(list: List<AppTotal>): List<AppTotal> =
        list.sortedWith(compareByDescending<AppTotal> { it.millis }.thenBy { it.pkg })
}
