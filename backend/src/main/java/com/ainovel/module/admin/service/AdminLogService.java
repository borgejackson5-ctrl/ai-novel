package com.ainovel.module.admin.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.admin.domain.vo.AdminLogVO;

/**
 * 管理端操作日志查询（审计追溯）。
 *
 * <p>只读：日志由 {@code AdminLogAspect} 在管理端写操作时自动持久化，此处不提供增删改接口，
 * 因为审计记录一旦可被业务修改即失去意义。
 */
public interface AdminLogService {

    /**
     * 日志分页。
     *
     * @param module    模块过滤（可选）
     * @param action    动作过滤（可选）
     * @param adminId   操作人过滤（可选）
     * @param keyword   摘要 / 详情 / 操作人账号模糊匹配（可选）
     * @param startDate 起始日期 yyyy-MM-dd（含当天，可选）
     * @param endDate   结束日期 yyyy-MM-dd（含当天，可选）
     * @param onlyFailed 是否仅查询失败记录
     */
    public PageResult<AdminLogVO> page(int pageNum, int pageSize, String module, String action,
                                       Long adminId, String keyword, String startDate,
                                       String endDate, boolean onlyFailed);
}
