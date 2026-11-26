package io.github.goodbanner.camcast

import android.content.Context
import android.media.MediaCodecInfo
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2

/**
 * RootEncoder 封装：参数应用、预览生命周期、推流控制、断线重连。
 *
 * 硬顺序（来自 Camera2Base/StreamBase 源码注释）：
 *   prepareVideo/prepareAudio → startPreview → startStream
 * 任何参数变更都必须整体重走一遍。
 *
 * 注意：RtmpCamera2 的可调 API 由 Java 子类 Camera2Base 提供，参数顺序为
 *   prepareVideo(width, height, fps, bitrate, iFrameInterval, rotation, profile, level)
 *   prepareAudio(bitrate, sampleRate, isStereo)
 * 与 StreamBase.kt 的 Kotlin 重载顺序不同，调用一律使用上述 Java 顺序。
 */
class StreamEngine(
    context: Context,
    private val surfaceView: SurfaceView,
    private val onEvent: (String) -> Unit,
) : ConnectChecker {

    private val rtmp = RtmpCamera2(context.applicationContext, this)
    private val handler = Handler(Looper.getMainLooper())

    var prefs: StreamPrefs = StreamPrefs()
        private set

    private var prepared = false
    private var frontTried = false

    @Volatile
    var streaming = false
        private set

    /** 应用参数：停一切 → prepare → 重开预览 → 需要时恢复推流。 */
    fun apply(p: StreamPrefs): Boolean {
        val wasStreaming = rtmp.isStreaming
        prefs = p
        try {
            if (wasStreaming) rtmp.stopStream()
            if (rtmp.isOnPreview) rtmp.stopPreview()

            val okVideo = rtmp.prepareVideo(
                p.width, p.height, p.fps, p.bitrateKbps * 1000,
                p.gopSec, p.rotation, avcProfile(p.profile), -1
            )
            if (!okVideo) {
                emit("prepareVideo 失败：${p.width}x${p.height}@${p.fps} 设备不支持，换相邻档位")
                return false
            }

            if (p.audioOn) {
                val okAudio = rtmp.prepareAudio(p.audioBitrateKbps * 1000, p.sampleRate, true)
                if (!okAudio) {
                    emit("prepareAudio 失败：${p.sampleRate}/${p.audioBitrateKbps}k 不支持")
                    return false
                }
            }
        } catch (e: Exception) {
            emit("参数被拒绝: ${e.message}")
            return false
        }

        selectFrontCamera()
        prepared = true
        openPreviewIfPossible()
        if (wasStreaming) {
            streaming = true
            rtmp.startStream(p.url)
        }
        emit(
            "参数已应用 ${p.width}x${p.height}@${p.fps} ${p.bitrateKbps}kbps " +
                "${p.profile} gop=${p.gopSec}s rot=${p.rotation}" +
                (if (p.audioOn) " audio=${p.sampleRate}/${p.audioBitrateKbps}k" else " 静音")
        )
        return true
    }

    /** 开始推流（必要时先走一遍 apply 完成 prepare + preview）。 */
    fun start(): Boolean {
        if (!prepared && !apply(prefs)) return false
        openPreviewIfPossible()
        if (!rtmp.isStreaming) rtmp.startStream(prefs.url)
        streaming = true
        emit("正在连接 ${prefs.url}")
        return true
    }

    fun stop() {
        streaming = false
        handler.removeCallbacksAndMessages(null)
        if (rtmp.isStreaming) rtmp.stopStream()
        emit("已停止推流")
    }

    fun switchCamera() {
        try {
            rtmp.switchCamera()
            emit("已切换摄像头")
        } catch (e: Exception) {
            emit("切换失败: ${e.message}")
        }
    }

    /** SurfaceView 变为可用时由 Activity 调用。 */
    fun onSurfaceReady() {
        if (prepared) openPreviewIfPossible()
    }

    /** SurfaceView 被销毁（退后台/熄屏）时由 Activity 调用。 */
    fun onSurfaceGone() {
        if (rtmp.isOnPreview) runCatching { rtmp.stopPreview() }
    }

    private fun openPreviewIfPossible() {
        if (!prepared || rtmp.isOnPreview) return
        if (!surfaceView.holder.surface.isValid) return
        runCatching { rtmp.startPreview(surfaceView) }
            .onFailure { emit("startPreview 失败: ${it.message}") }
    }

    /** 首次进入把默认摄像头切到前置（多摄设备；空闲时 switchCamera = 改 facing 偏好）。 */
    private fun selectFrontCamera() {
        if (frontTried) return
        frontTried = true
        try {
            val cams = rtmp.getCamerasAvailable()
            if (cams != null && cams.size > 1 && !rtmp.isOnPreview && !rtmp.isStreaming) {
                rtmp.switchCamera()
                emit("默认摄像头已切到前置")
            }
        } catch (e: Exception) {
            emit("默认前置设置失败: ${e.message}")
        }
    }

    private fun avcProfile(name: String): Int = when (name) {
        "baseline" -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        "main" -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        else -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    }

    private fun emit(msg: String) {
        handler.post { onEvent(msg) }
    }

    private fun scheduleReconnect(reason: String) {
        emit("连接失败: $reason")
        queueReconnect()
    }

    private fun queueReconnect() {
        val delay = prefs.reconnectMs
        if (!streaming || delay <= 0) return
        handler.postDelayed({
            if (streaming && !rtmp.isStreaming) rtmp.startStream(prefs.url)
        }, delay.toLong())
    }

    // ---- ConnectChecker（RootEncoder 2.8.2，6 个回调）----

    override fun onConnectionStarted(url: String) = emit("连接中: $url")

    override fun onConnectionSuccess() = emit("已连接，推流中")

    override fun onConnectionFailed(reason: String) = scheduleReconnect(reason)

    override fun onDisconnect() {
        emit("已断开")
        queueReconnect()
    }

    override fun onAuthError() = emit("鉴权失败，检查 URL 里的 stream key")

    override fun onAuthSuccess() = emit("鉴权通过")
}
