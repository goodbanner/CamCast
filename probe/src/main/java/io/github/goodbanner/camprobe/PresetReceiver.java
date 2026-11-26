package io.github.goodbanner.camprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收 Hook（运行在各目标 app 进程里）广播来的探测增量，落盘到 PresetStore。 */
public class PresetReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String pkg = intent.getStringExtra("pkg");
        String kind = intent.getStringExtra("kind");
        if (pkg == null || kind == null) return;
        PresetStore.update(
            context.getApplicationContext(),
            pkg,
            kind,
            intent.getStringExtra("val") == null ? "" : intent.getStringExtra("val"),
            intent.getIntExtra("w", 0),
            intent.getIntExtra("h", 0),
            intent.getStringExtra("src") == null ? "" : intent.getStringExtra("src")
        );
    }
}
