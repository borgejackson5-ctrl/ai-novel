package com.ainovel.module.admin.dao;

import com.ainovel.module.admin.domain.entity.AdminLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理端操作日志 Mapper
 */
@Mapper
public interface AdminLogMapper extends BaseMapper<AdminLog> {
}
