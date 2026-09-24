#!/usr/bin/env bash
# ============================================================
# 导出公版书种子数据
#
# 解决的问题：
#   本地后台导入的公版书（书籍 + 章节正文）只存在于本地 MySQL，
#   不会自动进入 Git；服务器使用另一个库，默认为空，因此线上看不到这些作品。
#   执行本脚本将作品数据导出为 sql/seed_novels.sql.gz，提交后由 MySQL 容器在
#   首次初始化时自动执行（见 docker-compose.prod.yml 的 entrypoint 挂载）。
#
# 采用 .gz 的原因：
#   几十本古典小说的正文接近 90MB 纯文本，直接提交会使仓库体积过大（重复导出还会
#   持续累积历史体积）。压缩后约 39MB。MySQL 官方镜像的初始化脚本原生支持
#   .sql / .sql.gz / .sql.bz2 / .sql.xz / .sql.zst，无需额外处理。
#
# 用法（在项目根目录）：
#   MYSQL_PASSWORD='你的数据库密码' bash scripts/export-seed.sh
#
# 仅导出「作品数据」：
#   t_novel    书籍（书名 / 作者 / 分类 / 封面 / 定价 / 状态）
#   t_chapter  章节（标题 + 正文 + 字数 + 解锁币）
# 不导出用户 / 订单 / 阅读记录 / 评论，这些为运行时数据，不属于种子数据。
# 分类（t_category）由 sql/init.sql 负责，这里不重复导出。
#
# 幂等：导出用 REPLACE INTO，重复执行不会导致数据翻倍。
# ============================================================
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-ai-novel-mysql}"
DB="${MYSQL_DATABASE:-ai_drama}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:?请提供数据库密码：MYSQL_PASSWORD='xxx' bash scripts/export-seed.sh}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_FILE="$ROOT_DIR/sql/seed_novels.sql.gz"
PLAIN_FILE="$(mktemp)"
BODY_FILE="$(mktemp)"
trap 'rm -f "$PLAIN_FILE" "$BODY_FILE"' EXIT

echo "容器：$CONTAINER    数据库：$DB"

# 前置检查：确认容器可访问
if ! docker exec "$CONTAINER" true >/dev/null 2>&1; then
  echo "✗ 连不上容器 $CONTAINER，请确认 MySQL 容器已启动（docker ps）" >&2
  exit 1
fi

count_of() {
  docker exec "$CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -B \
    -e "SELECT COUNT(*) FROM $DB.$1 WHERE is_deleted = 0" 2>/dev/null || echo '?'
}

BOOKS="$(count_of t_novel)"
CHAPTERS="$(count_of t_chapter)"
echo "本地数据：$BOOKS 本书 / $CHAPTERS 章"

if [ "$BOOKS" = "0" ]; then
  echo "✗ 本地库里一本书都没有，没什么可导出的。先在后台「公版书导入」里传几本。" >&2
  exit 1
fi

echo "正在导出 t_novel / t_chapter ..."
# --no-create-info     不带建表语句（表结构由 init.sql 负责）
# --complete-insert    每行都带列名，后续表新增字段也不会发生列错位
# --replace            生成 REPLACE INTO，重复执行不会数据翻倍
docker exec "$CONTAINER" mysqldump \
  -u"$DB_USER" -p"$DB_PASS" \
  --no-create-info \
  --complete-insert \
  --replace \
  --skip-triggers \
  --single-transaction \
  --skip-add-locks \
  --default-character-set=utf8mb4 \
  "$DB" t_novel t_chapter > "$BODY_FILE"

{
  echo "-- ============================================================"
  echo "-- 公版书种子数据"
  echo "--"
  echo "-- 本文件由 scripts/export-seed.sh 自动生成，请勿手工编辑。"
  echo "-- 改动请走：本地后台导入 / 修改 → 重跑脚本 → 提交。"
  echo "--"
  echo "-- 内容：t_novel（$BOOKS 本）+ t_chapter（$CHAPTERS 章）"
  echo "-- 不含用户 / 订单 / 阅读记录 —— 那些是运行时数据，不是种子。"
  echo "--"
  echo "-- 生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "-- ============================================================"
  echo
  cat "$BODY_FILE"
} > "$PLAIN_FILE"

gzip -9 -c "$PLAIN_FILE" > "$OUT_FILE"

echo
echo "✓ 已生成：sql/seed_novels.sql.gz（$(du -h "$OUT_FILE" | cut -f1)）"
echo
echo "下一步："
echo "  1) 提交并推送："
echo "     git add sql/seed_novels.sql.gz && git commit -m 'data: 补充公版书种子数据' && git push"
echo "  2) 服务器上导入（若数据库已经初始化过，init 脚本不会再自动跑，要手动执行一次）："
echo "     gunzip -c sql/seed_novels.sql.gz | docker exec -i $CONTAINER mysql -u$DB_USER -p'<密码>' $DB"
echo "  3) 重建搜索索引（数据和搜索索引是两套，不重建则搜不到新书）："
echo "     curl -X POST http://<服务器地址>/api/novel/reindex"
