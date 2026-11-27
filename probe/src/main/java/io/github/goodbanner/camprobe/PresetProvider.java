package io.github.goodbanner.camprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

/**
 * 给 CamCast 反向拉取预设数据用（拉取方活着即可，不依赖广播投递——
 * 广播会被 stopped/冻结状态卡住，provider 查询由 binder 驱动会自动唤醒/解冻本进程）。
 */
public class PresetProvider extends ContentProvider {

    public static final String AUTH = "io.github.goodbanner.camprobe.provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) return null;
        String url = PresetStore.loadExportUrl(ctx);
        JSONObject presets = PresetStore.presets(ctx, url);
        MatrixCursor cur = new MatrixCursor(new String[]{"presets"});
        cur.addRow(new Object[]{presets.toString()});
        return cur;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + AUTH + ".presets";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
