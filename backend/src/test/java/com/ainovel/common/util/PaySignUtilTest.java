package com.ainovel.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付回调签名工具单测：往返一致 + 任一字段篡改即失败 + 密钥隔离
 */
class PaySignUtilTest {

    private static final String SECRET = "s3cret-key";

    @Test
    @DisplayName("签名/验签往返一致")
    void signAndVerify_roundtrip() {
        String sign = PaySignUtil.sign("NO123", 100, 1700000000000L, "nonce1", SECRET);
        assertTrue(PaySignUtil.verify("NO123", 100, 1700000000000L, "nonce1", sign, SECRET));
    }

    @Test
    @DisplayName("篡改订单号/金额/时间戳/nonce 任一字段 → 验签失败")
    void verify_tampered_anyFieldFails() {
        String sign = PaySignUtil.sign("NO123", 100, 1700000000000L, "nonce1", SECRET);
        assertFalse(PaySignUtil.verify("NO124", 100, 1700000000000L, "nonce1", sign, SECRET));
        assertFalse(PaySignUtil.verify("NO123", 101, 1700000000000L, "nonce1", sign, SECRET));
        assertFalse(PaySignUtil.verify("NO123", 100, 1700000000001L, "nonce1", sign, SECRET));
        assertFalse(PaySignUtil.verify("NO123", 100, 1700000000000L, "nonce2", sign, SECRET));
    }

    @Test
    @DisplayName("密钥不同 → 验签失败")
    void verify_wrongSecret_fails() {
        String sign = PaySignUtil.sign("NO123", 100, 1700000000000L, "nonce1", SECRET);
        assertFalse(PaySignUtil.verify("NO123", 100, 1700000000000L, "nonce1", sign, "other-secret"));
    }

    @Test
    @DisplayName("空签名 → 验签失败")
    void verify_nullSign_fails() {
        assertFalse(PaySignUtil.verify("NO123", 100, 1700000000000L, "nonce1", null, SECRET));
        assertFalse(PaySignUtil.verify("NO123", 100, 1700000000000L, "nonce1", "", SECRET));
    }
}
