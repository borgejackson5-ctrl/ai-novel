package com.ainovel.common.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 章节块同步消息：**一章一条**。章节正文变更后，重建该章的向量块。
 *
 * <p>该消息的必要性：块索引（{@code chapter_chunk}）此前仅有 admin 的手动重建入口
 * （{@code POST /novel/vector-reindex}），且一次仅重建一本，导致作者新写的章节
 * <b>始终不在索引中</b>：审查时「服务端主动检索前文并放入 prompt」这一步检索不到内容，
 * 且不报错（仅表现为「跨章一致性判断缺少材料」）。该消息补全了写入路径。
 *
 * <p>消息中只携带 id，正文由消费者回查 DB，与项目中其它 MQ 消费的约定一致。
 * 该设计同时带来两个特性：
 * <ul>
 *   <li><b>幂等</b>：同一个 chapterId 重复投递仅重建两次同一章，块主键为业务键，
 *       重复写入相当于覆盖；</li>
 *   <li><b>与顺序无关</b>：作者连续修改三次投递三条消息，无论消费顺序如何，读取的都是
 *       「当前库中的正文」，不会出现「旧稿覆盖新稿」。</li>
 * </ul>
 *
 * <p>置于 {@code common/message} 而非某个模块内：消息类属于跨模块契约，
 * 放在任一侧都会产生反向依赖（见架构约定）。注意：修改包名会使队列中积压消息的
 * {@code __TypeId__} 反序列化失败。
 */
@Data
public class ChapterChunkSyncMessage implements Serializable {

    /** 所属作品 ID（块文档主键里有它，删章时也要靠它定位） */
    private Long novelId;

    /** 变更的章节 ID */
    private Long chapterId;
}
