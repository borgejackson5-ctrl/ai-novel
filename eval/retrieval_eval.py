"""检索侧评测：**不调模型**，直接量「检索有没有把该当依据的那一块捞出来」。

为什么要单独做一层：端到端报告里的「跨章命中」是**三段相乘**的结果 ——
检索有没有找到 → 有没有摆进提示词 → 模型有没有去比。哪一段掉了，从总数上看不出来。
两个方向都撞到过实例：
  · 把前文摆进提示词、模型也不比（第十轮：时间/称谓）；
  · 检索的关键词那一刀明明把依据排在第 2 名，融合后排到第 8 名，没进 top-3
    （第十轮：h10 那条，用 ES 直接复现量出来的）。
分开量，才知道该动哪一段。

判据不用「配对章号」这种写死的约定：对每条跨章标注，挑出**只出现在前文那一侧**的
特征串（不在本章正文里的那些），再看检索回来的块里有没有哪一块含它。
这就是「模型要核对时，手里有没有那份依据」。

用法：
    python eval/retrieval_eval.py --cases far-cases-v2
    python eval/retrieval_eval.py --cases far-cases-v2 --top-k 5
    python eval/retrieval_eval.py --cases holdout --chapter h10   # 只看某一章
"""
import argparse
import io
import json
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REPORTS = os.path.join(HERE, "reports")
ES = os.environ.get("EVAL_ES", "http://localhost:9201")
INDEX = "chapter_chunk"
LOCAL_YAML = os.path.join(ROOT, "backend", "src", "main", "resources", "application-local.yaml")

# 与服务端 HybridRanker / ChapterVectorServiceImpl 对齐的三个数
RRF_K = 10
CANDIDATE_MULTIPLIER = 2
MIN_CANDIDATES = 10
MAX_TOP_K = 20


def map_file(cases_name):
    return os.path.join(REPORTS, f".cases-map-{cases_name}.json")


def load_cases(cases_name):
    with io.open(os.path.join(HERE, "cases", f"{cases_name}.json"), encoding="utf-8") as f:
        return json.load(f)


# ==================== 向量模型配置（从工程配置里读，脚本不自己存一份） ====================

def load_embedding_config():
    """从 application-local.yaml 读 dashscope 段。**只读不打印密钥。**"""
    text = io.open(LOCAL_YAML, encoding="utf-8").read()
    block = re.search(r"^dashscope:.*?(?=^\S|\Z)", text, re.M | re.S)
    if not block:
        raise SystemExit(f"{LOCAL_YAML} 里没有 dashscope 段 —— 向量检索没配，量不了")

    def pick(key, default=None):
        m = re.search(rf"^\s*{key}:\s*(\S.*)$", block.group(0), re.M)
        return (m.group(1).strip().strip('"').strip("'") if m else default)

    api_key = pick("api-key")
    if not api_key:
        raise SystemExit("dashscope.api-key 是空的 —— 向量检索没配，量不了")
    return {
        "api_key": api_key,
        "base_url": pick("base-url", "https://dashscope.aliyuncs.com"),
        "model": pick("embedding-model", "text-embedding-v3"),
        "dimensions": int(pick("embedding-dimensions", "1024")),
    }


def embed(cfg, text):
    body = json.dumps({"model": cfg["model"], "input": [text],
                       "dimensions": cfg["dimensions"], "encoding_format": "float"}).encode("utf-8")
    req = urllib.request.Request(cfg["base_url"] + "/compatible-mode/v1/embeddings", data=body,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + cfg["api_key"]})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.load(r)
    return data["data"][0]["embedding"]


# ==================== 两路检索 + 融合（复刻服务端实现） ====================

