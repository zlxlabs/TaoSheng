package app.resound.probe

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 卡 189 调试探针（仅 debug 源码集，不合入 main）：
 * 验证「通知使用权 + MediaSessionManager.getActiveSessions」能读到哪些媒体会话字段，
 * 以及熄屏 2 小时后监听服务是否仍存活。只写 logcat，tag 固定为 mediaprobe。
 */
class MediaProbeService : NotificationListenerService() {

    private val componentName by lazy { ComponentName(this, MediaProbeService::class.java) }
    private var manager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    // 活跃会话的控制器与其回调，会话列表变化时整批注销
    private val registrations = ArrayList<Pair<MediaController, MediaController.Callback>>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "created at=" + System.currentTimeMillis())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val m = getSystemService(MediaSessionManager::class.java)
        manager = m
        Log.i(TAG, "connected at=" + System.currentTimeMillis())
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            registrations.forEach { (c, cb) -> c.unregisterCallback(cb) }
            registrations.clear()
            (controllers ?: emptyList()).forEach { c ->
                logLine("snapshot", c, c.playbackState, c.metadata)
                registrations.add(c to makeCallback(c))
            }
        }
        sessionsListener = listener
        m.addOnActiveSessionsChangedListener(listener, componentName)
        m.getActiveSessions(componentName).forEach { c ->
            logLine("snapshot", c, c.playbackState, c.metadata)
            registrations.add(c to makeCallback(c))
        }
    }

    override fun onListenerDisconnected() {
        val m = manager
        val listener = sessionsListener
        if (m != null && listener != null) m.removeOnActiveSessionsChangedListener(listener)
        registrations.forEach { (c, cb) -> c.unregisterCallback(cb) }
        registrations.clear()
        Log.i(TAG, "disconnected at=" + System.currentTimeMillis())
    }

    override fun onDestroy() {
        Log.i(TAG, "destroyed at=" + System.currentTimeMillis())
        super.onDestroy()
    }

    private fun makeCallback(controller: MediaController): MediaController.Callback {
        return object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                logLine("state", controller, state, controller.metadata)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                logLine("meta", controller, controller.playbackState, metadata)
            }

            override fun onSessionDestroyed() {
                logLine("gone", controller, controller.playbackState, controller.metadata)
            }
        }
    }

    private fun logLine(event: String, controller: MediaController, state: PlaybackState?, md: MediaMetadata?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val subtitle = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        // getLong 对缺失键返回 0，用 keySet 判断缺失才能区分「时长真的是 0」和「没这个字段」
        val duration =
            if (md != null && md.keySet().contains(MediaMetadata.METADATA_KEY_DURATION))
                md.getLong(MediaMetadata.METADATA_KEY_DURATION).toString() else "null"
        Log.i(
            TAG,
            "$event pkg=${controller.packageName}" +
                " state=${state?.state ?: "null"}" +
                " title=${title ?: "null"}" +
                " artist=${artist ?: "null"}" +
                " album=${album ?: "null"}" +
                " subtitle=${subtitle ?: "null"}" +
                " duration=$duration" +
                " pos=${state?.position ?: "null"}",
        )
    }

    private companion object {
        const val TAG = "mediaprobe"
    }
}
