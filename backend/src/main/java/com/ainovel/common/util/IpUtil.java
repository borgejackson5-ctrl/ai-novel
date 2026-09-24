package com.ainovel.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 解析工具（用于登录防爆破的 IP 维度）。
 *
 * <p>代理/网关部署下优先取 X-Forwarded-For 首段、X-Real-IP，最后回退 remoteAddr。
 * 无请求上下文（如单测）时返回 "unknown"，不抛出异常。
 */
public final class IpUtil {

    private static final String UNKNOWN = "unknown";

    private IpUtil() {
    }

    public static String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return UNKNOWN;
            }
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank() && !UNKNOWN.equalsIgnoreCase(ip)) {
                int idx = ip.indexOf(',');
                return (idx > 0 ? ip.substring(0, idx) : ip).trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isBlank()) {
                return ip.trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
