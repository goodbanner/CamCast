package io.github.goodbanner.camcast

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.pedro.library.view.OpenGlView
import java.io.File

class MainActivity : Activity() {

    private lateinit var glView: OpenGlView
    private lateinit var engine: StreamEngine
    private lateinit var status: TextView

    private lateinit var eUrl: EditText
    private lateinit var eWidth: EditText
    private lateinit var eHeight: EditText
    private lateinit var eFps: EditText
    private lateinit var eBitrate: EditText
    private lateinit var eGop: EditText
    private lateinit var spRotation: Spinner
    private lateinit var spProfile: Spinner
    private lateinit var swAudio: Switch
    private lateinit var eSampleRate: EditText
    private lateinit var eAudioBitrate: EditText
    private lateinit var eReconnect: EditText
    private lateinit var ePresetName: EditText
    private lateinit var btnStream: Button

    private var prefs = StreamPrefs()

    private val rotationItems = listOf("0", "90", "180", "270")
    private val profileItems = listOf("baseline", "main", "high")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = StreamPrefs.load(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        glView = OpenGlView(this)
        root.addView(glView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        status = TextView(this).apply {
            text = "CamCast 就绪，等待权限"
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(status)
        buildFields(panel)

        val scroll = ScrollView(this)
        scroll.addView(
            panel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f))
        setContentView(root)

        engine = StreamEngine(glView) { msg ->
            status.text = msg
            btnStream.text = if (engine.streaming) "停止推流" else "开始推流"
            // 文件埋点：绕开本机 logd 按 UID 限速
            runCatching {
                java.io.File(filesDir, "camcast.log")
                    .appendText("${System.currentTimeMillis() % 1000000} $msg\n")
            }
        }

        glView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                android.util.Log.i("CamCast", "surfaceCreated")
                engine.onSurfaceReady()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = engine.onSurfaceReady()
            override fun surfaceDestroyed(h: SurfaceHolder) {
                android.util.Log.i("CamCast", "surfaceDestroyed")
                engine.onSurfaceGone()
            }
        })

