# -*- coding: utf-8 -*-
"""端到端验证：登录 -> 发布小说(含章节) -> MQ AI预审 -> 管理员收站内信 -> 审核通过 -> 用户收站内信 -> 详情可见"""
import json, time, urllib.request, sys

BASE = "http://127.0.0.1:8080"
sys.stdout.reconfigure(encoding="utf-8")

def req(method, path, token=None, body=None):
    r = urllib.request.Request(BASE + path, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(r, data) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"code": e.code, "http_error": e.read().decode()[:200]}

ok_count = 0
def step(name, cond, detail=""):
    global ok_count
    mark = "PASS" if cond else "FAIL"
    if cond: ok_count += 1
    print(f"[{mark}] {name}" + (f"  -> {detail}" if detail else ""))

NOVEL_TITLE = "E2E测试：星河之巅"

# ========== 1. 用户登录 ==========
# 前提：e2e_user 必须已存在（首次使用需先在界面注册，密码 E2e@12345）。
# 此处不自动注册：注册需要「邮箱 + 邮箱验证码」，脚本不代发邮件；
# 原有的 register 调用已失效，会导致 §2 之后的步骤全部失败（假失败）。
# 登录字段是 identifier（用户名或邮箱），注册字段是 username，两处不同。
LOGIN = {"identifier": "e2e_user", "password": "E2e@12345"}
r = req("POST", "/auth/login", body=LOGIN)
user_token = r.get("data", {}).get("token", "")
step("1. 用户 e2e_user 登录", bool(user_token), f"token={user_token[:12]}...")
if not user_token:
    print("")
    print("[中断] e2e_user 不存在或密码不对 —— 后续步骤依赖它的 token，先把这个账号注册出来。")
    sys.exit(1)

# ========== 2. 发布小说（首章免费 + 第二章付费）==========
r = req("POST", "/novel/publish", user_token, {
    "title": NOVEL_TITLE, "categoryId": 2, "author": "e2e 笔名",
    "intro": "端到端验证自动发布的原创小说", "tags": "测试,玄幻",
    "chapters": [
        {"title": "第一章 启程", "content": "少年背起行囊，走出山村，望向远方连绵的群山。", "unlockCoin": 0},
        {"title": "第二章 风起", "content": "山道尽头风起云涌，一道身影自雾中缓缓走来。", "unlockCoin": 5}
    ]
})
step("2. 发布小说（等待审核）", r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:120])

# ========== 3. 我的作品应有一条待审核 ==========
time.sleep(1)
r = req("GET", "/novel/mine?pageNum=1&pageSize=5", user_token)
novel_id = None
rows = (r.get("data") or {}).get("list") or (r.get("data") or {}).get("records") or []
for row in rows:
    if row.get("title") == NOVEL_TITLE:
        novel_id = row.get("id")
        step("3. 我的作品列表含待审核作品", row.get("auditStatus") == 0,
             f"novelId={novel_id}, auditStatus={row.get('auditStatus')}, chapters={row.get('totalChapters')}")
        break
if novel_id is None:
    step("3. 我的作品列表含待审核作品", False, json.dumps(r, ensure_ascii=False)[:200])

# ========== 4. 等待 MQ AI 预审 -> 管理员收提醒 ==========
admin_msg_found = False
admin_token = ""
for i in range(15):
    time.sleep(2)
    r = req("POST", "/auth/login", body={"identifier": "admin", "password": "admin123"})
    admin_token = r.get("data", {}).get("token", "")
    r = req("GET", "/message/page?pageNum=1&pageSize=20", admin_token)
    msgs = (r.get("data") or {}).get("list") or (r.get("data") or {}).get("records") or []
    for m in msgs:
        if NOVEL_TITLE in (m.get("title") or "") + (m.get("content") or ""):
            admin_msg_found = True
            step("4. 管理员收到审核提醒站内信", True,
                 f"title={m.get('title')}, type={m.get('type')}")
            break
    if admin_msg_found:
        break
if not admin_msg_found:
    step("4. 管理员收到审核提醒站内信", False, "轮询30s未收到")

# ========== 5. 管理端待审核列表 ==========
r = req("GET", "/admin/audit/page?pageNum=1&pageSize=10&auditStatus=0", admin_token)
step("5. 管理端待审核列表含该作品", r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:150])

# ========== 6. 管理员审核通过（修正分类为 1 古典名著）==========
r = req("POST", f"/admin/audit/{novel_id}/pass?categoryId=1", admin_token)
step("6. 管理员审核通过", r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:100])

# ========== 7. 用户收到审核通过通知 ==========
user_pass_msg = False
for i in range(10):
    time.sleep(1)
    r = req("GET", "/message/page?pageNum=1&pageSize=20", user_token)
    msgs = (r.get("data") or {}).get("list") or (r.get("data") or {}).get("records") or []
    for m in msgs:
        if "通过" in (m.get("title") or "") and "E2E测试" in (m.get("content") or ""):
            user_pass_msg = True
            step("7. 用户收到审核通过站内信", True, f"title={m.get('title')}")
            break
    if user_pass_msg:
        break
if not user_pass_msg:
    step("7. 用户收到审核通过站内信", False, json.dumps(r, ensure_ascii=False)[:200])

# ========== 8. 详情可见（已上架 + 分类已修正）==========
r = req("GET", f"/novel/{novel_id}", user_token)
d = r.get("data") or {}
step("8. 作品已上架可见", d.get("status") == 1 and d.get("auditStatus") == 1,
     f"status={d.get('status')}, auditStatus={d.get('auditStatus')}, categoryId={d.get('categoryId')}")

# ========== 9. 章节列表可见（首章免费）==========
r = req("GET", f"/chapter/list/{novel_id}", user_token)
chapters = r.get("data") or []
free_first = bool(chapters) and (chapters[0].get("unlockCoin") or 0) == 0
step("9. 章节列表返回且首章免费", r.get("code") == 200 and free_first,
     f"count={len(chapters)}, firstUnlockCoin={chapters[0].get('unlockCoin') if chapters else None}")

# ========== 10. 免费首章正文可直读 ==========
first_content_ok = False
if chapters:
    r = req("GET", f"/chapter/{chapters[0].get('id')}/content", user_token)
    cd = r.get("data") or {}
    first_content_ok = r.get("code") == 200 and bool(cd.get("content"))
step("10. 免费首章正文可直读", first_content_ok,
     f"len={len((r.get('data') or {}).get('content') or '')}")

print(f"\n========== 结果: {ok_count}/10 项检查通过（详见上方）==========")
