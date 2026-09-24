package com.ainovel.common.util;

import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码加密工具（BCrypt）
 */
public class PasswordUtil {

    private PasswordUtil() {
    }

    public static String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    public static boolean matches(String rawPassword, String hashedPassword) {
        try {
            return BCrypt.checkpw(rawPassword, hashedPassword);
        } catch (Exception e) {
            return false;
        }
    }
}
