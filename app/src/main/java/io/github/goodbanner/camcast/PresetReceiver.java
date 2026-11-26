package io.github.goodbanner.camcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/** 接收 CamProbe「导出到 CamCast」的预设 JSON，合并进本 app 的预设库。 */
public class PresetReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String json = intent.getStringExtra("presets_json");
        if (json == null || json.isEmpty()) return;
        try {
            int n = StreamPrefs.mergePresets(context.getApplicationContext(), json);
            Log.i("CamCast", "preset imported from CamProbe: +" + n
                + ", total=" + StreamPrefs.presetNames(context.getApplicationContext()).size()
                + " -> " + StreamPrefs.presetNames(context.getApplicationContext()));
            Toast.makeText(context.getApplicationContext(),
                "已从 CamProbe 导入 " + n + " 个预设", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("CamCast", "preset import failed", e);
            Toast.makeText(context.getApplicationContext(),
                "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
