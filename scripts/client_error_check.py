# -*- coding: utf-8 -*-
"""「调用方写错」的请求，接口返回的是否是应有的状态码？

需要它的原因：全局异常处理中只要存在一条 `@ExceptionHandler(Exception.class)` 兜底，
任何未被显式处理的标准异常都会变成 500「系统繁忙」。原因不在于继承关系，而在于解析器的
注册顺序：advice 的解析器排在 Spring 自带的 `DefaultHandlerExceptionResolver` 之前，
而链的契约是「返回 null 才交给后面的解析器」，兜底那条始终返回响应，链在此处中断。

后果是排查方向被误导：调用方认为服务端故障，实际是自身请求写错。
曾出现两次该问题（把流式起名的 GET 发成 POST，返回 500；curl 中中文按 GBK 发送，
请求体不是合法 UTF-8，同样返回 500），排查后确认为八种情形全部命中。

    python scripts/client_error_check.py                      # 请求 localhost:8081
    python scripts/client_error_check.py --base http://localhost:8080 --log <实例日志>

判据：八种写错的请求都返回 4xx（400/415/406），且既有语义（业务 400 / 未登录 401 /
方法 405 / 确实不存在 404）均未被波及。指定 `--log` 时还会检查日志中是否存在 ERROR。

两个已知的预期内噪声，不作为故障：

1. `Accept: application/xml` 那条，Spring 自身会输出一条 WARN 加完整堆栈
   （`Failure in @ExceptionHandler ...`），因为连错误响应的 JSON body 也无法写出。
   状态码是正确的（406），只是日志多出一屏。要消除需在 handler 中解析 Accept 头，成本大于收益。
2. 每条被拒的请求都会在日志中留下一行 WARN（由 `GlobalExceptionHandler` 记录），这是设计如此：
   「调用方写错」需要留痕，但不属于 ERROR。
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

RESULTS = []


def make_client(base):
    def call(method, path, body=None, ctype="application/json", token=None, accept=None):
        if body is None:
            data = None
        elif isinstance(body, bytes):
            data = body
        else:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {}
        if ctype:
            headers["Content-Type"] = ctype
        if accept:
            headers["Accept"] = accept
        if token:
            headers["Authorization"] = token
        req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"_raw": raw[:160]}
        except Exception as e:
            return None, {"_err": "%s: %s" % (type(e).__name__, str(e)[:120])}

    return call


def verdict(name, ok, detail):
    """不涉及 HTTP 响应的判据（例如「日志里没有 ERROR」）"""
    RESULTS.append((name, ok, detail))
    print("  [%s] %-38s %s" % ("PASS" if ok else "FAIL", name, detail))


def check(name, expected, st, res):
    msg = res.get("msg") or res.get("message") or res.get("_raw") or res.get("_err") or ""
    exp = expected if isinstance(expected, (tuple, list, set)) else (expected,)
    ok = st in exp
    RESULTS.append((name, ok, "HTTP %s" % st))
    print("  [%s] %-38s 期望 %-8s 实际 %-4s  %s"
          % ("PASS" if ok else "FAIL", name, "/".join(str(e) for e in exp), st, str(msg)[:44]))


def main():
    ap = argparse.ArgumentParser(description="客户端错误请求的状态码语义检查")
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--account", default="admin")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--log", default="", help="实例日志路径；给了就一并查有没有 ERROR")
    args = ap.parse_args()
    call = make_client(args.base)

    st, res = call("POST", "/auth/login", {"identifier": args.account, "password": args.password})
    tok = (res.get("data") or {}).get("token")
    print("目标 %s   登录 %s：HTTP %s" % (args.base, args.account, st))
    if not tok:
        print("  登录没拿到 token —— 后面的用例大多要登录，先把它弄通")
        return 2

    mark = 0
    if args.log:
        try:
            with open(args.log, "r", encoding="utf-8", errors="replace") as f:
                mark = sum(1 for _ in f)
        except OSError as e:
            print("  读日志失败：%s" % e)

    print()
    print("== 一、请求体坏了（HttpMessageNotReadableException）==")
    check("JSON 语法错（截断）", 400, *call("POST", "/auth/login",
                                            b'{"identifier":"admin","password":"admin123"'))
    gbk = "实验测试".encode("gbk")
    check("非法 UTF-8（GBK 中文）", 400, *call("POST", "/auth/login",
                                               b'{"identifier":"' + gbk + b'","password":"x"}'))
    check("字段类型不对（对象给字符串字段）", 400, *call("POST", "/auth/login",
                                                         b'{"identifier":{"a":1},"password":"x"}'))
    check("空 body", 400, *call("POST", "/auth/login", b""))
    # 流式接口：body 错误发生在开流之前，因此仍可正常返回 400（而不是半开的 200 流）
    check("SSE 接口：请求体坏", 400, *call("POST", "/ai/write/continue", b'{"content":', token=tok))

    print()
    print("== 二、Content-Type / Accept 不对 ==")
    check("application/json 接口发 text/plain", 415,
          *call("POST", "/auth/login", '{"identifier":"admin"}', ctype="text/plain"))
    check("Accept 只要 XML", 406,
          *call("POST", "/auth/login", {"identifier": args.account, "password": args.password},
                accept="application/xml"))
    # 两者都不对：415 那条 handler 需写出 JSON body，而 body 又要按 Accept 协商一次并失败。
    # 期望仍属于协商失败那一类，不能是 404（否则会将「协商失败」表述为「资源不存在」）。
    check("Accept + Content-Type 都不对", (406, 415),
          *call("POST", "/auth/login", "{}", ctype="text/plain", accept="application/xml"))

    print()
    print("== 三、查询参数 / 路径变量写错 ==")
    check("缺必填查询参数", 400, *call("GET", "/ai/generate/stream", None, ctype=None, token=tok))
    check("路径变量类型不对（id=abc）", 400, *call("POST", "/ai/review/chapter/abc", None, token=tok))
    check("查询参数类型不对", 400, *call("POST", "/novel/vector-search?novelId=abc&q=x", None, token=tok))

    print()
    print("== 四、对照：既有语义不能被改动波及 ==")
    check("业务异常仍是 400", 400,
          *call("POST", "/auth/login", {"identifier": "没有这个人", "password": "x"}))
    check("未登录仍是 401", 401, *call("POST", "/ai/generate", {"type": "TITLE", "input": "x"}))
    check("方法用错仍是 405", 405,
          *call("POST", "/ai/generate/stream?type=TITLE&input=x", None, token=tok))
    check("真不存在的接口仍是 404", 404,
          *call("GET", "/ai/no-such-endpoint", None, ctype=None, token=tok))

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
                        errors.append(line.strip()[:170])
                    elif " WARN " in line:
                        warns.append(line.strip()[:170])
            print("  ERROR %d 条，WARN %d 条（WARN 是预期的：每条被拒的请求留一行痕）"
                  % (len(errors), len(warns)))
            for e in errors[:6]:
                print("   [ERROR] " + e)
            # 改造前此处每一条都会产生 ERROR 加一页堆栈：调用方写错，日志却报告系统故障
            verdict("日志 0 条 ERROR", not errors, "%d 条 ERROR / %d 条 WARN" % (len(errors), len(warns)))
        except OSError as e:
            print("  读日志失败：%s" % e)

    bad = [r for r in RESULTS if not r[1]]
    print()
    print("结果：PASS %d / %d%s" % (len(RESULTS) - len(bad), len(RESULTS),
                                  ("，FAIL：" + ", ".join(r[0] for r in bad)) if bad else "，全绿"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