def es_search(body):
    req = urllib.request.Request(f"{ES}/{INDEX}/_search", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        hits = json.load(r)["hits"]["hits"]
    return [{"chapterNo": h["_source"].get("chapterNo"), "chapterId": h["_source"].get("chapterId"),
             "seq": h["_source"].get("seq"), "text": h["_source"].get("text") or ""} for h in hits]


def keyword_hits(novel_id, query, size):
    return es_search({
        "size": size,
        "query": {"bool": {"filter": {"term": {"novelId": novel_id}},
                           "must": {"match": {"text": {"query": query}}}}},
        "_source": ["chapterNo", "chapterId", "seq", "text"],
    })


def vector_hits(novel_id, vector, size):
    return es_search({
        "size": size,
        "knn": {"field": "vector", "query_vector": vector, "k": size,
                "num_candidates": max(size * 10, 50),
                "filter": {"term": {"novelId": novel_id}}},
        "_source": ["chapterNo", "chapterId", "seq", "text"],
    })


def fuse(lists, top_k, rrf_k=RRF_K):
    """复刻 HybridRanker.fuse：同一块只在多路出现时把分相加；只出现在一路的**照样算分**。"""
    by_key, scores = {}, {}
    for hits in lists:
        for rank, hit in enumerate(hits, start=1):
            key = f"{hit['chapterId']}-{hit['seq']}"
            by_key.setdefault(key, hit)
            scores[key] = scores.get(key, 0.0) + 1.0 / (rrf_k + rank)
    ranked = sorted(scores.items(), key=lambda kv: -kv[1])
    return [by_key[k] for k, _ in ranked[:top_k]]


def candidate_size(top_k, weights=None):
    return min(max(top_k * CANDIDATE_MULTIPLIER, MIN_CANDIDATES), MAX_TOP_K * 2)


# ==================== 主流程 ====================

def prior_only(hits, chapter_no):
    """只看前文：服务端会把「本章及以后的章」过滤掉（那些不是依据）"""
    return [h for h in hits if h["chapterNo"] is not None and h["chapterNo"] < chapter_no]


def check(cases_name, top_k, only_chapter):
    cases = load_cases(cases_name)
    with io.open(map_file(cases_name), encoding="utf-8") as f:
        mapping = json.load(f)
    novel_id = mapping["novelId"]
    by_id = {c["id"]: c for c in cases["chapters"]}
    cfg = load_embedding_config()

    rows = []
    for ann in cases.get("crossChapter") or []:
        cid = ann["chapterId"]
        if only_chapter and cid != only_chapter:
            continue
        ch = by_id.get(cid)
        if ch is None:
            print(f"  [跳过] {cid} 不在章节列表里")
            continue
        # 「前文那一半」的依据串：优先用标注里显式写好的 `priorMark`；
        # 没写才退回启发式（挑不在本章正文里的那些特征串）。
        # 为什么要显式写：matchAny 是给「打分匹配」用的（要在本章报告里找特征串），
        # 它经常只写本章那一边；碰上「本章也出现该串」的情形（比如本章在否定它），
        # 启发式就一条都挑不出来、整条被跳过（实测 holdout 5 条里有 3 条被跳过）。
        prior_marks = ann.get("priorMark") or [m for m in ann["matchAny"] if m not in ch["text"]]
        if not prior_marks:
            print(f"  [跳过] {cid} 的特征串全在本章里，没有「前文那一半」可比")
            continue

        query = ch["text"]          # 服务端实际用的 query：整段（这些章里没有数字，走不到「疑似设定句」）
        chapter_no = mapping["chapters"][cid]["chapterNo"]
        size = candidate_size(top_k)
        vec = embed(cfg, query)
        vec_hits = vector_hits(novel_id, vec, size)
        kw_hits = keyword_hits(novel_id, query, size)
        fused = fuse([vec_hits, kw_hits], top_k)

        def found(hits):
            return [m for m in prior_marks
                    if any(m in h["text"] for h in prior_only(hits, chapter_no))]

        rows.append({"chapterId": cid, "chapterNo": chapter_no, "marks": prior_marks,
                     "vector": found(vec_hits), "keyword": found(kw_hits), "fused": found(fused),
                     "fused_rank": [
                         (h["chapterNo"], [m for m in prior_marks if m in h["text"]])
                         for h in prior_only(fused, chapter_no)][:top_k]})

    print(f"== {cases_name}：{len(rows)} 处跨章对照，top_k={top_k}（线上 AUTO 走的是「融合」那一列）")
    print("   （判据：检索回来的前文块里，有没有哪一块含「前文那一半」的依据串）")
    print()
    header = "%-6s %-8s %-10s %-10s %-10s %s" % ("章", "章号", "向量路", "关键词路", "融合", "前提串")
    print(header)
    print("-" * len(header))
    for r in rows:
        mark = lambda xs: "✓ 有依据" if xs else "✗ 没给到"
        print("%-6s %-8d %-10s %-10s %-10s %s"
              % (r["chapterId"], r["chapterNo"], mark(r["vector"]), mark(r["keyword"]),
                 mark(r["fused"]), "/".join(r["marks"])))
    n = len(rows) or 1
    print()
    print("依据进了 top-%d 的比例：向量路 %d/%d、关键词路 %d/%d、**融合 %d/%d**"
          % (top_k, sum(1 for r in rows if r["vector"]), n,
             sum(1 for r in rows if r["keyword"]), n,
             sum(1 for r in rows if r["fused"]), n))
    only_kw = [r["chapterId"] for r in rows if r["keyword"] and not r["fused"]]
    if only_kw:
        print("⚠️ 关键词路找到了、**融合却把它挤掉**的：%s" % "、".join(only_kw))
    only_vec = [r["chapterId"] for r in rows if r["vector"] and not r["fused"]]
    if only_vec:
        print("⚠️ 向量路找到了、融合却把它挤掉的：%s" % "、".join(only_vec))
    both_lost = [r["chapterId"] for r in rows if not r["vector"] and not r["keyword"]]
    if both_lost:
        print("（两路都没找到，融合自然也找不到：%s）" % "、".join(both_lost))


def main():
    ap = argparse.ArgumentParser(description="检索侧评测：量「依据有没有被检索到」")
    ap.add_argument("--cases", default="far-cases-v2")
    ap.add_argument("--top-k", type=int, default=3, help="与服务端 app.rag.max-context-chunks 对齐")
    ap.add_argument("--chapter", default="", help="只看某一章（如 h10 / f13）")
    args = ap.parse_args()
    check(args.cases, args.top_k, args.chapter)


if __name__ == "__main__":
    main()
