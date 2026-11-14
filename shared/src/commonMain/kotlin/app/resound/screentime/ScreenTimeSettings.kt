package app.resound.screentime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 屏幕时间总开关，默认关闭；关闭时不调用事件源、不拉取。写法照 DigestPreferenceStore：成功提交后才发布状态。 */
class ScreenTimeSettings(
    readBoolean: (String, Boolean) -> Boolean,
    private val saveBoolean: (String, Boolean) -> Boolean,
    private val readLong: (String, Long) -> Long,
    private val saveLong: (String, Long) -> Boolean,
    /** 从关到开时通知；装配层据此异步查覆盖进度、补记 disabled 缺口（fromAt=coveredUntil，untilAt=此刻）。 */
    private val onReenabled: () -> Unit = {},
) {
    private val _enabled = MutableStateFlow(readBoolean(KEY, false))
    val enabled = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        val was = _enabled.value
        check(saveBoolean(KEY, on))
        if (!was && on) onReenabled()
        _enabled.value = on
    }

    /** 上次成功拉取时刻（毫秒）；0 表示从未拉过，只作节流判据，不进数据库。 */
    var lastPullAt: Long
        get() = readLong(LAST_PULL_KEY, 0)
        private set(value) { check(saveLong(LAST_PULL_KEY, value)) }
    internal fun markPulled(at: Long) { lastPullAt = at }

    companion object {
        const val KEY = "screenTimeEnabled"
        const val LAST_PULL_KEY = "screenTimeLastPullAt"
    }
}
