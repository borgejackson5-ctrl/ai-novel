package com.ainovel.module.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ainovel.common.domain.PageResult;
import com.ainovel.module.admin.dao.AdminLogMapper;
import com.ainovel.module.admin.domain.entity.AdminLog;
import com.ainovel.module.admin.domain.vo.AdminLogVO;
import com.ainovel.common.domain.PageParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.ainovel.module.admin.service.AdminLogService;

/**
 * 管理端操作日志查询（审计追溯）。
 *
 * <p>只读：日志由 {@code AdminLogAspect} 在管理端写操作时自动持久化，此处不提供增删改接口，
 * 因为审计记录一旦可被业务修改即失去意义。
 */
@Service
@RequiredArgsConstructor
public class AdminLogServiceImpl implements AdminLogService {

    private final AdminLogMapper adminLogMapper;

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
                                       String endDate, boolean onlyFailed) {
        LambdaQueryWrapper<AdminLog> qw = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(module)) {
            qw.eq(AdminLog::getModule, module);
        }
        if (StrUtil.isNotBlank(action)) {
            qw.eq(AdminLog::getAction, action);
        }
        if (adminId != null) {
            qw.eq(AdminLog::getAdminId, adminId);
        }
        if (onlyFailed) {
            qw.eq(AdminLog::getSuccess, 0);
        }
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(AdminLog::getSummary, kw)
                    .or().like(AdminLog::getDetail, kw)
                    .or().like(AdminLog::getAdminName, kw));
        }
        LocalDateTime start = parseDate(startDate, false);
        if (start != null) {
            qw.ge(AdminLog::getCreateTime, start);
        }
        LocalDateTime end = parseDate(endDate, true);
        if (end != null) {
            qw.lt(AdminLog::getCreateTime, end);
        }
        qw.orderByDesc(AdminLog::getId);

        Page<AdminLog> page = adminLogMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), qw);
        List<AdminLogVO> vos = page.getRecords().stream()
                .map(l -> BeanUtil.copyProperties(l, AdminLogVO.class))
                .toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), vos);
    }

    /** 解析日期：end=true 时取次日零点，实现「含当天」的闭区间语义 */
    private LocalDateTime parseDate(String text, boolean endExclusive) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text.trim());
            return endExclusive ? date.plusDays(1).atStartOfDay() : date.atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }
}
