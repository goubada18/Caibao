#!/usr/bin/env python3
"""控件树「语义可定位性」分析 —— 评估纯 UI 树方案的天花板，作为是否引入 OCR 双通道的依据。

输出三组指标：
  1. 标签覆盖率：可点击节点自身 / 含后代 的 text、content-desc 覆盖情况
  2. 文字歧义度：可点击节点子树内 text 段数分布（0 段=盲点，1 段=语义唯一，>=2 段=歧义）
  3. 幽灵节点：零面积 / 完全屏外 / 部分屏外 的可点击节点

用法: python analyze_labels.py
数据: raw/*.xml（由 measure_dump.sh 采集）
屏幕: 1080x2400（可按测试机修改 SW/SH）
"""
import glob
import os
import re
import xml.etree.ElementTree as ET

RAW_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw")
SW, SH = 1080, 2400  # Redmi K60 Pro 实际输出分辨率


def subtree_texts(node):
    """节点自身及全部后代的 text 列表。"""
    return [(x.get("text") or "").strip() for x in node.iter()
            if (x.get("text") or "").strip()]


def subtree_any_label(node):
    """节点自身及后代是否有任意可读标签（text 或 content-desc）。"""
    return any((x.get(k) or "").strip()
               for x in node.iter() for k in ("text", "content-desc"))


def parse_bounds(node):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]",
                 (node.get("bounds") or "").strip())
    return tuple(map(int, m.groups())) if m else None


def load():
    for path in sorted(glob.glob(os.path.join(RAW_DIR, "*.xml"))):
        name = os.path.basename(path)[:-4]
        try:
            nodes = list(ET.parse(path).getroot().iter("node"))
        except ET.ParseError as e:
            print(f"{name}: 解析失败 {e}")
            continue
        clickable = [n for n in nodes if n.get("clickable") == "true"]
        yield name, nodes, clickable


def report_coverage():
    print("=== 1. 标签覆盖率 ===")
    print(f"{'App':<16}{'节点':>6}{'可点击':>7}{'自身有标签':>11}{'含后代':>9}")
    print("-" * 50)
    rows = []
    for name, nodes, clk in load():
        if not clk:
            print(f"{name:<16}{len(nodes):>6}{0:>7}{'-':>11}{'-':>9}")
            continue
        self_lab = sum(1 for x in clk
                       if (x.get("text") or "").strip()
                       or (x.get("content-desc") or "").strip())
        sub_lab = sum(1 for x in clk if subtree_any_label(x))
        rows.append((name, len(clk), self_lab, sub_lab))
        print(f"{name:<16}{len(nodes):>6}{len(clk):>7}"
              f"{self_lab:>8}{self_lab/len(clk)*100:>4.0f}%"
              f"{sub_lab:>6}{sub_lab/len(clk)*100:>4.0f}%")
    if rows:
        c = sum(r[1] for r in rows); s = sum(r[2] for r in rows); d = sum(r[3] for r in rows)
        print("-" * 50)
        print(f"{'合计':<16}{'':>6}{c:>7}{s:>8}{s/c*100:>4.0f}%{d:>6}{d/c*100:>4.0f}%")
        print(f"→ 含后代可读标签 {d}/{c} = {d/c*100:.1f}%；纯盲 {c-d} 个 = {(c-d)/c*100:.1f}%")


def report_ambiguity():
    print("\n=== 2. 文字歧义度（可点击节点子树内 text 段数）===")
    buckets = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0}  # 4 表示 >=4
    total = 0
    for _, _, clk in load():
        for x in clk:
            total += 1
            k = len(subtree_texts(x))
            buckets[min(k, 4)] += 1
    labels = {0: "0 段（纯盲）", 1: "1 段（语义唯一）", 2: "2 段", 3: "3 段",
              4: "4 段及以上（歧义大）"}
    for k in sorted(buckets):
        v = buckets[k]
        print(f"  {labels[k]:<20}{v:>5}  {v/total*100:>5.1f}%")
    print(f"\n  语义唯一（仅 1 段）: {buckets[1]}/{total} = {buckets[1]/total*100:.1f}%")
    print(f"  需 OCR 介入（0 段或 >=2 段）: "
          f"{total-buckets[1]}/{total} = {(total-buckets[1])/total*100:.1f}%")


def report_ghosts():
    print("\n=== 3. 幽灵节点（零面积 / 屏外 的可点击节点）===")
    total = zero = off = part = 0
    for _, _, clk in load():
        for x in clk:
            total += 1
            b = parse_bounds(x)
            if not b:
                continue
            l, t, r, bo = b
            if r - l <= 0 or bo - t <= 0:
                zero += 1
            elif r <= 0 or bo <= 0 or l >= SW or t >= SH:
                off += 1
            elif l < 0 or t < 0 or r > SW or bo > SH:
                part += 1
    print(f"  可点击 {total}：零面积 {zero}，完全屏外 {off}，部分屏外 {part}")
    print(f"  → 幽灵节点占比 {(zero+off)/total*100:.1f}%")


if __name__ == "__main__":
    report_coverage()
    report_ambiguity()
    report_ghosts()
