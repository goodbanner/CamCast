package io.github.goodbanner.camprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class ProbeActivity extends Activity {

    private static final String CAMCAST_PKG = "io.github.goodbanner.camcast";
    private static final String CAMCAST_ACTION = "io.github.goodbanner.camcast.ACTION_IMPORT_PRESETS";
    private static final String CAMCAST_CLASS = "io.github.goodbanner.camcast.PresetReceiver";
    private static final String DEFAULT_URL = "rtmp://192.168.1.50/live/cam";

    private EditText eUrl;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("CamProbe — 相机参数探针");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        status = new TextView(this);
        status.setText("探测数据:\n" + PresetStore.summary(this));
        status.setPadding(0, dp(8), 0, dp(8));
        panel.addView(status);

        TextView urlLabel = label("导出时使用的推流地址:");
        panel.addView(urlLabel);
        eUrl = new EditText(this);
        eUrl.setSingleLine(true);
        eUrl.setText(DEFAULT_URL);
        eUrl.setHint("rtmp://...");
        panel.addView(eUrl);

        Button export = new Button(this);
        export.setText("导出到 CamCast");
        export.setAllCaps(false);
        export.setOnClickListener(v -> doExport());
        panel.addView(export, matchWrap());

        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setPadding(0, dp(12), 0, 0);
        help.setText(
            "流程:\n"
            + "1. LSPosed 启用本模块，作用域勾目标 app\n"
            + "2. 强停并重开目标 app，走完相机流程\n"
            + "   (预览/切前后摄/拍照/录像)\n"
            + "3. 回到这里看「探测数据」刷新\n"
            + "4. 填推流地址 → 点「导出到 CamCast」\n"
            + "5. CamCast 里点「加载预设」(名字=包名)\n\n"
            + "logcat 同步输出: adb logcat -s CamProbe\n"
            + "仓库: github.com/goodbanner/CamCast");
        panel.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        status.setText("探测数据:\n" + PresetStore.summary(this));
    }

    private void doExport() {
        String url = eUrl.getText().toString().trim();
        if (url.isEmpty()) url = DEFAULT_URL;
        try {
            JSONObject presets = PresetStore.presets(this, url);
            if (presets.length() == 0) {
                status.setText("没有可导出的数据（或都缺分辨率）。\n先完成目标 app 的相机流程。\n\n"
                    + PresetStore.summary(this));
                return;
            }
            Intent i = new Intent(CAMCAST_ACTION);
            i.setComponent(new ComponentName(CAMCAST_PKG, CAMCAST_CLASS));
            i.putExtra("presets_json", presets.toString());
            boolean found = !getPackageManager().queryBroadcastReceivers(i, 0).isEmpty();

            // 关键：stopped 状态（被划掉/强停）的应用收不到任何广播。
            // 显式 startActivity 不受 stopped 限制：先拉起 CamCast 解除 stopped，再延迟投递。
            boolean launched = false;
            try {
                Intent launch = new Intent();
                launch.setClassName(CAMCAST_PKG, CAMCAST_PKG + ".MainActivity");
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                launched = true;
            } catch (Exception ignored) {
            }
            status.setText("正在拉起 CamCast 并导出 " + presets.length() + " 个预设…");

            final boolean fFound = found;
            final boolean fLaunched = launched;
            final int count = presets.length();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                sendBroadcast(i);
                String head;
                if (fLaunched) {
                    head = "已导出 " + count + " 个预设（已拉起 CamCast，1 秒内应看到 Toast）\n"
                        + "→ 预设名填目标包名 → 「加载预设」\n\n";
                } else if (fFound) {
                    head = "已导出 " + count + " 个预设到 CamCast\n"
                        + "→ 打开 CamCast，预设名填目标包名，点「加载预设」\n\n";
                } else {
                    head = "已尝试发送 " + count + " 个预设（未查询到 CamCast：确认已安装）\n\n";
                }
                status.setText(head + PresetStore.summary(this));
            }, 800);
        } catch (Exception e) {
            status.setText("导出失败: " + e + "\n\n" + PresetStore.summary(this));
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
