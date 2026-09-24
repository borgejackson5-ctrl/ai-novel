package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 公版书 TXT 导入结果
 */
@Data
public class ImportResultVO {

    /** 小说 ID（跳过时为 null） */
    private Long novelId;

    /** 书名 */
    private String title;

    /** 是否因同名已存在而跳过 */
    private boolean skipped;

    /** 是否为「同名覆盖」导入（overwrite 命中同名书） */
    private boolean overwrite;

    /** 本次识别并入库的章节数 */
    private int chapterCount;

    /** 全书总字数 */
    private long wordCount;

    /** 探测到的字符编码（UTF-8 / GBK） */
    private String charset;

    /** 提示信息 */
    private String message;
}
