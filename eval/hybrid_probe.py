"""混合检索的效果评测：**打生产接口**，不复制检索逻辑。

与 `chunk_probe.py` 的区别很重要：

- `chunk_probe.py` 是给「块粒度」调参用的 —— 它在 ES 里另建 `chunk_probe_*` 索引、
  用 Python 复刻的切块器，测的是**参数**；
- 这个脚本测的是**生产代码路径**：走 `POST /novel/vector-search`（管理员诊断接口），
  三种走法 vector / keyword / hybrid 在同一次部署里各跑一遍，比的是 Java 里那套实现。

判定口径：**返回的块文本里含不含依据句**（都去掉空白后比对）。
依据句在原文里是唯一出现的（查询集加载时断言），所以「含依据句」等价于
「既命中正确那一章、又给出了有用的那一段」—— 比只看章号更严，也正是要证明的东西。

用法：
    python hybrid_probe.py run --cases review-cases --label hybrid-main
    python hybrid_probe.py run --cases holdout --label hybrid-holdout
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = "D:/java-project/ai-novel"
CASES_DIR = ROOT + "/eval/cases"
REPORTS = ROOT + "/eval/reports"
BASE = "http://127.0.0.1:8081"
ACCOUNT = {"identifier": "admin", "password": "admin123"}

MODES = ["vector", "keyword", "hybrid"]
TOP_K = 5           # 一次多取几条，便于看「第几条才有用」
INJECT_K = 3        # 生产实际注入 prompt 的条数（审查那边取 3）


def call(method, path, body=None, token=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")


def squash(text: str) -> str:
    """去掉所有空白：块文本里有换行与缩进，而依据句是紧凑的"""
    return re.sub(r"\s+", "", text or "")


def load_cases(name: str):
    path = f"{CASES_DIR}/{name}.json"
    data = json.load(io.open(path, encoding="utf-8"))
    return data


def probe(cases, mode, token, novel_id):
    rows = []
    t0 = time.time()
    for q in cases["queries"]:
        resp = call("POST", f"/novel/vector-search?novelId={novel_id}"
                           f"&q={urllib.parse.quote(q['query'])}&topK={TOP_K}&mode={mode}", None, token)
        hits = resp.get("data") or []
        evidence = squash(q["evidence"])
        texts = [squash(h.get("text")) for h in hits]
        # 第几条开始有用（1 起；0 表示一条都没有）
        firstUseful = 0
        for i, t in enumerate(texts):
            if evidence in t:
                firstUseful = i + 1
                break
        chapters = [h.get("chapterNo") for h in hits]
        rows.append({
            "id": q["id"], "kind": q["kind"], "query": q["query"],
            "expect": q["expectChapterNo"], "evidence": q["evidence"],
            "chapters": chapters,
            "hitCount": len(hits),
            "firstUseful": firstUseful,
            "top1Useful": firstUseful == 1,
            "top3Useful": 1 <= firstUseful <= INJECT_K,
            "topKUseful": firstUseful > 0,
            "top1Chapter": chapters[0] if chapters else None,
            "top1ChapterHit": bool(chapters) and chapters[0] == q["expectChapterNo"],
            "top3ChapterHit": q["expectChapterNo"] in chapters[:INJECT_K],
            "top1Head": (hits[0].get("text") or "")[:40].replace("\n", " ") if hits else None,
        })
    n = len(rows)
    summary = {
        "mode": mode, "n": n,
        "top1Useful": sum(r["top1Useful"] for r in rows),
        "top3Useful": sum(r["top3Useful"] for r in rows),
        "topKUseful": sum(r["topKUseful"] for r in rows),
        "top1ChapterHit": sum(r["top1ChapterHit"] for r in rows),
        "top3ChapterHit": sum(r["top3ChapterHit"] for r in rows),
        "emptyHits": sum(1 for r in rows if r["hitCount"] == 0),
        "top1Useful_A": sum(r["top1Useful"] for r in rows if r["kind"] == "A"),
        "top1Useful_A_n": sum(1 for r in rows if r["kind"] == "A"),
        "top1Useful_B": sum(r["top1Useful"] for r in rows if r["kind"] == "B"),
        "top1Useful_B_n": sum(1 for r in rows if r["kind"] == "B"),
        "ms": round((time.time() - t0) / n * 1000),
    }
    return summary, rows


def write_report(label, cases, results, path):
    L = []
    add = L.append
    add(f"# 混合检索评测：{label}\n")
    add(f"时间：{time.strftime('%Y-%m-%d %H:%M')}　数据：`cases/{cases.get('_file')}.json`"
        f"　查询 {len(cases['queries'])} 条")
    add("")
    add("**判定**：返回的块文本里含不含依据句（去空白后比对）。依据句在原文里唯一出现，")
    add("所以「含依据句」= 既命中正确那一章、又给出了有用的那一段。")
    add(f"`Top3 有用` 是生产口径 —— 审查那边正好注入 3 条。\n")
    add("| 走法 | Top1 有用 | **Top3 有用** | Top5 有用 | Top1 命中该章 | 一条都没返回 | 平均耗时 |")
    add("|---|---|---|---|---|---|---|")
    for mode in MODES:
        m = results[mode]["summary"]
        add(f"| {mode} | {m['top1Useful']}/{m['n']} | **{m['top3Useful']}/{m['n']}** | "
            f"{m['topKUseful']}/{m['n']} | {m['top1ChapterHit']}/{m['n']} | {m['emptyHits']} | {m['ms']}ms |")
    add("")
    add("## 分类（A 类提问带实体名 / B 类只能靠语义）\n")
    add("| 走法 | Top1 有用·A | Top1 有用·B |")
    add("|---|---|---|")
    for mode in MODES:
        m = results[mode]["summary"]
        add(f"| {mode} | {m['top1Useful_A']}/{m['top1Useful_A_n']} | "
            f"{m['top1Useful_B']}/{m['top1Useful_B_n']} |")
    add("")
    add("## 逐条（第几条才有用：1 = 第一条就是，0 = 一条都没有）\n")
    add("| 查询 | 类 | 期望章 | 向量 | 关键词 | 混合 |")
    add("|---|---|---|---|---|---|")
    index = {mode: {r["id"]: r for r in results[mode]["rows"]} for mode in MODES}
    for q in cases["queries"]:
        cells = []
        for mode in MODES:
            r = index[mode][q["id"]]
            mark = "—" if r["firstUseful"] == 0 else str(r["firstUseful"])
            if r["firstUseful"] == 1:
                mark = "**1**"
            cells.append(mark)
        add(f"| {q['query']} | {q['kind']} | 第 {q['expectChapterNo']} 章 | " + " | ".join(cells) + " |")
    add("")
    io.open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")
    return len(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["run"])
    ap.add_argument("--cases", default="chunk-queries")
    ap.add_argument("--label", default="hybrid")
    ap.add_argument("--modes", default=",".join(MODES))
    args = ap.parse_args()

    cases = load_cases(args.cases)
    cases["_file"] = args.cases
    novel_id = cases["novelId"]
    token = call("POST", "/auth/login", ACCOUNT)["data"]["token"]

    results = {}
    for mode in args.modes.split(","):
        summary, rows = probe(cases, mode, token, novel_id)
        results[mode] = {"summary": summary, "rows": rows}
        print(f"  {mode:>8}: Top1 有用 {summary['top1Useful']}/{summary['n']}　"
              f"Top3 有用 {summary['top3Useful']}/{summary['n']}　"
              f"空结果 {summary['emptyHits']}　{summary['ms']}ms")

    os.makedirs(REPORTS, exist_ok=True)
    md = f"{REPORTS}/{args.label}.md"
    n = write_report(args.label, cases, results, md)
    with io.open(f"{REPORTS}/{args.label}.json", "w", encoding="utf-8") as f:
        f.write(json.dumps({"cases": cases.get("_file"), "novelId": novel_id,
                            "at": time.strftime("%Y-%m-%d %H:%M"),
                            "results": results}, ensure_ascii=False, indent=1) + "\n")
    print(f"\n报告已写出：{md}（{n} 行）")


if __name__ == "__main__":
    main()
