package app.resound.screentime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 屏幕时间总开关，默认关闭；关闭时不调用事件源、不拉取。写法照 DigestPreferenceStore：成功提交后才发布状态。 */
class ScreenTimeSettings(
    readBoolean: (String, Boolean) -> Boolean,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val readLong: (String, Long) -> Long,
    private val saveLong: (String, Long) -> Boolean,
    private val now: () -> Long,
) {
    private val _enabled = MutableStateFlow(readBoolean(KEY, false))
    val enabled = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        val was = _enabled.value
        check(saveBoolean(KEY, on))
        // 从关到开时记录打开时刻：拉取起点与 disabled 缺口都以此为界，关闭期间不补采。
        if (!was && on) check(saveLong(ENABLED_AT_KEY, now()))
        // 从开到关时记录关闭时刻：disabled 缺口从这里起算，关闭前仍开着的使用不丢。
        if (was && !on) check(saveLong(DISABLED_AT_KEY, now()))
        _enabled.value = on
    }

    /** 最近一次从关到开的时刻（毫秒）；从未打开为 0。 */
    val enabledAt: Long get() = readLong(ENABLED_AT_KEY, 0)

    /** 最近一次从开到关的时刻（毫秒）；从未关闭为 0。 */
    val disabledAt: Long get() = readLong(DISABLED_AT_KEY, 0)

    /** 上次成功拉取时刻（毫秒）；0 表示从未拉过，只作节流判据，不进数据库。 */
    var lastPullAt: Long
        get() = readLong(LAST_PULL_KEY, 0)
        private set(value) { check(saveLong(LAST_PULL_KEY, value)) }
    internal fun markPulled(at: Long) { lastPullAt = at }

    companion object {
        const val KEY = "screenTimeEnabled"
        const val LAST_PULL_KEY = "screenTimeLastPullAt"
        const val ENABLED_AT_KEY = "screenTimeEnabledAt"
        const val DISABLED_AT_KEY = "screenTimeDisabledAt"
    }
}