        if (hasAllPermissions()) engineApply() else requestPermissions(permissions(), REQ_PERMISSIONS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("CamCast", "onWindowFocusChanged hasFocus=$hasFocus")
        if (hasFocus && ::engine.isInitialized) engine.onSurfaceReady()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                engineApply()
            } else {
                status.text = "需要相机与录音权限才能预览/推流"
            }
        }
    }

    // ---------------- UI 构建 ----------------

    private fun buildFields(panel: LinearLayout) {
        eUrl = field(prefs.url, "RTMP 推流地址", InputType.TYPE_CLASS_TEXT)
        eWidth = field(prefs.width.toString(), "宽", InputType.TYPE_CLASS_NUMBER)
        eHeight = field(prefs.height.toString(), "高", InputType.TYPE_CLASS_NUMBER)
        eFps = field(prefs.fps.toString(), "帧率", InputType.TYPE_CLASS_NUMBER)
        eBitrate = field(prefs.bitrateKbps.toString(), "视频码率 kbps", InputType.TYPE_CLASS_NUMBER)
        eGop = field(prefs.gopSec.toString(), "关键帧间隔 s", InputType.TYPE_CLASS_NUMBER)
        spRotation = spinner(rotationItems, prefs.rotation.toString())
        spProfile = spinner(profileItems, prefs.profile)
        swAudio = Switch(this).apply { isChecked = prefs.audioOn }
        eSampleRate = field(prefs.sampleRate.toString(), "采样率", InputType.TYPE_CLASS_NUMBER)
        eAudioBitrate = field(prefs.audioBitrateKbps.toString(), "音频码率 kbps", InputType.TYPE_CLASS_NUMBER)
        eReconnect = field(prefs.reconnectMs.toString(), "断线重连 ms", InputType.TYPE_CLASS_NUMBER)
        ePresetName = field("", "预设名称", InputType.TYPE_CLASS_TEXT)

        row(panel, "推流 URL", eUrl)
        row(panel, "分辨率", pair(eWidth, eHeight))
        row(panel, "帧率/视频码率", pair(eFps, eBitrate))
        row(panel, "关键帧间隔(s)", eGop)
        row(panel, "旋转(度)", spRotation)
        row(panel, "编码 Profile", spProfile)
        row(panel, "音频开关", swAudio)
        row(panel, "音频 采样率/码率", pair(eSampleRate, eAudioBitrate))
        row(panel, "断线重连(ms)", eReconnect)

        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(button("应用参数") { applyClicked() }, buttonLp())
        btnStream = button("开始推流") { streamClicked() }
        r1.addView(btnStream, buttonLp())
        panel.addView(r1)

        row(panel, "预设名称", ePresetName)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(button("保存预设") { savePresetClicked() }, buttonLp())
        r2.addView(button("加载预设") { loadPresetClicked() }, buttonLp())
        r2.addView(button("切换前后摄") { engine.switchCamera() }, buttonLp())
        panel.addView(r2)

        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r3.addView(button("导出预设JSON") { exportPresets() }, buttonLp())
        r3.addView(button("导入预设JSON") { importPresets() }, buttonLp())
        panel.addView(r3)

        val hint = TextView(this).apply {
            text = "参数口径：改完点「应用参数」，用 ffprobe 核对输出与填写值一致。"
            setPadding(0, dp(10), 0, 0)
        }
        panel.addView(hint)
    }

    // ---------------- 事件 ----------------

    private fun applyClicked() {
        val p = collect()
        if (engine.apply(p)) {
            prefs = p
            StreamPrefs.saveCurrent(this, p)
        }
    }

    private fun streamClicked() {
        if (!engine.streaming) {
            prefs = collect()
            if (engine.start()) StreamPrefs.saveCurrent(this, prefs)
        } else {
            engine.stop()
        }
    }

    private fun engineApply() {
        prefs = collect()
        if (engine.apply(prefs)) StreamPrefs.saveCurrent(this, prefs)
    }

    private fun savePresetClicked() {
        val name = ePresetName.text.toString().trim()
        if (name.isEmpty()) { status.text = "先填预设名"; return }
        StreamPrefs.savePreset(this, name, collect())
        status.text = "已保存预设 [$name]"
    }

    private fun loadPresetClicked() {
        val name = ePresetName.text.toString().trim()
        if (name.isEmpty()) { status.text = "先填预设名"; return }
        val p = StreamPrefs.loadPreset(this, name)
        if (p == null) {
            status.text = "预设 [$name] 不存在，现有: ${StreamPrefs.presetNames(this)}"
            return
        }
        fill(p)
        prefs = p
        if (engine.apply(p)) StreamPrefs.saveCurrent(this, p)
    }

    private fun exportPresets() {
        try {
            val f = File(getExternalFilesDir(null) ?: filesDir, "camcast-presets.json")
            f.writeText(StreamPrefs.presetsRaw(this))
            status.text = "已导出: ${f.absolutePath}"
        } catch (e: Exception) {
            status.text = "导出失败: ${e.message}"
        }
    }

    private fun importPresets() {
        try {
            val f = File(getExternalFilesDir(null) ?: filesDir, "camcast-presets.json")
            if (!f.exists()) { status.text = "文件不存在: ${f.absolutePath}"; return }
            StreamPrefs.importPresets(this, f.readText())
            status.text = "已导入 ${StreamPrefs.presetNames(this).size} 个预设"
        } catch (e: Exception) {
            status.text = "导入失败: ${e.message}"
        }
    }

    // ---------------- 收集/回填 ----------------

    private fun collect() = StreamPrefs(
        url = eUrl.text.toString().trim(),
        width = eWidth.text.toString().toIntOrNull() ?: prefs.width,
        height = eHeight.text.toString().toIntOrNull() ?: prefs.height,
        fps = eFps.text.toString().toIntOrNull() ?: prefs.fps,
        bitrateKbps = eBitrate.text.toString().toIntOrNull() ?: prefs.bitrateKbps,
        gopSec = eGop.text.toString().toIntOrNull() ?: prefs.gopSec,
        rotation = spRotation.selectedItem.toString().toIntOrNull() ?: prefs.rotation,
        profile = spProfile.selectedItem.toString(),
        audioOn = swAudio.isChecked,
        sampleRate = eSampleRate.text.toString().toIntOrNull() ?: prefs.sampleRate,
        audioBitrateKbps = eAudioBitrate.text.toString().toIntOrNull() ?: prefs.audioBitrateKbps,
        reconnectMs = eReconnect.text.toString().toIntOrNull() ?: prefs.reconnectMs,
    )

    private fun fill(p: StreamPrefs) {
        eUrl.setText(p.url)
        eWidth.setText(p.width.toString())
        eHeight.setText(p.height.toString())
        eFps.setText(p.fps.toString())
        eBitrate.setText(p.bitrateKbps.toString())
        eGop.setText(p.gopSec.toString())
        spRotation.setSelection(rotationItems.indexOf(p.rotation.toString()).coerceAtLeast(0))
        spProfile.setSelection(profileItems.indexOf(p.profile).coerceAtLeast(0))
        swAudio.isChecked = p.audioOn
        eSampleRate.setText(p.sampleRate.toString())
        eAudioBitrate.setText(p.audioBitrateKbps.toString())
        eReconnect.setText(p.reconnectMs.toString())
    }

    // ---------------- 控件工具 ----------------

    private fun row(panel: LinearLayout, label: String, field: View) {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        l.addView(
            TextView(this).apply { text = label },
            LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        l.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun pair(a: View, b: View): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        l.addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return l
    }

    private fun field(value: String, hint: String, inputType: Int): EditText =
        EditText(this).apply {
            setText(value)
            setHint(hint)
            setSingleLine(true)
            this.inputType = inputType
        }

    private fun spinner(items: List<String>, selected: String): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(items.indexOf(selected).coerceAtLeast(0))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            isAllCaps = false
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun buttonLp() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 权限 ----------------

    private fun permissions() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun hasAllPermissions() =
        permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
