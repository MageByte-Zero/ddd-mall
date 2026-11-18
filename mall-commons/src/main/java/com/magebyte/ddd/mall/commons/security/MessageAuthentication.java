package com.magebyte.ddd.mall.commons.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authentication helpers for the course's closed, simulated integration. */
public final class MessageAuthentication {

    private MessageAuthentication() {}

    public static boolean matches(String expected, String supplied) {
        return expected != null && !expected.isBlank() && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                         supplied.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("支付交接密钥未配置");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法签名支付事实", e);
        }
    }
}
