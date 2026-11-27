package com.cetc28.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大系统单点登录令牌：{@code Base64URL(json) + "." + Base64URL(HMAC-SHA256)}。
 *
 * <p>json 约定字段：
 * <ul>
 *   <li>{@code u}  用户唯一标识（工号/账号，必填）</li>
 *   <li>{@code n}  显示名（可选）</li>
 *   <li>{@code r}  角色编码数组（可选；本地不存在时自动补建角色）</li>
 *   <li>{@code d}  知识域数组（可选；仅作参考，实际授权以本地配置为准）</li>
 *   <li>{@code sys} 来源系统标识（可选）</li>
 *   <li>{@code exp} 过期时间（epoch 秒，必填）</li>
 * </ul>
 */
@Component
public class SsoTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final IdentityProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SsoTokenService(IdentityProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.ssoEnabled();
    }

    public record SsoClaims(String userId, String displayName, List<String> roles,
                            List<String> domains, String system, long expiresAt) {
    }

    /** 生成令牌（大系统后端或联调脚本使用；也便于单元测试）。 */
    public String create(String userId, String displayName, List<String> roles,
                         List<String> domains, String system, long ttlSeconds) {
        requireSecret();
        if (userId == null || userId.isBlank()) {
            throw IdentityException.badRequest("SSO 用户标识不能为空");
        }
        long expiresAt = System.currentTimeMillis() / 1000 + Math.max(1, ttlSeconds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("u", userId);
        if (displayName != null && !displayName.isBlank()) payload.put("n", displayName);
        if (roles != null && !roles.isEmpty()) payload.put("r", roles);
        if (domains != null && !domains.isEmpty()) payload.put("d", domains);
        if (system != null && !system.isBlank()) payload.put("sys", system);
        payload.put("exp", expiresAt);
        try {
            String body = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signature = URL_ENCODER.encodeToString(sign(body));
            return body + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("生成 SSO 令牌失败: " + e.getMessage(), e);
        }
    }

    public SsoClaims verify(String token) {
        requireSecret();
        if (token == null || token.isBlank()) {
            throw IdentityException.unauthorized("SSO 令牌为空");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw IdentityException.unauthorized("SSO 令牌格式不正确");
        }
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        byte[] expected;
        try {
            expected = sign(body);
        } catch (Exception e) {
            throw IdentityException.unavailable("SSO 验签失败: " + e.getMessage());
        }
        byte[] actual;
        try {
            actual = URL_DECODER.decode(signature);
        } catch (IllegalArgumentException e) {
            throw IdentityException.unauthorized("SSO 令牌签名格式不正确");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw IdentityException.unauthorized("SSO 令牌签名校验失败");
        }
        Map<?, ?> payload;
        try {
            payload = objectMapper.readValue(URL_DECODER.decode(body), Map.class);
        } catch (Exception e) {
            throw IdentityException.unauthorized("SSO 令牌内容无法解析");
        }
        String userId = stringValue(payload.get("u"));
        if (userId == null || userId.isBlank()) {
            throw IdentityException.unauthorized("SSO 令牌缺少用户标识");
        }
        long expiresAt = longValue(payload.get("exp"));
        long tolerance = Math.max(0, properties.getAuth().getSso().getToleranceSeconds());
        if (expiresAt <= 0 || System.currentTimeMillis() / 1000 > expiresAt + tolerance) {
            throw IdentityException.unauthorized("SSO 令牌已过期");
        }
        return new SsoClaims(userId.trim(), stringValue(payload.get("n")),
                stringList(payload.get("r")), stringList(payload.get("d")),
                stringValue(payload.get("sys")), expiresAt);
    }

    private void requireSecret() {
        if (!enabled()) {
            throw IdentityException.unavailable("SSO 未启用：请配置 app.auth.sso.shared-secret（环境变量 SSO_SHARED_SECRET）");
        }
    }

    private byte[] sign(String body) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(properties.getAuth().getSso().getSharedSecret()
                .getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
            }
        } else if (value instanceof String text && !text.isBlank()) {
            for (String part : text.split(",")) {
                if (!part.isBlank()) result.add(part.trim());
            }
        }
        return result;
    }
}
