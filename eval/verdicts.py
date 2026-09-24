"""把「机器判不了的那几条报告」的人工判定写成文件，供 review_eval.py 的 rescore 用。

为什么要有这个脚本，而不是手工敲 `.verdicts.json`：

1. **每条判定都带着理由**，可以复查；落空的原因有好几种 ——
   真误报、方向报反、机制噪声 —— 而它们在「精确率」这一个数字里长得一模一样。
2. **换样本重跑时不会丢**。手敲的 JSON 会被下一次 `run` 覆盖掉，
   于是「上一轮我判成误报的那条」就查不到了（这个坑真踩过）。
3. 判定规则本身就是**可审查的代码**：想推翻哪一条，改这里，而不是去翻聊天记录。

用法：`python eval/verdicts.py [label ...]`（不给 label 就把 reports/ 下的都过一遍）
然后 `python eval/review_eval.py rescore --label <label>` 看报告里的「人工复核后」。

判定前缀决定计数口径（见 review_eval.py）：
`误报` 计入误报数，其它一律算「不是误报」。
"""

import io
import json
import os
import sys

REPORTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reports")

# (报告片段里的关键串, 判定)
#
# 这些是**机器判不了**的：报告没落在任何标注上，但也没法自动说它错 ——
# 得人看一眼才知道是「模型报了个真问题但方向反了」还是「纯噪声」。
RULES = [
    ("门板上钉着七颗铜钉",
     "方向反：矛盾是真的（同章后文写「一共六颗」），但报的是先出现的那句 —— "
     "作者照改会把对的那处改错"),
    ("他站在门后没动",
     "误报：与「他站着，一动没动」是同一种写法，模型当成啰嗦重复 —— 叙事里的有意复现"),
    ("他站着，一动没动",
     "误报：同上（模型在两轮里分别把这两句判成了重复）"),
    ("把地图卷起来",
     "误报：表里「旧地图」与正文「把地图」同位置只差一字，而差异字是虚词 —— 已加虚词过滤"),
    ("在地图边上",
     "误报：同上（「在地图」对「旧地图」）"),
    ("他肩上搭着一条灰布巾",
     "方向反：真问题（灰布巾 / 白布巾），但两轮给出的方向互相矛盾"),
    ("屋里只有一张桌子",
     "误报：正常承接写法，不是啰嗦"),
    ("灯油快见了底",
     "误报：口语里说得通，不是语病"),
    ("进来的是那个数铜钱的女子",
     "误报：模型对指代做了过度推断"),
    ("她把布巾还给他",
     "误报：过度推断 —— 「布巾」没写颜色，不算前后矛盾（前文的灰布巾/白布巾那处是真问题）"),
    ("北边的人还来吗",
     "误报：句子本身通顺，指代不算不清"),
    ("只有几个孩子围在摊子前看",
     "误报：把通顺的句子判成缺宾语"),
    ("能听见檐上滴水的声音",
     "误报：表里 2 字词「更声」与「能听见檐上」同位置只差一字 —— 与「灯」同一类碰撞噪声"),
    # 放最后：前面那些更具体的规则优先命中
    ("灯",
     "误报：表里的 2 字名字（灯市）或错写（灯心）带来的碰撞噪声 —— "
     "glossary1 那一轮十几条全是这一类，正是它把精确率打到 74%"),
]


def verdict_for(excerpt):
    for key, reason in RULES:
        if key in excerpt:
            return reason
    return None


def build(label):
    path = os.path.join(REPORTS, label + ".json")
    if not os.path.exists(path):
        return None
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)

    out, unknown = {}, []
    for mode in ("single", "task"):
        for cid, block in (data.get(mode) or {}).items():
            if cid.startswith("_"):
                continue
            for dt in block.get("details", []):
                if not dt["verdict"].startswith("未命中"):
                    continue
                reason = verdict_for(dt["excerpt"])
                if reason is None:
                    unknown.append((mode, cid, dt["excerpt"]))
                    continue
                # 同一个 excerpt 在两种模式下各出现一次是常态（去重键相同），只写一条
                out[f"{cid}|{dt['excerpt']}"] = reason

    with io.open(os.path.join(REPORTS, label + ".verdicts.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    return out, unknown


def main():
    labels = sys.argv[1:]
    if not labels:
        labels = sorted(f[:-5] for f in os.listdir(REPORTS)
                        if f.endswith(".json") and not f.endswith(".verdicts.json")
                        and not f.startswith("."))
    for label in labels:
        result = build(label)
        if result is None:
            print(f"{label}: 没有 {label}.json，跳过")
            continue
        out, unknown = result
        note = f"（{len(out)} 条判定）"
        if unknown:
            note += f" ⚠ 有 {len(unknown)} 条没写判定：{unknown}"
        print(f"{label}: {note}")


if __name__ == "__main__":
    main()
