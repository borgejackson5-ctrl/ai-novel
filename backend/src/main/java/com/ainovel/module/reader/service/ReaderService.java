package com.ainovel.module.reader.service;

import com.ainovel.module.reader.domain.form.PreferenceForm;
import com.ainovel.module.reader.domain.form.ProgressForm;
import com.ainovel.module.reader.domain.vo.PreferenceVO;
import com.ainovel.module.reader.domain.vo.ProgressVO;

/**
 * 阅读进度 / 阅读偏好服务：每用户一行，跨设备云同步
 *
 * <p>localStorage 仅作前端本地镜像（即时渲染 + 离线兜底），后端为云端唯一真源。
 * 进度采用「最后写入者胜」（LWW）冲突合并：客户端带 clientTime，服务端跳过 stale 推送；
 * 偏好为无条件 upsert（冲突低价值，不做 LWW）。
 */
public interface ReaderService {

    /**
     * 获取「继续阅读」书签；无记录返回 null（前端据此保留本地值并回推）
     */
    public ProgressVO getProgress();

    /**
     * 保存阅读进度：按 user_id 幂等 upsert（存在则更新，否则插入；并发首插唯一键冲突时重查并转更新）
     */
    public void saveProgress(ProgressForm form);

    /**
     * 获取阅读偏好；无记录返回 null
     */
    public PreferenceVO getPreference();

    /**
     * 保存阅读偏好：按 user_id 幂等 upsert（并发首插唯一键冲突时重查并转更新）
     */
    public void savePreference(PreferenceForm form);
}
