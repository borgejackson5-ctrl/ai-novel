#!/usr/bin/env python3
"""章节审查评测：跑真实链路、按标注打分、出报告。

为什么要有这个东西：清单 2.6 的验收标准是「能拿出一组标注样本 + 准确率/误报率，
而不是'感觉好多了'」。提示词改动没法靠单测证明好坏 —— 只能拿同一把尺子量两次。

用法：
    python review_eval.py setup                       # 建评测作品并导入样本章（只需一次）
    python review_eval.py run --label baseline        # 单章审查 + 全文审查，打分出报告
    python review_eval.py run --label after --mode single
    python review_eval.py run --label x --reset-quota  # 跑前清零评测账号的当日字数计数
    python review_eval.py rescore --label baseline    # 只用已存下的 JSON 重算（不调模型）
    python review_eval.py teardown                    # 只打印要删的 id，不自动删

**额度**：评测账号的每日免费字数（默认 3 万）会被一轮轮跑光，而「跑光」的表现是
从中途开始调用失败 —— 看起来只是「检出变少了」。所以 `run` 开跑前会先量一次剩余，
不够就直接拒绝（不会跑一半），跑完还会判这轮是否**完整**；不完整的轮次在报告里标
`complete=false`，数字不要拿去对比。相关环境变量：`EVAL_ACCOUNT`（默认 admin，
评测专用账号）、`EVAL_REDIS_CONTAINER` / `EVAL_REDIS_PASSWORD`（读计数器用）。

打分口径：
- 归一化与服务端同口径（**忽略正文里的空白**），否则会出现「服务端认为合法、
  打分脚本认为对不上」的假差异。
- 认位置不认字：报告片段在正文中的任意一次出现与标注区间重叠即命中。
  模型把「沈青悟」报成「沈青梧」是同一处，算命中；修法方向对不对单独统计。
- **跨章标注单独算**：同一处跨章问题被重复报多次时，只算 1 次检出，其余计为「重复报告」——
  重复不是误报，但确实是噪声，要能看到。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
# 口令读取与 scripts/ 下同源（环境变量优先，否则读容器），不把同一份逻辑写两遍
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "scripts"))
import _container_secret as secrets  # noqa: E402

CASES_DIR = os.path.join(HERE, "cases")
REPORTS = os.path.join(HERE, "reports")
MAP_FILE = os.path.join(REPORTS, ".cases-map.json")
TOOLS = ("列目录", "读正文", "邻近检索")

BASE = os.environ.get("EVAL_BASE", "http://127.0.0.1:8081")
ACCOUNT = os.environ.get("EVAL_ACCOUNT", "admin")
PASSWORD = os.environ.get("EVAL_PASSWORD", "admin123")
NOVEL_TITLE = "评测样本·章节审查"
KINDS = ("错别字", "语病", "标点", "前后不一致")


# ==================== 基础 HTTP ====================

def call(method, path, body=None, token=None, timeout=180):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {path} → HTTP {e.code}：{raw[:400]}")



# ==================== 额度（评测自己会把额度打满） ====================
# 为什么要有这一段：评测账号的当日免费字数被打满时，表现是「从某一章开始调用失败」——
# 而失败的样子（少报几条）与「模型确实没检出」长得一模一样。
# 所以：开跑前先量一次剩多少、不够就不跑；跑完再判这轮算不算数。

REDIS_CONTAINER = os.environ.get("EVAL_REDIS_CONTAINER", "ai-novel-redis")
REDIS_PASSWORD = os.environ.get("EVAL_REDIS_PASSWORD") or secrets.redis_password(REDIS_CONTAINER)
# 账号日额度上限：默认 30000；也可能被 t_user_ai_config.quota_limit 改成别的值。
# 脚本不查库（评测账号一律按默认值算），所以打印出来说明是估的
DEFAULT_QUOTA = 30000


def login_vo():
    """登录并返回整个 data（要 userId 才能定位额度计数器）"""
    return call("POST", "/auth/login", {"identifier": ACCOUNT, "password": PASSWORD})["data"]


def quota_key(user_id):
    """服务端口径：ai:user:usage:{userId}:{yyyy-MM-dd}（Redis 日键，跨天自然清零）"""
    return "ai:user:usage:" + str(user_id) + ":" + date.today().isoformat()


def redis_cmd(*args):
    out = subprocess.run(["docker", "exec", REDIS_CONTAINER, "redis-cli", "-a", REDIS_PASSWORD,
                          "--no-auth-warning"] + list(args),
                         capture_output=True, text=True, timeout=20)
    return (out.stdout or "").strip()


def quota_used(user_id):
    """当日已用字数；key 不存在 = 今天还没用过"""
    v = redis_cmd("GET", quota_key(user_id))
    if v in ("", "(nil)"):
        return 0
    try:
        return int(v)
    except ValueError:
        print("[额度][警告] 计数器不是整数（" + v[:40] + "），按 0 处理")
        return 0


def prepare_quota(user_id, cases, mode, reset, allow_low):
    """开跑前量额度：不够就拒绝跑（不要跑一半）"""
    need = sum(len(c.get("text") or "") for c in cases["chapters"]) * (2 if mode == "both" else 1)
    key = quota_key(user_id)
    if reset:
        before = redis_cmd("GET", key)
        redis_cmd("DEL", key)
        print("[额度] 已清零评测账号 " + ACCOUNT + "（userId=" + str(user_id) + "）的当日计数：原值="
              + (before if before else "不存在") + " -> " + key)
    used = quota_used(user_id)
    left = DEFAULT_QUOTA - used
    print("[额度] 账号 " + ACCOUNT + " 今日已用 " + str(used) + " / " + str(DEFAULT_QUOTA)
          + "（按默认上限估），剩 " + str(left) + "；本轮预计需要 ≈" + str(need) + " 字（mode=" + mode + "）")
    if left < need:
        msg = ("额度不够：剩 " + str(left) + " 字，预计需要 " + str(need)
               + " 字。跑下去会中途断，而断掉的数字看起来只是「检出变少了」")
        if not allow_low:
            raise SystemExit(
                "[额度] " + msg
                + "\n       这是评测账号的 Redis 日计数（只有评测专用账号才该动它）。两种处理："
                + "\n         a) 让脚本清零后重跑：python review_eval.py run --label <名字> --reset-quota"
                + "\n         b) 手工：docker exec " + REDIS_CONTAINER + " redis-cli -a "
                + REDIS_PASSWORD + " DEL " + key
                + "\n       也可以加 --allow-low-quota 照跑（本轮会被标成不完整，数字不用于对比）")
        print("[额度][警告] 已按 --allow-low-quota 继续，本轮会标为不完整")
    return {"userId": str(user_id), "key": key, "usedAtStart": used,
            "limitAssumed": DEFAULT_QUOTA, "estimatedNeed": need}


def completeness(cases, single, task, mode):
    """这一轮到底跑完了没有 —— 没跑完的数字不能拿去比"""
    bad = []
    if mode in ("single", "both"):
        for ch in cases["chapters"]:
            got = single.get(ch["id"]) or {}
            if not got.get("ok", True):
                bad.append("单章 " + ch["id"] + " 调用失败：" + str(got.get("error", ""))[:80])
    if mode in ("task", "both"):
        meta = task.get("_meta") or {}
        # 2 = 已完成；3 已中止 / 4 失败都不算数（额度用尽会走「中止」）
        if num(meta.get("status")) != 2:
            bad.append("全文审查任务状态 = 「" + str(meta.get("statusText")) + "」，不是「已完成」")
        if num(meta.get("failedChapters")) > 0:
            bad.append("全文审查有 " + str(num(meta.get("failedChapters"))) + " 章失败")
        if num(meta.get("doneChapters")) < num(meta.get("totalChapters")):
            bad.append("全文审查只审了 " + str(num(meta.get("doneChapters"))) + "/"
                       + str(num(meta.get("totalChapters"))) + " 章")
    return (not bad), bad


def login():
    """兼容原有调用：只取 token"""
    return login_vo()["token"]


def num(v, default=0):
    """Long 类字段被全局序列化成字符串（雪花 ID 超出 JS 安全整数）"""
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


# ==================== 样本与打分 ====================

def norm(s):
    """与服务端 normalize 同口径：去掉所有空白再比对"""
    return re.sub(r"\s+", "", s or "")


def load_cases(name="review-cases"):
    path = os.path.join(CASES_DIR, name + ".json")
    with open(path, encoding="utf-8") as f:
        cases = json.load(f)
    cases["_file"] = name
    for ch in cases["chapters"]:
        body = norm(ch["text"])
        for d in ch["defects"]:
            w = norm(d["wrong"])
            cnt = body.count(w)
            if cnt != 1:
                raise SystemExit(f"样本 {ch['id']} 的标注「{d['wrong']}」在正文里出现 {cnt} 次（要求恰好 1 次）")
            d["_start"] = body.index(w)
            d["_end"] = d["_start"] + len(w)
    by_id = {c["id"]: c for c in cases["chapters"]}
    for x in cases.get("crossChapter", []):
        if norm(x["wrong"]) not in norm(by_id[x["chapterId"]]["text"]):
            raise SystemExit(f"跨章标注「{x['wrong']}」不在 {x['chapterId']} 的正文里")
    return cases


# 跨章命中：建议文本里要出现这些词之一，才算「这条建议在主张前后不一致」。
# 判据为什么不是「类型必须是前后不一致」：实测有一次模型把人名矛盾标成了「错别字」，
# 加类型门会把那次真检出判成误报。看建议的措辞更准。
MISMATCH_WORDS = ("不一致", "矛盾", "不统一", "统一", "应一致", "保持一致", "前后不符")


def score_chapter(chapter, issues, cross_items, cross_seen=None):
    """把一章的报告与标注对照。

    每条报告落进三类之一：命中章内标注 / 命中跨章标注（含重复）/ 未命中（误报候选）。
    认位置不认字：报告片段与标注区间只要有重叠就算命中。

    **跨章条目不按章过滤**（2026-09-19 修的）：跨章矛盾是整本书的性质，模型在哪一章
    报出来都算 —— 同一处问题只记第一次，其余算「重复报告」。旧口径是「只认条目上写的
    那一章」，于是 12 章留出集里同一处人名写法矛盾（h2 里写错、h4/h8/h10/h11 里写对）
    被报了 5 次，其中 4 次全算成误报，误报率虚高、跨章召回虚低。

    判据是「excerpt 或 suggestion 里出现该条目的特征串」——**不加类型门**：
    实测有一次模型把人名矛盾标成了「错别字」，加了 `type == kind` 就会把这次真检出
    判成误报（类型标错已经由 type_ok 单独统计，不该影响「有没有发现」）。
    """
    body = norm(chapter["text"])
    defects = chapter["defects"]
    cross = list(cross_items)
    seen = cross_seen if cross_seen is not None else set()
    details, hit, cross_hit = [], set(), set()

    for it in issues:
        excerpt = norm(it.get("excerpt"))
        # 服务端已经做过反幻觉核对，能回来的必然能在正文里找到；这里再保险一次
        positions = [m.start() for m in re.finditer(re.escape(excerpt), body)] if excerpt else []
        row = {"kind": it.get("type"), "excerpt": it.get("excerpt"),
               "suggestion": it.get("suggestion")}

        matched = None
        for i, d in enumerate(defects):
            if any(p < d["_end"] and p + len(excerpt) > d["_start"] for p in positions):
                matched = i
                break
        if matched is not None:
            d = defects[matched]
            hit.add(d["wrong"])
            details.append({**row, "verdict": "命中", "defect": d["wrong"], "defect_kind": d["kind"],
                            "type_ok": it.get("type") == d["kind"],
                            # 修法方向只看错别字类（right 里给的是可直接对照的短串），其余类型仅供参考
                            "fix_ok": (norm(d["fix"]) in norm(it.get("suggestion"))
                                       if d["kind"] == "错别字" and d.get("fix") else None)})
            continue

        suggestion = norm(it.get("suggestion"))
        # 跨章这条要判两层，顺序有讲究：
        #
        # ① **先按「建议在讲哪一条」匹配**。excerpt 是 8~40 字的原文片段，里面常常
        #    顺带带上另一条的特征串 —— 实测有一条讲「布巾颜色」的建议，因为引用的
        #    句子里含「九根伞骨」，被算成了数字那条的命中（张冠李戴）。先看建议就不会认错。
        #    这里要求建议里出现「不一致/矛盾/统一」这类词：模型报别的问题（例如
        #    「回来缺少前提」这种语病）时，也可能在说明里顺带提一句「前文提过晌午」，
        #    那不是发现了时间矛盾，实测撞到过一次。
        # ② 建议里看不出来，才**退回按 excerpt 的位置匹配**（有的建议只写「应改为 X」，
        #    不含上面那些词，靠位置仍然能认出来）。
        claims = any(w in suggestion for w in MISMATCH_WORDS)
        x = None
        if claims:
            x = next((x for x in cross if any(m in suggestion for m in x["matchAny"])), None)
        if x is None:
            x = next((x for x in cross if any(m in excerpt for m in x["matchAny"])), None)
        if x is not None:
            # 只把「全局第一次」记进本章的 crossHit：这样各章 crossHit 相加正好 = 全书
            # 命中了几处跨章问题（否则同一处被多章重复报会重复计数，召回率虚高）
            first = x["wrong"] not in seen
            if first:
                cross_hit.add(x["wrong"])
            seen.add(x["wrong"])
            details.append({**row, "verdict": "命中跨章" if first else "重复报告（同一处跨章问题）",
                            "defect": x["wrong"], "defect_kind": x["kind"],
                            "type_ok": it.get("type") == x["kind"], "fix_ok": None})
            continue

        details.append({**row, "verdict": "未命中（误报候选）", "defect": None,
                        "defect_kind": None, "type_ok": None, "fix_ok": None})

    n_hit = sum(1 for d in details if d["verdict"] == "命中")
    n_cross = sum(1 for d in details if d["verdict"].startswith("命中跨章"))
    n_dup = sum(1 for d in details if d["verdict"].startswith("重复报告"))
    return details, {
        "hit": len(hit), "defects": len(defects), "reports": len(issues),
        "missed": [d for d in defects if d["wrong"] not in hit],
        "crossHit": len(cross_hit), "crossTotal": len(cross),
        "nHitReports": n_hit, "nCrossReports": n_cross, "nDupReports": n_dup,
        "nUnmatched": len(issues) - n_hit - n_cross - n_dup,
    }


# ==================== 工具调用统计（从后端日志）====================

TIME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})")


def log_lines(log_path):
    if not log_path or not os.path.exists(log_path):
        return []
    with open(log_path, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def tool_stats(lines):
    """统计审查工具被调用了多少次、分几类、落在哪一章。

    这一项是解释「跨章问题为什么时有时无」的关键：模型有没有真的去查前文，日志里写着。
    章节归属从日志行里抠（邻近检索有 current=N，读正文有 chapterNo=N，列目录没有章号）。
    只统计传进来的这一段日志 —— 所以「单章审查」和「全文审查」要各自切一刀。
    """
    stats = {"total": 0, "byTool": {t: 0 for t in TOOLS}, "byChapter": {}, "unknown": 0}
    for line in lines:
        if "审查工具：" not in line:
            continue
        stats["total"] += 1
        for t in TOOLS:
            if t in line:
                stats["byTool"][t] += 1
                break
        m = re.search(r"current=(\d+)", line) or re.search(r"chapterNo=(\d+)", line)
        if m:
            no = int(m.group(1))
            stats["byChapter"][no] = stats["byChapter"].get(no, 0) + 1
        else:
            stats["unknown"] += 1
    return stats


def glossary_stats(lines):
    """名词表这一轮做了多少事：服务端直接判出的矛盾条数 + 记进去的名字数。

    这一项是「跨章一致性不再靠模型自觉」的账 —— 它不为零，才说明机制真的在干活。
    """
    stats = {"conflicts": 0, "factConflicts": 0, "recorded": 0, "factsRecorded": 0,
             "recordChapters": 0}
    for line in lines:
        if "名词表：本章记入/更新" in line:
            stats["recordChapters"] += 1
            m = re.search(r"本章记入/更新 (\d+) 个", line)
            if m:
                stats["recorded"] += int(m.group(1))
            m = re.search(r"、(\d+) 条设定数字", line)
            if m:
                stats["factsRecorded"] += int(m.group(1))
        elif "名词表：本章「" in line:
            stats["conflicts"] += 1
            # 「写法」与「数字」两类日志前缀一样，靠引号里有没有 = 区分
            if re.search(r"名词表：本章「[^」]*=[^」]*」", line):
                stats["factConflicts"] += 1
    return stats


# ==================== 两种审查入口 ====================

def review_single(token, chapter_id):
    """单章审查：同步返回"""
    start = time.time()
    data = call("POST", f"/ai/review/chapter/{chapter_id}", token=token)["data"]
    data["_elapsed"] = round(time.time() - start, 1)
    return data


def review_task(token, novel_id, timeout_s=900):
    """全文审查：派发任务 → 轮询到结束 → 取问题清单"""
    start = time.time()
    task = call("POST", f"/ai/review/novel/{novel_id}", token=token)["data"]
    task_id = task["taskId"]
    while True:
        if time.time() - start > timeout_s:
            raise SystemExit(f"全文审查任务 {task_id} 超过 {timeout_s}s 还没结束")
        detail = call("GET", f"/ai/review/task/{task_id}", token=token)["data"]
        if not detail.get("running"):
            break
        time.sleep(3)

    issues, page = [], 1
    while True:
        page_data = call("GET", f"/ai/review/task/{task_id}/issues?pageNum={page}&pageSize=200",
                         token=token)["data"]
        chunk = page_data.get("list") or []
        issues.extend(chunk)
        if not chunk or len(issues) >= num(page_data.get("total")):
            break
        page += 1
    detail["_issues"] = issues
    detail["_elapsed"] = round(time.time() - start, 1)
    return detail


# ==================== setup / teardown ====================

def map_file(cases_name):
    return os.path.join(REPORTS, f".cases-map-{cases_name}.json")


def setup(cases_name):
    """建样本作品；**已经建过就只补缺的章**，不重建。

    为什么不做「每次重建」：重建会留下一个孤儿作品（旧 novelId 再没人引用），
    而且历史报告里记的是旧 chapterId，对比时全部失效。
    样本集扩章（2 章 → 12 章）应该是追加，不是推倒重来。
    """
    cases = load_cases(cases_name)
    token = login()
    mapping = None
    if os.path.exists(map_file(cases_name)):
        with open(map_file(cases_name), encoding="utf-8") as f:
            mapping = json.load(f)

    if mapping is None:
        data = call("POST", "/novel/publish", {
            "title": f"{NOVEL_TITLE}·{cases_name}",
            "categoryId": 2,
            "intro": "章节审查评测用样本集，不是真实作品。",
            "tags": "评测",
            "chapters": [{"title": c["title"], "content": c["text"], "unlockCoin": 0}
                         for c in cases["chapters"]],
        }, token=token)["data"]
        novel_id = num(data["id"])
        mapping = {"casesFile": cases_name, "novelId": novel_id,
                   "createdAt": datetime.now().isoformat(timespec="seconds"), "chapters": {}}
        print(f"  新建评测作品：novelId={novel_id}（{len(cases['chapters'])} 章随作品一起建好）")
        # ⚠️ 新建之后**必须把待补列表清空**：publish 已经把全部章都建了，
        # 而上面那个 chapters 还是空字典，接着走「补缺」会把每一章再建一遍 ——
        # 实测建出了 48 章的样本作品（24 章两份），且因为章数对不上而不写 map，
        # 下一次跑又新建一本书。这个 bug 只在「第一次建某个样本集」时出现，
        # 扩章（map 已存在）那条路是好的，所以上一次没撞到。
        pending = []
    else:
        novel_id = num(mapping["novelId"])
        print(f"  复用已有评测作品：novelId={novel_id}")
        # 补缺的章：按样本顺序逐章发，章号由服务端自增 —— 只能按序补齐，不能插空
        pending = [c for c in cases["chapters"] if c["id"] not in mapping["chapters"]]

    for c in pending:
        call("POST", "/chapter", {"novelId": novel_id, "title": c["title"],
                                 "content": c["text"], "unlockCoin": 0}, token=token)
        print(f"  补章：{c['id']} {c['title']}")

    listed = call("GET", f"/chapter/author/{novel_id}?pageNum=1&pageSize=200", token=token)["data"]
    rows = sorted(listed.get("list") or [], key=lambda r: num(r.get("chapterNo")))
    if len(rows) != len(cases["chapters"]):
        raise SystemExit(f"章节数对不上：库里 {len(rows)}，样本 {len(cases['chapters'])}"
                         f"（多出来的章不擅自删，人工确认后处理）")
    mapping["chapters"] = {c["id"]: {"chapterId": num(r["id"]), "chapterNo": num(r["chapterNo"]),
                                     "title": r.get("title")}
                           for c, r in zip(cases["chapters"], rows)}
    os.makedirs(REPORTS, exist_ok=True)
    with open(map_file(cases_name), "w", encoding="utf-8") as f:
        json.dump(mapping, f, ensure_ascii=False, indent=2)
    print(f"样本作品就绪：novelId={novel_id}，{len(rows)} 章（样本集 {cases_name}）")
    for cid, m in mapping["chapters"].items():
        print(f"  {cid} → 第{m['chapterNo']}章 id={m['chapterId']} {m['title']}")


def teardown(cases_name):
    """只打印要删的东西，不自动删 —— 破坏性操作交给人来按。"""
    with open(map_file(cases_name), encoding="utf-8") as f:
        m = json.load(f)
    nid = m["novelId"]
    print("要删的对象（确认后手动执行）：")
    print(f"  t_chapter WHERE novel_id = {nid}   -- {len(m['chapters'])} 行")
    print(f"  t_novel   WHERE id = {nid}")
    print(f"  t_ai_review_task/chapter/issue WHERE novel_id = {nid}")
    print("  t_message 等按时间窗核对后再删")


def load_map(cases_name):
    path = map_file(cases_name)
    if not os.path.exists(path):
        raise SystemExit(f"还没 setup：先跑 python review_eval.py setup --cases {cases_name}")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# ==================== 跑一轮 + 打分 ====================

def collect_block(cases, mapping, token, mode, log_path):
    """跑一遍审查并逐章打分，返回 (single_block, task_block, tools)"""
    single, task, tools = {}, {}, {}
    # 跨章问题在整本范围内只记第一次：两种审查方式各自一套，互不混淆
    cross_seen = set()
    lines = log_lines(log_path)
    mark = len(lines)
    if mode in ("single", "both"):
        print("=== 单章审查 ===")
        for ch in cases["chapters"]:
            # 单章失败不再直接 SystemExit：先记下来，跑完统一判「这轮算不算数」。
            # 以前是一报错就死，于是「跑一半挂了」与「跑完了但数字变差」长得一模一样
            try:
                vo = review_single(token, mapping["chapters"][ch["id"]]["chapterId"])
            except (Exception, SystemExit) as e:
                det, st = score_chapter(ch, [], cases.get("crossChapter", []), cross_seen)
                single[ch["id"]] = {"ok": False, "error": str(e)[:200], "message": str(e)[:200],
                                    "summary": None, "reviewedChars": 0, "segments": 0,
                                    "droppedIssues": 0, "elapsed": None, "details": det, **st}
                print("  " + ch["id"] + " 调用失败：" + str(e)[:110])
                continue
            det, st = score_chapter(ch, vo.get("issues") or [], cases.get("crossChapter", []), cross_seen)
            single[ch["id"]] = {
                "ok": vo.get("ok"), "message": vo.get("message"), "summary": vo.get("summary"),
                "reviewedChars": num(vo.get("reviewedChars")), "segments": num(vo.get("segments")),
                "droppedIssues": num(vo.get("droppedIssues")), "elapsed": vo.get("_elapsed"),
                "details": det, **st}
            print(f"  {ch['id']} 报 {st['reports']} 条（章内命中 {st['nHitReports']}，跨章 "
                  f"{st['nCrossReports']}，未命中 {st['nUnmatched']}）/ 标注 {st['defects']} 处 / "
                  f"命中 {st['hit']}（{vo.get('_elapsed')}s）")
        if log_path:
            lines = log_lines(log_path)
            tools["single"] = tool_stats(lines[mark:])
            tools["single"]["glossary"] = glossary_stats(lines[mark:])
            mark = len(lines)

    if mode in ("task", "both"):
        print("=== 全文审查 ===")
        t = review_task(token, mapping["novelId"])
        # 逐章归属：等所有章的审查都跑完再切日志，MQ 是并发的，按时间切不准
        if log_path:
            lines = log_lines(log_path)
        by_no = {}
        for it in t["_issues"]:
            by_no.setdefault(num(it.get("chapterNo")), []).append(it)
        for ch in cases["chapters"]:
            no = mapping["chapters"][ch["id"]]["chapterNo"]
            det, st = score_chapter(ch, by_no.get(no, []), cases.get("crossChapter", []), cross_seen)
            task[ch["id"]] = {"ok": True, "message": None, "summary": None, "reviewedChars": None,
                              "segments": None, "droppedIssues": None, "elapsed": None,
                              "details": det, **st}
            print(f"  {ch['id']} 报 {st['reports']} 条 / 标注 {st['defects']} 处 / 命中 {st['hit']}")
        if log_path:
            tools["task"] = tool_stats(lines[mark:])
            tools["task"]["glossary"] = glossary_stats(lines[mark:])
        task["_meta"] = {
            "taskId": num(t.get("taskId")), "status": t.get("status"), "statusText": t.get("statusText"),
            "totalChapters": num(t.get("totalChapters")), "doneChapters": num(t.get("doneChapters")),
            "failedChapters": num(t.get("failedChapters")), "issueCount": num(t.get("issueCount")),
            "chargedUnits": num(t.get("chargedUnits")), "refundedUnits": num(t.get("refundedUnits")),
            "reviewedChars": num(t.get("reviewedChars")), "message": t.get("message"),
            "elapsed": t.get("_elapsed")}
        print(f"  任务 {t.get('statusText')}，扣费 {num(t.get('chargedUnits'))} 字，{t.get('_elapsed')}s")
    return single, task, tools


def run(label, mode, cases_name, note, log_path, reset_quota=False, allow_low_quota=False):
    cases = load_cases(cases_name)
    mapping = load_map(cases_name)
    auth = login_vo()
    token = auth["token"]
    quota = prepare_quota(str(auth.get("userId") or ""), cases, mode, reset_quota, allow_low_quota)
    single, task, tools = collect_block(cases, mapping, token, mode, log_path)
    complete, reasons = completeness(cases, single, task, mode)
    result = {"label": label, "mode": mode, "note": note, "casesFile": cases_name,
              "at": datetime.now().isoformat(timespec="seconds"), "account": ACCOUNT,
              "quota": quota, "complete": complete, "incompleteReasons": reasons,
              "novelId": mapping["novelId"], "single": single, "task": task, "tools": tools}
    save(result, cases, label)
    if not complete:
        print("")
        print("=" * 62)
        print("[不完整] 本轮有 " + str(len(reasons)) + " 项没跑成，数字不要拿去对比：")
        for r in reasons:
            print("    - " + r)
        print("   报告里已标 complete=false")
        print("=" * 62)
    return result


def rescore(label, log_path=""):
    """只用已存下的 JSON 重算 —— 改了打分口径时不用再花一次调用"""
    with open(os.path.join(REPORTS, f"{label}.json"), encoding="utf-8") as f:
        result = json.load(f)
    cases = load_cases(result.get("casesFile") or "review-cases")
    for key in ("single", "task"):
        block = result.get(key) or {}
        cross_seen = set()          # 与 collect_block 同一个口径：跨章问题整本只记第一次
        for ch in cases["chapters"]:
            r = block.get(ch["id"])
            if not r:
                continue
            issues = [{"type": d["kind"], "excerpt": d["excerpt"], "suggestion": d["suggestion"]}
                      for d in r["details"]]
            det, st = score_chapter(ch, issues, cases.get("crossChapter", []), cross_seen)
            r.update(st)
            r["details"] = det
    if log_path and not result.get("tools"):
        # 老 JSON 里没存工具统计：只能给整份日志的合计（含两种方式），标注清楚
        all_lines = log_lines(log_path)
        result["tools"] = {"all": tool_stats(all_lines)}
        result["tools"]["all"]["glossary"] = glossary_stats(all_lines)
    save(result, cases, label)
    print(f"已按新口径重算 {label}")


def save(result, cases, label):
    os.makedirs(REPORTS, exist_ok=True)
    with open(os.path.join(REPORTS, f"{label}.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    path = os.path.join(REPORTS, f"{label}.md")
    write_report(result, cases, path)
    print(f"\nJSON → {os.path.join(REPORTS, label + '.json')}\n报告 → {path}")


# ==================== 出报告 ====================

def per_kind(block, cases):
    kinds, reports, matched, type_ok = {}, 0, 0, 0
    for ch in cases["chapters"]:
        r = block.get(ch["id"])
        if not r:
            continue
        reports += r["reports"]
        matched += r["nHitReports"] + r["nCrossReports"] + r["nDupReports"]
        missed = {m["wrong"] for m in r["missed"]}
        for d in ch["defects"]:
            s = kinds.setdefault(d["kind"], {"defects": 0, "hit": 0})
            s["defects"] += 1
            if d["wrong"] not in missed:
                s["hit"] += 1
        type_ok += sum(1 for dt in r["details"] if dt["verdict"] == "命中" and dt["type_ok"])
    return kinds, reports, matched, type_ok


def load_verdicts(label):
    """人工复核结论文档：reports/<label>.verdicts.json

    键是「章id|报告片段」，值是判定结果。机器只能判「落没落在标注区间上」，
    但落不上的报告可能是「真问题、只是我底稿也有毛病」或「方向反了」——
    这两种都不该算误报。所以精确率最终要人工过一遍，结论写在这里，报告里分开列。
    """
    path = os.path.join(REPORTS, f"{label}.verdicts.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_report(result, cases, path):
    verdicts = load_verdicts(result["label"])
    L = []
    add = L.append
    mode_name = {"single": "单章审查", "task": "全文审查（任务）", "both": "单章 + 全文"}[result["mode"]]
    total_defects = sum(len(c["defects"]) for c in cases["chapters"])
    cross_total = len(cases.get("crossChapter", []))
    add(f"# 章节审查评测报告（{result['label']}）\n")
    add(f"- 时间：{result['at']}　账号：`{result['account']}`　方式：{mode_name}"
        f"　样本集：`{result.get('casesFile', 'review-cases')}`")
    add(f"- 样本：{len(cases['chapters'])} 章 / 章内标注 {total_defects} 处 / 跨章标注 {cross_total} 处")
    # 完整性写在最前面：不完整的轮次里「检出变少」有相当一部分不是模型的问题
    quota = result.get("quota") or {}
    if result.get("complete") is False:
        add("- **⚠️ 本轮不完整，数字不要用于对比**：" + "；".join(result.get("incompleteReasons") or []))
    if quota:
        add(f"- 额度：开跑前该账号今日已用 {quota.get('usedAtStart')} 字（上限按 "
            f"{quota.get('limitAssumed')} 估），本轮预计需要 ≈{quota.get('estimatedNeed')} 字")
    if result.get("note"):
        add(f"- 说明：{result['note']}")
    add("")

    for key, title in (("single", "单章审查"), ("task", "全文审查")):
        block = result.get(key)
        if not block:
            continue
        kinds, reports, matched, type_ok = per_kind(block, cases)
        cross_hit = sum(block.get(c["id"], {}).get("crossHit", 0) for c in cases["chapters"])
        add(f"## {title}\n")
        add("| 类型 | 标注数 | 检出数 | 召回率 |")
        add("|---|---|---|---|")
        for k in KINDS:
            if k not in kinds:
                continue
            d, h = kinds[k]["defects"], kinds[k]["hit"]
            add(f"| {k} | {d} | {h} | {'—' if d == 0 else f'{h / d * 100:.0f}%'} |")
        # 有些样本集（比如 far-cases）**只有跨章标注、没有章内标注** ——
        # 那时这里的分母是 0。之前直接除，整份报告生成失败、这一轮的调用全白花
        sum_hit_all = sum(k["hit"] for k in kinds.values())
        recall = "—" if total_defects == 0 else f"{sum_hit_all / total_defects * 100:.0f}%"
        add(f"| **合计** | **{total_defects}** | **{sum_hit_all}** | **{recall}** |")
        if cross_total:
            add(f"| 跨章标注 | {cross_total} | {cross_hit} | **{cross_hit / cross_total * 100:.0f}%** |")
        add("")
        if reports:
            sum_hit = sum(block.get(c["id"], {}).get("nHitReports", 0) for c in cases["chapters"])
            sum_cross = sum(block.get(c["id"], {}).get("nCrossReports", 0) for c in cases["chapters"])
            sum_dup = sum(block.get(c["id"], {}).get("nDupReports", 0) for c in cases["chapters"])
            sum_un = sum(block.get(c["id"], {}).get("nUnmatched", 0) for c in cases["chapters"])
            add(f"- 报告总数 **{reports}**：章内命中 **{sum_hit}**，跨章命中 **{sum_cross}**，"
                f"重复 **{sum_dup}**，未命中 **{sum_un}**")
            add(f"- 精确率（机器口径，未人工复核）：**{matched / reports * 100:.0f}%**"
                f"（= 不是误报的报告 / 报告总数）")
            add(f"- 章内命中的报告里类型判对：**{type_ok}/{sum_hit}**")
        else:
            add("- 一条报告都没有")
        add("")

        add("| 章 | 标注 | 报告 | 章内命中 | 跨章命中 | 未命中 | 说明 |")
        add("|---|---|---|---|---|---|---|")
        for ch in cases["chapters"]:
            r = block.get(ch["id"])
            if not r:
                continue
            memo = ""
            if key == "single":
                if not r.get("ok"):
                    memo = f"**没审成**：{r.get('message')}"
                elif r.get("droppedIssues"):
                    memo = f"反幻觉丢弃 {r['droppedIssues']} 条"
            add(f"| {ch['id']} | {r['defects']} | {r['reports']} | {r['nHitReports']} | "
                f"{r['nCrossReports']} | {r['nUnmatched']} | {memo} |")
        add("")

        add("### 漏报（标注了但没检出）\n")
        rows = [f"- {ch['id']}　[{d['kind']}]　`{d['wrong']}` → {d['right']}（{d['note']}）"
                for ch in cases["chapters"] for d in (block.get(ch["id"], {}).get("missed") or [])]
        add("\n".join(rows) if rows else "- 无")
        add("")

        add("### 未命中的报告（人工复核是不是误报）\n")
        rows, manual_fp, manual_other = [], 0, 0
        for ch in cases["chapters"]:
            for dt in (block.get(ch["id"], {}).get("details") or []):
                if dt["verdict"] != "未命中（误报候选）":
                    continue
                v = verdicts.get(f"{ch['id']}|{dt['excerpt']}")
                if v and v.startswith("误报"):
                    manual_fp += 1
                elif v:
                    manual_other += 1
                rows.append(f"- {ch['id']}　[{dt['kind']}]　`{dt['excerpt']}` → {dt['suggestion']}"
                            + (f"　**人工判定：{v}**" if v else "　（还没复核）"))
        add("\n".join(rows) if rows else "- 无")
        add("")
        if verdicts:
            add(f"- 人工复核后：判为误报 **{manual_fp}** 条，其余 {manual_other} 条不算误报"
                f"（真问题或方向反）→ **精确率 {((reports - manual_fp) / reports * 100) if reports else 0:.0f}%**")
            add("")

        bad_fix = [(ch["id"], dt) for ch in cases["chapters"]
                   for dt in (block.get(ch["id"], {}).get("details") or [])
                   if dt.get("fix_ok") is False]
        if bad_fix:
            add("### 命中但修法方向不对（错别字类）\n")
            for cid, dt in bad_fix:
                add(f"- {cid}　`{dt['defect']}`：建议是「{dt['suggestion']}」")
            add("")

        dup = [(ch["id"], r["nDupReports"]) for ch in cases["chapters"]
               for r in [block.get(ch["id"], {})] if r.get("nDupReports")]
        if dup:
            add("### 重复报告（同一处跨章问题报了好几次）\n")
            for cid, n in dup:
                add(f"- {cid}：{n} 条")
            add("")

    add("## 跨章一致性\n")
    add("看的是「人名/道具写法的跨章矛盾」。这一项**不是靠单章正文能判的** —— "
        "能不能发现，取决于模型有没有主动去查前文（见下表的工具调用）。\n")
    for key, title in (("single", "单章审查"), ("task", "全文审查")):
        block = result.get(key)
        if not block:
            continue
        add(f"**{title}**")
        for x in cases.get("crossChapter", []):
            r = block.get(x["chapterId"], {})
            found = next((dt for dt in r.get("details", [])
                          if any(m in norm(dt["excerpt"]) for m in x["matchAny"])), None)
            add(f"- `{x['wrong']}`（应写 {x['right']}）：{'**检出**' if found else '没检出'}"
                + (f"，报的是「{found['excerpt']}」→ {found['suggestion']}" if found else ""))
        add("")

    tools = result.get("tools") or {}
    if any(tools.get(k) for k in ("single", "task", "all")):
        add("## 工具调用（模型有没有真的去查前文）\n")
        for k, title in (("single", "单章审查"), ("task", "全文审查"),
                         ("all", "本轮合计（两种方式混在一起）")):
            st = tools.get(k)
            if not st:
                continue
            used = "，".join(f"{a} {b} 次" for a, b in st["byTool"].items() if b)
            add(f"- **{title}**：合计 {st['total']} 次" + (f"（{used}）" if used else ""))
            if st["byChapter"]:
                add("  - 按章：" + "，".join(f"第{no}章 {n} 次"
                                           for no, n in sorted(st["byChapter"].items()) if no))
            if st["unknown"]:
                add(f"  - 没带章号的（列目录之类）：{st['unknown']} 次")
            g = st.get("glossary")
            if g:
                add(f"  - 名词表：服务端直接判出矛盾 **{g['conflicts']}** 处"
                    f"（其中数字矛盾 {g.get('factConflicts', 0)} 处）；"
                    f"记入/更新名字 {g['recorded']} 个、设定数字 {g.get('factsRecorded', 0)} 条"
                    f"（涉及 {g['recordChapters']} 章）")
        add("")

    if result.get("task"):
        m = result["task"]["_meta"]
        add("## 成本\n")
        add(f"- 全文审查：{m['totalChapters']} 章，成功 {m['doneChapters']}，失败 {m['failedChapters']}，"
            f"扣费 {m['chargedUnits']} 字，退款 {m['refundedUnits']} 字，用时 {m['elapsed']}s")
        if result.get("single"):
            el = [r["elapsed"] for r in result["single"].values() if r.get("elapsed")]
            chars = [r["reviewedChars"] for r in result["single"].values()]
            if el:
                add(f"- 单章审查：{len(el)} 次调用，平均 {sum(el) / len(el):.1f}s，正文合计 {sum(chars)} 字")
        add("")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))


def compare(a_label, b_label, out_name=""):
    """两张报告做差值。改完提示词之后，靠这个说「更准了」而不是「感觉更准了」。"""
    def load(label):
        with open(os.path.join(REPORTS, f"{label}.json"), encoding="utf-8") as f:
            return json.load(f)

    a, b = load(a_label), load(b_label)
    cases = load_cases(b.get("casesFile") or a.get("casesFile") or "review-cases")
    total = {(k): sum(len(c["defects"]) for c in cases["chapters"]) for k in ("_",)}
    cross_total = len(cases.get("crossChapter", []))
    L = []
    add = L.append
    add(f"# 对比：{a_label} → {b_label}\n")
    add(f"- {a_label}：{a['at']}　{b_label}：{b['at']}")
    for lab, r in ((a_label, a), (b_label, b)):
        if r.get("note"):
            add(f"- {lab} 说明：{r['note']}")
    add("")
    add("| 指标 | " + a_label + " | " + b_label + " | 差值 |")
    add("|---|---|---|---|")
    for key, title in (("single", "单章"), ("task", "全文")):
        if not a.get(key) or not b.get(key):
            continue
        for name, field in (("章内召回", "recall"), ("跨章召回", "cross")):
            va = metric(a[key], cases, field)
            vb = metric(b[key], cases, field)
            add(f"| {title}·{name} | {va} | {vb} | {vb - va:+.2f} |")
        pa, pb = precision(a[key], cases), precision(b[key], cases)
        add(f"| {title}·精确率 | {pa} | {pb} | {pb - pa:+.2f} |")
        ra, rb = report_count(a[key], cases), report_count(b[key], cases)
        add(f"| {title}·报告条数 | {ra} | {rb} | {rb - ra:+d} |")
    ta, tb = (a.get("tools") or {}).get("single", {}), (b.get("tools") or {}).get("single", {})
    if ta or tb:
        add(f"| 单章·工具调用次数 | {ta.get('total', '-')} | {tb.get('total', '-')} | "
            f"{(tb.get('total') or 0) - (ta.get('total') or 0):+d} |")
    wa, wb = (a.get("tools") or {}).get("task", {}), (b.get("tools") or {}).get("task", {})
    if wa or wb:
        add(f"| 全文·工具调用次数 | {wa.get('total', '-')} | {wb.get('total', '-')} | "
            f"{(wb.get('total') or 0) - (wa.get('total') or 0):+d} |")
    ga = (ta.get("glossary") or {}).get("conflicts", 0) + (wa.get("glossary") or {}).get("conflicts", 0)
    gb = (tb.get("glossary") or {}).get("conflicts", 0) + (wb.get("glossary") or {}).get("conflicts", 0)
    if ga or gb:
        add(f"| 服务端名词表判出的矛盾（两模式合计） | {ga} | {gb} | {gb - ga:+d} |")
    add("")

    # 逐条对：哪些缺陷从漏到中、从中到漏
    for key, title in (("single", "单章"), ("task", "全文")):
        if not a.get(key) or not b.get(key):
            continue
        add(f"## {title}：逐条对比\n")
        gains, losses = [], []
        for ch in cases["chapters"]:
            ra, rb = a[key].get(ch["id"]), b[key].get(ch["id"])
            for d in ch["defects"]:
                ma = ra and d["wrong"] not in {m["wrong"] for m in ra["missed"]}
                mb = rb and d["wrong"] not in {m["wrong"] for m in rb["missed"]}
                if mb and not ma:
                    gains.append(f"- 补上：{ch['id']}　[{d['kind']}]　`{d['wrong']}`")
                elif ma and not mb:
                    losses.append(f"- 丢了：{ch['id']}　[{d['kind']}]　`{d['wrong']}`")
        for x in cases.get("crossChapter", []):
            ra, rb = a[key].get(x["chapterId"], {}), b[key].get(x["chapterId"], {})
            if rb.get("crossHit", 0) > ra.get("crossHit", 0):
                gains.append(f"- 补上（跨章）：`{x['wrong']}`")
            elif ra.get("crossHit", 0) > rb.get("crossHit", 0):
                losses.append(f"- 丢了（跨章）：`{x['wrong']}`")
        add("**补上的**" if gains else "**补上的**：无")
        add("\n".join(gains) if gains else "")
        add("**丢掉的**" if losses else "**丢掉的**：无")
        add("\n".join(losses) if losses else "")
        add("")

    path = os.path.join(REPORTS, out_name or f"compare-{a_label}-{b_label}.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("\n".join(L))
    print(f"\n对比报告 → {path}")


def metric(block, cases, field):
    total = sum(len(c["defects"]) for c in cases["chapters"])
    if field == "cross":
        hit = sum(block.get(c["id"], {}).get("crossHit", 0) for c in cases["chapters"])
        return round(hit / len(cases.get("crossChapter", [])), 2) if cases.get("crossChapter") else 0.0
    hit = 0
    for ch in cases["chapters"]:
        r = block.get(ch["id"], {})
        missed = {m["wrong"] for m in r.get("missed", [])}
        hit += sum(1 for d in ch["defects"] if d["wrong"] not in missed)
    return round(hit / total, 2)


def precision(block, cases):
    reports = sum(block.get(c["id"], {}).get("reports", 0) for c in cases["chapters"])
    unmatched = sum(block.get(c["id"], {}).get("nUnmatched", 0) for c in cases["chapters"])
    return round((reports - unmatched) / reports, 2) if reports else 0.0


def report_count(block, cases):
    return sum(block.get(c["id"], {}).get("reports", 0) for c in cases["chapters"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["setup", "run", "rescore", "teardown", "compare"])
    ap.add_argument("--label", default="baseline")
    ap.add_argument("--label-b", default="")
    ap.add_argument("--mode", default="both", choices=["single", "task", "both"])
    ap.add_argument("--cases", default="review-cases")
    ap.add_argument("--note", default="")
    ap.add_argument("--log", default=os.environ.get("EVAL_LOG", ""))
    ap.add_argument("--reset-quota", action="store_true",
                    help="跑前把评测账号的当日字数计数清零（只对评测专用账号用）")
    ap.add_argument("--allow-low-quota", action="store_true",
                    help="额度不够也照跑；本轮会标成不完整")
    args = ap.parse_args()
    if args.action == "setup":
        setup(args.cases)
    elif args.action == "teardown":
        teardown(args.cases)
    elif args.action == "rescore":
        rescore(args.label, args.log)
    elif args.action == "compare":
        compare(args.label, args.label_b)
    else:
        run(args.label, args.mode, args.cases, args.note, args.log,
            reset_quota=args.reset_quota, allow_low_quota=args.allow_low_quota)


if __name__ == "__main__":
    sys.exit(main())
