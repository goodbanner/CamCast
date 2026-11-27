package io.github.goodbanner.camprobe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * 探测数据落盘（CamProbe 自己的 SharedPreferences），导出时转换为 CamCast 预设格式。
 *
 * 内部结构: {"<pkg>": {"w":..,"h":..,"size_src":..,"fps":..,"fps_src":..,"cam":..,"fmt":..}}
 * 优先级: size  ImageReader(3) > Recorder(2) > setPreviewSize(1)
 *         fps   AE_FPS(3) > setPreviewFpsRange(2) > VideoFrameRate(1)
 */
public class PresetStore {

    private static final String PREFS = "probe_presets";
    private static final int[][] STD_SIZES = {
        {320, 240}, {640, 480}, {800, 600}, {1280, 720}, {1920, 1080}
    };

    private static int pri(String src) {
        if ("ImageReader".equals(src) || "AE_FPS".equals(src)) return 3;
        if ("Recorder".equals(src) || "setPreviewFpsRange".equals(src)) return 2;
        if ("setPreviewSize".equals(src) || "VideoFrameRate".equals(src)) return 1;
        return 0;
    }

    private static JSONObject safe(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static synchronized void update(Context c, String pkg, String kind, String val,
                                           int w, int h, String src) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject all = safe(sp.getString("all", "{}"));
        try {
            JSONObject e = all.has(pkg) ? all.getJSONObject(pkg) : new JSONObject();
            switch (kind) {
                case "image":
                    e.put("w", w); e.put("h", h); e.put("size_src", "ImageReader");
                    e.put("fmt", val);
                    break;
                case "size":
                    if (pri(src) >= pri(e.optString("size_src", ""))) {
                        e.put("w", w); e.put("h", h); e.put("size_src", src);
                    }
                    break;
                case "fps":
                    if (pri(src) >= pri(e.optString("fps_src", ""))) {
                        int f;
                        try {
                            f = Integer.parseInt(val);
                        } catch (NumberFormatException nfe) {
                            f = 0;
                        }
                        if (f > 0) {
                            e.put("fps", f);
                            e.put("fps_src", src);
                        }
                    }
                    break;
                case "cam":
                    e.put("cam", val);
                    break;
                default:
                    break;
            }
            all.put(pkg, e);
            sp.edit().putString("all", all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** 归一化到标准编码档：同宽高比优先，否则相对距离最小。 */
    private static int[] normalize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{0, 0};
        double target = (double) w / h;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] s : STD_SIZES) {
            double d = Math.abs((double) s[0] / s[1] - target) < 0.02
                ? Math.abs(s[0] - w) / (double) w + Math.abs(s[1] - h) / (double) h   // 同比优先
                : 10 + Math.abs((double) s[0] / s[1] - target);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best != null ? best : new int[]{w, h};
    }

    /** 输出 CamCast presets JSON（键=目标包名，值=StreamPrefs 格式）。 */
    public static synchronized JSONObject presets(Context c, String url) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject all = safe(sp.getString("all", "{}"));
        JSONObject out = new JSONObject();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String pkg = it.next();
            try {
                JSONObject e = all.getJSONObject(pkg);
                int w = e.optInt("w", 0), h = e.optInt("h", 0);
                if (w <= 0 || h <= 0) continue;
                int[] n = normalize(w, h);
                int fps = e.optInt("fps", 30);
                if (fps < 1 || fps > 60) fps = 30;

                JSONObject p = new JSONObject();
                p.put("url", url);
                p.put("width", n[0]);
                p.put("height", n[1]);
                p.put("fps", fps);
                p.put("bitrateKbps", 2000);
                p.put("gopSec", 1);
                p.put("rotation", 0);
                p.put("profile", "baseline");
                p.put("audioOn", true);
                p.put("sampleRate", 44100);
                p.put("audioBitrateKbps", 128);
                p.put("reconnectMs", 2000);
                out.put(pkg, p);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** 人读摘要：每个包的原始探测值。 */
    public static synchronized String summary(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject all = safe(sp.getString("all", "…"));
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String pkg = it.next();
            try {
                JSONObject e = all.getJSONObject(pkg);
                sb.append("· ").append(pkg)
                    .append("  ").append(e.optInt("w", 0)).append("x").append(e.optInt("h", 0))
                    .append(" (").append(e.optString("size_src", "?")).append(")")
                    .append("  fps=").append(e.optString("fps", "-"))
                    .append(" (").append(e.optString("fps_src", "-")).append(")")
                    .append("  cam=").append(e.optString("cam", "-"));
                String fmt = e.optString("fmt", "");
                if (!fmt.isEmpty()) sb.append("  fmt=").append(fmt);
                sb.append('\n');
            } catch (JSONException ignored) {
            }
        }
        return sb.length() == 0 ? "（暂无探测数据）" : sb.toString().trim();
    }

    private static final String DEFAULT_URL = "rtmp://192.168.1.50/live/cam";

    /** 导出时用户填的推流地址，供 PresetProvider 拉取时使用。 */
    public static void saveExportUrl(Context c, String url) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("export_url", url == null || url.isEmpty() ? DEFAULT_URL : url)
            .apply();
    }

    public static String loadExportUrl(Context c) {
        String u = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("export_url", "");
        return u == null || u.isEmpty() ? DEFAULT_URL : u;
    }
}
