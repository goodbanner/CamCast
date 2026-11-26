# CamCast

Android 前置摄像头采集 + 可定制参数 RTMP 推流 app。基于 [RootEncoder 2.8.2](https://github.com/pedroSG94/RootEncoder)（Camera2 + MediaCodec + RTMP）。

## 可定制参数

| 参数 | 说明 | 默认 |
|---|---|---|
| RTMP URL | 任意 RTMP 服务器地址 + stream key | `rtmp://192.168.1.50/live/cam` |
| 分辨率 | 宽 × 高 | 1280×720 |
| 帧率 | 1–60，受相机 HAL 限制 | 30 |
| 视频码率 | kbps | 2000 |
| 关键帧间隔 | 秒（MediaCodec i-frame-interval） | 1 |
| 旋转 | 0/90/180/270 | 0 |
| 编码 Profile | baseline / main / high | baseline |
| 音频 | 开关 + 采样率 + 码率 | 44100/128k |
| 断线重连 | 毫秒 | 2000 |

预设管理：按名称保存/加载多组参数；JSON 导出/导入到
`/sdcard/Android/data/io.github.goodbanner.camcast/files/camcast-presets.json`。

启动后默认切到**前置摄像头**（多摄设备），界面按钮可随时切换前后摄。

## 构建

GitHub Actions（`.github/workflows/build.yml`）：

1. push 自动触发，或 Actions 页手动 `Run workflow`
2. JDK 17 + Gradle 8.9 + `assembleDebug`
3. 产物在 Artifacts 里的 `CamCast-debug`

本地（可选）：`gradle assembleDebug`（无需 wrapper jar，CI 用 setup-gradle 指定版本）。

## 验证输出格式

```bash
ffprobe rtmp://<你的服务器>/live/<key>
#   Stream #0: Video: h264 (Baseline), 1280x720, 30 fps, yuv420p
```

`ffprobe` 输出应与设置面板逐项一致；分辨率档位报 `prepareVideo 失败` 说明该组合设备编码器不支持，换相邻档位。

## CamProbe（LSPosed 相机参数探针）

`probe/` 模块：hook 目标 app 的相机调用，把"它要了什么参数"全部打到 `logcat -s CamProbe`。

抓取项：`openCamera` 的 cameraId、`ImageReader.newInstance` 的宽×高+格式（`0x22 PRIVATE`=GPU 消费，Java 层注不进）、`CaptureRequest` 的 AE 帧率区间+裁剪区、Camera1 的 `setPreviewSize/Format/FpsRange` 与 `setParameters(flatten)`、MediaRecorder 录像参数，全部附调用栈。

安装：LSPosed 启用 CamProbe → 作用域勾目标 app → 重启目标 app → `adb logcat -s CamProbe`。

### 探测数据 → CamCast 预设

```bash
adb logcat -s CamCapture -d > probe.txt      # 实际 tag 是 CamProbe
adb logcat -s CamProbe -d > probe.txt
python3 tools/probe2preset.py probe.txt -o camcast-presets.json
adb push camcast-presets.json /sdcard/Android/data/io.github.goodbanner.camcast/files/camcast-presets.json
```

CamCast 里点「导入预设」→ 预设名填目标包名 → 「加载预设」→ 参数自动填入并应用。

映射：`ImageReader w×h → width/height`（优先）、`AE_FPS 上界 → fps`；码率/关键帧不走相机 API，探测不到，用默认值（2000k/1s），装完界面里手调。`--exact` 保持原始分辨率，`--url` 统一填推流地址。

## 权限

`CAMERA`、`RECORD_AUDIO`、`INTERNET`，运行时申请。
