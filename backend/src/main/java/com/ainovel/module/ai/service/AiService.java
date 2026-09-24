package com.ainovel.module.ai.service;

import com.ainovel.common.mq.MqSender;
import com.ainovel.module.ai.domain.entity.AiConfig;
import java.util.function.Consumer;

/**
 * AI 内容生成服务（生成书名/简介；平台未配置 Key 时按开关决定是否返回演示内容）。
 */
public interface AiService {

    /**
     * 提交 AI 审核：置为待审核 + 发送 MQ 异步审核。
     *
     * <p>投递必须走 {@link MqSender#sendAfterCommit}：这是全项目唯一的 MQ 出口。
     * 直接调用 {@code rabbitTemplate.convertAndSend} 会丢失 publisher-confirm 的
     * CorrelationData，broker 拒收时无从感知；且本方法**一旦加上
     * {@code @Transactional}**，直发即变为「事务尚未提交就发出消息」，
     * 消费者可能在数据库中读不到新状态。
     */
    public void submitAudit(Long novelId);

    /**
     * 统一生成入口。
     *
     * @param type  生成类型 TITLE/INTRO
     * @param input 用户输入（题材/书名等）
     */
    public String generate(String type, String input);

    /**
     * 本次生成会消耗多少字（额度按字数扣）。
     *
     * <p>流式接口必须在**创建 SSE 之前**扣额度（此时尚未开始生成），因此需要一个不依赖
     * 实际调用的估算口径；提示词位于 {@link AiService}，估算也放在此处。
     *
     * <p>**口径（全站唯一）：只算用户内容，不算系统提示词。** 提示词是平台的固定成本，
     * 不随用户输入变化，转嫁给用户既不公平也解释不清。新增生成类功能时按该口径执行，
     * 不再自行决定是否计入提示词。
     *
     * @param type  生成类型，非法类型直接抛错（与 {@link #generate} 同一套校验）
     * @param input 用户输入
     */
    public int estimateUnits(String type, String input);

    /**
     * 流式生成：逐段推送增量文本（SSE）。
     *
     * <p>无 Key 时以 mock 逐字推送模拟流式，保证无 Key 也可体验流式效果。
     *
     * @param type    生成类型 TITLE/INTRO
     * @param input   用户输入
     * @param config  已解析的生效配置（由调用方在创建 SSE 前同步解析并扣额度，额度耗尽时抛 JSON 错误）
     * @param onChunk 每收到一段增量文本时的回调
     */
    public void generateStream(String type, String input, AiConfig config, Consumer<String> onChunk);

    /**
     * 开流前的可用性判定（起名/简介）。
     *
     * <p>规则与 {@link #generateStream} 中该段完全相同，抽出的目的是**为控制器提供
     * 开流前即可调用的入口**：原先该判断仅发生在异步线程中，「AI 暂未开放」只能以
     * 「异步失败」的形式表达，用户侧仍会得到 400 与提示文案，但日志中会多出一条带堆栈的
     * ERROR，使排查者误认为发生真实故障。续写/润色早已如此处理（{@code requireXxxReady}）。
     */
    public void requireGenerateReady(AiConfig config);
}
