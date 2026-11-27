package io.github.goodbanner.camcast;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * 打开 CamCast 时反向从 CamProbe 拉取预设（与广播互补）：
 * 广播受发送方冻结/接收方 stopped 影响；拉取只要双方装着就行，
 * 且 binder 查询会自动解冻/唤醒 CamProbe 进程。
 */
public final class PresetPull {

    private static final String AUTH = "io.github.goodbanner.camprobe.provider";

    private PresetPull() {
    }

    /** 后台线程拉取，结果合入预设库；不阻塞 UI。 */
    public static void pullAsync(final Context ctx) {
        new Thread(() -> {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = null;
            try {
                Uri uri = Uri.parse("content://" + AUTH + "/presets");
                c = cr.query(uri, null, null, null, null);
                if (c == null || !c.moveToFirst()) {
                    Log.i("CamCast", "preset pull: empty cursor (probe 未装/无数据/<queries> 缺声明)");
                    return;
                }
                String json = c.getString(c.getColumnIndexOrThrow("presets"));
                if (json == null || json.isEmpty()) return;
                final int n = StreamPrefs.mergePresets(ctx.getApplicationContext(), json);
                final String names = StreamPrefs.presetNames(ctx.getApplicationContext()).toString();
                Log.i("CamCast", "preset pulled from CamProbe: +" + n + ", total="
                    + StreamPrefs.presetNames(ctx.getApplicationContext()).size() + " -> " + names);
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx.getApplicationContext(),
                        "已从 CamProbe 拉取 " + n + " 个预设", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                // probe 没装/没数据都属正常，静默
                Log.i("CamCast", "preset pull skipped: " + t.getClass().getSimpleName());
            } finally {
                if (c != null) try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }, "preset-pull").start();
    }
}
