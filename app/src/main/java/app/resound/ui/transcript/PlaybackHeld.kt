package app.resound.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.resound.transcribe.SegmentTranscript
import app.resound.transcribe.TranscriptStatus
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill

/** 已转写片段：外放时录的默认不参与整理，可手动放行。 */
@Composable
internal fun PlaybackHeldDone(seg: SegmentTranscript, onAllowPlaybackDigest: () -> Unit) {
    Column {
        SpeakerLines(seg.lines)
        if (seg.playbackHeld) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("外放时录的，可能是节目声音，暂不整理成事件", modifier = Modifier.weight(1f))
                Pill("参与整理", filled = false, onClick = onAllowPlaybackDigest)
            }
        }
    }
}

@Composable
internal fun StatusChip(seg: SegmentTranscript) {
    val c = Rs.colors
    val (text, color) = when (seg.status) {
        TranscriptStatus.Pending -> "等待中" to c.textTertiary
        TranscriptStatus.Running -> "转写中" to c.accent
        TranscriptStatus.Failed -> "没转成功" to c.critical
        TranscriptStatus.Empty -> "没有文字" to c.textTertiary
        TranscriptStatus.Done -> if (seg.playbackHeld) "外放 · 不参与整理" to c.textTertiary
            else if (seg.audioAvailable) return else "录音已删" to c.textTertiary
        TranscriptStatus.SkippedPlayback -> "外放时录的" to c.textTertiary
        TranscriptStatus.Abandoned -> "无法转写" to c.textTertiary
        TranscriptStatus.Quiet -> "已忽略" to c.textTertiary
    }
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = RsType.caption, color = color)
    }
}
