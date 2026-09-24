package com.ainovel.common.mq;

import com.ainovel.common.message.AiAuditMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 出站消息写入数据库的两个关键行为：去重标识灌入的时机、序列化失败必须抛出。
 *
 * <p>需要专门约束「时机」的原因：投递时使用的是数据库中的那份 JSON
 * （{@code MqOutboxDispatcher.send} 按 payloadType 反序列化后再投递），
 * 因此灌入必须发生在 {@code writeValueAsString} 之前。若写在之后，
 * 代码同样合理、也能编译，但投出的消息中没有 outboxId，消费端去重会静默失效（一条都不拦截）。
 * 这类「不报错也不生效」的写法正是需要约束的对象。
 */
class MqOutboxStoreTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private final MqOutboxStore store = new MqOutboxStore(jdbcTemplate, new ObjectMapper());

    /** 抓出 INSERT 绑定的第 6 个参数：占位符顺序是 id / exchange / rk / type / payload / status / 三个时间 */
    private String capturedPayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any(),
                payload.capture(), any(), any(), any());
        return payload.getValue();
    }

    @Test
    void outboxIdIsWrittenIntoPayload() {
        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(100L);

        MqOutboxRecord record = store.save("ex", "rk", message);

        String payload = capturedPayload();
        assertTrue(payload.contains("\"outboxId\":" + record.id()),
                () -> "消息体里应当带着本行的 outbox 主键，实际：" + payload);
        assertEquals(record.id(), message.getOutboxId(),
                "同一个 id 也要落在原对象上，便于调用方/测试直接读到");
    }

    @Test
    void messageWithoutOutboxIdAwareIsUntouched() {
        Map<String, Object> message = Map.of("novelId", 100L);

        store.save("ex", "rk", message);

        String payload = capturedPayload();
        assertFalse(payload.contains("outboxId"),
                () -> "没实现 OutboxIdAware 的消息不该被塞字段，实际：" + payload);
    }

    /** 每次 save 的主键必须不同：否则「用户重新提交」会被消费端当作重投而跳过审核 */
    @Test
    void everySaveGetsItsOwnId() {
        AiAuditMessage first = new AiAuditMessage();
        first.setNovelId(100L);
        AiAuditMessage second = new AiAuditMessage();
        second.setNovelId(100L);

        store.save("ex", "rk", first);
        store.save("ex", "rk", second);

        assertTrue(!first.getOutboxId().equals(second.getOutboxId()),
                "同样的业务内容、两次提交必须拿到不同的去重键，否则第二次审核会被吃掉");
    }

    /** 无法序列化的消息必须让调用方失败（事务随之回滚），不能写入表中等待补投 */
    @Test
    void unserializableMessageFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> store.save("ex", "rk", new Object()));
    }
}
