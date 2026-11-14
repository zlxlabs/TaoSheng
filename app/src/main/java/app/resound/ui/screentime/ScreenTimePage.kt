package app.resound.ui.screentime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.capture.MicHolderGuess
import app.resound.screentime.ScreenTimeSettings
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick

/** 设置 → 屏幕时间：总开关、使用情况访问授权引导。 */
@Composable
fun ScreenTimePage(settings: ScreenTimeSettings, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val guess = remember { MicHolderGuess(context.applicationContext) }
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(guess.permitted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = guess.permitted() }
    var saveError by remember { mutableStateOf(false) }
    var askUsage by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记录屏幕时间", style = RsType.headline, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打开后，回响每小时从系统读取 App 使用记录，用来回答“那段时间在用什么”、生成每日屏幕时间，并在整理时标注当时在用的 App。关闭后不再采集，已记录的数据保留但不会被问问、推送或整理用到；要删除请用隐私删除。",
                        style = RsType.subhead,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Toggle(enabled) { on ->
                    saveError = false
                    try {
                        settings.setEnabled(on)
                        if (on && !guess.permitted()) askUsage = true
                    } catch (_: IllegalStateException) {
                        saveError = true
                    }
                }
            }
        }
        if (saveError) {
            Text("保存失败，请重试", style = RsType.subhead, color = c.warning, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (enabled) {
            RsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "使用情况访问：" + if (permitted) "已开启" else "未开启",
                        style = RsType.headline,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (!permitted) {
                        Text(
                            "去开启",
                            style = RsType.headline,
                            color = c.accent,
                            modifier = Modifier.springClick { guess.openSettings() },
                        )
                    }
                }
            }
        }
    }
    if (askUsage) {
        RsSheet("需要“使用情况访问”权限才能读取 App 使用记录", onDismiss = { askUsage = false }) {
            ActionButton("去开启", ActionKind.Primary) {
                guess.openSettings()
                askUsage = false
            }
            ActionButton("稍后", ActionKind.Secondary) { askUsage = false }
        }
    }
}
