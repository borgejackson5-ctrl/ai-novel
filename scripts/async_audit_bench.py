"""「提交审核」接口的异步化效果测量（延迟与轻量并发）。

测量两项：
  1. POST /novel/publish 的响应时间：异步化后接口只做「写入数据库 + 投递消息」，不等待 AI 审核；
  2. 审核链路本身的耗时：从接口返回到 t_novel.audit_result 被写入（即改造前接口需额外等待的时间）。

采用该口径的原因：这条链路关注延迟（用户提交后需等待多久），不关注吞吐，因此单请求耗时是合适的指标。
另外增加一轮 10 并发，用于验证异步化后并发提交仍能快速返回（这是异步化的主要收益）。

用法：
    python scripts/async_audit_bench.py [base] [rounds]
    python scripts/async_audit_bench.py http://127.0.0.1:8081 8

注意：base 不带 `/api`。后端没有 context-path，`/api` 是前端 dev server 的代理前缀
（vite.config.js 中 rewrite 会将其去掉）。直连后端时使用 http://127.0.0.1:8081。
"""
import json
import statistics
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
CONCURRENCY = 10

USERNAME = "user"
PASSWORD = "user123"
PRE_PASS = "AI 预审通过，等待人工终审"

_pw = None


def _mysql_pw():
    global _pw
    if _pw is None:
        _pw = subprocess.check_output(
            ["docker", "exec", "ai-novel-mysql", "sh", "-c", 'printf %s "$MYSQL_ROOT_PASSWORD"'],
            text=True).strip()
    return _pw


def db(sql):
    """只读查询。SQL 刻意只使用 ASCII（中文经命令行容易被编码破坏）。"""
    out = subprocess.check_output(
        ["docker", "exec", "-e", "MYSQL_PWD=" + _mysql_pw(), "ai-novel-mysql",
         "mysql", "--default-character-set=utf8mb4", "-uroot", "--batch", "--skip-column-names",
         "ai_drama", "-e", sql],
        text=True, encoding="utf-8", errors="replace")
    return [line.strip() for line in out.splitlines() if line.strip()]


def login():
    r = requests.post(BASE + "/auth/login",
                      json={"identifier": USERNAME, "password": PASSWORD}, timeout=30)
    r.raise_for_status()
    body = r.json()
    if not body.get("success"):
        raise SystemExit("登录失败: " + json.dumps(body, ensure_ascii=False))
    return body["data"]["token"]


def latest_novel_id(tag):
    """返回刚发布作品的 id：标题为 ASCII 唯一串，直接按标题查询，无需解析响应体结构。"""
    rows = db("SELECT id FROM t_novel WHERE title = '%s' ORDER BY id DESC LIMIT 1" % tag)
    return rows[0] if rows else None


def publish(token, tag, idx, long_intro=False):
    intro = "benchmark payload for async audit latency measurement"
    if long_intro:
        # 贴近真实投稿：作品预审送入模型的是「书名 + 简介」，简介上限是 1000 字（@Size(max=1000)）
        intro = ("这是一段用于测量审核链路的测试文本。" * 30)
    payload = {
        "title": tag,
        "intro": intro,
        "categoryId": CATEGORY_ID,
        "tags": "bench",
        "author": "bench",
        "chapters": [{"title": "ch1", "content": "chapter body for latency measurement " + str(idx),
                      "unlockCoin": 0}],
    }
    t0 = time.perf_counter()
    r = requests.post(BASE + "/novel/publish", json=payload,
                      headers={"Authorization": token}, timeout=120)
    ms = (time.perf_counter() - t0) * 1000
    ok = r.status_code == 200 and r.json().get("success")
    return ok, ms, (r.text[:200] if not ok else "")


def wait_audited(novel_id, timeout_s=180):
    """等待审核结果写入，返回耗时（秒）。"""
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < timeout_s:
        rows = db("SELECT audit_result FROM t_novel WHERE id = %s" % novel_id)
        if rows and rows[0] != "NULL" and rows[0]:
            return time.perf_counter() - t0, rows[0]
        time.sleep(0.2)
    return None, None


def pct(vals, p):
    vals = sorted(vals)
    k = max(0, min(len(vals) - 1, int(round((p / 100.0) * len(vals) + 0.5)) - 1))
    return vals[k]


