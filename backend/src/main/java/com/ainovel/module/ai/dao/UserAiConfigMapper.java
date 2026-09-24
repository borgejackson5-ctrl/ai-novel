package com.ainovel.module.ai.dao;

import com.ainovel.module.ai.domain.entity.UserAiConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAiConfigMapper extends BaseMapper<UserAiConfig> {

    /**
     * 重置某用户的 DB 侧额度计数（保留自带 Key）。
     *
     * <p>额度已改为 Redis 日键（见 {@code AiConfigService} 的 USER_USAGE_KEY_PREFIX），
     * 每日自然清零，不再依赖这个字段；此方法只用于清理历史数据。
     */
    @Update("UPDATE t_user_ai_config SET used_count = 0 WHERE user_id = #{userId}")
    int resetQuota(@Param("userId") Long userId);
}
