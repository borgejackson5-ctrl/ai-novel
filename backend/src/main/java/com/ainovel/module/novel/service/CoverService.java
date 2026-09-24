package com.ainovel.module.novel.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 封面服务：AI 生成（文生图）或用户上传，统一转存 OSS 并返回永久公开 URL。
 *
 * <p>版权约束（任务书）：AI 原创封面为安全来源；用户自行上传需在前端确认拥有版权/使用权，
 * 后端仅做类型与大小校验，内容合规交由既有审核链路兜底。
 */
public interface CoverService {

    /** AI 生成封面：文生图 → 下载 → 转存 OSS */
    public String generate(String prompt);

    /** 用户上传封面：大小 → 后缀白名单 → 文件头魔数 → 转存 OSS */
    public String upload(MultipartFile file);
}
