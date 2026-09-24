#!/usr/bin/env python3
"""评测一条命令：起依赖 -> 起评测实例 -> 建/复用样本 -> 跑样本集 -> 出报告。

为什么要有这个包装：以前每跑一轮要手工做四五件事 —— 起四个中间件、把 jar 打出来、
起 8081 实例（还得记得改平台日调用上限）、盯评测账号的额度、跑 dev 再跑留出集。
其中任何一步忘了或者做错，结果不是报错，而是**指标悄悄变差**（最典型的是拿旧 jar 跑：
代码改了没重编，报告里却写着"改完更准了"）。所以这里把这几步都变成脚本的检查项。

用法：
    python eval/run_all.py --label after-fix
    python eval/run_all.py --label after-fix --cases review-cases
    python eval/run_all.py --label after-fix --reset-quota --vs after-fix-a
    python eval/run_all.py --label x --skip-run          # 只把环境起好并打印将执行的命令

它做的事，顺序固定（任一步失败就停，不往下跑）：
    1. 四个中间件容器：不在跑就 docker start，等就绪
    2. jar：不存在、或**比任一 .java 旧**就重新 package（防"测的是旧代码"）
    3. 8081 实例：没在跑就按评测参数起一个，日志落到 --log；已在跑则复用并提醒日志问题
    4. 样本：reports/.cases-map-<样本集>.json 不存在就先 setup
    5. 逐样本集跑 review_eval.py run（额度预检、完整性判定都在它里面）
    6. 汇总：每个 label 的报告路径 + 是否完整；给了 --vs 就再跑一次 compare
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EVAL_PY = os.path.join(HERE, "review_eval.py")
REPORTS = os.path.join(HERE, "reports")

# 本机 maven 的坑：`bin/mvn`（无扩展名的 POSIX 脚本）跑不起来，报
# ClassNotFoundException: plexus.classworlds.launcher.Launcher；`bin/mvn.cmd` 正常。
# 而 subprocess 传 ["mvn"] 是直接 FileNotFoundError（Windows 的 CreateProcess
# 不把 .cmd 当可执行文件找）。shutil.which("mvn") 正好返回 .CMD 那一个，所以用它。
MVN = shutil.which("mvn") or shutil.which("mvn.cmd")

CONTAINERS = ["ai-novel-mysql", "ai-novel-redis", "ai-novel-rabbitmq", "ai-novel-es"]
# 评测实例的日志放存档目录，别扔 %TEMP%（它随时会被清，而 rescore --log 还要回头看）
DEFAULT_LOG_DIR = r"D:\WorkBuddySave\ai-novel\logs"
DEFAULT_CASES = "review-cases,holdout"


def sh(cmd, **kw):
    """跑一条命令，返回 (returncode, stdout+stderr)。脚本要自己判成败，不抛出。"""
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", **kw)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def step(n, text):
    print()
    print("=" * 64)
    print(f"[{n}] {text}")
    print("=" * 64)


# ==================== 1. 中间件 ====================

def ensure_containers():
    step(1, "中间件容器")
    code, out = sh(["docker", "ps", "-a", "--format", "{{.Names}}\t{{.Status}}"])
    if code != 0:
        raise SystemExit("docker 不可用：\n" + out[:400])
    state = {}
    for line in out.splitlines():
        if "\t" in line:
            name, status = line.split("\t", 1)
            state[name.strip()] = status.strip()

    todo = []
    for c in CONTAINERS:
        st = state.get(c)
        if st is None:
            raise SystemExit(f"容器 {c} 不存在 —— 先 docker compose up -d 建一次")
        if not st.startswith("Up"):
            todo.append(c)
        print(f"  {c:22s} {st}")
    if todo:
        print(f"  起：{' '.join(todo)}")
        code, out = sh(["docker", "start"] + todo)
        if code != 0:
            raise SystemExit("docker start 失败：\n" + out[:400])

    # ES 最慢（启动期约 60 秒），单独等到它真的应答
    deadline = time.time() + 120
    while time.time() < deadline:
        code, out = sh(["docker", "exec", CONTAINERS[3], "curl", "-fs",
                        "http://localhost:9200/_cluster/health"])
        if code == 0:
            print("  ES 已应答")
            return
        time.sleep(3)
    print("  [警告] ES 等了 120 秒还没应答，继续往下走（后面检索那层会退化）")


# ==================== 2. jar ====================

def jar_path():
    return os.path.join(ROOT, "backend", "target", "ai-novel-backend.jar")


def ensure_jar(force_build, no_build):
    step(2, "后端 jar")
    jar = jar_path()
    exists = os.path.exists(jar)
    if exists:
        jar_m = os.path.getmtime(jar)
        newest, newest_f = 0, ""
        # ⚠️ 不能只看 .java：配置也是打进 jar 的。改了 application.yaml 而不重编，
        # 后果和「拿旧 jar 跑」一模一样 —— 报告写着「改完更准了」，跑的却是旧配置。
        # 2026-09-19 踩到（改 app.rag.max-context-chunks 时，run_all 判定「不用重编」）。
        # 只看 src/main：测试代码不进 jar。
        main_src = os.path.join(ROOT, "backend", "src", "main")
        for pattern in ("**/*.java", "**/*.yaml", "**/*.yml", "**/*.xml", "**/*.sql", "**/*.properties"):
            for f in glob.glob(os.path.join(main_src, pattern), recursive=True):
                m = os.path.getmtime(f)
                if m > newest:
                    newest, newest_f = m, f
        stale = newest > jar_m
        print(f"  jar 存在（{time.strftime('%m-%d %H:%M', time.localtime(jar_m))}）")
        if stale:
            print("  [关键] 有源文件比 jar 新：" + os.path.relpath(newest_f, ROOT))
            print("          —— 不重编的话，这一轮测的是旧代码/旧配置，而报告会写着「改完更准了」")
    else:
        stale = True
        print("  jar 不存在")

    if not (stale or force_build):
        print("  不重编（--build 可强制）")
        return jar
    if no_build:
        raise SystemExit("jar 需要重编，但给了 --no-build")
    if not MVN:
        raise SystemExit("PATH 里找不到 mvn（本机要用 mvn.cmd，无扩展名的那个跑不起来）")
    print(f"  正在打包（{os.path.basename(MVN)} clean package，约 1~2 分钟，含单测）…")
    code, out = sh([MVN, "-q", "clean", "package"], cwd=os.path.join(ROOT, "backend"))
    if code != 0:
        tail = "\n".join(out.strip().splitlines()[-25:])
        raise SystemExit("打包失败：\n" + tail)
    print("  打包完成")
    return jar


# ==================== 3. 评测实例 ====================

def port_busy(port):
    code, out = sh(["netstat", "-ano"])
    for line in out.splitlines():
        if f":{port} " in line and "LISTEN" in line.upper():
            return True
    return False


def wait_ready(port, timeout=180):
    # ⚠️ 路径要用**真实存在**的那个：`/novel` 是 404（作品列表的真实路径是 `/novel/page`）。
    # 原来用 `/novel?pageNum=1` 靠「有应答就算就绪」也能工作（401/404 都算），
    # 但会在实例日志里留下一条误导性的「资源不存在」—— 2026-09-19 查请求体异常时才发现。
    url = f"http://127.0.0.1:{port}/novel/page?pageNum=1"
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as resp:
                return True                     # 2xx 也算就绪
        except urllib.error.HTTPError:
            return True                         # 401 就是起来了（需要登录而已）
        except Exception:
            time.sleep(3)
    return False


def ensure_instance(port, log_dir, platform_limit, app_args=()):
    step(3, f"评测实例（:{port}）")
    if port_busy(port):
        print(f"  [警告] :{port} 已有实例在跑 —— 复用它，不重启。")
        print("         后果：拿不到这次启动的日志（工具调用统计会缺）；--app-arg 也不会生效 —— 它是启动参数")
        if not wait_ready(port, 20):
            raise SystemExit(f":{port} 被占用但不应答")
        return None, None

    os.makedirs(log_dir, exist_ok=True)
    log = os.path.join(log_dir, f"eval-{port}-{time.strftime('%Y%m%d-%H%M%S')}.log")
    # 限流默认关掉。评测脚本是**受控的压测客户端**：一串章节背靠背地挨个调，
    # 而 2026-09-22 加上的接口限流（@RateLimit）会把它当洪水打 —— 表现是后半本书整片
    # 429「操作过于频繁」，报告变成 complete=false，而**看上去只像「模型变差了」**
    # （2026-09-22 实测：holdout 的 h11/h12 直接失败，章内召回 0.88→0.73 是假跌）。
    # 真实用户不会这么打，所以这是评测侧的噪声，不是被测行为。
    cmd = ["java", "-jar", jar_path(), f"--server.port={port}",
           f"--app.ai-platform-daily-limit={platform_limit}",
           "--app.rate-limit.enabled=false"] + list(app_args)
    print("  " + " ".join(cmd))
    print("  日志 -> " + log)
    with open(log, "wb") as fh:
        proc = subprocess.Popen(cmd, cwd=ROOT, stdout=fh, stderr=subprocess.STDOUT)
    if not wait_ready(port):
        print("  [失败] 90 秒内没起来，日志尾部：")
        print(tail_of(log, 25))
        proc.terminate()
        raise SystemExit("评测实例起不来")
    print(f"  已就绪（pid={proc.pid}）")
    return proc, log


def tail_of(path, n=30):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return "\n".join(f.read().splitlines()[-n:])
    except Exception as e:
        return f"（读不到日志：{e}）"


# ==================== 4/5. 跑样本集 ====================

def needs_setup(cases):
    """样本作品要用的时候才建：map 不存在，或者**样本集扩过章、map 里缺对应的章**。

    只看「map 文件在不在」是不够的 —— 扩样本集（holdout 2 章 → 12 章）时 map 是旧的，
    照着跑会在取 chapterId 时报 KeyError（或者更糟：静默少跑几章）。
    """
    p = os.path.join(REPORTS, f".cases-map-{cases}.json")
    if not os.path.exists(p):
        return True
    try:
        with open(p, encoding="utf-8") as f:
            mapped = set((json.load(f).get("chapters") or {}).keys())
        with open(os.path.join(HERE, "cases", f"{cases}.json"), encoding="utf-8") as f:
            want = {c["id"] for c in json.load(f)["chapters"]}
        missing = want - mapped
        if missing:
            print(f"  {cases}：map 里缺 {len(missing)} 章（{','.join(sorted(missing))}），需要补建")
        return bool(missing)
    except Exception as e:
        print(f"  {cases}：读 map 失败（{e}），按需要重建处理")
        return True


def ensure_samples(cases, env):
    """建/补样本作品（幂等：setup 自己按 map 判断补哪几章）"""
    code, out = sh([sys.executable, EVAL_PY, "setup", "--cases", cases], cwd=ROOT, env=env)
    print(out.rstrip())
    return code == 0


def run_cases(labels, mode, log, env, reset_quota, allow_low):
    step(4, f"跑样本集（{mode}）")
    done = []
    for label, cs in labels:
        print()
        print(f"--- {cs} -> label={label} ---")
        cmd = [sys.executable, EVAL_PY, "run", "--label", label,
               "--cases", cs, "--mode", mode]
        if log:
            cmd += ["--log", log]
        if reset_quota:
            cmd += ["--reset-quota"]
        if allow_low:
            cmd += ["--allow-low-quota"]
        code, out = sh(cmd, cwd=ROOT, env=env)
        print(out.rstrip())
        if code != 0:
            print(f"  [失败] {cs} 这一轮没跑完（exit={code}），后面不再继续")
            return done, False
        done.append(label)
    return done, True


# ==================== 6. 汇总 ====================

def summarize(labels):
    step(5, "汇总")
    rows = []
    for label in labels:
        p = os.path.join(REPORTS, f"{label}.json")
        if not os.path.exists(p):
            rows.append((label, "没有 JSON", "", ""))
            continue
        with open(p, encoding="utf-8") as f:
            r = json.load(f)
        state = "完整" if r.get("complete", True) else "不完整（数字别用）"
        def hit(block):
            if not block:
                return ""
            h = sum(v.get("hit", 0) for k, v in block.items() if not k.startswith("_"))
            d = sum(v.get("defects", 0) for k, v in block.items() if not k.startswith("_"))
            return f"{h}/{d}"
        rows.append((label, state, hit(r.get("single")), hit(r.get("task"))))
    print(f"  {'label':28s} {'状态':16s} {'单章命中':>10s} {'全文命中':>10s}")
    for r in rows:
        print(f"  {r[0]:28s} {r[1]:16s} {r[2]:>10s} {r[3]:>10s}")
    print()
    print("  报告（给人看的）：" + "、".join(
        os.path.join("eval/reports", f"{l}.md") for l in labels))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True, help="这一轮的标签，用来命名报告")
    ap.add_argument("--cases", default=DEFAULT_CASES, help=f"逗号分隔，默认 {DEFAULT_CASES}")
    ap.add_argument("--mode", default="both", choices=["single", "task", "both"])
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--platform-limit", type=int, default=500,
                    help="平台日调用上限，评测一轮就会用掉一百多次")
    ap.add_argument("--log", default="", help="指定实例日志路径；不给则自动生成")
    ap.add_argument("--log-dir", default=DEFAULT_LOG_DIR)
    ap.add_argument("--build", action="store_true", help="即使 jar 不旧也重编")
    ap.add_argument("--no-build", action="store_true", help="绝不重编（jar 旧就报错退出）")
    ap.add_argument("--reset-quota", action="store_true", help="跑前清零评测账号当日字数计数")
    ap.add_argument("--allow-low-quota", action="store_true")
    ap.add_argument("--vs", default="", help="跑完再和这个 label 比一次（review_eval compare）")
    ap.add_argument("--keep", action="store_true", help="跑完不停实例（默认停掉自己起的）")
    ap.add_argument("--skip-run", action="store_true", help="只起环境、打印将执行的命令")
    ap.add_argument("--app-arg", action="append", default=[], metavar="--x=y",
                    help="透传给评测实例的 Spring 参数，可重复。做隔离组就靠它，"
                         "例如关掉前文注入：--app-arg=--app.rag.max-context-chunks=0")
    args = ap.parse_args()

    cases_list = [c.strip() for c in args.cases.split(",") if c.strip()]
    # 标签约定沿用已有报告：dev 用 --label 本身，其余样本集加后缀
    labels = [(args.label, cases_list[0])] + [(f"{args.label}-{c}", c) for c in cases_list[1:]]

    env = dict(os.environ)
    env["EVAL_BASE"] = f"http://127.0.0.1:{args.port}"

    ensure_containers()
    ensure_jar(args.build, args.no_build)
    proc, own_log = ensure_instance(args.port, args.log_dir, args.platform_limit, args.app_arg)
    log = args.log or own_log or ""

    print()
    if args.app_arg:
        print("本轮实例的额外启动参数（做隔离组用）：" + " ".join(args.app_arg))
    print("将执行的命令：")
    for label, cs in labels:
        print(f"  python eval/review_eval.py run --label {label} --cases {cs} --mode {args.mode}"
              + (f" --log {log}" if log else ""))
    if args.vs:
        print(f"  python eval/review_eval.py compare --label {args.vs} --label-b {labels[0][0]}")

    if args.skip_run:
        print("\n[--skip-run] 到此为止。")
    else:
        print()
        print("[提示] 样本作品不存在（或扩过章）就先 setup —— setup 是幂等的，只补缺的章")
        for _, cs in labels:
            if needs_setup(cs):
                print(f"  建/补样本：{cs}")
                if not ensure_samples(cs, env):
                    raise SystemExit(f"setup 失败：{cs}")
        done, ok = run_cases(labels, args.mode, log, env,
                             args.reset_quota, args.allow_low_quota)
        summarize(done)
        if not ok:
            print("\n有一轮没跑完 —— 先看上面那轮的失败原因，不要拿半截数字做对比。")
        elif args.vs:
            print()
            step(6, f"与 {args.vs} 对比")
            code, out = sh([sys.executable, EVAL_PY, "compare",
                            "--label", args.vs, "--label-b", labels[0][0]], cwd=ROOT, env=env)
            print(out.rstrip())

    if proc is not None and not args.keep:
        print()
        print("停掉本次起的评测实例（--keep 可保留）")
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
