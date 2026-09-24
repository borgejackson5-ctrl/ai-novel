package com.ainovel.module.novel.service;

import com.ainovel.common.constant.NovelConstant;
import com.ainovel.module.novel.domain.vo.ImportResultVO;
import com.ainovel.module.novel.domain.vo.ParsedChapterVO;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * 公版书 TXT 导入器
 *
 * <p>处理链路：编码探测（UTF-8 BOM / 严格 UTF-8 / 回退 GBK）→ 正则分章（中文章节号 &gt;
 * Chapter N &gt; 数字序号，按优先级择一）→ 按书名幂等（同名跳过，或 overwrite 覆盖重建）
 * → 批量入库（saveBatch 500/批）→ 回填 totalChapters/wordCount → 发 ES 同步消息。
 *
 * <p>公版书由管理员导入，默认审核通过 + 上架，user_id 为 null（区别于用户投稿）。
 * 定价沿用种子约定：首章免费，其余每章 {@link NovelConstant#CHAPTER_PRICE} 币；整本价为付费章的
 * 六折打包价（必须 &gt; 0，否则「整本解锁」将免费绕过单章付费墙）。
 */
public interface NovelImportService {

    /**
     * 导入一本公版书 TXT
     *
     * @param file       TXT 文件
     * @param categoryId 分类 ID
     * @param title      书名（幂等键）
     * @param author     作者，留空则「佚名」（覆盖模式下留空保留原作者）
     * @param overwrite  同名书是否覆盖（true=删旧章节重建，保留 id/简介/封面/阅读量/点赞量）
     * @param free       是否整本免费（公版名著等没有付费意图的作品）。true 时全部章节 0 币、整本价 0，
     *                   不再套用「首章免费 + 其余按字数定价」的默认规则
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importTxt(MultipartFile file, Long categoryId, String title, String author,
                                    boolean overwrite, boolean free);

    /**
     * 仅解析不写入数据库：将 TXT 拆成章节列表（用户端投稿预览编辑用），不创建小说。
     * 复用编码探测 + 正则分章；首章免费、其余按付费价（与导入定价一致）。
     */
    public List<ParsedChapterVO> parseTxt(MultipartFile file);
}