if __name__ == "__main__":
    print("== 目标:", BASE, "| 串行轮数:", ROUNDS, "| 并发数:", CONCURRENCY)
    token = login()
    print("== 登录成功")

    rows = db("SELECT id FROM t_category ORDER BY id LIMIT 1")
    if not rows:
        raise SystemExit("没有分类，先导入种子数据")
    CATEGORY_ID = int(rows[0])
    print("== 用分类 id =", CATEGORY_ID)

    # ---------- 1) 串行：接口延迟 + 审核耗时 ----------
    api_ms, audit_s, tags = [], [], []
    base_ts = int(time.time())
    for i in range(ROUNDS):
        tag = "bench-serial-%d-%d" % (base_ts, i)
        ok, ms, err = publish(token, tag, i)
        if not ok:
            print("!! 第 %d 轮发布失败: %s" % (i, err))
            continue
        api_ms.append(ms)
        tags.append(tag)
        nid = latest_novel_id(tag)
        cost, result = wait_audited(nid) if nid else (None, None)
        if cost is None:
            print("!! 第 %d 轮审核未在超时内完成（novelId=%s）" % (i, nid))
        else:
            audit_s.append(cost)
            print("  轮 %d: 接口 %.0f ms | 审核 %.1f s | 结果=%s" % (i, ms, cost, result))

    print()
    print("== 串行结果（n=%d）" % len(api_ms))
    if api_ms:
        print("   接口延迟  min=%.0f p50=%.0f max=%.0f ms"
              % (min(api_ms), statistics.median(api_ms), max(api_ms)))
    if audit_s:
        print("   审核耗时  min=%.1f p50=%.1f max=%.1f s"
              % (min(audit_s), statistics.median(audit_s), max(audit_s)))

    # ---------- 2) 长简介：贴近真实投稿，观察审核耗时是否随文本长度变化 ----------
    print()
    print("== 长简介（约 1800 字，贴近真实投稿）")
    long_api, long_audit = [], []
    lts = int(time.time())
    for i in range(3):
        tag = "bench-long-%d-%d" % (lts, i)
        ok, ms, err = publish(token, tag, i, long_intro=True)
        if not ok:
            print("!! 长简介第 %d 轮失败: %s" % (i, err))
            continue
        long_api.append(ms)
        nid = latest_novel_id(tag)
        cost, result = wait_audited(nid) if nid else (None, None)
        if cost is None:
            print("!! 长简介第 %d 轮审核超时" % i)
        else:
            long_audit.append(cost)
            print("  轮 %d: 接口 %.0f ms | 审核 %.1f s" % (i, ms, cost))
    if long_api:
        print("   接口延迟  p50=%.0f ms" % statistics.median(long_api))
    if long_audit:
        print("   审核耗时  p50=%.1f s  max=%.1f s" % (statistics.median(long_audit), max(long_audit)))

    # ---------- 3) 并发：异步化的主要收益 ----------
    print()
    print("== 并发 %d 提交（同时到达）" % CONCURRENCY)
    conc_ts = int(time.time())
    conc_tags = ["bench-conc-%d-%d" % (conc_ts, i) for i in range(CONCURRENCY)]
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        t0 = time.perf_counter()
        results = list(pool.map(lambda p: publish(token, p[0], p[1]),
                                [(t, i) for i, t in enumerate(conc_tags)]))
        wall = (time.perf_counter() - t0) * 1000
    ok_ms = [ms for ok, ms, _ in results if ok]
    bad = [(ms, err) for ok, ms, err in results if not ok]
    print("   全部请求墙钟耗时 %.0f ms | 成功 %d/%d" % (wall, len(ok_ms), len(results)))
    if ok_ms:
        print("   单请求延迟 min=%.0f p50=%.0f max=%.0f ms"
              % (min(ok_ms), statistics.median(ok_ms), max(ok_ms)))
    for ms, err in bad:
        print("   失败样本: %.0f ms %s" % (ms, err))

    conc_audit = []
    for tag in conc_tags:
        nid = latest_novel_id(tag)
        if not nid:
            continue
        cost, _ = wait_audited(nid)
        if cost is not None:
            conc_audit.append(cost)
    if conc_audit:
        print("   这 %d 篇的审核耗时 p50=%.1f max=%.1f s"
              % (len(conc_audit), statistics.median(conc_audit), max(conc_audit)))

    print()
    print("== 结论对比")
    if api_ms and audit_s:
        print("   接口不再等审核：接口 %.0f ms，而审核本身 %.1f s（相差约 %.0f 倍）"
              % (statistics.median(api_ms), statistics.median(audit_s),
                 statistics.median(audit_s) * 1000 / max(1.0, statistics.median(api_ms))))
