#!/usr/bin/env bash
# 管理后台端到端验证：角色字段 + dashboard + 权限守卫 + 订单筛选
BASE="http://localhost:8080"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ❌ $1"; }

TOK_ADMIN=$(curl -s $BASE/auth/login -X POST -H "Content-Type: application/json" -d '{"identifier":"admin","password":"admin123"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
ROLE_ADMIN=$(curl -s $BASE/auth/login -X POST -H "Content-Type: application/json" -d '{"identifier":"admin","password":"admin123"}' | sed 's/.*"role":"\([^"]*\)".*/\1/')
TOK_USER=$(curl -s $BASE/auth/login -X POST -H "Content-Type: application/json" -d '{"identifier":"user","password":"user123"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
ROLE_USER=$(curl -s $BASE/auth/login -X POST -H "Content-Type: application/json" -d '{"identifier":"user","password":"user123"}' | sed 's/.*"role":"\([^"]*\)".*/\1/')

echo "[1] 登录返回角色字段"
[ "$ROLE_ADMIN" = "admin" ] && ok "admin 登录 role=admin" || bad "admin role 异常: $ROLE_ADMIN"
[ "$ROLE_USER" = "user" ] && ok "user 登录 role=user" || bad "user role 异常: $ROLE_USER"
[ -n "$TOK_ADMIN" ] && [ -n "$TOK_USER" ] || bad "token 为空"

echo "[2] /user/me 带 role"
ME_ROLE=$(curl -s $BASE/user/me -H "Authorization: $TOK_ADMIN" | sed 's/.*"role":"\([^"]*\)".*/\1/')
[ "$ME_ROLE" = "admin" ] && ok "admin /user/me role=admin" || bad "me role 异常: $ME_ROLE"

echo "[3] /admin/dashboard 聚合"
DASH=$(curl -s $BASE/admin/dashboard -H "Authorization: $TOK_ADMIN")
echo "$DASH" | grep -q '"userTotal"' && ok "返回 userTotal" || bad "缺 userTotal: $(echo $DASH | head -c 200)"
echo "$DASH" | grep -q '"hotNovels"' && ok "返回 hotNovels" || bad "缺 hotNovels"
echo "$DASH" | grep -q '"recentOrders"' && ok "返回 recentOrders" || bad "缺 recentOrders"
echo "    摘要: $(echo "$DASH" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('用户',d['userTotal'],'小说',d['novelTotal'],'充值¥',d['rechargeAmount'],'解锁',d['subscribeCount'],'热度Top',len(d['hotNovels']),'近期单',len(d['recentOrders']))" 2>/dev/null)"

echo "[4] 普通用户访问 admin 接口被拒(403)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' $BASE/admin/dashboard -H "Authorization: $TOK_USER")
[ "$CODE" = "403" ] && ok "user 访问 /admin/dashboard -> 403" || bad "期望403 实际 $CODE"

echo "[5] 未登录访问 admin 被拒(401)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' $BASE/admin/dashboard)
[ "$CODE" = "401" ] && ok "无 token 访问 -> 401" || bad "期望401 实际 $CODE"

echo "[6] 用户管理分页含 role 与注册时间"
UPAGE=$(curl -s "$BASE/admin/user/page?pageNum=1&pageSize=5" -H "Authorization: $TOK_ADMIN")
echo "$UPAGE" | grep -q '"role"' && ok "userPage 返回 role" || bad "缺 role: $(echo $UPAGE | head -c 200)"
echo "$UPAGE" | grep -q '"createTime"' && ok "userPage 返回 createTime" || bad "缺 createTime"

echo "[7] 订单筛选 status=1 与 keyword"
R1=$(curl -s "$BASE/admin/order/recharge/page?status=1" -H "Authorization: $TOK_ADMIN")
echo "$R1" | grep -q '"username"' && ok "recharge 返回 username" || bad "缺 username"
S1=$(curl -s "$BASE/admin/order/subscribe/page?status=1&keyword=admin" -H "Authorization: $TOK_ADMIN")
echo "$S1" | grep -q '"list"' && ok "subscribe keyword 筛选可执行" || bad "subscribe 筛选报错: $(echo $S1 | head -c 200)"

echo ""
echo "========= 结果: PASS=$PASS FAIL=$FAIL ========="
[ "$FAIL" -eq 0 ] && echo "ALL GREEN" || echo "HAS FAILURE"
