package com.ainovel.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 支付回调签名工具：HMAC-SHA256 + 常量时间比较
 *
 * <p>签名串按固定顺序拼接，网关侧（生成签名）与本服务侧（校验签名）必须用同一
 * canonical 串。校验用 {@link MessageDigest#isEqual} 做常量时间比较，避免逐字节短路
 * 带来的时序侧信道（攻击者可据此逐字节猜测签名）。
 */
public final class PaySignUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PaySignUtil() {
    }

    /**
     * 生成签名。
     */
    public static String sign(String orderNo, int amount, long timestamp, String nonce, String secret) {
        String canonical = canonical(orderNo, amount, timestamp, nonce);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }

    /**
     * 校验签名（常量时间比较）。
     */
    public static boolean verify(String orderNo, int amount, long timestamp, String nonce, String sign, String secret) {
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        String expected = sign(orderNo, amount, timestamp, nonce, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(String orderNo, int amount, long timestamp, String nonce) {
        return orderNo + "|" + amount + "|" + timestamp + "|" + nonce;
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
