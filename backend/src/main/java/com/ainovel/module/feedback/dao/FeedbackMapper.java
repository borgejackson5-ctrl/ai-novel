package com.ainovel.module.feedback.dao;

import com.ainovel.module.feedback.domain.entity.Feedback;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户反馈 Mapper
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
