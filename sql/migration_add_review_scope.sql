-- ============================================================
-- 给 t_ai_review_task 补两列：任务发起时的章号范围
--
-- 增加这两列的原因：
--   全文审查支持「只审最近 N 章 / 指定章号区间」之后，补派发（复用进行中的任务时
--   重新派发未处理完的章）与续跑（resume）无法得知任务范围，两者一直按
--   「整本」取章，导致「最近 1 章」的任务被补派成整本 233 章。
--   1 章的任务执行完成后账面变为 total=1 / done=2，章节表多出一章（第 2 章）的记录。
--
--   NULL 表示不限（整本）：整本任务的行为保持不变，补派发与续跑依然会带上新写的章。
--
-- 幂等：用 information_schema 判断存在后再 ALTER，重复执行不报错
--   （MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS）
-- ============================================================

SET @db := DATABASE();

SET @sql := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE `t_ai_review_task` ADD COLUMN `scope_from_chapter_no` INT DEFAULT NULL COMMENT ''发起范围的下界章号（含）；NULL=不限（整本）'' AFTER `novel_title`',
              'SELECT ''scope_from_chapter_no 已存在，跳过'' AS skipped')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_ai_review_task' AND COLUMN_NAME = 'scope_from_chapter_no'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE `t_ai_review_task` ADD COLUMN `scope_to_chapter_no` INT DEFAULT NULL COMMENT ''发起范围的上界章号（含）；NULL=不限（整本）'' AFTER `scope_from_chapter_no`',
              'SELECT ''scope_to_chapter_no 已存在，跳过'' AS skipped')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_ai_review_task' AND COLUMN_NAME = 'scope_to_chapter_no'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 末尾输出一行结果，以区分「跳过」与「未生效」
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_ai_review_task'
  AND COLUMN_NAME IN ('scope_from_chapter_no', 'scope_to_chapter_no');
