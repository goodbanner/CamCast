#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
probe2preset — 把 CamProbe 的 logcat 输出转换成 CamCast 的预设 JSON。

用法:
    adb logcat -s CamProbe -d > probe.txt
    python3 tools/probe2preset.py probe.txt -o camcast-presets.json

输出的 camcast-presets.json 推到:
    /sdcard/Android/data/io.github.goodbanner.camcast/files/camcast-presets.json
然后在 CamCast 里点「导入预设」，预设名填目标包名，点「加载预设」。

字段映射（CamProbe 抓得到的 -> preset）:
    ImageReader w x h        -> width / height      （优先级最高）
    Recorder.setVideoSize    -> width / height      （次选）
    Camera1 setPreviewSize   -> width / height      （兜底）
    CaptureRequest AE_FPS 上界 -> fps              （优先）
    Camera1 setPreviewFpsRange 上界 -> fps          （次选）
    Recorder.setVideoFrameRate -> fps               （兜底）
抓不到的字段用默认值: bitrate=2000k gop=1s profile=baseline rot=0 audio=44100/128k
（码率/关键帧本来就不走相机 API，探测不到，装完在 CamCast 界面里手调。）

选项:
    --url rtmp://...   给所有预设统一填推流地址
    --exact            分辨率保持原值，不归一化到标准档（默认归一化）
