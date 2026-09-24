-- ============================================================
-- 迁移：阅读记录按「用户 + 章节」去重（同一章只保留一条，更新时间戳）
-- 背景：此前每次翻章都 INSERT 一条流水，同一次阅读被反复刷新/重放会产生大量重复行。
--       展示侧（最近阅读）按作品去重取最新，重复行除占用空间外无其他作用。
-- 做法：加唯一索引 (user_id, chapter_id)，写入改为 INSERT ... ON DUPLICATE KEY UPDATE。
-- 幂等：可重复执行（索引已存在时会报 Duplicate key name，可忽略；本脚本先做存在性判断）。
-- 用法（服务器）：
--   docker exec -i ai-novel-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' ai_drama < sql/migration_dedup_read_history.sql
-- ============================================================

-- 1) 先清理存量重复行：每个 (user_id, chapter_id) 只留最新的一条
DELETE h FROM t_read_history h
JOIN (
    SELECT user_id, chapter_id, MAX(id) AS keep_id
    FROM t_read_history
    WHERE chapter_id IS NOT NULL AND is_deleted = 0
    GROUP BY user_id, chapter_id
    HAVING COUNT(*) > 1
) d ON h.user_id = d.user_id AND h.chapter_id = d.chapter_id
WHERE h.id <> d.keep_id AND h.chapter_id IS NOT NULL;

-- 2) 加唯一索引（已存在则跳过）
SET @exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 't_read_history'
      AND index_name = 'uk_user_chapter'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE t_read_history ADD UNIQUE KEY uk_user_chapter (user_id, chapter_id)',
    'SELECT "uk_user_chapter 已存在，跳过" AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
