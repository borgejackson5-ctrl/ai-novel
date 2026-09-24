"""块粒度实验：在真实长篇章节上量「每块多少字」对检索的影响。

背景：生产代码 `ChapterChunker` 的块大小写死 400 字，注释里自己写着「没有调过参」。
评测样本每章只有 150~260 字（整章一块），所以从来没在真实章节上量过 ——
而真实章节平均 3700+ 字（邪修天王，40 章 13.5 万字），400 字一块 = 每章约 10 块。
「一块多少字」在真实数据上到底影响什么，这个脚本就是回答它的。

切块用 `chunk_impl.py`（生产 `ChapterChunker` 的逐行复刻，已被哈希比对证明一致）。
不直接改 Java 常量重启，是因为要跑 4 组、每组都要重新打包 + 重启 + 回填；
复刻一致的前提下，这样快得多，而且结论落到产品上是改一个常量的事。

指标（都跨粒度可比）：
- **章级命中**：Top1 / Top3 里有没有正确的那一章 —— 决定报告能不能写「第 N 章」；
- **定位覆盖**：Top1 那个块有没有**覆盖到依据句**（用原文里的字符区间判，不看块号）——
  粒度越细越容易覆盖，越粗越容易「命中了章但没给出有用的一段」；
- **分差**：Top1 与 Top2 的相似度差 —— 越小说明向量越「什么都像」，等于没有区分力；
- **同章冗余**：Top3 里有几条来自同一章 —— 粒度太细时同一件事被拆成几块，白占 prompt；
- **关键词检索对照**：同样的问题交给 IK 分词的关键词检索，看差多少 —— 这才是 RAG 的增量。

用法：
    python chunk_probe.py check                      # 只校验查询集与切块一致性
    python chunk_probe.py run                        # 跑全部粒度
    python chunk_probe.py run --sizes 400,1000       # 只跑指定粒度
"""

from __future__ import annotations

import argparse
import io
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chunk_impl  # noqa: E402

ROOT = "D:/java-project/ai-novel"
PROBE_DIR = "D:/WorkBuddySave/ai-novel/probe"
CHAPTERS = PROBE_DIR + "/chapters.jsonl"
CACHE = PROBE_DIR + "/embed-cache.jsonl"
CASES = ROOT + "/eval/cases/chunk-queries.json"
REPORTS = ROOT + "/eval/reports"
NOVEL_ID = 2098335799332089857

DIMENSIONS = 1024
EMBED_MODEL = "text-embedding-v3"
EMBED_BATCH = 10          # 百炼单次上限就是 10 条（实测 25 条直接 400）
DEFAULT_SIZES = [200, 400, 600, 1000]
# 由命令行设置：换一批章节/查询时必须换索引后缀，否则两套数据会混进同一个索引
_CHAPTERS_FILE = CHAPTERS
_CASES_FILE = CASES
_SUFFIX = ""
OVERLAP_RATIO = 0.15      # 重叠按粒度的 15% 走：生产是 60/400，正好 15%
TOP_K = 3
# 关键词检索的候选数：不设 num_candidates 之类的东西，就是普通 match + size=3


# ==================== 配置 ====================

def load_conf():
    conf = io.open(ROOT + "/backend/src/main/resources/application-local.yaml", encoding="utf-8").read()
    block = conf.split("dashscope:")[1].split("\n\n")[0]
    key = re.search(r'api-key:\s*"?([^"\n]+)"?', block).group(1).strip()
    base = re.search(r"base-url:\s*(\S+)", block).group(1).strip()
    es = "http://127.0.0.1:9201"
    m = re.search(r"elasticsearch:\s*\n(?:.*\n)*?\s+uris?:\s*(\S+)", conf)
    if m:
        es = m.group(1).strip().rstrip(",")
    return key, base.rstrip("/"), es


# ==================== embedding（带本地缓存，重跑不再花钱） ====================

