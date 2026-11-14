package app.resound.screentime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次拉取的结果。Failed.type 是异常类名，只用于计数与告警，不携带包名或时间线。 */
sealed class PullResult {
    data class Pulled(val count: Int) : PullResult()
    data object Disabled : PullResult()
    data object NoPermission : PullResult()
    data object Throttled : PullResult()
    data class Failed(val type: String) : PullResult()
}

/** 把系统使用情况事件搬进 screen_events。定时任务与查询前拉取共用同一实例（AppGraph 装配为单例），全程串行。 */
class ScreenTimePuller(
    private val settings: ScreenTimeSettings,
    private val source: ScreenEventSource,
    private val dao: ScreenTimeDao,
    private val now: () -> Long,
    /** 拉取途中的授权异常判据（安卓传 SecurityException）；命中返回 NoPermission。 */
    private val denied: (Throwable) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
) {
    private val gate = Mutex()

    suspend fun pull(): PullResult = gate.withLock {
        // 开关关闭时不碰事件源的任何方法。
        if (!settings.enabled.value) return@withLock PullResult.Disabled
        if (!source.permitted()) return@withLock PullResult.NoPermission
        val at = now()
        if (at - settings.lastPullAt < PULL_THROTTLE_MILLIS) return@withLock PullResult.Throttled
        try {
            val startedAt = now()
            val state = dao.state(SCREEN_SOURCE)
            val horizon = at - HORIZON_MILLIS
            val start = maxOf(dao.lastEventAt() ?: 0L, state?.coveredUntil ?: 0L, state?.floor ?: 0L, horizon, settings.enabledAt)
            // 停采超出系统保留期的部分已不可补回，先落缺口留痕。
            state?.coveredUntil?.takeIf { it < horizon }?.let {
                dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = it, untilAt = horizon, code = "pull_failed"))
            }
            // 总开关关闭期间的区间不补采，落 disabled 缺口；提交后 coveredUntil 前进，下次不会重复写。
            state?.coveredUntil?.takeIf { it < settings.enabledAt }?.let {
                dao.recordGap(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = it, untilAt = settings.enabledAt, code = "disabled"))
            }
            val raw = source.since(start, at)
            val events = raw.filter { it.type in SCREEN_EVENT_TYPES }
                .map { ScreenEventRow(at = it.at, pkg = it.pkg, cls = it.cls ?: "", type = it.type) }
            val names = if (events.isEmpty()) emptyList() else
                source.appNames(events.map { it.pkg }.toSet()).map { (pkg, name) -> ScreenAppNameRow(pkg, name, at) }
            dao.commitPull(events, names, at)
            settings.markPulled(at)
            diagnostic("screentime: pulled ${events.size} events (${raw.size} raw) in ${now() - startedAt}ms")
            PullResult.Pulled(events.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (denied(e)) {
                diagnostic("screentime: pull denied by ${e::class.simpleName}")
                return@withLock PullResult.NoPermission
            }
            diagnostic("screentime: pull failed by ${e::class.simpleName}")
            PullResult.Failed(e::class.simpleName ?: "Unknown")
        }
    }

    companion object {
        /** 两次成功拉取的最小间隔；185a 的 ScreenTimeConfig 合并后改引它。 */
        const val PULL_THROTTLE_MILLIS = 30_000L
        /** 系统使用情况保留期；更早的区间不可补回。 */
        const val HORIZON_MILLIS = 10 * 24 * 60 * 60 * 1000L
    }
}
