package com.cetc28.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户体系配置（app.auth / app.knowledge / app.embed / app.state）。
 *
 * <p>属性名与 application.yml 保持一致，便于运维按环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class IdentityProperties {

    private Auth auth = new Auth();
    private Knowledge knowledge = new Knowledge();
    private Embed embed = new Embed();
    private State state = new State();

    @Data
    public static class Auth {
        private long sessionTtlSeconds = 28800;
        private String cookieName = "dsh_sid";
        private Anonymous anonymous = new Anonymous();
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
        private int loginMaxFailures = 8;
        private long loginFailWindowSeconds = 300;
        private Sso sso = new Sso();
        private SchemaInit schemaInit = new SchemaInit();
    }

    @Data
    public static class Anonymous {
        private boolean enabled = true;
    }

    @Data
    public static class BootstrapAdmin {
        private String username = "admin";
        private String password = "admin123";
    }

    @Data
    public static class Sso {
        private String sharedSecret = "";
        private long toleranceSeconds = 300;
        private TrustedHeader trustedHeader = new TrustedHeader();
    }

    @Data
    public static class TrustedHeader {
        private boolean enabled = false;
        private String userHeader = "X-Auth-User";
        private String nameHeader = "X-Auth-Name";
        private String rolesHeader = "X-Auth-Roles";
        private String domainsHeader = "X-Auth-Domains";
        /** 受信代理 IP 列表（逗号分隔）；为空表示不信任任何来源 */
        private String trustedProxies = "";
    }

    @Data
    public static class SchemaInit {
        private boolean enabled = true;
    }

    @Data
    public static class Knowledge {
        private String defaultReadDomains = "default";
        private boolean denyAllWhenUnassigned = false;
    }

    @Data
    public static class Embed {
        /** 允许嵌入本系统的父页面来源（逗号分隔）；为空表示不加 CSP 限制 */
        private String allowedOrigins = "";
    }

    @Data
    public static class State {
        private String prefix = "agentchat:v2:";
    }

    public boolean ssoEnabled() {
        return auth.getSso() != null
                && auth.getSso().getSharedSecret() != null
                && !auth.getSso().getSharedSecret().isBlank();
    }
}