class Embedder:

    def __init__(self, key: str, base: str):
        self.url = base + "/compatible-mode/v1/embeddings"
        self.key = key
        self.cache = {}
        self.hits = 0
        self.calls = 0
        self.chars = 0
        if os.path.exists(CACHE):
            for line in io.open(CACHE, encoding="utf-8"):
                try:
                    row = json.loads(line)
                    self.cache[row["h"]] = row["v"]
                except Exception:
                    continue

    def _save(self, h: str, vec):
        with io.open(CACHE, "a", encoding="utf-8") as f:
            f.write(json.dumps({"h": h, "v": vec}, ensure_ascii=False) + "\n")

    def _request(self, texts):
        payload = {"model": EMBED_MODEL, "input": texts, "dimensions": DIMENSIONS,
                   "encoding_format": "float"}
        req = urllib.request.Request(self.url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                     method="POST")
        req.add_header("Authorization", "Bearer " + self.key)
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=90) as r:
            body = json.loads(r.read().decode("utf-8"))
        # 必须按 index 排序：返回顺序不保证与输入一致，错位了向量就配错文本，且不报错
        data = sorted(body["data"], key=lambda d: d["index"])
        return [[round(x, 6) for x in d["embedding"]] for d in data]

    def embed(self, texts):
        """按 10 条一批切分，返回与输入等长的向量列表"""
        out = [None] * len(texts)
        todo = []
        for i, t in enumerate(texts):
            h = chunk_impl.sha16(t)
            if h in self.cache:
                out[i] = self.cache[h]
                self.hits += 1
            else:
                todo.append((i, h, t))
        for s in range(0, len(todo), EMBED_BATCH):
            batch = todo[s:s + EMBED_BATCH]
            for attempt in range(3):
                try:
                    vecs = self._request([b[2] for b in batch])
                    break
                except Exception as e:
                    if attempt == 2:
                        raise
                    print(f"    embedding 重试（{e}）")
                    time.sleep(2)
            self.calls += 1
            self.chars += sum(len(b[2]) for b in batch)
            for (i, h, _), v in zip(batch, vecs):
                out[i] = v
                self.cache[h] = v
                self._save(h, v)
        return out


# ==================== ES ====================

def es_call(method, path, body=None, es_url="http://127.0.0.1:9201", ndjson=None):
    data = None
    headers = {"Content-Type": "application/json"}
    if ndjson is not None:
        data = ndjson.encode("utf-8")
        headers["Content-Type"] = "application/x-ndjson"
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(es_url + path, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = r.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"ES {method} {path} -> {e.code}: {detail[:500]}")


def index_name(size: int) -> str:
    name = f"chunk_probe_p{size}{_SUFFIX}"
    # 防呆：这个脚本只允许碰自己的探针索引，绝不能碰到 novel / chapter_chunk
    assert name.startswith("chunk_probe_"), name
    return name


def recreate_index(size: int, es_url: str):
    name = index_name(size)
    es_call("DELETE", f"/{name}", es_url=es_url) if es_exists(name, es_url) else None
    mapping = {
        "mappings": {
            "properties": {
                "novelId": {"type": "long"},
                "chapterNo": {"type": "integer"},
                "chapterTitle": {"type": "keyword", "index": False},
                "seq": {"type": "integer"},
                "charStart": {"type": "integer"},
                "charEnd": {"type": "integer"},
                "text": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "vector": {"type": "dense_vector", "dims": DIMENSIONS, "index": True,
                           "similarity": "cosine"},
            }
        }
    }
    es_call("PUT", f"/{name}", mapping, es_url)


def es_exists(name: str, es_url: str) -> bool:
    try:
        es_call("HEAD", f"/{name}", es_url=es_url)
        return True
    except RuntimeError as e:
        if "404" in str(e):
            return False
        raise


# ==================== 数据 ====================

