package app.resound.screentime

/** 六种缺口的修复文案已定死；落库行遇到未知代码直接抛错（fail fast）。 */
private fun fixFor(code: String): String = when (code) {
    GapCode.USAGE_ACCESS_MISSING -> "设置 → 使用情况访问 → 回响 开启"
    GapCode.NOTIFICATION_ACCESS_MISSING -> "设置 → 通知使用权 → 回响 开启"
    GapCode.PULL_FAILED -> "这段超出系统保留期或采集失败，无法补回"
    GapCode.COVERAGE_LAG -> "打开回响，等待下一次采集"
    GapCode.LISTENER_DISCONNECTED -> "设置 → 通知使用权 → 回响 重新开启"
    GapCode.DISABLED -> "屏幕时间关闭期间未采集"
    else -> throw IllegalArgumentException("未知缺口代码: $code")
}

/**
 * [ScreenTimeReader] 的唯一实现：总开关门控、查询前增量拉取、按 185a 规则算使用段、
 * 拼显示名并脱敏、汇总覆盖缺口。问问、推送、订阅文档、情境行都只通过本类取数。
 * 无授权时已落库历史照常返回，只追加 [GapCode.USAGE_ACCESS_MISSING]。
 */
class ScreenTimeQueries(
    private val dao: ScreenTimeDao,
    private val puller: ScreenTimePuller,
    private val settings: ScreenTimeSettings,
    /** 使用情况访问权限；只影响缺口的呈现，与拉取器内部判据同源，由装配层传同一实现。 */
    private val permitted: () -> Boolean,
    /** 通知使用权；只影响 playback 缺口的呈现。已落库的播放行在没有授权时照常返回。 */
    private val playbackPermitted: () -> Boolean,
    private val excluded: Set<String>,
    private val now: () -> Long,
    private val config: ScreenTimeConfig = ScreenTimeConfig(),
) : ScreenTimeReader {
    override suspend fun sessions(from: Long, until: Long): ScreenTimeResult {
        require(until > from) { "until($until) 必须晚于 from($from)" }
        if (!settings.enabled.value) return ScreenTimeResult.DISABLED
        val pull = puller.pull()
        val state = dao.state(SCREEN_SOURCE)
        val coveredUntil = state?.coveredUntil
        val gaps = mutableListOf<CoverageGap>()
        // ① 落库缺口（DAO 已按 fromAt 排序；再排一次锁死契约）。
        gaps += dao.gaps(SCREEN_SOURCE, from, until)
            .map { CoverageGap(it.code, it.source, it.fromAt, it.untilAt, fixFor(it.code)) }
            .sortedBy { it.from }
        // ② 无授权。
        if (!permitted()) {
            gaps += CoverageGap(GapCode.USAGE_ACCESS_MISSING, SCREEN_SOURCE, from, until, fixFor(GapCode.USAGE_ACCESS_MISSING))
        }
        // ③ coverage_lag：此刻减 coveredUntil 超过 1 小时且与查询区间相交时现算，不落库。
        val t = now()
        if (t - (coveredUntil ?: 0) > COVERAGE_LAG_MILLIS && (coveredUntil ?: from) < until) {
            val lagFrom = maxOf(from, coveredUntil ?: from)
            val lagUntil = minOf(until, t)
            if (lagFrom < lagUntil) {
                gaps += CoverageGap(GapCode.COVERAGE_LAG, SCREEN_SOURCE, lagFrom, lagUntil, fixFor(GapCode.COVERAGE_LAG))
            }
        }
        // ④ 查询前的增量拉取失败：不阻断查询，返回已落库数据并加一条 pull_failed。
        if (pull is PullResult.Failed) {
            val failFrom = maxOf(coveredUntil ?: from, from)
            val failUntil = minOf(until, now())
            if (failFrom < failUntil) {
                gaps += CoverageGap(GapCode.PULL_FAILED, SCREEN_SOURCE, failFrom, failUntil, fixFor(GapCode.PULL_FAILED))
            }
        }
        // ⑤ playback 落库缺口；until 裁到 min(untilAt, now())。播放查询不触发拉取。
        val nowAt = now()
        gaps += dao.gaps(PLAYBACK_SOURCE, from, until)
            .map { CoverageGap(it.code, it.source, it.fromAt, minOf(it.untilAt, nowAt), fixFor(it.code)) }
            .sortedBy { it.from }
        if (!playbackPermitted()) {
            gaps += CoverageGap(
                GapCode.NOTIFICATION_ACCESS_MISSING, PLAYBACK_SOURCE, from, until,
                fixFor(GapCode.NOTIFICATION_ACCESS_MISSING),
            )
        }
        val names = dao.names().associate { it.pkg to it.name }
        val events = dao.events(from - config.lookbackMillis, until).mapNotNull { row ->
            ScreenEventType.of(row.type)?.let { ScreenEvent(row.id, row.at, row.pkg, row.cls, it) }
        }
        val sessions = ScreenTimeRules
            .sessions(events, from, until, coveredUntil ?: from, excluded, config)
            .map {
                NamedAppSession(
                    it.pkg,
                    ScreenTimeText.clean(names[it.pkg] ?: it.pkg, config.titleMaxCodePoints),
                    it.from, it.until,
                )
            }
        val devicePlayback = dao.playbacksOverlapping(from, until).mapNotNull { row ->
            val start = maxOf(row.fromAt, from)
            val end = minOf(row.untilAt ?: row.lastSeenAt, until)
            if (end <= start) null
            else DevicePlayback(
                row.pkg,
                ScreenTimeText.clean(row.name, config.titleMaxCodePoints),
                row.title?.let { ScreenTimeText.clean(it, config.titleMaxCodePoints) },
                row.subtitle?.let { ScreenTimeText.clean(it, config.titleMaxCodePoints) },
                start, end,
            )
        }.sortedBy { it.from }
        return ScreenTimeResult(
            enabled = true, sessions = sessions, coverageUntil = coveredUntil, gaps = gaps,
            devicePlayback = devicePlayback,
        )
    }

    private companion object {
        /** coverage_lag 的触发阈值（设计 dx：此刻减 coveredUntil 超过 1 小时）。 */
        const val COVERAGE_LAG_MILLIS = 3_600_000L
    }
}
