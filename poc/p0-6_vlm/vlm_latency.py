#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P0-6: 云端 VLM 延迟实测（智谱 GLM-4.1V-Thinking-Flash）

模拟菜包 L4 通道的真实决策负载：抓当前屏幕 -> JPEG 压缩/降采样 -> base64
-> 求定位 JSON。测三档分辨率的端到端延迟，各 N 次，输出 p50。

用法: python vlm_latency.py [次数，默认3]
密钥: 同目录 zhipu_key.txt（不进 git）
"""
import base64
import io
import json
import subprocess
import sys
import time
import urllib.request
import os

from PIL import Image

URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
# 用户订阅的是 FlashX；但实测同任务 Flash 更快更稳（p50 6.2s/3全对 vs FlashX 9.1s/2对），
# 详见 POC 报告第 11 章。L4 选型建议 Flash。
MODEL = "glm-4.1v-thinking-flashx"
HERE = os.path.dirname(os.path.abspath(__file__))

KEY = open(os.path.join(HERE, "zhipu_key.txt")).read().strip()

# 真实 agent 决策 prompt：定位 + 结构化输出（微信界面 = 树盲区场景）
PROMPT = (
    "你是Android自动化助手的视觉决策模块。这是竖屏手机截图。"
    "请在截图中找到底部Tab栏的【我】按钮和顶部搜索图标，"
    '输出JSON: {"targets":[{"name":"我","bbox_2d":[x1,y1,x2,y2]},'
    '{"name":"搜索","bbox_2d":[x1,y1,x2,y2]}]}，'
    "坐标用原图像素。只输出JSON，不要解释。"
)

VARIANTS = [
    ("1080w 开思考", 1080, 80, "enabled"),
    ("1080w 关思考", 1080, 80, "disabled"),
    ("720w  关思考", 720, 80, "disabled"),
]


def grab_screenshot() -> Image.Image:
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                       capture_output=True, timeout=30)
    if r.returncode != 0 or len(r.stdout) < 1000:
        raise RuntimeError("screencap 失败: " + r.stderr.decode(errors="replace")[:200])
    return Image.open(io.BytesIO(r.stdout)).convert("RGB")


def encode(img: Image.Image, width: int, quality: int):
    w, h = img.size
    if w > width:
        img = img.resize((width, round(h * width / w)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    return buf.getvalue(), img.size


def call_vlm(jpg: bytes, thinking: str = "enabled"):
    b64 = base64.b64encode(jpg).decode()
    body = {
        "model": MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url",
                 "image_url": {"url": "data:image/jpeg;base64," + b64}},
                {"type": "text", "text": PROMPT},
            ],
        }],
        "thinking": {"type": thinking},
    }
    req = urllib.request.Request(
        URL, data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + KEY,
                 "Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.load(resp)
    ms = (time.perf_counter() - t0) * 1000
    return ms, data


def summarize(data):
    try:
        usage = data.get("usage", {})
        msg = data["choices"][0]["message"]
        return msg.get("content", ""), usage
    except Exception:
        return json.dumps(data, ensure_ascii=False)[:300], {}


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p / 100))]


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    print("抓取屏幕...")
    shot = grab_screenshot()
    print("原图:", shot.size)

    results = {}
    for label, width, q, thinking in VARIANTS:
        jpg, size = encode(shot, width, q)
        kb = len(jpg) // 1024
        lat, oks, toks = [], 0, []
        print(f"\n=== {label}  ({size[0]}x{size[1]}, {kb}KB) ===")
        for i in range(n):
            try:
                ms, data = call_vlm(jpg, thinking)
                content, usage = summarize(data)
                ct = usage.get("completion_tokens", 0)
                # GLM-4.1V 坐标为 0-1000 归一化，换算回原图并做粗校验
                ok = False
                try:
                    j = content[content.index("{"):content.rindex("}") + 1]
                    j = json.loads(j)
                    me = [t for t in j["targets"] if t["name"] == "我"][0]
                    x = (me["bbox_2d"][0] + me["bbox_2d"][2]) / 2000.0  # 归一化中心
                    y = (me["bbox_2d"][1] + me["bbox_2d"][3]) / 2000.0
                    ok = x > 0.75 and y > 0.85   # 底部右侧 Tab
                except Exception:
                    j = None
                oks += ok
                toks.append(ct)
                print(f"  run{i+1}: {ms:7.0f}ms  completion_tok={ct} "
                      f"bbox_ok={ok}")
                lat.append(ms)
                if i == 0:
                    print("  首响输出:", content.replace("\n", " ")[:160])
            except Exception as e:
                print(f"  run{i+1}: FAIL {e}")
        if lat:
            results[label] = (min(lat), pct(lat, 50), max(lat), oks,
                              kb, sum(toks) / max(len(toks), 1))
    print("\n========= 汇总 =========")
    for k, (mn, p50, mx, oks, kb, tok) in results.items():
        print(f"{k}: p50={p50:.0f}ms  min={mn:.0f}  max={mx:.0f}  "
              f"bbox_ok={oks}/{n}  平均completion_tok={tok:.0f}  payload={kb}KB")


if __name__ == "__main__":
    main()
