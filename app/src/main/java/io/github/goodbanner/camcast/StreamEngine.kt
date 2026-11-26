package io.github.goodbanner.camcast

import android.media.MediaCodecInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * RootEncoder（jitpack 2.8.2 AAR）封装：参数应用、预览生命周期、推流控制、断线重连。
 *
 * 类层次（以 AAR 实际字节码为准）：RtmpCamera2 → Camera2Base → Object
 * （StreamBase 是平行基类，不在本链上，其 SurfaceView 版 startPreview 不可用）。
 *
 * 可用 API 与参数顺序（Camera2Base.java）：
 *   prepareVideo(width, height, fps, bitrate, iFrameInterval, rotation, profile, level)
 *   prepareAudio(bitrate, sampleRate, isStereo)
 *   startPreview()            —— 渲染到构造器传入的 OpenGlView，需先 prepareVideo
 *   stopPreview()             —— 推流中调用被忽略（源码内置保护）
 *
 * 硬顺序：prepareVideo/prepareAudio → startPreview → startStream
 *
 * 所有状态经 emit() 输出：logcat tag=CamCast + 界面状态栏。
 */
class StreamEngine(
    private val glView: OpenGlView,
    private val onEvent: (String) -> Unit,
) : ConnectChecker {

    private val rtmp = RtmpCamera2(glView, this)
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
        Log.i(TAG, "apply: ${p.width}x${p.height}@${p.fps} br=${p.bitrateKbps}k " +
            "gop=${p.gopSec} rot=${p.rotation} profile=${p.profile} " +
            "audio=${p.audioOn}/${p.sampleRate}/${p.audioBitrateKbps}k url=${p.url}")
        try {
            if (wasStreaming) rtmp.stopStream()
            if (rtmp.isOnPreview) rtmp.stopPreview()

            val okVideo = rtmp.prepareVideo(
                p.width, p.height, p.fps, p.bitrateKbps * 1000,
                p.gopSec, p.rotation, avcProfile(p.profile), -1
            )
            Log.i(TAG, "prepareVideo -> $okVideo")
            if (!okVideo) {
                emit("prepareVideo 失败：${p.width}x${p.height}@${p.fps} 设备不支持，换相邻档位")
                return false
            }

            if (p.audioOn) {
                val okAudio = rtmp.prepareAudio(p.audioBitrateKbps * 1000, p.sampleRate, true)
                Log.i(TAG, "prepareAudio -> $okAudio")
                if (!okAudio) {
                    // 麦克风被占用/参数不支持时不整体失败，降级纯视频
                    Log.w(TAG, "prepareAudio failed, falling back to video-only")
                    emit("音频准备失败(${p.sampleRate}/${p.audioBitrateKbps}k)，降级纯视频推流")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "apply exception during prepare", e)
            emit("参数被拒绝: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        selectFrontCamera()
        prepared = true
        openPreviewIfPossible()
        // 预览触发兜底：surface 可能稍后才就绪，holder 回调 + 焦点 + 定时重试三重回宿
        glView.postDelayed({ Log.i(TAG, "retry300"); onSurfaceReady() }, 300)
        glView.postDelayed({ Log.i(TAG, "retry1000"); onSurfaceReady() }, 1000)
        glView.postDelayed({ Log.i(TAG, "retry2000"); onSurfaceReady() }, 2000)
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
        if (!prepared && !apply(prefs)) {
            Log.w(TAG, "start blocked: apply failed")
            return false
        }
        openPreviewIfPossible()
        if (!rtmp.isStreaming) {
            Log.i(TAG, "startStream ${prefs.url}")
            rtmp.startStream(prefs.url)
        }
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
            Log.e(TAG, "switchCamera failed", e)
            emit("切换失败: ${e.message}")
        }
    }

    /** SurfaceView（OpenGlView 底层）可用时由 Activity 调用。 */
    fun onSurfaceReady() {
        Log.i(TAG, "onSurfaceReady prepared=$prepared isOnPreview=${rtmp.isOnPreview}")
        if (prepared) openPreviewIfPossible()
    }

    /** Surface 被销毁（退后台/熄屏）时由 Activity 调用；推流中内部会忽略。 */
    fun onSurfaceGone() {
        if (rtmp.isOnPreview) runCatching { rtmp.stopPreview() }
    }

    private fun openPreviewIfPossible() {
        if (!prepared) { Log.d(TAG, "preview skip: not prepared"); return }
        if (rtmp.isOnPreview) { Log.d(TAG, "preview skip: already on preview"); return }
        if (!glView.holder.surface.isValid) { Log.d(TAG, "preview skip: surface invalid"); return }
        try {
            Log.i(TAG, "startPreview()")
            rtmp.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            emit("startPreview 失败: ${e.message}")
        }
    }

    /** 首次进入把默认摄像头切到前置（多摄设备；空闲时 switchCamera = 改 facing 偏好）。 */
    private fun selectFrontCamera() {
        if (frontTried) return
        frontTried = true
        try {
            val cams = rtmp.getCamerasAvailable()
            Log.i(TAG, "cameras=${cams?.joinToString(",")}")
            if (cams != null && cams.size > 1 && !rtmp.isOnPreview && !rtmp.isStreaming) {
                rtmp.switchCamera()
                Log.i(TAG, "default camera -> FRONT")
                emit("默认摄像头已切到前置")
            }
        } catch (e: Exception) {
            Log.e(TAG, "selectFrontCamera failed", e)
            emit("默认前置设置失败: ${e.message}")
        }
    }

    private fun avcProfile(name: String): Int = when (name) {
        "baseline" -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        "main" -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        else -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
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
            if (streaming && !rtmp.isStreaming) {
                Log.i(TAG, "auto reconnect -> ${prefs.url}")
                rtmp.startStream(prefs.url)
            }
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

    companion object {
        private const val TAG = "CamCast"
    }
}
