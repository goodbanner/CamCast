package io.github.goodbanner.camprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(
            "CamProbe — 相机参数探针\n\n"
            + "使用步骤：\n"
            + "1. 打开 LSPosed Manager → 模块 → 启用 CamProbe\n"
            + "2. 在作用域里勾选要抓取的目标 app（可多选）\n"
            + "3. 强行停止目标 app 后重新打开，走一遍它的相机流程\n"
            + "   （开预览 / 切前后摄 / 拍照 / 录像 各来一次）\n"
            + "4. 采集结果：\n"
            + "   adb logcat -s CamProbe\n"
            + "   也可在 LSPosed Manager → 日志 里看 XposedBridge 日志\n\n"
            + "抓取项：\n"
            + "  · openCamera 的 cameraId（前置=1 / 后置=0，视机型）\n"
            + "  · ImageReader.newInstance 的宽×高 + 像素格式（0x23=YUV_420_888，"
            + "0x22=PRIVATE 表示 GPU 消费、Java 层注不进去）\n"
            + "  · CaptureRequest 的 AE 帧率区间 + 裁剪区域\n"
            + "  · Camera1 的 setPreviewSize / setPreviewFormat / setPreviewFpsRange / setParameters(flat)\n"
            + "  · MediaRecorder 的视频尺寸/帧率/码率\n\n"
            + "局限：纯 View 预览（无 ImageReader）的 surface 尺寸取不到，"
            + "从 ImageReader 项或日志里的调用栈推断。\n\n"
            + "仓库: github.com/goodbanner/CamCast"
        );
        setContentView(tv);
    }
}
