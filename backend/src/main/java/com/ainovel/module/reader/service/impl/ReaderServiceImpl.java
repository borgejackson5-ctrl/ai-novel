package com.ainovel.module.reader.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.reader.dao.ReaderPreferenceMapper;
import com.ainovel.module.reader.dao.ReadingProgressMapper;
import com.ainovel.module.reader.domain.entity.ReaderPreference;
import com.ainovel.module.reader.domain.entity.ReadingProgress;
import com.ainovel.module.reader.domain.form.PreferenceForm;
import com.ainovel.module.reader.domain.form.ProgressForm;
import com.ainovel.module.reader.domain.vo.PreferenceVO;
import com.ainovel.module.reader.domain.vo.ProgressVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Duration;
import com.ainovel.module.reader.service.ReaderService;

/**
 * 阅读进度 / 阅读偏好服务：每用户一行，跨设备云同步
 *
 * <p>localStorage 仅作前端本地镜像（即时渲染 + 离线兜底），后端为云端唯一真源。
 * 进度采用「最后写入者胜」（LWW）冲突合并：客户端带 clientTime，服务端跳过 stale 推送；
 * 偏好为无条件 upsert（冲突低价值，不做 LWW）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReaderServiceImpl implements ReaderService {

    /** 客户端时钟超前容忍上限（毫秒）：超过视为时钟异常，钳制到服务端时间，防伪造未来值 */
    private static final long CLIENT_TIME_TOLERANCE_MS = Duration.ofMinutes(5).toMillis();

    private final ReadingProgressMapper progressMapper;

    private final ReaderPreferenceMapper preferenceMapper;

    /**
     * 获取「继续阅读」书签；无记录返回 null（前端据此保留本地值并回推）
     */
    public ProgressVO getProgress() {
        Long userId = LoginUserUtil.getUserId();
        ReadingProgress p = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId));
        return p == null ? null : BeanUtil.copyProperties(p, ProgressVO.class);
    }

    /**
     * 保存阅读进度：按 user_id 幂等 upsert（存在则更新，否则插入；并发首插唯一键冲突时重查并转更新）
     */
    public void saveProgress(ProgressForm form) {
        Long userId = LoginUserUtil.getUserId();
        ReadingProgress exist = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId));
        if (exist != null) {
            updateProgress(form, exist);
            return;
        }
        ReadingProgress p = BeanUtil.copyProperties(form, ReadingProgress.class);
        p.setUserId(userId);
        try {
            progressMapper.insert(p);
        } catch (DuplicateKeyException e) {
            // 并发首插：唯一键冲突兜底，重查后转更新（幂等，避免多端首登触发 uk_user 冲突返回 500）
            ReadingProgress fresh = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                    .eq(ReadingProgress::getUserId, userId));
            if (fresh != null) {
                updateProgress(form, fresh);
            }
        }
    }

    /**
     * 获取阅读偏好；无记录返回 null
     */
    public PreferenceVO getPreference() {
        Long userId = LoginUserUtil.getUserId();
        ReaderPreference p = preferenceMapper.selectOne(new LambdaQueryWrapper<ReaderPreference>()
                .eq(ReaderPreference::getUserId, userId));
        return p == null ? null : BeanUtil.copyProperties(p, PreferenceVO.class);
    }

    /**
     * 保存阅读偏好：按 user_id 幂等 upsert（并发首插唯一键冲突时重查并转更新）
     */
    public void savePreference(PreferenceForm form) {
        Long userId = LoginUserUtil.getUserId();
        ReaderPreference exist = preferenceMapper.selectOne(new LambdaQueryWrapper<ReaderPreference>()
                .eq(ReaderPreference::getUserId, userId));
        if (exist != null) {
            updatePreference(form, exist);
            return;
        }
        ReaderPreference p = BeanUtil.copyProperties(form, ReaderPreference.class);
        p.setUserId(userId);
        try {
            preferenceMapper.insert(p);
        } catch (DuplicateKeyException e) {
            ReaderPreference fresh = preferenceMapper.selectOne(new LambdaQueryWrapper<ReaderPreference>()
                    .eq(ReaderPreference::getUserId, userId));
            if (fresh != null) {
                updatePreference(form, fresh);
            }
        }
    }

    /**
     * 进度 LWW 合并：客户端时钟异常（超前过多）先钳制；云端已有更新写入则忽略 stale 推送
     */
    private void updateProgress(ProgressForm form, ReadingProgress exist) {
        long clientTime = clampClientTime(form.getClientTime());
        if (exist.getClientTime() != null && exist.getClientTime() > clientTime) {
            return;
        }
        ReadingProgress update = BeanUtil.copyProperties(form, ReadingProgress.class);
        update.setId(exist.getId());
        update.setClientTime(clientTime);
        progressMapper.updateById(update);
    }

    private void updatePreference(PreferenceForm form, ReaderPreference exist) {
        ReaderPreference update = BeanUtil.copyProperties(form, ReaderPreference.class);
        update.setId(exist.getId());
        preferenceMapper.updateById(update);
    }

    /** 钳制客户端时钟：超前服务端时间过多视为异常，取服务端时间，防伪造的未来时间戳持续胜出 */
    private long clampClientTime(Long clientTime) {
        if (clientTime == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        return clientTime > now + CLIENT_TIME_TOLERANCE_MS ? now : clientTime;
    }
}