"""
import argparse
import json
import os
import re
import sys

STD_SIZES = [(320, 240), (640, 480), (800, 600), (1280, 720), (1920, 1080)]

DEFAULTS = {
    "url": "rtmp://192.168.1.50/live/cam",
    "bitrateKbps": 2000,
    "gopSec": 1,
    "rotation": 0,
    "profile": "baseline",
    "audioOn": True,
    "sampleRate": 44100,
    "audioBitrateKbps": 128,
    "reconnectMs": 2000,
}

PKG = r"pkg=(\S+)"

RX_IMAGE = re.compile(r"\[ImageReader\]\s+(\d+)x(\d+)\s+format=0x[0-9a-fA-F]+\(([^)]*)\).*?" + PKG)
RX_REC_SIZE = re.compile(r"\[Recorder\.setVideoSize\]\s+(\d+)x(\d+)\s+" + PKG)
RX_PV_SIZE = re.compile(r"\[setPreviewSize\]\s+(\d+)x(\d+)\s+" + PKG)
RX_AE_FPS = re.compile(r"AE_FPS=\[(\d+),\s*(\d+)\].*?" + PKG)
RX_PV_FPS = re.compile(r"\[setPreviewFpsRange\]\s+(\d+)-(\d+)\s+" + PKG)
RX_REC_FPS = re.compile(r"\[Recorder\.setVideoFrameRate\]\s+(\d+)\s+" + PKG)
RX_OPEN = re.compile(r"\[openCamera2?\]\s+id=(\S+?)(?:\s|\n).*?" + PKG)
RX_FMT = re.compile(r"\[ImageReader\].*?format=0x[0-9a-fA-F]+\(([^)]*)\).*?" + PKG)


def normalize_size(w, h):
    """归一化到标准编码档；同宽高比优先，否则按相对尺寸距离最小。"""
    def ratio(s):
        return abs(s[0] / s[1] - w / h)

    same = [s for s in STD_SIZES if ratio(s) < 0.02]
    pool = same or STD_SIZES
    best = min(pool, key=lambda s: abs(s[0] - w) / max(w, 1) + abs(s[1] - h) / max(h, 1))
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile", help="adb logcat -s CamProbe -d 的输出文件，或 -")
    ap.add_argument("-o", "--out", default="camcast-presets.json")
    ap.add_argument("--url", help="统一设置所有预设的推流地址")
    ap.add_argument("--exact", action="store_true", help="分辨率不归一化")
    args = ap.parse_args()

    text = sys.stdin.read() if args.logfile == "-" else open(args.logfile, encoding="utf-8", errors="replace").read()

    # pkg -> 结果字典
    found = {}

    def slot(pkg):
        return found.setdefault(pkg, {
            "size": None, "size_src": "", "fps": 0, "fps_src": "",
            "cam_id": "", "fmt": "", "notes": [],
        })

    for line in text.splitlines():
        m = RX_IMAGE.search(line)
        if m:
            s = slot(m.group(4))
            s["size"], s["size_src"] = (int(m.group(1)), int(m.group(2))), "ImageReader"
            s["fmt"] = m.group(3)
            if "PRIVATE" in m.group(3):
                s["notes"].append("目标 app 输出为 PRIVATE(GPU 消费)")
            continue
        m = RX_REC_SIZE.search(line)
        if m:
            s = slot(m.group(3))
            if not s["size"]:
                s["size"], s["size_src"] = (int(m.group(1)), int(m.group(2))), "Recorder"
            continue
        m = RX_PV_SIZE.search(line)
        if m:
            s = slot(m.group(3))
            if not s["size"]:
                s["size"], s["size_src"] = (int(m.group(1)), int(m.group(2))), "setPreviewSize"
            continue
        m = RX_AE_FPS.search(line)
        if m:
            s = slot(m.group(3))
            s["fps"], s["fps_src"] = int(m.group(2)), "AE_FPS"
            continue
        m = RX_PV_FPS.search(line)
        if m:
            s = slot(m.group(3))
            if not s["fps"]:
                s["fps"], s["fps_src"] = int(m.group(2)), "setPreviewFpsRange"
            continue
        m = RX_REC_FPS.search(line)
        if m:
            s = slot(m.group(2))
            if not s["fps"]:
                s["fps"], s["fps_src"] = int(m.group(1)), "VideoFrameRate"
            continue
        m = RX_OPEN.search(line)
        if m:
            s = slot(m.group(2))
            s["cam_id"] = m.group(1)
            continue
        m = RX_FMT.search(line)
        if m:
            slot(m.group(2))["fmt"] = m.group(1)

    if not found:
        print("没解析到任何 CamProbe 记录。确认: adb logcat -s CamProbe -d 有输出、目标 app 已走完相机流程。")
        sys.exit(1)

    # 与已有输出合并
    presets = {}
    if os.path.exists(args.out):
        try:
            presets = json.load(open(args.out, encoding="utf-8"))
        except Exception:
            presets = {}

    ok = True
    for pkg, s in sorted(found.items()):
        if not s["size"]:
            print(f"跳过 {pkg}: 没抓到分辨率（只走了 openCamera? 确认走完预览流程）")
            ok = False
            continue
        w, h = s["size"]
        if not args.exact:
            w, h = normalize_size(w, h)
        fps = s["fps"] or 30
        if fps < 1 or fps > 60:
            print(f"警告 {pkg}: fps={fps} 异常，回退 30")
            fps = 30
        preset = dict(DEFAULTS)
        preset.update({"width": w, "height": h, "fps": fps})
        if args.url:
            preset["url"] = args.url
        presets[pkg] = preset
        note = f"（{s['size_src']}{'' if args.exact else f' {s["size"][0]}x{s["size"][1]} -> 归一化'}, fps={fps} from {s['fps_src'] or 'default'}"
        note += f", cam_id={s['cam_id']}" if s["cam_id"] else ""
        note += "）"
        print(f"预设 {pkg}: {w}x{h}@{fps} {note}")
        for n in s["notes"]:
            print(f"    注意: {n}")

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(presets, f, ensure_ascii=False, indent=1)
    print(f"\n已写入 {args.out}，共 {len(presets)} 个预设")
    print("下一步:")
    print(f"  adb push {args.out} /sdcard/Android/data/io.github.goodbanner.camcast/files/camcast-presets.json")
    print("  CamCast 里点「导入预设」→ 预设名填包名 → 「加载预设」")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
