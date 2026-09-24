package com.ainovel.module.novel.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.constant.NovelConstant;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.ImportResultVO;
import com.ainovel.module.novel.domain.vo.ParsedChapterVO;
import com.ainovel.common.message.SearchSyncMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.time.LocalDateTime;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelImportService;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelImportServiceImpl implements NovelImportService {

    private final NovelMapper novelMapper;

    private final ChapterMapper chapterMapper;

    private final ChapterService chapterService;

    private final NovelService novelService;

    private final MqSender mqSender;

    /** 免费试读章数 */
    private static final int FREE_CHAPTERS = 1;
    /** 批量入库每批条数 */
    private static final int BATCH_SIZE = 500;
    /** 章节标题最大长度（对齐 t_chapter.title VARCHAR(100)） */
    private static final int TITLE_MAX = 100;

    private static final Charset GBK = Charset.forName("GBK");

    /** 分章正则优先级：中文章节号 → Chapter N → 数字序号 */
    private static final List<Pattern> CHAPTER_PATTERNS = List.of(
            Pattern.compile("^\\s*第[0-9一二三四五六七八九十百千零两]+[回章卷节篇]"),
            Pattern.compile("^\\s*Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*\\d+[、.．]\\s*\\S+")
    );

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
                                    boolean overwrite, boolean free) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请上传 TXT 文件");
        }
        if (categoryId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择分类");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写书名");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 若不记录日志且不传递 cause，根因（磁盘/流已关闭/编码）会完全丢失
            log.error("读取上传文件失败: filename={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取文件失败", e);
        }

        Charset charset = detectCharset(bytes);
        String text = stripBom(new String(bytes, charset));

        // 幂等/覆盖：同名小说已存在
        Novel existing = novelMapper.selectOne(new LambdaQueryWrapper<Novel>().eq(Novel::getTitle, title));
        if (existing != null && !overwrite) {
            ImportResultVO vo = new ImportResultVO();
            vo.setTitle(title);
            vo.setSkipped(true);
            vo.setCharset(charset.name());
            vo.setMessage("同名小说已存在，已跳过导入");
            return vo;
        }

        List<Chapter> chapters = splitChapters(text);
        if (chapters.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未识别到任何正文，请检查 TXT 内容");
        }

        long wordCount = 0L;
        for (Chapter c : chapters) {
            wordCount += c.getWordCount();
        }
        // 免费书：全部章节 0 币，整本价亦归零。公版名著不存在付费墙语义，
        // 套用默认的「首章免费 + 其余按字数定价」会使读者在第二章被拦截
        if (free) {
            chapters.forEach(c -> c.setUnlockCoin(0));
        }
        long paidChapters = free ? 0 : Math.max(0, chapters.size() - FREE_CHAPTERS);
        int coinPrice = paidChapters == 0 ? 0
                : (int) Math.max(1, Math.round(paidChapters * NovelConstant.CHAPTER_PRICE * NovelConstant.BUNDLE_DISCOUNT));

        Novel novel;
        if (existing != null) {
            // 覆盖：删除旧章节后重建；保留 id/简介/封面/阅读量/点赞量（及 userId/状态/审核），
            // 仅更新分类/作者/章节数/字数/整本价。
            // 必须物理删除：Service 的 remove 走 @TableLogic 逻辑删除，残留行仍占用
            // 唯一索引 uk_novel_no(novel_id, chapter_no)，插入新章节会产生唯一键冲突
            chapterMapper.deleteByNovelId(existing.getId());
            existing.setCategoryId(categoryId);
            existing.setAuthor(author == null || author.isBlank() ? existing.getAuthor() : author);
            existing.setTotalChapters(chapters.size());
            existing.setWordCount(wordCount);
            existing.setCoinPrice(coinPrice);
            // 导入的公版书均为完本，标记为已完结；同时写入 finish_time，
            // 避免出现「已完结但无完结时间」的状态
            existing.setSerialStatus(SerialStatusEnum.FINISHED.getCode());
            existing.setFinishTime(LocalDateTime.now());
            novelMapper.updateById(existing);
            novel = existing;
        } else {
            novel = new Novel();
            novel.setTitle(title);
            novel.setCategoryId(categoryId);
            novel.setAuthor(author == null || author.isBlank() ? "佚名" : author);
            novel.setIntro(buildIntro(chapters.get(0).getContent()));
            novel.setTotalChapters(chapters.size());
            novel.setWordCount(wordCount);
            novel.setCoinPrice(coinPrice);
            novel.setReadCount(0L);
            novel.setLikeCount(0L);
            novel.setStatus(CommonStatusEnum.ENABLED.getCode());
            novel.setAuditStatus(AuditStatusEnum.PASS.getCode());
            novel.setAuditResult("公版书导入，自动通过");
            // 公版书一律为完本；导入 60 本名著全部标记为「连载中」不符合实际
            novel.setSerialStatus(SerialStatusEnum.FINISHED.getCode());
            novel.setFinishTime(LocalDateTime.now());
            novelMapper.insert(novel);
        }

        for (Chapter c : chapters) {
            c.setNovelId(novel.getId());
        }
        chapterService.saveBatch(chapters, BATCH_SIZE);

        // 覆盖导入重建章节/回填字数：失效相关缓存（详情 + 章节目录），避免读者读到旧目录
        if (existing != null) {
            novelService.evictDetailCache(novel.getId());
            chapterService.evictListCache(novel.getId());
        }

        // 同步 ES（最终一致）：importTxt 在事务内，afterCommit 后投递，避免提交前被消费回查 null 而删文档
        SearchSyncMessage msg = new SearchSyncMessage();
        msg.setNovelId(novel.getId());
        msg.setOperation("UPSERT");
        mqSender.sendAfterCommit(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY, msg);

        // 刻意**不**逐章请求重建向量块：该数据为整本公版书（6500 章 / 60 部），
        // 逐章投递会产生上万次向量调用，而其主要用途是供读者阅读，不参与作者的跨章审查。
        // 需让某本书可被 RAG 检索时，用 admin 的 POST /novel/vector-reindex?novelId= 显式重建一次。
        // 在此说明的原因：该缺口不产生报错，仅表现为「跨章核对缺少一层依据」。
        log.info("导入完成（未建章节向量块，需要 RAG 检索这本书时用 /novel/vector-reindex 显式重建）: novelId={}",
                novel.getId());

        ImportResultVO vo = new ImportResultVO();
        vo.setNovelId(novel.getId());
        vo.setTitle(title);
        vo.setSkipped(false);
        vo.setOverwrite(existing != null);
        vo.setChapterCount(chapters.size());
        vo.setWordCount(wordCount);
        vo.setCharset(charset.name());
        vo.setMessage(existing != null ? "同名书已覆盖导入" : "导入成功");
        log.info("导入公版书: title={}, chapters={}, wordCount={}, charset={}, overwrite={}",
                title, chapters.size(), wordCount, charset.name(), existing != null);
        return vo;
    }

    /**
     * 仅解析不写入数据库：把 TXT 拆成章节列表（用户端投稿预览编辑用），不创建小说。
     * 复用编码探测 + 正则分章；首章免费、其余按付费价（与导入定价一致）。
     */
    public List<ParsedChapterVO> parseTxt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请上传 TXT 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".txt")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 TXT 文件");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 若不记录日志且不传递 cause，根因（磁盘/流已关闭/编码）会完全丢失
            log.error("读取上传文件失败: filename={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取文件失败", e);
        }

        Charset charset = detectCharset(bytes);
        String text = stripBom(new String(bytes, charset));

        List<Chapter> chapters = splitChapters(text);
        if (chapters.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未识别到任何正文，请检查 TXT 内容");
        }

        List<ParsedChapterVO> result = new ArrayList<>(chapters.size());
        for (Chapter c : chapters) {
            ParsedChapterVO vo = new ParsedChapterVO();
            vo.setTitle(c.getTitle());
            vo.setContent(c.getContent());
            vo.setWordCount(c.getWordCount());
            vo.setUnlockCoin(c.getUnlockCoin() == null ? 0 : c.getUnlockCoin());
            result.add(vo);
        }
        return result;
    }

    /**
     * 编码探测：UTF-8 BOM → 严格 UTF-8 可解码 → 回退 GBK
     */
    private Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return isValidUtf8(bytes) ? StandardCharsets.UTF_8 : GBK;
    }

    private boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** 去掉解码后可能残留的 BOM 字符（\uFEFF），否则会破坏首行章节标题的正则匹配 */
    private String stripBom(String text) {
        return (!text.isEmpty() && text.charAt(0) == '\uFEFF') ? text.substring(1) : text;
    }

    /**
     * 分章：选定优先级最高的正则后按行扫描，标题行开章、其余行累积为正文；
     * 首个标题行之前的内容（封面/序言）丢弃；全文无标题则整篇作为单章。
     */
    private List<Chapter> splitChapters(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        Pattern active = choosePattern(lines);

        List<Chapter> chapters = new ArrayList<>();
        if (active == null) {
            String body = text.trim();
            if (!body.isEmpty()) {
                chapters.add(buildChapter(null, body, 1));
            }
            return chapters;
        }

        StringBuilder content = new StringBuilder();
        String title = null;
        boolean started = false;
        for (String line : lines) {
            if (active.matcher(line).find()) {
                if (started) {
                    chapters.add(buildChapter(title, content.toString(), chapters.size() + 1));
                }
                title = line.trim();
                content.setLength(0);
                started = true;
            } else if (started) {
                content.append(line).append('\n');
            }
        }
        if (started) {
            chapters.add(buildChapter(title, content.toString(), chapters.size() + 1));
        }
        return chapters;
    }

    /**
     * 选定分章正则：优先取能匹配到 ≥2 个标题的最高优先级正则；
     * 若都不足 2 个，退化取能匹配到 ≥1 个的最高优先级正则；全无则返回 null。
     */
    private Pattern choosePattern(String[] lines) {
        for (Pattern p : CHAPTER_PATTERNS) {
            int count = 0;
            for (String line : lines) {
                if (p.matcher(line).find() && ++count >= 2) {
                    return p;
                }
            }
        }
        for (Pattern p : CHAPTER_PATTERNS) {
            for (String line : lines) {
                if (p.matcher(line).find()) {
                    return p;
                }
            }
        }
        return null;
    }

    private Chapter buildChapter(String title, String content, int no) {
        String body = content == null ? "" : content.trim();
        Chapter c = new Chapter();
        c.setChapterNo(no);
        c.setTitle(normalizeTitle(title, no));
        c.setContent(body);
        c.setWordCount(body.length());
        c.setUnlockCoin(no <= FREE_CHAPTERS ? 0 : NovelConstant.CHAPTER_PRICE);
        c.setSort(no);
        // 公版书管理员导入直接通过，显式置 PASS，不依赖 DB 默认值
        c.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
        c.setAuditResult("公版书导入，自动通过");
        return c;
    }

    private String normalizeTitle(String title, int no) {
        if (title == null || title.isBlank()) {
            return "第" + no + "章";
        }
        String t = title.trim();
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
    }

    /** 导入书籍无简介时，取首章正文前 ~120 字作为占位简介 */
    private String buildIntro(String firstContent) {
        if (firstContent == null || firstContent.isBlank()) {
            return "公版书导入";
        }
        String s = firstContent.trim().replaceAll("\\s+", "");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
