package io.github.goodbanner.camprobe;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageFormat;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;

import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * CamProbe: 记录目标 app 向相机子系统请求的全部关键参数。
 *
 * 输出双通道：logcat（tag=CamProbe，主采集口）+ XposedBridge.log（LSPosed 日志）。
 * 框架类 hook 每进程只装一次；每个 app 进程独立 fork，静态变量天然按进程隔离。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "CamProbe";
    private static volatile boolean frameworkHooked = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (lpp.packageName == null) return;
        if (lpp.packageName.equals("io.github.goodbanner.camprobe")) return;
        if (frameworkHooked) return;
        frameworkHooked = true;

        final String pkg = lpp.packageName;
        final ClassLoader cl = lpp.classLoader;
        emit(pkg + " installed camera probes (v0.1)");

        // ================= Camera2 =================

        // 1. 打开哪个摄像头
        hookAll(cl, "android.hardware.camera2.CameraManager", "openCamera", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[openCamera2] id=" + p.args[0] + " pkg=" + pkg + "\n" + caller());
            }
        });

        // 2. 消费帧的宽/高/格式（最核心）
        hookAll(cl, "android.media.ImageReader", "newInstance", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                int w = (Integer) p.args[0];
                int h = (Integer) p.args[1];
                int fmt = (Integer) p.args[2];
                int max = (Integer) p.args[3];
                emit(String.format(Locale.US,
                    "[ImageReader] %dx%d format=0x%x(%s) maxImgs=%d pkg=%s\n%s",
                    w, h, fmt, fmtName(fmt), max, pkg, caller()));
            }
        });

        // 3. 会话输出 surface 列表（尺寸未知，给出数量与对象信息）
        hookAll(cl, "android.hardware.camera2.CameraDevice", "createCaptureSession", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[createCaptureSession] arg0=" + p.args[0] + " pkg=" + pkg);
            }
        });

        // 4. 帧率区间 + 裁剪区域（CaptureRequest 关键参数）
        XC_MethodHook reqHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object a0 = p.args[0];
                    CaptureRequest req;
                    if (a0 instanceof CaptureRequest) {
                        req = (CaptureRequest) a0;
                    } else if (a0 instanceof List) {
                        List<?> list = (List<?>) a0;
                        if (list.isEmpty() || !(list.get(0) instanceof CaptureRequest)) return;
                        req = (CaptureRequest) list.get(0);
                    } else {
                        return;
                    }
                    StringBuilder sb = new StringBuilder("[");
                    Range<Integer> fps = req.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                    if (fps != null) sb.append("AE_FPS=").append(fps);
                    Rect crop = req.get(CaptureRequest.SCALER_CROP_REGION);
                    if (crop != null) sb.append(" crop=").append(crop.toShortString());
                    sb.append("] pkg=").append(pkg);
                    emit(sb.toString());
                } catch (Throwable t) {
                    emit("[CaptureRequest] parse failed: " + t);
                }
            }
        };
        hookAll(cl, "android.hardware.camera2.CameraCaptureSession", "setRepeatingRequest", reqHook);
        hookAll(cl, "android.hardware.camera2.CameraCaptureSession", "setRepeatingBurst", reqHook);
        hookAll(cl, "android.hardware.camera2.CameraCaptureSession", "capture", reqHook);
        hookAll(cl, "android.hardware.camera2.CameraCaptureSession", "captureBurst", reqHook);

        // ================= Camera1 =================

        hookAll(cl, "android.hardware.Camera", "open", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String id = p.args.length > 0 ? String.valueOf(p.args[0]) : "default";
                emit("[openCamera1] id=" + id + " pkg=" + pkg + "\n" + caller());
            }
        });

        hookAll(cl, "android.hardware.Camera", "setParameters", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Camera.Parameters params = (Camera.Parameters) p.args[0];
                    emit("[setParameters] " + params.flatten() + " pkg=" + pkg);
                } catch (Throwable ignored) {
                }
            }
        });

        hookAll(cl, "android.hardware.Camera$Parameters", "setPreviewSize", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[setPreviewSize] " + p.args[0] + "x" + p.args[1] + " pkg=" + pkg + "\n" + caller());
            }
        });

        hookAll(cl, "android.hardware.Camera$Parameters", "setPreviewFormat", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                int f = (Integer) p.args[0];
                emit(String.format(Locale.US, "[setPreviewFormat] 0x%x(%s) pkg=%s",
                    f, fmtName(f), pkg));
            }
        });

        hookAll(cl, "android.hardware.Camera$Parameters", "setPreviewFpsRange", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[setPreviewFpsRange] " + p.args[0] + "-" + p.args[1] + " pkg=" + pkg);
            }
        });

        // ================= MediaRecorder（录像） =================

        XC_MethodHook recSize = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[Recorder.setVideoSize] " + p.args[0] + "x" + p.args[1] + " pkg=" + pkg);
            }
        };
        hookAll(cl, "android.media.MediaRecorder", "setVideoSize", recSize);
        hookAll(cl, "android.media.MediaRecorder", "setVideoFrameRate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[Recorder.setVideoFrameRate] " + p.args[0] + " pkg=" + pkg);
            }
        });
        hookAll(cl, "android.media.MediaRecorder", "setVideoEncodingBitRate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[Recorder.setVideoEncodingBitRate] " + p.args[0] + " pkg=" + pkg);
            }
        });
        hookAll(cl, "android.media.MediaRecorder", "setProfile", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[Recorder.setProfile] " + p.args[0] + " pkg=" + pkg);
            }
        });
    }

    // ---------------- 工具 ----------------

    private static void hookAll(ClassLoader cl, String className, String method, XC_MethodHook hook) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(clazz, method, hook);
        } catch (Throwable t) {
            emit("hook failed: " + className + "#" + method + " -> " + t);
        }
    }

    private static void emit(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** 过滤掉 Xposed/本模块自身的栈帧，只留 app 侧调用点。 */
    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("de.robv.android.xposed")
                    || cn.startsWith("io.github.goodbanner.camprobe")
                    || cn.startsWith("java.lang.Throwable")) {
                continue;
            }
            if (shown++ >= 5) break;
            sb.append("      at ").append(e).append('\n');
        }
        return sb.toString().trim();
    }

    private static String fmtName(int f) {
        if (f == ImageFormat.JPEG) return "JPEG";
        if (f == ImageFormat.YUV_420_888) return "YUV_420_888";
        if (f == 0x22) return "PRIVATE(GPU消费,Java层不可注入)";
        if (f == 0x21) return "IMPLEMENTATION_DEFINED";
        if (f == 17) return "NV21";
        if (f == 4) return "RGB_565";
        if (f == 1) return "RGBA_8888";
        if (f == 3) return "RGB_888";
        return "?";
    }
}
