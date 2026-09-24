-- ============================================================
-- 章序号唯一索引迁移：t_chapter 加 (novel_id, chapter_no) 唯一键
-- 执行方式：mysql -uroot -p ai_drama < migration_add_chapter_no_unique.sql
-- （全新库直接跑 init.sql 已含本唯一键，无需本脚本）
--
-- 背景：addChapter 中 selectMaxChapterNo + 1 非原子操作，并发新增同一本书
-- 可能读到相同 max 并写入相同 chapter_no。本唯一索引兜底（第二个 insert 触发键冲突），
-- 服务层配合有限乐观重试（冲突后重读 max 再插入）。
--
-- 注意：执行前若存在重复的 (novel_id, chapter_no)，会因冲突失败。
-- 存量数据由单线程发布/导入生成，理论上无重复；如有需先手动去重。
-- ============================================================

ALTER TABLE `t_chapter`
  ADD UNIQUE KEY `uk_novel_no` (`novel_id`, `chapter_no`);
