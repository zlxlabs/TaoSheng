package app.resound.screentime

/**
 * 屏幕时间的读取约定（Claude 定稿，签名不得自行修改）。
 * 问问工具、推送、订阅文档、情境行都只依赖本文件；实现是 ScreenTimeQueries（卡 186a）。
 * 区间一律左闭右开、纪元毫秒。
 */

/** 带显示名的使用段。[name] 已脱敏（去换行、替换【】｜《》、截到 40 码点）；查不到显示名时等于 [pkg]。 */
data class NamedAppSession(val pkg: String, val name: String, val from: Long, val until: Long)

/** 覆盖缺口。[code] 取 [GapCode] 之一；[source] 为 [SCREEN_SOURCE] 或 "playback"；[fix] 是一句中文修复办法。 */
data class CoverageGap(val code: String, val source: String, val from: Long, val until: Long, val fix: String)

/**
 * 一次查询的结果。[enabled] 为 false 表示屏幕时间总开关关闭：其余字段为空，调用方不得输出任何屏幕数据。
 * [coverageUntil] 是已成功采集到的时刻（可能早于查询终点）；从未采集过为 null。
 */
data class ScreenTimeResult(
    val enabled: Boolean,
    val sessions: List<NamedAppSession>,
    val coverageUntil: Long?,
    val gaps: List<CoverageGap>,
) {
    companion object {
        val DISABLED = ScreenTimeResult(enabled = false, sessions = emptyList(), coverageUntil = null, gaps = emptyList())
    }
}

/** 缺口代码，共六个；coverage_lag 只在查询时现算，不落库。 */
object GapCode {
    const val USAGE_ACCESS_MISSING = "usage_access_missing"
    const val NOTIFICATION_ACCESS_MISSING = "notification_access_missing"
    const val PULL_FAILED = "pull_failed"
    const val COVERAGE_LAG = "coverage_lag"
    const val LISTENER_DISCONNECTED = "listener_disconnected"
    const val DISABLED = "disabled"
}

/** 屏幕时间读取入口。实现负责总开关门控、查询前增量拉取与缺口汇总。 */
fun interface ScreenTimeReader {
    suspend fun sessions(from: Long, until: Long): ScreenTimeResult
}
