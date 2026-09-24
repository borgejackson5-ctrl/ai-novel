"""AI 链路端到端冒烟：每条链路在真实环境中各执行一遍，验证可用性。

单独一个脚本的原因：项目中的 57 个测试文件、450+ 条用例均为纯单测（没有 Spring
上下文、没有真实库 / MQ / ES / 模型），无法拦截装配层面的问题，而实际问题几乎都属于这一类：
  · 跨模块接线遗漏（块索引只有人工重建入口，新章节永远不会进入索引）；
  · 接口契约变更而调用方未同步（登录字段 username → identifier）；
  · 配置变更后未重新构建（jar 新鲜度检查只看 .java）。
这类问题只有「启动真实环境、实际调用一次、再检查日志」才能发现。

每条链路的判据：
  ① HTTP 200 且业务 code=200；
  ② 返回体符合预期（非空 / 状态到位 / SSE 帧为合法 JSON）；
  ③ 服务端日志没有新增 ERROR（流式链路尤其重要：内容能输出但收尾失败时，
     前端只看到「网络错误」，该问题仅在日志中有堆栈）。

用法：
    python scripts/ai_smoke.py                                  # 请求 8081
    python scripts/ai_smoke.py --base http://localhost:8080
    python scripts/ai_smoke.py --log <实例日志路径>              # 一并检查 ERROR（推荐）
    python scripts/ai_smoke.py --reset-quota                    # 执行前清空评测账号当日字数
    python scripts/ai_smoke.py --skip task                      # 跳过全文审查（需投 MQ，较慢）

本脚本不负责启动环境（由 eval/run_all.py 负责）。执行前实例需已启动。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import _container_secret as secrets

# 临时样本：脚本自行创建并复用（按标题查找），执行后不删除（保留作为 fixture）
TMP_TITLE = "冒烟测试·AI 链路临时样本"
CHAPTERS = [
    ("第一章 归途",
     "裴仲书到镇上的时候，天已经黑透了。街上没有灯，他沿着墙根走，脚边有一条水沟，"
     "水面映出一点天光。他肩上搭着一条灰布巾，手里那把伞有九根伞骨。\n\n"
     "走到路口，他停住了。前面站着一个女子，正低头数着手里的铜钱，数完了，又从头数一遍。\n\n"
     "他上前问了一句。女子抬起头，看了他一眼，没有说话。她问：“你是从北边来的？”"
     "他说是。她又低下头去数铜钱，一枚一枚，数得很慢。\n\n"
     "雨点落下来的时候，两个人还站在路口。"),
    ("第二章 灯下",
     "裴仲书把伞靠在门边，坐下来喝了一碗凉水。屋里只有一张桌子，桌角摆着一只粗瓷碗，"
     "碗底还剩一点水。\n\n"
     "他把那把七根伞骨的伞拿起来，抖了抖，水珠落在地上。\n\n"
     "外面有人敲门。他没有起身，只说了一句：“进来。”进来的是那个数铜钱的女子。"
     "她把布巾还给他，布巾是洗净了的。裴仲书接过来，搭回肩上。\n\n"
     "“北边的人还来吗？”她问。他说不知道。两个人都没有再说话。灯油快见了底，"
     "火苗矮下去。他把灯芯挑了一下，屋里才重新亮起来。\n\n"
     "她走的时候，雨已经停了。"),
]


class Fail(Exception):
    pass


class Client:
    def __init__(self, base, token=""):
        self.base = base.rstrip("/")
        self.token = token

    def _headers(self, json_body):
        h = {}
        if json_body:
            h["Content-Type"] = "application/json; charset=utf-8"
        if self.token:
            h["Authorization"] = self.token
        return h

    def call(self, method, path, body=None, timeout=90):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers=self._headers(body is not None))
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"code": e.code, "message": raw[:200]}

    def stream(self, method, path, body=None, timeout=240):
        """读取一次 SSE。

        返回 (扣费字数, 拼接后的内容, 非法帧列表)。

        注意：「正常 EOF」与「收尾失败」需区分处理：收尾失败时服务端会中断连接，
        客户端抛异常（IncompleteRead / URLError），前端表现为 onerror 被调用并
        提示「生成失败」，而此时内容已输出一部分。因此不能只判断「是否有内容」。
        """
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers=self._headers(body is not None))
        with urllib.request.urlopen(req, timeout=timeout) as r:
            units = r.headers.get("X-AI-Charged-Units")
            chunks, bad = [], []
            for raw in r:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if not payload:
                    continue
                try:
                    chunks.append(json.loads(payload))
                except Exception:
                    bad.append(payload[:60])
            return units, "".join(chunks), bad


def log_mark(path):
    """记录日志当前行数，执行完成后只检查新增部分"""
    if not path or not os.path.exists(path):
        return 0
    try:
        with open(path, "rb") as f:
            return sum(1 for _ in f)
    except OSError:
        return 0


def log_errors(path, from_line):
    """返回 (ERROR 列表, WARN 列表)。

    仅 ERROR 参与失败判定。WARN 单独输出供查看：「边界与拒绝」那一组
    每条被拒的入参都会在 GlobalExceptionHandler 留下一条 WARN，属预期行为，
    计入失败会导致该脚本长期判定为失败。
    """
    if not path or not os.path.exists(path):
        return None
    errors, warns = [], []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                if i <= from_line:
                    continue
                if " ERROR " in line:
                    errors.append(line.strip()[:200])
                elif " WARN " in line:
                    warns.append(line.strip()[:200])
    except OSError:
        return None
    return errors, warns


def find_or_create_novel(c):
    """按标题查找临时样本；不存在则创建（幂等，可重复执行）"""
    _, res = c.call("GET", "/novel/mine?pageNum=1&pageSize=50")
    for row in (res.get("data") or {}).get("list") or []:
        if row.get("title") == TMP_TITLE:
            novel_id = int(row["id"])
            _, chs = c.call("GET", f"/chapter/author/{novel_id}?pageNum=1&pageSize=50")
            rows = sorted(((chs.get("data") or {}).get("list") or []),
                          key=lambda r: int(r.get("chapterNo") or 0))
            if rows:
                print(f"  复用临时样本：novelId={novel_id}，{len(rows)} 章")
                return novel_id, [int(r["id"]) for r in rows]
    _, res = c.call("POST", "/novel/publish", {
        "title": TMP_TITLE, "categoryId": 2, "tags": "冒烟测试",
        "intro": "ai_smoke.py 每次跑用的临时样本，不是真实作品。",
        "chapters": [{"title": t, "content": body, "unlockCoin": 0} for t, body in CHAPTERS],
    })
    novel_id = int((res.get("data") or {})["id"])
    _, chs = c.call("GET", f"/chapter/author/{novel_id}?pageNum=1&pageSize=50")
    rows = sorted(((chs.get("data") or {}).get("list") or []),
                  key=lambda r: int(r.get("chapterNo") or 0))
    print(f"  新建临时样本：novelId={novel_id}，{len(rows)} 章")
    return novel_id, [int(r["id"]) for r in rows]


def reset_quota(container, password):
    """把评测账号当日字数计数清掉。

    需要它的原因：全文审查执行在 MQ 消费线程中，没有登录态，isAdmin() 为假，
    管理员同样按「免费额度 3 万字/天」扣减。冒烟执行几轮即可耗尽额度，
    之后每轮全文审查都会以「额度不足」中止，表现为功能异常。
    """
    uid = subprocess.run(
        ["docker", "exec", "-e", f"MYSQL_PWD={password}", container, "mysql",
         "--default-character-set=utf8mb4", "-uroot", "-N", "-B", "ai_drama",
         "-e", "SELECT id FROM t_user WHERE username='admin';"],
        capture_output=True, text=True, timeout=30).stdout.strip()
    if not uid:
        print("  [警告] 查不到 admin 的 userId，跳过清零")
        return
    key = f"ai:user:usage:{uid}:{time.strftime('%Y-%m-%d')}"
    out = subprocess.run(["docker", "exec", "ai-novel-redis", "redis-cli", "-a", secrets.redis_password(),
                          "--no-auth-warning", "DEL", key],
                         capture_output=True, text=True, timeout=20)
    print(f"  已清评测账号当日计数：{key}（DEL -> {out.stdout.strip()}）")


RESULTS = []


def step(name, fn):
    t0 = time.time()
    try:
        detail = fn()
        RESULTS.append((name, True, detail, time.time() - t0))
        print(f"  [PASS] {name}  {detail}  ({time.time() - t0:.1f}s)")
    except Exception as e:
        msg = f"{type(e).__name__}: {e}"[:180]
        RESULTS.append((name, False, msg, time.time() - t0))
        print(f"  [FAIL] {name}  {msg}  ({time.time() - t0:.1f}s)")


def expect_code(method, path, body=None, timeout=90):
    status, res = CLIENT.call(method, path, body, timeout)
    if status != 200 or res.get("code") != 200:
        raise Fail(f"HTTP {status} code={res.get('code')} msg={res.get('message')}")
    return res.get("data")


def main():
    ap = argparse.ArgumentParser(description="AI 链路端到端冒烟")
    ap.add_argument("--base", default=os.environ.get("SMOKE_BASE", "http://localhost:8081"))
    ap.add_argument("--account", default=os.environ.get("SMOKE_ACCOUNT", "admin"))
    ap.add_argument("--password", default=os.environ.get("SMOKE_PASSWORD", "admin123"))
    ap.add_argument("--log", default=os.environ.get("SMOKE_LOG", ""), help="实例日志路径")
    ap.add_argument("--reset-quota", action="store_true", help="跑前清掉评测账号当日字数")
    ap.add_argument("--skip", default="", help="跳过的项，逗号分隔：task,cancel,search")
    args = ap.parse_args()
    skip = {s.strip() for s in args.skip.split(",") if s.strip()}

    global CLIENT
    CLIENT = Client(args.base)
    print(f"== AI 链路冒烟  base={args.base}  账号={args.account}")
    print()

    print("-- 准备 --")
    if args.reset_quota:
        reset_quota("ai-novel-mysql", secrets.mysql_password())
    token = expect_code("POST", "/auth/login",
                        {"identifier": args.account, "password": args.password}).get("token")
    CLIENT.token = token
    print(f"  登录成功（token {len(token)} 字符）")
    novel_id, chapter_ids = find_or_create_novel(CLIENT)
    text = CHAPTERS[0][1]
    mark = log_mark(args.log)
    print()

    print("-- 创作辅助 --")
    step("起名（同步 /ai/generate TITLE）",
         lambda: (lambda d: f"{len(d)} 字：{d[:24]}…" if d else _fail("返回为空"))(
             expect_code("POST", "/ai/generate",
                         {"type": "TITLE", "input": "一个瓷匠回到镇上重开旧窑的故事"})))
    step("简介（同步 /ai/generate INTRO）",
         lambda: (lambda d: f"{len(d)} 字：{d[:24]}…" if d else _fail("返回为空"))(
             expect_code("POST", "/ai/generate",
                         {"type": "INTRO", "input": "一个瓷匠回到镇上重开旧窑的故事"})))

    def sse(path, body, label):
        units, content, bad = CLIENT.stream("POST" if body is not None else "GET", path, body)
        if bad:
            raise Fail(f"{len(bad)} 个非法帧，例如 {bad[0]!r}")
        if not content:
            raise Fail("没有收到任何内容帧")
        if units is None:
            raise Fail("响应头里没有 X-AI-Charged-Units（开流前的扣费没发生？）")
        return f"扣 {units} 字，收 {len(content)} 字：{content[:20]}…"

    step("起名（流式 /ai/generate/stream）",
         lambda: sse("/ai/generate/stream?type=INTRO&input="
                     + urllib.parse.quote("一个瓷匠回到镇上重开旧窑的故事"), None, "stream"))
    step("续写（流式 /ai/write/continue）",
         lambda: sse("/ai/write/continue",
                     {"content": text, "direction": "写他重新点火的这一段", "length": "SHORT"},
                     "continue"))
    step("润色（流式 /ai/write/polish）",
         lambda: sse("/ai/write/polish",
                     {"content": "他走进院子，看见地上有很多很多的水，院子里的树也在那里站着，"
                                 "他觉得心里面有一些说不出来的感觉。", "mode": "EXPRESS"},
                     "polish"))

    print()
    print("-- 审查 --")
    step("单章审查",
         lambda: (lambda d: f"报 {len(d.get('issues') or [])} 条，summary {len(d.get('summary') or '')} 字"
                            if (d.get('summary') or d.get('issues') is not None) else _fail("空结构"))(
             expect_code("POST", f"/ai/review/chapter/{chapter_ids[0]}", None, timeout=240)))
    step("审查总览 /review/novel/{id}/overview",
         lambda: (lambda d: f"共 {d.get('chapterCount')} 章，剩余额度 {d.get('remainingUnits')}，"
                            f"任务 {((d.get('task') or {}).get('statusText')) or '无'}")(
             expect_code("GET", f"/ai/review/novel/{novel_id}/overview")))

    task_id = {}

    def start_task():
        d = expect_code("POST", f"/ai/review/novel/{novel_id}", None, timeout=60)
        task_id["id"] = d.get("taskId") or d.get("id")
        if not task_id["id"]:
            raise Fail(f"建任务没返回 id：{str(d)[:120]}")
        return f"taskId={task_id['id']} 状态={d.get('statusText')}"

    if "task" not in skip:
        step("全文审查：建任务", start_task)

        def wait_done():
            if not task_id:
                raise Fail("上一个步骤没建出任务")
            for _ in range(60):
                d = expect_code("GET", f"/ai/review/task/{task_id['id']}")
                st = d.get("statusText") or d.get("status")
                if d.get("status") == 2 or st == "已完成":
                    return (f"已完成：{d.get('doneChapters')}/{d.get('totalChapters')} 章，"
                            f"失败 {d.get('failedChapters')}，问题 {d.get('issueCount')}")
                if d.get("status") in (3, 4) or st in ("已中止", "失败"):
                    raise Fail(f"任务没跑完就停了：{st}")
                time.sleep(2)
            raise Fail("等 120s 还没完成")

        step("全文审查：跑到完成", wait_done)
        step("审查问题列表 /review/task/{id}/issues",
             lambda: f"第 1 页 {len((expect_code('GET', f'/ai/review/task/{task_id['id']}/issues?pageNum=1&pageSize=10') or {}).get('list') or [])} 条")

    print()
    print("-- 检索 --")
    step("智能搜索 /novel/search/smart",
         lambda: (lambda d: f"意图={d.get('intent') or '—'}，作品 {(d.get('page') or {}).get('total')} 部")(
             expect_code("POST", "/novel/search/smart", {"query": "瓷匠 窑", "page": 1, "size": 5})))
    step("向量检索诊断 /novel/vector-search（管理员口，三种模式都跑一遍）",
         lambda: "；".join(
             "%s %d 条" % (m, len(expect_code(
                 "POST", "/novel/vector-search?novelId=%d&q=%s&topK=5&mode=%s"
                 % (novel_id, urllib.parse.quote("他手里那把伞"), m)) or []))
             for m in ("vector", "keyword", "hybrid")))

    print()
    print("-- 边界与拒绝：这些入参本该被拒，而且提示要是人话 --")

    def rejected(method, path, body=None, needle=""):
        status, res = CLIENT.call(method, path, body)
        if status == 200 and res.get("code") == 200:
            raise Fail("居然成功了 —— 这个入参本该被拒")
        if status >= 500 or res.get("code") == 500:
            raise Fail(f"变成了 500（应该给可读的业务错误）：{str(res)[:110]}")
        msg = str(res.get("message") or res.get("msg") or "")
        if needle and needle not in msg:
            raise Fail(f"提示里没有「{needle}」：{msg[:70]}")
        return f"HTTP {status}：{msg[:34]}"

    step("起名：type 为空", lambda: rejected("POST", "/ai/generate", {"type": "", "input": "x"}))
    step("续写：content 为空",
         lambda: rejected("POST", "/ai/write/continue", {"content": "", "length": "SHORT"},
                          "先写点内容"))
    step("润色：mode 不合法",
         lambda: rejected("POST", "/ai/write/polish",
                          {"content": "他走进院子。", "mode": "不存在的模式"}, "请选择"))
    step("润色：正文超长（>2000 字）",
         lambda: rejected("POST", "/ai/write/polish",
                          {"content": "字" * 2001, "mode": "EXPRESS"}, "太长"))
    step("单章审查：章节不存在", lambda: rejected("POST", "/ai/review/chapter/1"))
    step("审查任务：任务不存在", lambda: rejected("GET", "/ai/review/task/1"))

    def anon_rejected():
        saved = CLIENT.token
        CLIENT.token = ""
        try:
            return rejected("POST", "/ai/generate", {"type": "TITLE", "input": "x"})
        finally:
            CLIENT.token = saved

    step("未登录访问 AI 接口", anon_rejected)

    print()
    errs = log_errors(args.log, mark)
    if errs is None:
        print("-- 日志 --  （没给 --log，跳过。强烈建议带上：流式收尾那类失败只在日志里看得见）")
    else:
        errors, warns = errs
        print(f"-- 日志 --  ERROR {len(errors)} 条，WARN {len(warns)} 条")
        for e in errors[:12]:
            print("   [ERROR] " + e)
        if warns:
            print(f"   （WARN 不算失败：下面这 {min(len(warns), 6)} 条基本都是"
                  f"「边界与拒绝」那组故意触发的参数校验/业务异常）")
            for w in warns[:6]:
                print("   [WARN] " + w)
        if errors:
            RESULTS.append(("服务端日志有 ERROR", False, f"{len(errors)} 条", 0.0))

    print()
    ok = sum(1 for _, p, _, _ in RESULTS if p)
    print("=" * 74)
    print(f"结果：PASS {ok} / {len(RESULTS)}")
    for name, passed, detail, cost in RESULTS:
        if not passed:
            print(f"  [FAIL] {name}：{detail}")
    print("=" * 74)
    return 0 if ok == len(RESULTS) else 1


def _fail(msg):
    raise Fail(msg)


if __name__ == "__main__":
    sys.exit(main())
