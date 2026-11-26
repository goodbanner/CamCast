package io.github.goodbanner.camprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * CamProbe: 记录目标 app 向相机子系统请求的全部关键参数。
 *
 * 输出三通道:
 *   1. logcat tag=CamProbe（主采集口，带调用栈）
 *   2. XposedBridge.log（LSPosed 日志）
 *   3. 显式广播回 CamProbe app 的 PresetReceiver，落盘为 CamCast 预设数据，
 *      由 ProbeActivity 的「导出到 CamCast」按钮一键推送。
 *
 * 框架类 hook 每进程只装一次；每个 app 进程独立 fork，静态变量天然按进程隔离。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "CamProbe";
    private static final String ACTION_PROBE = "io.github.goodbanner.camprobe.ACTION_PROBE";
    private static final String DST_CLASS = "io.github.goodbanner.camprobe.PresetReceiver";
    private static final String DST_PKG = "io.github.goodbanner.camprobe";

    private static volatile boolean frameworkHooked = false;
    private static volatile Context appCtx;
    private static final Map<String, String> lastSent = new HashMap<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (lpp.packageName == null) return;
        if (lpp.packageName.equals(DST_PKG)) return;
        if (frameworkHooked) return;
        frameworkHooked = true;

        final String pkg = lpp.packageName;
        final ClassLoader cl = lpp.classLoader;
        emit(pkg + " installed camera probes (v0.2)");

        // 拿应用 Context 用于 sendBroadcast（attach 早于任何相机调用）
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        appCtx = (Context) p.args[0];
                    }
                });
        } catch (Throwable ignored) {
        }

        // ================= Camera2 =================

        hookAll(cl, "android.hardware.camera2.CameraManager", "openCamera", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[openCamera2] id=" + p.args[0] + " pkg=" + pkg + "\n" + caller());
                send(pkg, "cam", String.valueOf(p.args[0]), 0, 0, "openCamera2");
            }
        });

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
                send(pkg, "image", fmtName(fmt), w, h, "ImageReader");
            }
        });

        hookAll(cl, "android.hardware.camera2.CameraDevice", "createCaptureSession", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[createCaptureSession] arg0=" + p.args[0] + " pkg=" + pkg);
            }
        });

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
                    if (fps != null) {
                        sb.append("AE_FPS=").append(fps);
                        send(pkg, "fps", String.valueOf(fps.getUpper()), 0, 0, "AE_FPS");
                    }
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
                send(pkg, "cam", id, 0, 0, "openCamera1");
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
                int w = (Integer) p.args[0];
                int h = (Integer) p.args[1];
                emit("[setPreviewSize] " + w + "x" + h + " pkg=" + pkg + "\n" + caller());
                send(pkg, "size", "", w, h, "setPreviewSize");
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
                int hi = (Integer) p.args[1];
                emit("[setPreviewFpsRange] " + p.args[0] + "-" + hi + " pkg=" + pkg);
                send(pkg, "fps", String.valueOf(hi), 0, 0, "setPreviewFpsRange");
            }
        });

        // ================= MediaRecorder（录像） =================

        hookAll(cl, "android.media.MediaRecorder", "setVideoSize", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                int w = (Integer) p.args[0];
                int h = (Integer) p.args[1];
                emit("[Recorder.setVideoSize] " + w + "x" + h + " pkg=" + pkg);
                send(pkg, "size", "", w, h, "Recorder");
            }
        });
        hookAll(cl, "android.media.MediaRecorder", "setVideoFrameRate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                emit("[Recorder.setVideoFrameRate] " + p.args[0] + " pkg=" + pkg);
                send(pkg, "fps", String.valueOf(p.args[0]), 0, 0, "VideoFrameRate");
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

    /** 把探测增量广播回 CamProbe app 落盘；同 (pkg,kind) 值未变则去重不发。 */
    private static void send(String pkg, String kind, String val, int w, int h, String src) {
        Context ctx = appCtx;
        if (ctx == null) return;
        String fingerprint = val + "|" + w + "x" + h + "|" + src;
        synchronized (lastSent) {
            String key = pkg + "|" + kind;
            if (fingerprint.equals(lastSent.get(key))) return;
            lastSent.put(key, fingerprint);
        }
        try {
            Intent i = new Intent(ACTION_PROBE);
            i.setComponent(new ComponentName(DST_PKG, DST_CLASS));
            i.putExtra("pkg", pkg)
                .putExtra("kind", kind)
                .putExtra("val", val)
                .putExtra("w", w)
                .putExtra("h", h)
                .putExtra("src", src);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            // 接收端没装等情况：logcat 通道仍在，不打断目标 app
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
        // 魔数字对照 android.media.ImageFormat（api-82.jar 的 android 桩会遮蔽该类，故不 import）
        if (f == 0x100) return "JPEG";
        if (f == 0x23) return "YUV_420_888";
        if (f == 0x22) return "PRIVATE(GPU消费,Java层不可注入)";
        if (f == 0x21) return "IMPLEMENTATION_DEFINED";
        if (f == 17) return "NV21";
        if (f == 4) return "RGB_565";
        if (f == 1) return "RGBA_8888";
        if (f == 3) return "RGB_888";
        return "?";
    }
}
