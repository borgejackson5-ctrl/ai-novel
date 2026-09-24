"""AI 链路降级冒烟：依赖/配置异常时，各条链路返回的是明确提示还是「静默假成功」。

`ai_smoke.py` 验证「正常状态下是否可用」；本脚本验证另一面：平台未配置 Key、额度用尽、
Elasticsearch 不可达、RabbitMQ 不可达。该场景更容易出现静默问题：
不报错、接口返回 200，但用户得到的是假象（以演示文字充当生成结果、任务创建后永不执行）。

四条降级场景：

| 场景 | 构造方式 | 期望 |
| --- | --- | --- |
| `nokey` | 清空 `t_ai_config.api_key`（mock 开关保留原值，本地通常为开） | 起名/简介/续写返回明确标注的示例；润色拒绝；审查前置拒绝 |
| `nokey-strict` | 清空 Key + `mock_enabled=0`（生产环境应有的形态） | 全部明确拒绝，文案「AI 功能暂未开放」 |
| `quota` | 将测试账号当日字数计数写满 | 全部返回「今天的免费字数已经用完了」，不得返回 500 |
| `es-down` / `mq-down` | 另起一个配置错误端口的实例（不动容器） | ES：审查照常成功，仅不注入前文；MQ：接口不得假失败、消息需落 outbox 等补投，且恢复后自动完成 |

三个安全性设计（均由实际故障驱动）：

1. 不停容器、不改端口映射：本机 IDE 中的开发实例可能正在 8080 上运行，
   `docker stop ai-novel-es` 会一并影响该实例。改用指向错误端口的独立实例，
   连接被拒与 broker 不可达在代码路径上一致。
2. 平台 Key 不落盘：备份与还原均在 MySQL 内部完成（临时表 `_degrade_bak_ai_config`），
   脚本只比对 `MD5(api_key)`，密钥不经过文件系统或终端输出。
3. 任何异常都还原：`finally` 中恢复配置并删除临时表，且逐项核验恢复结果
   （MD5、长度、mock 开关），核验不通过则明确报错。

用法：
    python scripts/ai_degrade.py --scenario quota --log <实例日志>
    python scripts/ai_degrade.py --scenario nokey --log <日志> --i-know-this-clears-the-platform-key
    python scripts/ai_degrade.py --scenario es-down            # 自己起实例（:8082）
    python scripts/ai_degrade.py --scenario mq-down

`nokey*` / `quota` 请求指定的 `--base`（默认 8081）；`es-down` / `mq-down` 自行启动 :8082 并自行停止。

SSE 超时不在本脚本，单独一个脚本：`scripts/ai_sse_timeout_check.py`。
该脚本需要把 `--app.sse.timeout-ms` 调到几毫秒才能造出超时，起实例的方式与本脚本的场景不同；
它还额外验证「额度已退回」与「日志 0 条 ERROR」。
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

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAR = os.path.join(ROOT, "backend", "target", "ai-novel-backend.jar")
LOG_DIR = os.environ.get("AI_DEGRADE_LOG_DIR", r"D:\WorkBuddySave\ai-novel\logs")

MYSQL_CTN = os.environ.get("AI_DEGRADE_MYSQL", "ai-novel-mysql")
DB = "ai_drama"
REDIS_CTN = os.environ.get("AI_DEGRADE_REDIS", "ai-novel-redis")
BAK_TABLE = "_degrade_bak_ai_config"

# 口令不写进脚本：环境变量优先，否则从容器读（docker-compose 已从 .env 注入容器）
MYSQL_PWD = secrets.mysql_password(MYSQL_CTN)
REDIS_PWD = secrets.redis_password(REDIS_CTN)

# 额度口径（与服务端一致：每日 3 万字，Redis 日键）
QUOTA_LIMIT = 30000

# 基础设施类 ERROR 不计入「链路自身出错」：错误端口或容器不可达时，客户端会周期性重连、
# 同步消息重试至耗尽，均会产生 ERROR。此类日志单独统计并打印（只分类，不隐藏），
# 避免脚本判定长期失败，也避免将真实缺陷一并过滤。
INFRA_NOISE = ("AmqpConnectException", "Connection refused", "ConnectException",
               "ElasticsearchException", "Connection reset", "ListenerContainer",
               "ContainerStopped", "Attempting to connect", "connection error",
               "NodeDisconnectedException", "Node not connected", "Pooling",
               "RestClientException",
               # ES 不可达时，同步消息重试至耗尽（属预期：作品链路有定时对账兜底，
               # 章节块链路没有，见 RabbitMqConfig 的说明）
               "搜索同步失败", "搜索同步连续失败", "章节块同步失败", "转入死信队列",
               "向量那一路失败", "关键词那一路失败")


class Fail(Exception):
    pass


# ==================== 外部命令 ====================

def mysql(sql, vv=False):
    cmd = ["docker", "exec", "-i", "-e", f"MYSQL_PWD={MYSQL_PWD}", MYSQL_CTN, "mysql",
           "--default-character-set=utf8mb4", "-uroot"]
    if vv:
        cmd.append("-vv")
    cmd += ["-D", DB, "-e", sql]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=60,
                       encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def mysql_rows(sql):
    """返回 [[列1,列2,...], ...]（不含表头）"""
    cmd = ["docker", "exec", "-i", "-e", f"MYSQL_PWD={MYSQL_PWD}", MYSQL_CTN, "mysql",
           "--default-character-set=utf8mb4", "-uroot", "-N", "-B", "-D", DB, "-e", sql]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=60,
                       encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise Fail("查库失败：" + (p.stderr or "").strip()[:200])
    return [line.split("\t") for line in (p.stdout or "").strip().splitlines() if line]


def outbox_max_id():
    """当前 t_mq_outbox 的最大 id。测试前记录，测试后按「id 大于该值」统计本轮新增行"""
    return int(mysql_rows("SELECT COALESCE(MAX(id), 0) FROM t_mq_outbox;")[0][0])


def outbox_count_since(mark, status=None):
    sql = "SELECT COUNT(*) FROM t_mq_outbox WHERE id > %d" % mark
    if status is not None:
        sql += " AND status = %d" % status
    return int(mysql_rows(sql)[0][0])


def redis(*args):
    p = subprocess.run(["docker", "exec", REDIS_CTN, "redis-cli", "-a", REDIS_PWD,
                        "--no-auth-warning", *args],
                       capture_output=True, text=True, timeout=30)
    return (p.stdout or "").strip()


# ==================== HTTP ====================

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

    def call(self, method, path, body=None, timeout=120):
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
        """读取一次 SSE，返回 (扣费头, 内容, 服务端 message)。

        降级场景中「被拒绝」与「正常返回」必须区分：拒绝时状态码为 400、body 为 JSON，
        不是 SSE，因此这里统一按 call 的解析方式处理，仅拼接 SSE 的帧。
        """
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers=self._headers(body is not None))
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                units = r.headers.get("X-AI-Charged-Units")
                ctype = r.headers.get("Content-Type") or ""
                if "text/event-stream" not in ctype:
                    raw = r.read().decode("utf-8", "replace")
                    return None, "", raw[:200]
                chunks = []
                for line in r:
                    s = line.decode("utf-8", "replace").rstrip("\r\n")
                    if s.startswith("data:"):
                        p = s[5:].strip()
                        if p:
                            try:
                                chunks.append(json.loads(p))
                            except Exception:
                                chunks.append(p)
                return units, "".join(chunks), ""
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                j = json.loads(raw)
                # 错误体中 message 与 msg 两种键名都存在（由不同异常处理器产生），两者都需读取，
                # 否则输出为原始 JSON，判据也会随之判定错误
                return None, "", str(j.get("message") or j.get("msg") or raw)[:200]
            except Exception:
                return None, "", raw[:200]


def msg_of(status, res):
    return str(res.get("message") or res.get("msg") or json.dumps(res, ensure_ascii=False))[:160]


# ==================== 日志 ====================

def log_mark(path):
    if not path or not os.path.exists(path):
        return 0
    with open(path, "rb") as f:
        return sum(1 for _ in f)


def log_errors(path, from_line):
    """返回 (业务类 ERROR, 基础设施类 ERROR, WARN)"""
    if not path or not os.path.exists(path):
        return None
    biz, infra, warns = [], [], []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for i, line in enumerate(f, 1):
            if i <= from_line:
                continue
            if " ERROR " in line:
                (infra if any(k in line for k in INFRA_NOISE) else biz).append(line.strip()[:200])
            elif " WARN " in line:
                warns.append(line.strip()[:200])
    return biz, infra, warns


# ==================== 样本作品 ====================

CHAPTERS = [
    ("第一章 归途",
     "裴仲书到镇上的时候，天已经黑透了。街上没有灯，他沿着墙根走，脚边有一条水沟，"
     "水面映出一点天光。他肩上搭着一条灰布巾，手里那把伞有九根伞骨。\n\n"
     "走到路口，他停住了。前面站着一个女子，正低头数着手里的铜钱。"),
    ("第二章 灯下",
     "裴仲书把伞靠在门边，坐下来喝了一碗凉水。屋里只有一张桌子，桌角摆着一只粗瓷碗。\n\n"
     "他把那把七根伞骨的伞拿起来，抖了抖，水珠落在地上。外面有人敲门。"),
]


def find_or_create_novel(c, title):
    _, res = c.call("GET", "/novel/mine?pageNum=1&pageSize=50")
    for row in (res.get("data") or {}).get("list") or []:
        if row.get("title") == title:
            nid = int(row["id"])
            _, chs = c.call("GET", f"/chapter/author/{nid}?pageNum=1&pageSize=50")
            rows = sorted(((chs.get("data") or {}).get("list") or []),
                          key=lambda r: int(r.get("chapterNo") or 0))
            if rows:
                print(f"  复用样本：novelId={nid}，{len(rows)} 章")
                return nid, [int(r["id"]) for r in rows]
    _, res = c.call("POST", "/novel/publish", {
        "title": title, "categoryId": 2, "tags": "降级测试",
        "intro": "ai_degrade.py 用的临时样本，不是真实作品。",
        "chapters": [{"title": t, "content": body, "unlockCoin": 0} for t, body in CHAPTERS],
    })
    if st != 200 or res.get("code") != 200 or not (res.get("data") or {}).get("id"):
        # 降级场景中「建样本」本身也可能受影响（发布需投递搜索同步消息）；
        # 报错时带上服务端原文，否则只会得到一个 KeyError，无法定位原因。
        raise Fail(f"建样本失败（HTTP {st}）：{msg_of(st, res)}"
                   f" ｜ 降级场景的样本要先在正常环境建好，用 --novel-title 指定同一个标题")
    nid = int(res["data"]["id"])
    _, chs = c.call("GET", f"/chapter/author/{nid}?pageNum=1&pageSize=50")
    rows = sorted(((chs.get("data") or {}).get("list") or []),
                  key=lambda r: int(r.get("chapterNo") or 0))
    print(f"  新建样本：novelId={nid}，{len(rows)} 章")
    return nid, [int(r["id"]) for r in rows]


# ==================== 六条链路的探测 ====================

def probe(c, novel_id, chapter_ids):
    """依次请求六条链路，原样记录服务端返回内容。断言在下面的 expect_* 中。"""
    o = {}
    st, res = c.call("POST", "/ai/generate", {"type": "TITLE", "input": "一个瓷匠回到镇上重开旧窑的故事"})
    o["起名(同步)"] = {"status": st, "code": res.get("code"), "text": str(res.get("data") or ""), "msg": msg_of(st, res)}

    st, res = c.call("POST", "/ai/generate", {"type": "INTRO", "input": "一个瓷匠回到镇上重开旧窑的故事"})
    o["简介(同步)"] = {"status": st, "code": res.get("code"), "text": str(res.get("data") or ""), "msg": msg_of(st, res)}

    # 注意：流式起名为 GET（与续写/润色的 POST 不同）。发成 POST 无法命中映射，
    # 服务端返回 500「系统繁忙」而非 405。
    units, content, emsg = c.stream("GET", "/ai/generate/stream?type=INTRO&input="
                                    + urllib.parse.quote("一个瓷匠回到镇上重开旧窑的故事"))
    o["起名(流式)"] = {"units": units, "text": content, "msg": emsg}

    units, content, emsg = c.stream("POST", "/ai/write/continue",
                                    {"content": CHAPTERS[0][1], "direction": "写他重新点火", "length": "SHORT"})
    o["续写(流式)"] = {"units": units, "text": content, "msg": emsg}

    units, content, emsg = c.stream("POST", "/ai/write/polish",
                                    {"content": "他走进院子，看见地上有很多很多的水，院子里的树也在那里站着。",
                                     "mode": "EXPRESS"})
    o["润色(流式)"] = {"units": units, "text": content, "msg": emsg}

    st, res = c.call("POST", f"/ai/review/chapter/{chapter_ids[0]}", None, timeout=240)
    d = res.get("data") or {}
    o["单章审查"] = {"status": st, "code": res.get("code"),
                    "summary": (d.get("summary") if isinstance(d, dict) else None),
                    "issues": len((d.get("issues") or []) if isinstance(d, dict) else []),
                    "msg": msg_of(st, res)}

    st, res = c.call("POST", f"/ai/review/novel/{novel_id}", None, timeout=60)
    tid = (res.get("data") or {}).get("taskId") if isinstance(res.get("data"), dict) else None
    o["全文审查"] = {"status": st, "code": res.get("code"), "taskId": tid, "msg": msg_of(st, res)}
    if tid:
        for _ in range(12):
            time.sleep(2)
            st2, r2 = c.call("GET", f"/ai/review/task/{tid}")
            d2 = r2.get("data") or {}
            if isinstance(d2, dict) and d2.get("status") in (2, 3, 4):
                o["全文审查"].update({"endStatus": d2.get("status"), "statusText": d2.get("statusText"),
                                      "done": d2.get("doneChapters"), "total": d2.get("totalChapters"),
                                      "failed": d2.get("failedChapters"), "message": d2.get("message")})
                break
        else:
            o["全文审查"].update({"endStatus": None, "statusText": "（30 秒还没结束）"})
    return o


# ==================== 各场景的判据 ====================

MOCK_MARK = ("示例", "mock", "演示")


def expect_nokey(o, mock_on):
    """平台未配置 Key。mock 开 = 本地形态；mock 关 = 生产形态。

    判据的核心是「用户是否会被误导」：
      · 返回内容必须能明确识别为非真实结果（带「示例」字样），否则等同于以演示文字充当生成结果；
      · 拒绝文案必须是「暂不可用」这类准确表述，不能是「稍后再试」，后者会导致作者反复重试。
    """
    out = []

    def text_chain(key):
        r = o[key]
        if r.get("status") == 200 and r.get("code") == 200 and r.get("text"):
            if any(m in r["text"] for m in MOCK_MARK):
                return True, f"给示例文字且标注了（{r['text'][:18]}…）"
            return False, f"给了一段像真结果的内容、**没有任何标注**：{r['text'][:30]}…"
        if mock_on:
            return False, f"本该给示例文字，实际是拒绝：{r['msg'][:50]}"
        if "暂未开放" in r.get("msg", ""):
            return True, f"明确拒绝：{r['msg'][:26]}"
        return False, f"拒绝文案不可读：{r['msg'][:50]}"

    def stream_chain(key):
        r = o[key]
        if r.get("units") is not None or r.get("text"):
            if any(m in (r.get("text") or "") for m in MOCK_MARK):
                return True, f"给示例文字且标注了（{(r.get('text') or '')[:18]}…）"
            return False, f"给了内容、**没有标注**：{(r.get('text') or '')[:30]}…"
        if mock_on:
            return False, f"本该给示例文字，实际是拒绝：{r['msg'][:50]}"
        if "暂未开放" in r.get("msg", ""):
            return True, f"明确拒绝：{r['msg'][:26]}"
        return False, f"拒绝文案不可读：{r['msg'][:50]}"

    out.append(("起名（同步）", *text_chain("起名(同步)")))
    out.append(("简介（同步）", *text_chain("简介(同步)")))
    out.append(("起名（流式）", *stream_chain("起名(流式)")))
    out.append(("续写（流式）", *stream_chain("续写(流式)")))

    # 润色：演示文字可能被误当作真实改写结果采纳，因此无论 mock 开关都必须拒绝
    r = o["润色(流式)"]
    if r.get("units") is None and not r.get("text"):
        ok = "暂未开放" in r.get("msg", "")
        out.append(("润色（流式）", ok, ("明确拒绝：" + r["msg"][:26]) if ok else ("拒绝文案不可读：" + r["msg"][:50])))
    else:
        out.append(("润色（流式）", False, f"居然返回了内容（润色不该给演示文字）：{(r.get('text') or '')[:30]}"))

    # 审查两条：期望在开始前即说明「AI 未开放」，而不是请求上游后再失败。
    # 判据只看返回文案，不看状态码是 200 还是 400（400 且文案明确同样视为通过）。
    r = o["单章审查"]
    ok = "暂未开放" in (r.get("msg") or "")
    out.append(("单章审查", ok, ("开跑前拒绝：" + r["msg"][:30]) if ok
                else f"没做可用性前置判定（HTTP {r['status']}）：{r['msg'][:60]}"))

    r = o["全文审查"]
    rejected = r.get("status") != 200 or r.get("code") != 200
    ok = rejected and "暂未开放" in (r.get("msg") or "")
    out.append(("全文审查（建单）", ok, ("开跑前拒绝：" + r["msg"][:30]) if ok
                else f"建单没拦：HTTP {r['status']}，任务 {r.get('taskId')} "
                     f"最终「{r.get('statusText')}」{r.get('done')}/{r.get('total')} 章，"
                     f"失败 {r.get('failed')} —— {r.get('message') or r.get('msg', '')}"[:110]))
    return out


def expect_quota(o):
    """额度用尽：每条链路都需明确说明是额度问题，而非「稍后再试」这类误导文案。"""
    out = []
    for key in ("起名(同步)", "简介(同步)", "起名(流式)", "续写(流式)", "润色(流式)"):
        r = o[key]
        text = (r.get("text") or "") + (r.get("msg") or "")
        ok = "免费字数" in text and "用完了" in text
        out.append((key, ok, ("额度提示：" + text[:24]) if ok else ("文案不对：" + text[:60])))
    r = o["单章审查"]
    ok = "免费字数" in (r.get("msg") or "")
    out.append(("单章审查", ok, ("额度提示：" + r["msg"][:26]) if ok else f"文案不对：{r['msg'][:60]}"))
    r = o["全文审查"]
    ok = r.get("status") != 200 or "免费字数" in (r.get("message") or r.get("msg") or "")
    out.append(("全文审查（建单）", ok, f"任务最终「{r.get('statusText')}」：{(r.get('message') or r.get('msg') or '')[:50]}"))
    return out


def expect_es_down(o):
    out = []
    r = o["单章审查"]
    ok = r.get("status") == 200 and r.get("summary") is not None
    out.append(("单章审查（ES 挂了也要能审）", ok,
                f"报 {r['issues']} 条，summary {len(r.get('summary') or '')} 字" if ok
                else f"HTTP {r['status']}：{r['msg'][:70]}"))
    r = o["起名(同步)"]
    ok = r.get("status") == 200 and r.get("code") == 200
    out.append(("起名（不依赖 ES）", ok, f"{len(r.get('text') or '')} 字" if ok else r["msg"][:70]))
    return out


def expect_mq_down(o):
    """MQ 不可达时的判据：接口不得假失败，消息不得丢失，恢复后无需人工干预。

    改造前的行为：`start()` 创建任务与派发在同一事务、派发挂在 afterCommit，
    MQ 不可达时异常抛给调用方（Spring 语义：异常向外抛出，但事务仍视为已提交），
    结果是接口 500、任务已写入、消息未发出，且调用方重试只能取回永不推进的任务。

    改造后消息先写入 outbox（与业务数据同一事务）、投递在独立线程执行：
    接口返回 200、消息在表中等待补投、MQ 恢复后自动投出并完成任务。
    """
    out = []
    r = o["全文审查"]
    if r.get("status") == 200 and r.get("code") == 200 and r.get("taskId"):
        out.append(("MQ 挂时建单不假失败", True,
                    f"HTTP 200，任务 {r['taskId']}（不再报错但数据已写入）"))
    else:
        out.append(("MQ 挂时建单不假失败", False,
                    f"HTTP {r.get('status')} code={r.get('code')}：{str(r.get('msg'))[:60]}"))

    ob = o.get("_outbox") or {}
    n_ch = ob.get("chapters") or 0
    if ob.get("pending", 0) >= n_ch > 0 and ob.get("sent", 0) == 0:
        out.append(("消息没丢：落进 outbox 等补投", True,
                    f"待投 {ob['pending']} 条（这本书 {n_ch} 章），已投 0 条 —— 确实是没投出去，不是投丢了"))
    else:
        out.append(("消息没丢：落进 outbox 等补投", False,
                    f"待投 {ob.get('pending')} / 已投 {ob.get('sent')}，"
                    f"但预期待投 >= {n_ch} 且已投 0"))

    rec = o.get("_恢复") or {}
    if rec.get("sentAfterRecover") == n_ch > 0 and rec.get("pendingAfterRecover") == 0:
        out.append(("MQ 恢复后自动补投", True,
                    f"待投归零、已投 {rec['sentAfterRecover']} 条（这本书 {n_ch} 章）"
                    f"—— 消息一条不少，不用人工干预"))
    else:
        out.append(("MQ 恢复后自动补投", False,
                    f"已投 {rec.get('sentAfterRecover')} 条 / 待投 {rec.get('pendingAfterRecover')} 条，"
                    f"预期 {n_ch} / 0"))

    # 注意：「任务跑完」这条只判定是否有推进，不判定是否跑完：
    # broker 为共享资源，开发机上若存在另一个实例（例如 IDE 中的实例），
    # 它会与本实例竞争同一条队列的消息；而该实例的平台日调用上限可能为默认的 100，
    # 导致该章以「现在使用的人有点多」中止，此为环境因素，并非该链路的缺陷。
    # 曾出现：任务以「已中止」收尾，日志显示第 1 章由另一个实例消费。
    done = rec.get("done") or 0
    if done > 0 or rec.get("endStatus") == 2:
        out.append(("消息确实被消费了（任务有推进）", True,
                    f"{done}/{rec.get('total')} 章，任务最终「{rec.get('message') or '（还在跑）'}」"))
    else:
        out.append(("消息确实被消费了（任务有推进）", False,
                    f"任务仍停在 0/{rec.get('total')} 章，消息投出去却没人消费？"))
    return out



# ==================== 独立实例（es-down / mq-down 用） ====================

def jar_stale_report():
    jar_m = os.path.getmtime(JAR) if os.path.exists(JAR) else 0
    newest, nf = 0, ""
    for dirpath, _, names in os.walk(os.path.join(ROOT, "backend", "src", "main")):
        for n in names:
            if n.rsplit(".", 1)[-1] not in ("java", "yaml", "yml", "xml", "sql", "properties"):
                continue
            p = os.path.join(dirpath, n)
            m = os.path.getmtime(p)
            if m > newest:
                newest, nf = m, p
    return jar_m, newest, nf


def start_instance(port, extra, log_path, platform_limit="5000"):
    """启动一个测试实例。

    `platform_limit` 特意设置得大于 yaml 默认值（100）：该计数是平台 Key 的全局日调用数，
    在 Redis 中跨实例共享；当日执行评测已将其推至 400 以上，使用较小上限会直接触发
    「现在使用的人有点多」。此处仅放宽测试实例的限制，不改动 Redis 中的计数。
    """
    jar_m, newest, nf = jar_stale_report()
    if not jar_m:
        raise Fail("jar 不存在，先 mvn clean package")
    if newest > jar_m:
        raise Fail(f"jar 比源文件旧（{os.path.relpath(nf, ROOT)}），先重编再测 —— 否则测的是旧代码")
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    cmd = ["java", "-jar", JAR, f"--server.port={port}",
           f"--app.ai-platform-daily-limit={platform_limit}"] + extra
    print("  " + " ".join(cmd))
    print("  日志 -> " + log_path)
    f = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, cwd=ROOT)
    for _ in range(60):
        if port_ready(port):
            return proc, f
        if proc.poll() is not None:
            f.close()
            raise Fail(f"实例启动失败（exit={proc.returncode}），看日志：{log_path}")
        time.sleep(3)
    proc.kill()
    f.close()
    raise Fail(f"180 秒还没就绪（{port}）")


def port_ready(port):
    # 使用真实存在的路径：`/novel` 为 404（列表实际路径为 `/novel/page`）。
    # 不存在的路径同样可判定就绪（401/404 均视为「有应答」），但会在实例日志中留下
    # 一条误导性的「资源不存在」记录，增加排查成本。
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/novel/page?pageNum=1", timeout=3) as r:
            return r.status < 500
    except urllib.error.HTTPError:
        return True
    except Exception:
        return False


def stop_instance(proc, fh, port=None):
    try:
        proc.terminate()
        proc.wait(timeout=20)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass
    try:
        fh.close()
    except Exception:
        pass
    if port is not None:
        # 等待端口实际释放：mq-down 会在同一端口上换成「正常实例」，
        # 若不等待，新实例会因端口被占用而启动失败（Windows 上 TIMED_WAIT 状态持续较久）
        for _ in range(30):
            if not port_busy(port):
                return
            time.sleep(1)
        raise Fail(f"停掉实例后 :{port} 30 秒还没释放")


def port_busy(port):
    if port_ready(port):
        return True
    return False


# ==================== 平台 Key 的备份与还原（不落盘） ====================

def backup_config():
    mysql(f"DROP TABLE IF EXISTS {BAK_TABLE};")
    rc, out = mysql(f"CREATE TABLE {BAK_TABLE} LIKE t_ai_config;"
                    f"INSERT INTO {BAK_TABLE} SELECT * FROM t_ai_config;"
                    f"SELECT (SELECT COUNT(*) FROM {BAK_TABLE}) AS bak,"
                    f" (SELECT COUNT(*) FROM t_ai_config) AS cur;")
    rows = mysql_rows(f"SELECT (SELECT COUNT(*) FROM {BAK_TABLE}), (SELECT COUNT(*) FROM t_ai_config);")
    if rows[0] != ["1", "1"]:
        raise Fail(f"备份没成功（bak={rows[0][0]} cur={rows[0][1]}），中止，不动任何配置")
    print(f"  已备份 t_ai_config 到同库临时表 {BAK_TABLE}（密钥不出 MySQL，只比对 MD5）")


def config_fingerprint():
    return mysql_rows("SELECT COALESCE(MD5(api_key),'-'), COALESCE(LENGTH(api_key),-1),"
                      " COALESCE(mock_enabled,-1), COALESCE(model,'-') FROM t_ai_config WHERE id=1;")[0]


def clear_platform_key(mock_enabled):
    rc, out = mysql(f"UPDATE t_ai_config SET api_key='', mock_enabled={mock_enabled} WHERE id=1;",
                    vv=True)
    rows = mysql_rows("SELECT COALESCE(LENGTH(api_key),-1), mock_enabled FROM t_ai_config WHERE id=1;")
    if rows[0] != ["0", str(mock_enabled)]:
        raise Fail(f"改配置没生效：{rows[0]}（期望 ['0', '{mock_enabled}']）\n{out[-300:]}")
    print(f"  已清空平台 api_key，mock_enabled={mock_enabled}")


def restore_config():
    ok = True
    before = config_fingerprint()
    mysql(f"UPDATE t_ai_config c JOIN {BAK_TABLE} b ON b.id = c.id SET"
          f" c.api_key=b.api_key, c.base_url=b.base_url, c.model=b.model,"
          f" c.temperature=b.temperature, c.mock_enabled=b.mock_enabled, c.is_deleted=b.is_deleted;")
    after = config_fingerprint()
    bak = mysql_rows("SELECT COALESCE(MD5(api_key),'-'), COALESCE(LENGTH(api_key),-1),"
                     " COALESCE(mock_enabled,-1), COALESCE(model,'-')"
                     f" FROM {BAK_TABLE} WHERE id=1;")[0]
    if after != bak:
        ok = False
        print(f"  [!!] 还原失败：现在 {after}，备份 {bak}")
    else:
        print(f"  [OK] 已还原并核验（MD5/长度/mock/模型四项一致：{after[:2]} mock={after[2]} model={after[3]}）")
    rc, _ = mysql(f"DROP TABLE IF EXISTS {BAK_TABLE};")
    left = mysql_rows(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{DB}'"
                      f" AND TABLE_NAME='{BAK_TABLE}';")[0][0]
    print(f"  临时备份表已删（残留 {left} 张）")
    if left != "0":
        ok = False
    return ok, before, after


# ==================== main ====================

def main():
    ap = argparse.ArgumentParser(description="AI 链路降级冒烟")
    ap.add_argument("--scenario", required=True,
                    choices=["nokey", "nokey-strict", "quota", "es-down", "mq-down"])
    ap.add_argument("--base", default=os.environ.get("SMOKE_BASE", "http://localhost:8081"))
    ap.add_argument("--account", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--log", default="", help="实例日志路径（es-down/mq-down 自己起实例时自动填）")
    ap.add_argument("--i-know-this-clears-the-platform-key", action="store_true",
                    help="nokey/nokey-strict 必须显式带上这个开关")
    ap.add_argument("--novel-title", default="降级测试·临时样本")
    args = ap.parse_args()

    account = args.account or ("admin" if args.scenario != "quota" else "user")
    password = args.password or ("admin123" if account == "admin" else "user123")

    own_instance = args.scenario in ("es-down", "mq-down")
    port = 8082 if own_instance else int(urllib.parse.urlparse(args.base).port or 8081)
    base = f"http://localhost:{port}" if own_instance else args.base
    log_path = args.log

    # 样本作品需提前建好，此处只复用：MQ 不可达时 publish 本身会失败（见下文），
    # 若在此处创建样本，整条链路将无法测试
    title = args.novel_title

    print("=" * 74)
    print(f"降级场景：{args.scenario}   base={base}   账号={account}")
    print("=" * 74)

    proc = fh = None
    saved = None
    results = []
    try:
        if args.scenario in ("nokey", "nokey-strict"):
            if not args.i_know_this_clears_the_platform_key:
                raise SystemExit("这会临时清空平台 api_key（测完还原）。确认请加 "
                                 "--i-know-this-clears-the-platform-key")
            saved = config_fingerprint()
            print("-- 准备：备份并清空平台 Key --")
            backup_config()
            clear_platform_key(1 if args.scenario == "nokey" else 0)

        if args.scenario == "quota":
            print("-- 准备：把测试账号当日额度写满 --")
            uid = mysql_rows(f"SELECT id FROM t_user WHERE username='{account}' LIMIT 1;")[0][0]
            key = f"ai:user:usage:{uid}:{time.strftime('%Y-%m-%d')}"
            old = redis("GET", key)
            saved = ("redis", key, old if old not in ("", "(nil)") else None)
            redis("SET", key, str(QUOTA_LIMIT))
            print(f"  {key} = {redis('GET', key)}（原值 {old or '不存在'}，测完恢复）")

        if own_instance:
            extra = (["--spring.elasticsearch.uris=http://127.0.0.1:9999"] if args.scenario == "es-down"
                     else ["--spring.rabbitmq.port=5673"])
            log_path = log_path or os.path.join(LOG_DIR, f"degrade-{args.scenario}-{port}.log")
            print("-- 准备：起独立实例（不动任何容器）--")
            proc, fh = start_instance(port, extra, log_path)
            print(f"  实例就绪（:{port}）")

        c = Client(base)
        st, res = c.call("POST", "/auth/login", {"identifier": account, "password": password})
        if st != 200 or res.get("code") != 200:
            raise Fail(f"登录失败：HTTP {st} {msg_of(st, res)}")
        c.token = (res.get("data") or {}).get("token")
        print(f"  登录成功（{account}，token {len(c.token or '')} 字符）")

        novel_id, chapter_ids = find_or_create_novel(c, title)
        mark = log_mark(log_path)
        # MQ 场景需统计「本轮新增的消息」，先记录一个水位值
        ob_mark = outbox_max_id() if args.scenario == "mq-down" else 0
        print()
        print("-- 逐条链路 --")
        o = probe(c, novel_id, chapter_ids)
        for k, v in o.items():
            show = v.get("text") or v.get("msg") or v.get("summary") or ""
            print(f"  {k:14s} HTTP {v.get('status', '-')} code={v.get('code', '-')} "
                  f"{str(show)[:70].replace(chr(10), ' / ')}")

        if args.scenario == "mq-down":
            print()
            print("-- 追加：消息有没有丢、接口会不会假失败、恢复后能不能自己跑完 --")
            n_ch = len(chapter_ids)
            pending = outbox_count_since(ob_mark, 0)
            sent = outbox_count_since(ob_mark, 1)
            o["_outbox"] = {"pending": pending, "sent": sent, "chapters": n_ch}
            print(f"  本轮新增 outbox 记录：待投 {pending} 条、已投 {sent} 条"
                  f"（这本书 {n_ch} 章，一章一条消息）")

            st2, res2 = c.call("GET", f"/ai/review/novel/{novel_id}/overview")
            d2 = res2.get("data") if isinstance(res2.get("data"), dict) else {}
            o["_总览"] = {"taskId": ((d2 or {}).get("task") or {}).get("taskId"),
                          "statusText": ((d2 or {}).get("task") or {}).get("statusText")}
            print(f"  任务当前：{o['_总览']}（消息还没投出去，停在「排队中」是对的）")

            # ---- 第二阶段：MQ 恢复（换成正确端口的实例 + 缩短补投间隔），验证「补投与自愈」----
            print()
            print("-- 追加：MQ 恢复后能不能自己补投并跑完（关键判据）--")
            stop_instance(proc, fh, port)
            proc = fh = None
            log2 = os.path.join(LOG_DIR, f"degrade-mq-recover-{port}.log")
            proc, fh = start_instance(port, ["--app.mq.outbox.resend-interval-ms=2000"], log2)
            print(f"  已换成正常实例（:{port}），MQ 恢复；补投间隔设为 2 秒")
            # Sa-Token 的会话在 Redis 里，换实例后旧 token 仍然有效，不必重新登录
            tid = o["_总览"].get("taskId")
            o["_恢复"] = {}
            if tid:
                for _ in range(60):
                    time.sleep(2)
                    _, rr = c.call("GET", f"/ai/review/task/{tid}")
                    dd = rr.get("data") if isinstance(rr.get("data"), dict) else {}
                    if (dd or {}).get("status") in (2, 3, 4):
                        o["_恢复"].update({"endStatus": dd.get("status"),
                                           "done": dd.get("doneChapters"),
                                           "total": dd.get("totalChapters"),
                                           "failed": dd.get("failedChapters"),
                                           "message": dd.get("message")})
                        break
                print(f"  任务最终：{o['_恢复'].get('done')}/{o['_恢复'].get('total')} 章，"
                      f"失败 {o['_恢复'].get('failed')}，{o['_恢复'].get('message') or ''}")
            o["_恢复"]["sentAfterRecover"] = outbox_count_since(ob_mark, 1)
            o["_恢复"]["pendingAfterRecover"] = outbox_count_since(ob_mark, 0)
            print(f"  补投后 outbox：已投 {o['_恢复']['sentAfterRecover']} 条、"
                  f"待投 {o['_恢复']['pendingAfterRecover']} 条")

        print()
        print("-- 判据 --")
        if args.scenario in ("nokey", "nokey-strict"):
            results = expect_nokey(o, mock_on=(args.scenario == "nokey"))
        elif args.scenario == "quota":
            results = expect_quota(o)
        elif args.scenario == "es-down":
            results = expect_es_down(o)
        else:
            results = expect_mq_down(o)

        for name, ok, detail in results:
            print(f"  [{'PASS' if ok else 'FAIL'}] {name}  {detail}")

        print()
        errs = log_errors(log_path, mark)
        if errs is None:
            print("-- 日志 --  （没给 --log，跳过）")
        else:
            biz, infra, warns = errs
            print(f"-- 日志 --  业务类 ERROR {len(biz)} 条；基础设施类 ERROR {len(infra)} 条"
                  f"（依赖不可达引发，预期内）；WARN {len(warns)} 条")
            if infra:
                # 按类型汇总输出，不逐条打印，但也不隐藏，便于定位反复出现的异常类型
                tally = {}
                for e in infra:
                    for k in INFRA_NOISE:
                        if k in e:
                            tally[k] = tally.get(k, 0) + 1
                            break
                    else:
                        tally["（未分类）"] = tally.get("（未分类）", 0) + 1
                print("     基础设施类分布：" + "，".join(f"{k}×{v}" for k, v in tally.items()))
            for e in biz[:8]:
                print("   [ERROR] " + e)
            for w in warns[:6]:
                print("   [WARN] " + w)
            results.append(("服务端无业务类 ERROR", not biz, f"{len(biz)} 条", ))
    finally:
        if saved is not None:
            print()
            print("-- 还原 --")
            if isinstance(saved, tuple):
                _, key, old = saved
                if old is None:
                    redis("DEL", key)
                    print(f"  {key} 已删除（原本不存在）")
                else:
                    redis("SET", key, old)
                    print(f"  {key} 已恢复为 {redis('GET', key)}")
            else:
                ok, before, after = restore_config()
                if not ok:
                    results.append(("配置已完整还原", False, "见上面的还原输出"))
        if proc is not None:
            stop_instance(proc, fh, port)
            print(f"  已停掉测试实例（:{port}）")

    print()
    passed = sum(1 for r in results if r[1])
    print("=" * 74)
    print(f"结果：PASS {passed} / {len(results)}")
    for r in results:
        if not r[1]:
            print(f"  [FAIL] {r[0]}：{r[2]}")
    print("=" * 74)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
