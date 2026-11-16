package app.resound.screentime

import app.resound.diagnostics.FailureNotes
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
        if (!source.permitted()) {
            FailureNotes.note("screentime/ScreenTimePuller:NoPermission")
            return@withLock PullResult.NoPermission
        }
        val at = now()
        if (at - settings.lastPullAt < PULL_THROTTLE_MILLIS) return@withLock PullResult.Throttled
        try {
            val startedAt = now()
            val state = dao.state(SCREEN_SOURCE)
            val horizon = at - HORIZON_MILLIS
            val covered = state?.coveredUntil ?: 0L
            // 关闭时刻未知（升级用户）且上次覆盖早于本次打开：沿用旧口径把关闭前整段当关闭期间，起点圈到 enabledAt。
            val legacyDisabled = state != null && covered < settings.enabledAt && settings.disabledAt == 0L
            // 从未提交过时不补采历史；已提交过则起点不再受 enabledAt 约束，关闭前仍开着的使用照常补拉。
            val start = when {
                state == null -> maxOf(dao.lastEventAt() ?: 0L, horizon, settings.enabledAt)
                legacyDisabled -> maxOf(dao.lastEventAt() ?: 0L, covered, state.floor, horizon, settings.enabledAt)
                else -> maxOf(dao.lastEventAt() ?: 0L, covered, state.floor, horizon)
            }
            // 缺口随本次事务一起落库：事件源抛错时与覆盖指针一起回滚，下次拉取不会重复写。
            val gaps = buildList {
                state?.coveredUntil?.takeIf { it < horizon }?.let {
                    add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = it, untilAt = horizon, code = "pull_failed"))
                }
                if (state != null && covered < settings.enabledAt && settings.disabledAt > 0) {
                    // 有关闭时刻：缺口从「上次采集」与「关闭时刻」的较大者起算，关闭前仍开着的使用照常入库。
                    val offFrom = maxOf(covered, settings.disabledAt)
                    if (offFrom < settings.enabledAt)
                        add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = offFrom, untilAt = settings.enabledAt, code = "disabled"))
                } else if (legacyDisabled) {
                    add(ScreenCoverageGapRow(source = SCREEN_SOURCE, fromAt = covered, untilAt = settings.enabledAt, code = "disabled"))
                }
            }
            val raw = source.since(start, at)
            val events = raw.filter { it.type in SCREEN_EVENT_TYPES }
                .map { ScreenEventRow(at = it.at, pkg = it.pkg, cls = it.cls ?: "", type = it.type) }
                // 关闭期间系统残留的使用事件不入库（缺口已留痕）。
                .filter { row -> gaps.none { it.code == "disabled" && row.at in it.fromAt until it.untilAt } }
            val names = if (events.isEmpty()) emptyList() else
                source.appNames(events.map { it.pkg }.toSet()).map { (pkg, name) -> ScreenAppNameRow(pkg, name, at) }
            dao.commitPull(events, names, at, gaps)
            settings.markPulled(at)
            diagnostic("screentime: pulled ${events.size} events (${raw.size} raw) in ${now() - startedAt}ms")
            PullResult.Pulled(events.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (denied(e)) {
                FailureNotes.record("screentime/ScreenTimePuller:NoPermission", e)
                diagnostic("screentime: pull denied by ${e::class.simpleName}")
                return@withLock PullResult.NoPermission
            }
            FailureNotes.record("screentime/ScreenTimePuller:Failed", e)
            diagnostic("screentime: pull failed by ${e::class.simpleName}")
            PullResult.Failed(e::class.simpleName ?: "Unknown")
        }
    }

    companion object {
        /** 两次成功拉取的最小间隔。 */
        const val PULL_THROTTLE_MILLIS = 30_000L
        /** 系统使用情况保留期；更早的区间不可补回。 */
        const val HORIZON_MILLIS = 10 * 24 * 60 * 60 * 1000L
    }
}
