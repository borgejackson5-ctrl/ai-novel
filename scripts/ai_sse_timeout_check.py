# -*- coding: utf-8 -*-
"""SSE 超时（流已开启但一直未结束）这一路径的行为，独立的小型检查脚本。

单独启动一个实例的原因：超时值不能在线修改，需通过参数调到几毫秒，
否则只能等待五分钟。因此该检查不并入 `ai_degrade.py`（其每个场景都需要
样本作品并走完整流程），单独成脚本更简单。

用法：

    # 1) 启动一个把超时调到 1 毫秒的实例（超时值为配置项，此处可用上）
    java -jar backend/target/ai-novel-backend.jar --server.port=8081 \\
         --app.ai-platform-daily-limit=5000 --app.sse.timeout-ms=1 \\
         > /tmp/sse.log 2>&1

    # 2) 执行检查（使用普通用户：管理员走平台 Key、不扣个人额度，无法验证是否退回）
    python scripts/ai_sse_timeout_check.py --log /tmp/sse.log

判据三条：

  1. 两个 SSE 入口都返回 503 与明确文案，而不是 500「系统繁忙」；
  2. 额度退回（超时视为「未交付」，按失败退费口径处理），
     而不是「已扣不退」（后者为「主动停止」的口径）；
  3. 服务端日志 0 条 ERROR。改造前为 4 条 ERROR 加 155 行堆栈，
     其中一条为「调用 AI 接口失败，请检查 Key 与网络」（超时场景却在检查 Key）。
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

RESULTS = []


def call(base, method, path, body=None, token=None, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, str(e)[:140])


def redis_get(key, container="ai-novel-redis", password=None):
    """读取额度计数器。按约定使用 docker exec（本项目中间件均在容器中）"""
    password = password or secrets.redis_password(container)
    try:
        out = subprocess.run(["docker", "exec", container, "redis-cli", "-a", password,
                              "--no-auth-warning", "GET", key],
                             capture_output=True, text=True, timeout=15)
    except Exception as e:
        return None, "读 Redis 失败：%s" % e
    v = (out.stdout or "").strip()
    if v in ("", "(nil)"):
        return 0, None
    try:
        return int(v), None
    except ValueError:
        return None, "计数器不是整数：%r" % v[:40]


def check(name, ok, detail):
    RESULTS.append((name, ok, detail))
    print("  [%s] %-34s %s" % ("PASS" if ok else "FAIL", name, detail))


def main():
    ap = argparse.ArgumentParser(description="检查 SSE 超时那条路（需配合 --app.sse.timeout-ms=1 起实例）")
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--account", default="user", help="要用普通用户：管理员走平台 Key、不扣个人额度")
    ap.add_argument("--password", default="user123")
    ap.add_argument("--log", default="", help="实例日志路径；给了就一并统计 ERROR 条数")
    args = ap.parse_args()
    base = args.base

    st, body = call(base, "POST", "/auth/login",
                    {"identifier": args.account, "password": args.password})
    if st != 200:
        print("登录失败（HTTP %s）：%s\n       账号对吗？默认是 user/user123" % (st, str(body)[:120]))
        return 2
    auth = json.loads(body)["data"]
    tok, uid = auth["token"], auth["userId"]
    key = "ai:user:usage:%s:%s" % (uid, time.strftime("%Y-%m-%d"))
    print("账号 %s（userId=%s）" % (args.account, uid))

    mark = 0
    if args.log and os.path.exists(args.log):
        with open(args.log, "r", encoding="utf-8", errors="replace") as f:
            mark = sum(1 for _ in f)
    print()

    # ---- 入口一：润色（POST + SSE）----
    print("== 润色（SSE）==")
    before, err = redis_get(key)
    if err:
        print("  " + err)
    st, body = call(base, "POST", "/ai/write/polish",
                    {"content": "他走进院子，看见桌上放着一盏灯。灯下压着一张纸。", "mode": "EXPRESS"},
                    token=tok)
    print("  HTTP %s  响应体 = %s" % (st, body[:110]))
    # 实例未把超时调短时会成功，此处明确提示，避免与正常「生成成功」混淆
    check("超时 → HTTP 503", st == 503,
          "HTTP %s%s" % (st, "" if st == 503 else "（这个实例的超时不是 1 毫秒？见文件头的启动命令）"))
    check("提示是人话", "等太久" in body, body[:56])
    time.sleep(4)
    after, _ = redis_get(key)
    check("额度退回（按失败退，不是扣着不退）", after is not None and after == before,
          "%s → %s" % (before, after))

    # ---- 入口二：起名/简介的流式版（GET + SSE）----
    print()
    print("== 流式起名/简介（另一个 SSE 入口）==")
    before2, _ = redis_get(key)
    st, body = call(base, "GET", "/ai/generate/stream?type=INTRO&input="
                    + urllib.parse.quote("一个瓷匠回到镇上重开旧窑的故事"), token=tok)
    print("  HTTP %s  响应体 = %s" % (st, body[:110]))
    check("超时 → HTTP 503", st == 503, "HTTP %s" % st)
    time.sleep(4)
    after2, _ = redis_get(key)
    check("额度退回", after2 is not None and after2 == before2, "%s → %s" % (before2, after2))

    # ---- 日志 ----
    if args.log:
        print()
        print("== 服务端日志 ==")
        errors, warns = [], []
        try:
            with open(args.log, "r", encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    if i <= mark:
                        continue
                    if " ERROR " in line:
                        errors.append(line.strip()[:180])
                    elif " WARN " in line:
                        warns.append(line.strip()[:180])
            print("  ERROR %d 条，WARN %d 条" % (len(errors), len(warns)))
            for e in errors[:6]:
                print("   [ERROR] " + e)
            for w in warns[:4]:
                print("   [WARN] " + w)
            # 改造前此处为 4 条 ERROR（即「调用 AI 接口失败，请检查 Key 与网络」这一条）
            check("日志 0 条 ERROR", not errors, "%d 条" % len(errors))
        except OSError as e:
            print("  读日志失败：%s" % e)
    else:
        print()
        print("（没给 --log，跳过日志检查。**建议带上**：这次修的就是日志里那两条误导的 ERROR）")

    bad = [r for r in RESULTS if not r[1]]
    print()
    print("结果：PASS %d / %d%s" % (len(RESULTS) - len(bad), len(RESULTS),
                                  ("，FAIL：" + ", ".join(r[0] for r in bad)) if bad else "，全绿"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
