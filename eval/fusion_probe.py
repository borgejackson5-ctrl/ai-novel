"""在已跑通的两路结果上试不同的融合方式（离线，不打乱生产实现）。

背景：`hybrid_probe.py` 测出来 RRF 融合在「块含依据句」这个口径上只赢了一点点，
而 Top1 反而变差（向量 12 → 混合 10）。怀疑是融合方式的问题 ——
先在这里把几种策略并排跑一遍，挑出真正有用的那个，再落到 Java 里。
数据来自生产诊断接口（`mode=vector` / `mode=keyword`，各取 Top10），
所以试的是**真实两路排名**，只有「怎么合」这一步在 Python 里。

用法：python fusion_probe.py [--cases chunk-queries]
"""

from __future__ import annotations

import argparse
import io
import json
import re
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8081"
CASES_DIR = "D:/java-project/ai-novel/eval/cases"
INJECT_K = 3
CAND = 10        # 每路取多少候选


def call(method, path, token=None, timeout=120):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def squash(t):
    return re.sub(r"\s+", "", t or "")


def key(hit):
    return f"{hit.get('chapterId')}-{hit.get('seq')}"


def rrf(lists, k):
    scores, by = {}, {}
    for hits in lists:
        for rank, h in enumerate(hits, start=1):
            by.setdefault(key(h), h)
            scores[key(h)] = scores.get(key(h), 0.0) + 1.0 / (k + rank)
    return [by[x] for x in sorted(scores, key=scores.get, reverse=True)]


def vector_first(v, kw):
    """向量为主、关键词只补位（不去改动向量的名次）"""
    out, seen = [], set()
    for h in list(v) + list(kw):
        if key(h) in seen:
            continue
        seen.add(key(h))
        out.append(h)
    return out


def intersection_first(v, kw):
    """两路都命中的排最前（它们最可能是真要找的那条），其余按向量的顺序"""
    vkeys = [key(h) for h in v]
    kkeys = {key(h) for h in kw}
    both = [h for h in v if key(h) in kkeys]
    only = [h for h in v if key(h) not in kkeys] + [h for h in kw if key(h) not in set(vkeys)]
    seen, out = set(), []
    for h in both + only:
        if key(h) in seen:
            continue
        seen.add(key(h))
        out.append(h)
    return out


def round_robin(v, kw):
    """交替取：向量第1、关键词第1、向量第2、关键词第2……"""
    out, seen, i = [], set(), 0
    while i < max(len(v), len(kw)):
        for lst in (v, kw):
            if i < len(lst) and key(lst[i]) not in seen:
                seen.add(key(lst[i]))
                out.append(lst[i])
        i += 1
    return out


STRATEGIES = {
    "只向量": lambda v, kw: v,
    "只关键词": lambda v, kw: kw,
    "RRF(k=10)": lambda v, kw: rrf([v, kw], 10),
    "RRF(k=60)": lambda v, kw: rrf([v, kw], 60),
    "向量优先+补位": vector_first,
    "交集优先": intersection_first,
    "交替取": round_robin,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default="chunk-queries")
    args = ap.parse_args()

    cases = json.load(io.open(f"{CASES_DIR}/{args.cases}.json", encoding="utf-8"))
    novel_id = cases["novelId"]
    # 登录（POST 要带 body）
    req = urllib.request.Request(BASE + "/auth/login",
                                 data=json.dumps({"identifier": "admin", "password": "admin123"}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as r:
        token = json.loads(r.read().decode())["data"]["token"]

    pairs = []
    for q in cases["queries"]:
        qq = urllib.parse.quote(q["query"])
        v = call("POST", f"/novel/vector-search?novelId={novel_id}&q={qq}&topK={CAND}&mode=vector", token)["data"]
        kw = call("POST", f"/novel/vector-search?novelId={novel_id}&q={qq}&topK={CAND}&mode=keyword", token)["data"]
        pairs.append((q, v, kw))
    print(f"两路候选已取回：{len(pairs)} 条查询 × Top{CAND}\n")

    print(f"{'策略':<14}{'Top1 有用':>10}{'Top3 有用':>11}{'Top5 有用':>11}")
    print("-" * 46)
    for name, fn in STRATEGIES.items():
        t1 = t3 = t5 = 0
        for q, v, kw in pairs:
            fused = fn(v, kw)
            ev = squash(q["evidence"])
            useful = [squash(h.get("text")) for h in fused]
            pos = next((i + 1 for i, t in enumerate(useful) if ev in t), 0)
            t1 += 1 if pos == 1 else 0
            t3 += 1 if 1 <= pos <= INJECT_K else 0
            t5 += 1 if pos > 0 else 0
        print(f"{name:<14}{t1:>7}/{len(pairs)}{t3:>8}/{len(pairs)}{t5:>8}/{len(pairs)}")

    # 融合后「有用的那条排第几」的分布，用来看某条被挤到哪里去了
    print("\n有用的那一条排在第几（按策略）：")
    for name, fn in STRATEGIES.items():
        dist = {}
        for q, v, kw in pairs:
            fused = fn(v, kw)
            ev = squash(q["evidence"])
            pos = next((i + 1 for i, h in enumerate(fused) if ev in squash(h.get("text"))), 0)
            dist[pos] = dist.get(pos, 0) + 1
        pretty = "　".join(f"第{p or '未命中'}名={c}" for p, c in sorted(dist.items()))
        print(f"  {name:<12} {pretty}")


if __name__ == "__main__":
    main()
