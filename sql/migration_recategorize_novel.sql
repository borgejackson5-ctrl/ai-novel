-- ============================================================
-- 迁移：作品分类重划（6 类 → 7 类）+ 修正归类
--
-- 背景：分类槽位最初按现代网文平台设置（玄幻奇幻 / 言情古风 / 武侠仙侠），
--       但书库实际为 59 部公版古籍 + 1 部原创连载，槽位与内容不匹配：
--         · 「玄幻奇幻」仅挂 1 本，且为《山海经》（上古神话，非玄幻）
--         · 「言情古风」10 本中有 9 本为拟话本 / 世情 / 狭邪，非言情
--         · 「武侠仙侠」混有侠义、公案、历史三种流派
--         · 「历史演义」含 5 部晚清谴责小说
--
-- 调整为 7 类（60 部）：
--   1 古典名著 6 · 2 仙侠修真 1 · 3 侠义公案 14 · 4 历史演义 13
--   5 志怪神魔 11 · 6 世情讽喻 13 · 7 儿女英雄 2
--
-- 要点：
--   · 分类主键不变，仅改名：cover.js 按 id 存配色、ES 存 categoryId，
--     改 id 会连带影响书封配色与索引，因此 id 保持不变，只调整 sort。
--   · 幂等：改名与挪书都带「原值」条件，重复执行第二次影响 0 行；
--     新增分类用 ON DUPLICATE KEY，重复执行不报错。
--   · 不含 USE：目标库由命令行给出（指定错误的库，其影响大于漏执行）。
--
-- 用法（服务器）：
--   docker exec -i ai-novel-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' ai_drama \
--     < sql/migration_recategorize_novel.sql
--
-- 执行后核对（预期 0 / 3 / 1 / 1 / 5 / 2 行，以及每类部数 6/1/14/13/11/13/2）：
--   SELECT c.id, c.name, c.sort, COUNT(n.id) AS cnt
--     FROM t_category c LEFT JOIN t_novel n ON n.category_id = c.id AND n.is_deleted = 0
--    WHERE c.is_deleted = 0 GROUP BY c.id, c.name, c.sort ORDER BY c.sort;
--
-- 回滚（见文件末尾注释）
-- ============================================================

-- ------------------------------------------------------------
-- 1) 改名 + 重排展示顺序
--    sort 决定首页分类 chip 的先后；按部数与题材分量排，
--    「仙侠修真」只有 1 部（原创连载），排到最后。
-- ------------------------------------------------------------
UPDATE `t_category` SET `name` = '古典名著', `sort` = 1, `update_time` = NOW() WHERE `id` = 1;
UPDATE `t_category` SET `name` = '历史演义', `sort` = 2, `update_time` = NOW() WHERE `id` = 4;
UPDATE `t_category` SET `name` = '侠义公案', `sort` = 3, `update_time` = NOW() WHERE `id` = 3;
UPDATE `t_category` SET `name` = '志怪神魔', `sort` = 4, `update_time` = NOW() WHERE `id` = 5;
UPDATE `t_category` SET `name` = '世情讽喻', `sort` = 5, `update_time` = NOW() WHERE `id` = 6;
UPDATE `t_category` SET `name` = '仙侠修真', `sort` = 7, `update_time` = NOW() WHERE `id` = 2;

-- ------------------------------------------------------------
-- 2) 新增「儿女英雄」
-- ------------------------------------------------------------
INSERT INTO `t_category` (`id`, `name`, `parent_id`, `sort`, `status`, `create_time`, `update_time`, `is_deleted`)
VALUES (7, '儿女英雄', 0, 6, 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE `name` = '儿女英雄', `sort` = 6, `status` = 1, `is_deleted` = 0, `update_time` = NOW();

-- ------------------------------------------------------------
-- 3) 挪书
--    每条都带「原分类」条件：题目同名的书在其他分类时不会被误改，
--    重复执行时因 category_id 已变也不会再匹配。
--    不带 is_deleted 条件：逻辑删除的行一并迁移，保证数据一致。
-- ------------------------------------------------------------

-- 3.1 古典名著 → 志怪神魔：聊斋志异 / 封神演义 / 阅微草堂笔记（预期 3 行）
UPDATE `t_novel` SET `category_id` = 5, `update_time` = NOW()
 WHERE `category_id` = 1
   AND `title` IN ('聊斋志异', '封神演义', '阅微草堂笔记');

-- 3.2 玄幻奇幻 → 志怪神魔：山海经（预期 1 行）
UPDATE `t_novel` SET `category_id` = 5, `update_time` = NOW()
 WHERE `category_id` = 2
   AND `title` = '山海经';

-- 3.3 武侠仙侠 → 仙侠修真：邪修天王（原创连载，预期 1 行）
UPDATE `t_novel` SET `category_id` = 2, `update_time` = NOW()
 WHERE `category_id` = 3
   AND `title` = '邪修天王';

-- 3.4 历史演义 → 世情讽喻：晚清谴责小说 5 部（预期 5 行）
UPDATE `t_novel` SET `category_id` = 6, `update_time` = NOW()
 WHERE `category_id` = 4
   AND `title` IN ('官场现形记', '二十年目睹之怪现状', '孽海花', '文明小史', '老残游记');

-- 3.5 言情古风 → 儿女英雄：木兰奇女传 / 儿女英雄传（预期 2 行）
UPDATE `t_novel` SET `category_id` = 7, `update_time` = NOW()
 WHERE `category_id` = 6
   AND `title` IN ('木兰奇女传', '儿女英雄传');

-- ============================================================
-- 回滚（将数据恢复至迁移前；新增的分类 7 逻辑删除即可）
-- ------------------------------------------------------------
-- UPDATE `t_novel` SET `category_id` = 6 WHERE `category_id` = 7
--   AND `title` IN ('木兰奇女传', '儿女英雄传');
-- UPDATE `t_novel` SET `category_id` = 4 WHERE `category_id` = 6
--   AND `title` IN ('官场现形记', '二十年目睹之怪现状', '孽海花', '文明小史', '老残游记');
-- UPDATE `t_novel` SET `category_id` = 3 WHERE `category_id` = 2 AND `title` = '邪修天王';
-- UPDATE `t_novel` SET `category_id` = 2 WHERE `category_id` = 5 AND `title` = '山海经';
-- UPDATE `t_novel` SET `category_id` = 1 WHERE `category_id` = 5
--   AND `title` IN ('聊斋志异', '封神演义', '阅微草堂笔记');
-- UPDATE `t_category` SET `name` = '玄幻奇幻', `sort` = 2 WHERE `id` = 2;
-- UPDATE `t_category` SET `name` = '武侠仙侠', `sort` = 3 WHERE `id` = 3;
-- UPDATE `t_category` SET `name` = '言情古风', `sort` = 6 WHERE `id` = 6;
-- UPDATE `t_category` SET `sort` = 4 WHERE `id` = 4;
-- UPDATE `t_category` SET `sort` = 5 WHERE `id` = 5;
-- UPDATE `t_category` SET `is_deleted` = 1 WHERE `id` = 7;
-- ============================================================