def load_chapters():
    rows = []
    for line in io.open(_CHAPTERS_FILE, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("{"):
            rows.append(json.loads(line))
    return rows


def load_queries(chapters):
    """加载查询集并校验：依据句必须在对应章的正文里唯一出现"""
    data = json.load(io.open(_CASES_FILE, encoding="utf-8"))
    by_no = {c["no"]: c["content"].strip() for c in chapters}
    bad = []
    for q in data["queries"]:
        text = by_no.get(q["expectChapterNo"])
        if text is None:
            bad.append(f'{q["id"]}：第 {q["expectChapterNo"]} 章不在实验数据里')
            continue
        n = text.count(q["evidence"])
        if n != 1:
            bad.append(f'{q["id"]}：依据句在正文里出现 {n} 次（要求恰好 1 次）→ {q["evidence"]}')
            continue
        q["_goldPos"] = text.index(q["evidence"])
        q["_goldLen"] = len(q["evidence"])
        # 把「依据句落在哪个块」算出来：这一章的切块要用**每一种粒度**各算一次，
        # 所以 gold 归属放在检索阶段判（用字符区间），不预存块号
    if bad:
        print("查询集校验失败：")
        for b in bad:
            print("  ✗", b)
        raise SystemExit(1)
    print(f"查询集校验通过：{len(data['queries'])} 条，依据句都在对应章里唯一出现")
    return data["queries"]


# ==================== 回填 ====================

def build_index(size: int, chapters, embedder: Embedder, es_url: str):
    overlap = max(1, int(size * OVERLAP_RATIO))
    recreate_index(size, es_url)
    total_chunks = 0
    total_chars = 0
    t0 = time.time()
    lines = []
    seq_by_chapter = []
    for c in chapters:
        chunks = chunk_impl.split(c["content"], chunk_chars=size, overlap_chars=overlap,
                                 with_offsets=True)
        seq_by_chapter.append((c["no"], chunks))
        total_chunks += len(chunks)
        total_chars += sum(len(x[1]) for x in chunks)
    print(f"  切块：{len(chapters)} 章 → {total_chunks} 块（含重叠共 {total_chars} 字）")

    written = 0
    for no, chunks in seq_by_chapter:
        vecs = embedder.embed([x[1] for x in chunks])
        for (seq, text, start, end), vec in zip(chunks, vecs):
            doc = {"novelId": NOVEL_ID, "chapterNo": no, "seq": seq,
                   "charStart": start, "charEnd": end, "text": text, "vector": vec}
            lines.append(json.dumps({"index": {"_index": index_name(size), "_id": f"{NOVEL_ID}-{no}-{seq}"}},
                                    ensure_ascii=False))
            lines.append(json.dumps(doc, ensure_ascii=False))
        if len(lines) >= 400:
            es_call("POST", "/_bulk", ndjson="\n".join(lines) + "\n", es_url=es_url)
            written += len(lines) // 2
            lines = []
    if lines:
        es_call("POST", "/_bulk", ndjson="\n".join(lines) + "\n", es_url=es_url)
        written += len(lines) // 2
    es_call("POST", f"/{index_name(size)}/_refresh", es_url=es_url)
    stat = es_call("GET", f"/{index_name(size)}/_stats", es_url=es_url)
    size_bytes = stat["indices"][index_name(size)]["total"]["store"]["size_in_bytes"]
    print(f"  写入 {written} 块，索引 {size_bytes / 1024 / 1024:.1f} MB，耗时 {time.time() - t0:.1f}s")
    return {"chunkCount": total_chunks, "chunkChars": total_chars, "indexBytes": size_bytes,
            "buildSeconds": round(time.time() - t0, 1),
            "avgChunkLen": round(total_chars / total_chunks, 1) if total_chunks else 0,
            "chunksPerChapter": round(total_chunks / len(chapters), 1) if chapters else 0}


# ==================== 检索 ====================

def vector_search(vec, size: int, es_url: str):
    body = {"knn": {"field": "vector", "query_vector": vec, "k": TOP_K, "num_candidates": 100,
                    "filter": {"term": {"novelId": NOVEL_ID}}},
            "_source": ["chapterNo", "seq", "charStart", "charEnd", "text", "chapterTitle"]}
    res = es_call("POST", f"/{index_name(size)}/_search", body, es_url)
    return res["hits"]["hits"]


def keyword_search(query: str, size: int, es_url: str):
    body = {"size": TOP_K,
            "query": {"bool": {"filter": [{"term": {"novelId": NOVEL_ID}}],
                               "must": [{"match": {"text": query}}]}},
            "_source": ["chapterNo", "seq", "charStart", "charEnd", "text"]}
    res = es_call("POST", f"/{index_name(size)}/_search", body, es_url)
    return res["hits"]["hits"]


def evaluate(size: int, queries, embedder: Embedder, es_url: str):
    # 依据句会不会被切块切散：直接查这一章里有没有**任何一个块**能完整覆盖它
    blocks_by_chapter = es_call("POST", f"/{index_name(size)}/_search", {
        "size": 2000, "query": {"term": {"novelId": NOVEL_ID}},
        "_source": ["chapterNo", "charStart", "charEnd"]}, es_url)["hits"]["hits"]
    spans = {}
    for h in blocks_by_chapter:
        spans.setdefault(h["_source"]["chapterNo"], []).append(
            (h["_source"]["charStart"], h["_source"]["charEnd"]))
    for q in queries:
        cands = spans.get(q["expectChapterNo"], [])
        q["singleChunkCovers"] = any(
            a <= q["_goldPos"] and q["_goldPos"] + q["_goldLen"] <= b for a, b in cands)
        q["coveringBlocks"] = sum(
            1 for a, b in cands if a <= q["_goldPos"] and q["_goldPos"] + q["_goldLen"] <= b)

    qvecs = embedder.embed([q["query"] for q in queries])
    rows = []
    t_vec = t_kw = 0.0
    for q, vec in zip(queries, qvecs):
        t0 = time.time()
        vhits = vector_search(vec, size, es_url)
        t_vec += time.time() - t0
        t0 = time.time()
        khits = keyword_search(q["query"], size, es_url)
        t_kw += time.time() - t0

        vchapters = [h["_source"]["chapterNo"] for h in vhits]
        kchapters = [h["_source"]["chapterNo"] for h in khits]
        top1 = vhits[0] if vhits else None
        cover = False
        if top1:
            s = top1["_source"]
            cover = s["charStart"] <= q["_goldPos"] and q["_goldPos"] + q["_goldLen"] <= s["charEnd"]
        # 生产是**注入 Top3**（不是只看第一名），所以还有一个更贴近实际的口径：
        # 三条里只要有一条既是正确章、又覆盖了依据句，模型就有对照依据了
        useful3 = False
        for h in vhits:
            s3 = h["_source"]
            if s3["chapterNo"] == q["expectChapterNo"] and s3["charStart"] <= q["_goldPos"]                     and q["_goldPos"] + q["_goldLen"] <= s3["charEnd"]:
                useful3 = True
                break
        # 注意：gold 位置是「原文（strip 后）」的绝对下标，块里存的就是同一套下标 ——
        # 块文本是原文的切片，所以能直接比。粒度换了这套下标不变，因此跨粒度可比
        gap = (vhits[0]["_score"] - vhits[1]["_score"]) if len(vhits) > 1 else None
        rows.append({
            "id": q["id"], "kind": q["kind"], "query": q["query"],
            "expect": q["expectChapterNo"],
            "vTop1": vchapters[0] if vchapters else None,
            "vTop3": vchapters,
            "vHit1": bool(vchapters) and vchapters[0] == q["expectChapterNo"],
            "vHit3": q["expectChapterNo"] in vchapters,
            "kTop1": kchapters[0] if kchapters else None,
            "kTop3": kchapters,
            "kHit1": bool(kchapters) and kchapters[0] == q["expectChapterNo"],
            "kHit3": q["expectChapterNo"] in kchapters,
            "cover": cover,
            "usefulIn3": useful3,
            "singleChunkCovers": q.get("singleChunkCovers"),
            "coveringBlocks": q.get("coveringBlocks"),
            "top1Chars": len(top1["_source"]["text"]) if top1 else None,
            "gap": round(gap, 4) if gap is not None else None,
            "sameChapterInTop3": sum(1 for c in vchapters if c == vchapters[0]) - 1 if vchapters else 0,
            "top1Text": (top1["_source"]["text"][:60].replace("\n", " ") if top1 else None),
        })
    n = len(rows)
    summary = {
        "size": size,
        "vHit1": sum(r["vHit1"] for r in rows),
        "vHit3": sum(r["vHit3"] for r in rows),
        "kHit1": sum(r["kHit1"] for r in rows),
        "kHit3": sum(r["kHit3"] for r in rows),
        "cover": sum(r["cover"] for r in rows),
        # 交叉：Top1 命中正确章、且块真的覆盖了依据句 —— 这才是「一条有用的结果」的严格口径。
        # 单看 cover 会被「Top1 命中错章」拖累，两类失败要分开看
        "useful": sum(1 for r in rows if r["vHit1"] and r["cover"]),
        # 最贴近生产的口径：注入 Top3，其中至少一条能当依据
        "usefulIn3": sum(1 for r in rows if r["usefulIn3"]),
        "coverWhenHit": sum(1 for r in rows if r["vHit1"] and r["cover"]),
        "coverWhenHitN": sum(1 for r in rows if r["vHit1"]),
        # 依据句跨了两个块：粒度小时会变多 —— 这是「块越细并不总是越好」的直接体现
        "goldSplit": sum(1 for r in rows if not r.get("singleChunkCovers", True)),
        "vHit1_A": sum(r["vHit1"] for r in rows if r["kind"] == "A"),
        "vHit1_A_n": sum(1 for r in rows if r["kind"] == "A"),
        "vHit1_B": sum(r["vHit1"] for r in rows if r["kind"] == "B"),
        "vHit1_B_n": sum(1 for r in rows if r["kind"] == "B"),
        "kHit1_A": sum(r["kHit1"] for r in rows if r["kind"] == "A"),
        "kHit1_B": sum(r["kHit1"] for r in rows if r["kind"] == "B"),
        "avgGap": round(sum(r["gap"] for r in rows if r["gap"] is not None)
                        / max(1, sum(1 for r in rows if r["gap"] is not None)), 4),
        "avgTop1Chars": round(sum(r["top1Chars"] for r in rows if r["top1Chars"])
                              / max(1, n), 1),
        "redundancy": round(sum(r["sameChapterInTop3"] for r in rows) / max(1, n), 2),
        "avgVecMs": round(t_vec / n * 1000),
        "avgKwMs": round(t_kw / n * 1000),
        "n": n,
    }
    return summary, rows


# ==================== 报告 ====================

def write_report(results, meta, path, suffix=""):
    L = []
    add = L.append
    add("# 块粒度实验：在真实长篇章节上量「每块多少字」（阶段 7 RAG）\n")
    add(f"时间：{meta['at']}　书：**{meta['novelTitle']}**（{meta['novelId']}）　"
        f"章节：前 {meta['chapters']} 章，共 {meta['totalChars']} 字"
        f"（章长 {meta['minLen']}~{meta['maxLen']}，均值 {meta['avgLen']}）")
    add(f"查询集：`eval/cases/chunk-queries.json`，{meta['queryCount']} 条"
        f"（A 类 {meta['aCount']} 条含实体名 / B 类 {meta['bCount']} 条只能靠语义）")
    add("")
    add("## 为什么要做这一遍\n")
    add("生产代码 `ChapterChunker` 的块大小写死 **400 字**，注释里自己写着「没有调过参」。")
    add("以前的评测样本每章只有 150~260 字 —— **整章一块**，所以「块粒度」这个参数从来没被量过。")
    add(f"真实章节是 {meta['avgLen']} 字（这 40 章里最短 {meta['minLen']}、最长 {meta['maxLen']}），")
    add("400 字一块在真实章上是 **每章 "
        f"{next(iter(results.values()))['build']['chunksPerChapter']:.0f} 块** 的量级 —— 完全是另一回事。\n")
    add("## 前提：实验用的切块实现与生产代码一致\n")
    add("多粒度对比要跑 4 组，每组都改 Java 常量重新打包重启不现实。所以把 `ChapterChunker`")
    add("逐行复刻成 Python（`eval/chunk_impl.py`），并**用 Java 探针 dump 出的真实结果逐块比哈希**：")
    add("4 章（第 3/11/24/40 章，共 43 块）**块数、长度、整块 SHA-256 全部一致**。")
    add("切块实现不一致的话，这一页所有数字都跟线上没关系 —— 所以这一步是先做的。\n")
    add("## 结果\n")
    add("| 粒度 | 块数 | 每章块数 | Top1 块长 | 索引 | 向量·Top1 | 向量·Top3 | "
        "**Top3 里有一条有用** | Top1 就有用 | 关键词·Top1 | Top1-Top2 分差 | 同章冗余 | 依据句被切散 |")
    add("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for s in sorted(results):
        r = results[s]
        b, m = r["build"], r["summary"]
        add(f"| **{s} 字** | {b['chunkCount']} | {b['chunksPerChapter']} | {m['avgTop1Chars']:.0f} 字 | "
            f"{b['indexBytes'] / 1024 / 1024:.1f} MB | "
            f"**{m['vHit1']}/{m['n']}** | {m['vHit3']}/{m['n']} | "
            f"**{m['usefulIn3']}/{m['n']}** | {m['useful']}/{m['n']} | {m['kHit1']}/{m['n']} | "
            f"{m['avgGap']} | {m['redundancy']} | {m['goldSplit']}/{m['n']} |")
    add("")
    add("读法：")
    add("")
    add("- **向量·Top1**：第一名落在正确那一章 —— 决定报告能不能写「前文第 N 章」；")
    add("- **Top3 里有一条有用**：检索回来的三条里，至少有一条既是正确章、又覆盖了依据句。")
    add("  **这是最贴近生产的口径** —— 审查时就是把这 3 条摆进提示词的；")
    add("- **Top1 就有用**：只有第一条能当依据。两者之差说明「靠前几名一起兜」有多大余量；")
    add("- 顺带说明「Top1 命中」这个常见指标的问题：命中错章、与「命中了章但给不出那一句」")
    add("  是两种不同的失败，只看 Top1 会把它们混在一起 —— 大块尤其容易在这里虚高（见下）；")
    add("- **同章冗余**：Top3 里有几条来自同一章 —— 粒度细时同一件事被拆成几块，白占 prompt；")
    add("- **依据句被切散**：那一句话在两个块里各占一半（谁都没法单独提供完整依据）——")
    add("  这是「块越细并不总是越好」的直接体现。")
    add("")
    add("## 分类看（这才是关键）\n")
    add("| 粒度 | 向量·Top1（A 类含实体名） | 向量·Top1（B 类只能靠语义） | "
        "关键词·Top1（A 类） | 关键词·Top1（B 类） |")
    add("|---|---|---|---|---|")
    for s in sorted(results):
        m = results[s]["summary"]
        add(f"| {s} 字 | {m['vHit1_A']}/{m['vHit1_A_n']} | {m['vHit1_B']}/{m['vHit1_B_n']} | "
            f"{m['kHit1_A']}/{m['vHit1_A_n']} | {m['kHit1_B']}/{m['vHit1_B_n']} |")
    add("")
    add("A 类（提问里带实体名）关键词检索本来就查得到，所以**看两个差值**：")
    add("向量比关键词高多少（RAG 的增量）、B 类上向量能不能顶住（关键词在 B 类上基本无效）。")
    add("")
    add("## 逐条明细（以最小与最大两个粒度为代表）\n")
    for s in sorted(results):
        r = results[s]
        add(f"### {s} 字\n")
        add("| 查询 | 类 | 期望章 | 向量 Top1 | Top1 判定 | Top3 有用 | 用了哪块 | 关键词 Top1 |")
        add("|---|---|---|---|---|---|---|---|")
        add("")
        add("Top1 判定：✓ = 命中且给对了那一段；△ = 命中了章但那段不在 Top1 里；"
            "○ = 只在 Top3 里；✗ = 未命中。　Top3 有用：三条里至少有一条能当依据。")
        for row in r["rows"]:
            add(f"| {row['query']} | {row['kind']} | 第 {row['expect']} 章 | "
                f"{('第 ' + str(row['vTop1']) + ' 章') if row['vTop1'] else '—'} | "
                f"{'✓' if (row['vHit1'] and row['cover']) else ('△' if row['vHit1'] else ('○' if row['vHit3'] else '✗'))} | "
                f"{'✓' if row['usefulIn3'] else '·'} | "
                f"{(row['top1Text'] or '')[:26]} | "
                f"{('第 ' + str(row['kTop1']) + ' 章') if row['kTop1'] else '（无结果）'} |")
        add("")
    io.open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")
    return len(L)


# ==================== 主流程 ====================

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["check", "run"])
    ap.add_argument("--sizes", default=",".join(str(s) for s in DEFAULT_SIZES))
    ap.add_argument("--chapters", default=CHAPTERS, help="章节 JSONL")
    ap.add_argument("--queries", default=CASES, help="查询集 JSON")
    ap.add_argument("--suffix", default="", help="索引与报告的后缀（换数据时必须给，避免混进同一索引）")
    args = ap.parse_args()

    global _CHAPTERS_FILE, _CASES_FILE, _SUFFIX
    _CHAPTERS_FILE, _CASES_FILE, _SUFFIX = args.chapters, args.queries, args.suffix
    if args.chapters != CHAPTERS and not args.suffix:
        raise SystemExit("换了章节数据就必须给 --suffix，否则会覆盖第一套的索引")

    chapters = load_chapters()
    queries = load_queries(chapters)
    if args.action == "check":
        return

    key, base, es_url = load_conf()
    print(f"ES: {es_url}　embedding: {EMBED_MODEL}（{DIMENSIONS} 维，{EMBED_BATCH} 条/批）")
    embedder = Embedder(key, base)
    sizes = [int(s) for s in args.sizes.split(",")]

    results = {}
    for size in sizes:
        print(f"=== 粒度 {size} 字（重叠 {max(1, int(size * OVERLAP_RATIO))} 字）===")
        build = build_index(size, chapters, embedder, es_url)
        summary, rows = evaluate(size, queries, embedder, es_url)
        results[size] = {"build": build, "summary": summary, "rows": rows}
        print(f"  向量 Top1 {summary['vHit1']}/{summary['n']}、Top3 {summary['vHit3']}/{summary['n']}"
              f"；关键词 Top1 {summary['kHit1']}/{summary['n']}；定位覆盖 {summary['cover']}/{summary['n']}"
              f"；分差 {summary['avgGap']}；冗余 {summary['redundancy']}")
        print(f"  embedding：新调用 {embedder.calls} 次、本次输入 {embedder.chars} 字"
              f"（缓存命中 {embedder.hits} 条）")

    lens = [len(c["content"]) for c in chapters]
    meta = {
        "at": time.strftime("%Y-%m-%d %H:%M"),
        "chaptersFile": os.path.basename(_CHAPTERS_FILE),
        "queriesFile": os.path.basename(_CASES_FILE),
        "novelId": NOVEL_ID, "novelTitle": "邪修天王",
        "chapters": len(chapters), "totalChars": sum(lens),
        "minLen": min(lens), "maxLen": max(lens), "avgLen": sum(lens) // len(lens),
        "queryCount": len(queries),
        "aCount": sum(1 for q in queries if q["kind"] == "A"),
        "bCount": sum(1 for q in queries if q["kind"] == "B"),
    }
    os.makedirs(REPORTS, exist_ok=True)
    out_md = f"{REPORTS}/chunk-granularity{_SUFFIX and '-' + _SUFFIX.lstrip('_') or ''}.md"
    n = write_report(results, meta, out_md, _SUFFIX)
    out_json = f"{REPORTS}/chunk-granularity{_SUFFIX and '-' + _SUFFIX.lstrip('_') or ''}.json"
    with io.open(out_json, "w", encoding="utf-8") as f:
        f.write(json.dumps({"meta": meta, "results": results}, ensure_ascii=False, indent=1) + "\n")
    print(f"\n报告已写出：{out_md}（{n} 行）+ chunk-granularity.json")


if __name__ == "__main__":
    main()
