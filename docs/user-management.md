# 用户体系：账号、SSO、角色与知识权限

> 适用版本：AgentChat v2（`2.1.0+`）。目标是把智能体作为**子页面嵌入多个大系统**时，仍能做到：
> 免二次登录、会话与记忆按用户隔离、知识库按角色/用户授权、界面状态（含数字地球）互不串台。

## 1. 能力总览

| 能力 | 实现 |
| --- | --- |
| 大系统免登录 | 签名 SSO 令牌（URL / 请求头 / iframe postMessage）/ 受信网关头 / 本地账号登录 |
| 会话与记忆绑定用户 | 沿用 `userId` 身份键；登录后替换匿名 `dsh_uid`，会话、文件、AgentState、workspace 全部按真实用户隔离 |
| 用户与角色 | DM 表 `AUTH_USER/AUTH_ROLE/AUTH_USER_ROLE`，管理台「用户与角色」页可视化管理 |
| 知识库按角色/用户授权 | `AUTH_KB_GRANT` 存「主体 → 知识域」授权；检索、上传、工具调用全链路按授权域过滤 |
| 长期偏好 | `USR_PROFILE`（DM）+ Redis 记忆；每轮沉淀常用模式/模型/主题，注入对话上下文 |
| UI 隔离 | 浏览器偏好按 `agentchat.u:<userId>:*` 分键；地球会话 `chatId` 按用户生成、按用户命名窗口 |
| 存储分工 | 身份、角色、授权、画像、审计 → 达梦 `DBTXZNT`；会话令牌、登录失败计数、会话、记忆缓存 → Redis |

## 2. 架构

```
浏览器 / 大系统页面
   │  ① 免登录：?sso=<token> / X-SSO-Token / postMessage / 受信网关头
   ▼
UserContextFilter (HIGHEST_PRECEDENCE)
   │  cookie dsh_sid → Bearer → SSO 令牌 → 网关头 → 匿名
   ▼
IdentityPrincipal ──► request attribute dsh.userId / agentchat.identity
   │
   ├─ AuthService ──► AuthSessionStore (Redis) + AUTH_USER/AUTH_ROLE (DM)
   ├─ KnowledgeScopeService ──► AUTH_KB_GRANT (DM) → 可见知识域集合
   ├─ UserProfileService ──► USR_PROFILE (DM) + mem:{userId}:pref (Redis)
   └─ ChatService / KnowledgeSearchTool / KnowledgeController
                         └─ Milvus 检索：domain in [...]
```

过滤器在**一次请求内**完成身份解析，并把 `userId` 写回原有 `UserContext`，
因此 `ConversationController`、`ChatService`、`UserMemoryService`、文件接口等 16 处调用点无需改动。

## 3. 数据模型（达梦 `DBTXZNT`）

建表脚本：`src/main/resources/db/dm-schema.sql`，应用启动时由 `DmSchemaInitializer` 幂等执行
（已存在的表/索引跳过；可用 `AUTH_SCHEMA_INIT=false` 关闭）。

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `AUTH_USER` | `USER_ID` `LOGIN_NAME` `PASSWORD_HASH` `DISPLAY_NAME` `USER_SOURCE` `EXTERNAL_ID` `ENABLED` `LAST_LOGIN_AT` | `USER_SOURCE=local` 本地账号；`sso` 大系统同步账号（`EXTERNAL_ID` 为对方用户 ID） |
| `AUTH_ROLE` | `ROLE_ID` `ROLE_CODE` `ROLE_NAME` `BULT_IN`→`BUILT_IN` `ROLE_DESC` | 内置 `admin`/`user` 不可删除 |
| `AUTH_USER_ROLE` | `USER_ID` `ROLE_ID` | 用户可多角色 |
| `AUTH_KB_GRANT` | `SUBJECT_TYPE`(`user`/`role`) `SUBJECT_ID` `DOMAIN_KEY` `ACCESS_LEVEL` | 知识域授权，角色与用户授权取并集 |
| `USR_PROFILE` | `USER_ID` `PROFILE_KEY` `PROFILE_VALUE` `SOURCE_TYPE`(`auto`/`manual`) | 画像条目，自动条目按用户保留上限 |
| `AUTH_AUDIT` | `ACTION_NAME` `TARGET_ID` `DETAIL` `CLIENT_IP` | 登录、SSO、用户/角色/授权变更审计 |

时间字段统一为 `BIGINT` 毫秒时间戳，主键由 Java 生成（`u_/r_/g_/p_/a_` + 16 hex），不依赖数据库序列。

Redis 键（前缀取 `app.state.prefix`，默认 `agentchat:v2:`）：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `{prefix}auth:sess:{sha256(token)}` | HASH | 会话 `userId/createdAt`，TTL 滑动续期 |
| `{prefix}auth:revoked:{userId}` | STRING | 改密/停用/删除后的会话吊销时间点 |
| `{prefix}auth:fail:{loginName}` | STRING | 登录失败计数（窗口 `login-fail-window-seconds`） |

## 4. 认证流程

### 4.1 身份解析顺序（`UserContextFilter`）

1. Cookie `dsh_sid`（本地会话）；
2. `Authorization: Bearer <sessionToken>`（跨站 iframe 场景 Cookie 可能被拦，用它兜底）；
3. `X-SSO-Token` 请求头或 `?sso=` / `?ssoToken=` / `?token=` 查询参数 → 验签 + 建会话；
4. 受信代理头 `X-Auth-User/X-Auth-Name/X-Auth-Roles/X-Auth-Domains`（仅当来源 IP 命中 `trusted-proxies`）；
5. 匿名（`app.auth.anonymous.enabled=true` 时保留旧行为，生成 `dsh_uid` cookie）。

匿名关闭（`AUTH_ANONYMOUS=false`）时，除 `/api/auth/**`、`/api/health`、`/api/models` 外的 `/api/**`
返回 `401 {"error":"需要登录","code":"unauthorized"}`，前端跳转 `/login`。

### 4.2 本地账号登录

- 引导管理员：首次启动按 `AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_PASSWORD`（默认 `admin` / `admin123`）创建，
  **部署后请立即在管理台改密**；已存在同名用户时不会覆盖密码。
- 密码使用 PBKDF2-HMAC-SHA256（120000 次迭代、16 字节随机盐），格式 `pbkdf2$120000$<salt>$<hash>`。
- 连续失败 `AUTH_LOGIN_MAX_FAILURES` 次（默认 8 次 / 5 分钟）返回 429。
- 改密、停用、删除会写入吊销时间点，旧会话**立即失效**。

### 4.3 大系统 SSO（签名令牌）

令牌格式：`Base64URL(payloadJson) + "." + Base64URL(HMAC-SHA256(payloadJson, sharedSecret))`。

payload 字段：

```json
{"u":"erp-user-1001","n":"张三","r":["user","analyst"],"d":["default","equipment"],"sys":"erp","exp":1765000000}
```

- `u` 必填（大系统用户 ID，映射到 `USER_SOURCE=sso` + `EXTERNAL_ID`）；
- `r` 角色编码（登录时自动补齐并赋给该 SSO 用户）、`d` 知识域（**非空时作为该用户在本系统的权威直接授权，每次 SSO 登录覆盖其用户级授权**；为空则保留平台内已有授权；角色授权始终保留）；
- `exp` 秒级过期时间；校验时允许 `SSO_TOLERANCE_SECONDS`（默认 300 秒）时钟偏差；
- 未配置 `SSO_SHARED_SECRET` 时 SSO 接口返回 503，系统只支持本地登录/匿名。

生成示例（Java）：

```java
String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(UTF_8));
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(UTF_8)));
String token = body + "." + sig;
```

本地联调可用 `scripts/gen-sso-token.ps1`（PowerShell 实现，参数与上表一致）。

三种交付方式，任选其一：

1. **URL 跳转**：大系统跳转 `https://agentchat.example.com/?sso=<token>&redirect=/#/admin/users`；
   前端交换后立即把 `sso` 参数从地址栏移除（避免复制链接泄露）。
2. **iframe 嵌入**：父页面加载 `https://agentchat.example.com/`，子页启动后向父页发：

   ```js
   // 子页 → 父页
   { type: 'agentchat:ready' }
   { type: 'agentchat:request-sso' }
   // 父页 → 子页（token 由大系统后端签发）
   iframe.contentWindow.postMessage({ type: 'agentchat:sso', token }, 'https://agentchat.example.com');
   ```

   子页会用令牌换会话并刷新；也支持父页发 `{type:'agentchat:logout'}`、`{type:'agentchat:ping'}`。
3. **受信网关头**：网关完成登录后注入 `X-Auth-User` 等头，并把网关出口 IP 配到
   `SSO_TRUSTED_PROXIES=10.0.0.0/8,192.168.1.10`（支持 `*`、前缀通配、精确匹配）。
   只应在 AgentChat 不可被客户端直连时启用。

### 4.4 嵌入与跨域

- 允许的父页面来源：`EMBED_ALLOWED_ORIGINS=https://erp.example.com,https://oa.example.com`；
  配置后后端下发 `Content-Security-Policy: frame-ancestors 'self' <origins>` 并对 `/api/**` 开启 CORS
  （`allowCredentials=true`）。留空则同源可用、不额外限制。
- 跨站 iframe 里第三方 Cookie 可能被浏览器拦截：前端已把 `/api/auth/me` 返回的 `sessionToken`
  放在内存中，后续请求用 `Authorization: Bearer`，不依赖 Cookie。
- `SameSite` 未显式设置时按 Spring 默认 `Lax`；如需跨站 Cookie，可在网关把 AgentChat 视作同站部署。

## 5. 权限模型：角色、用户与知识域

- **管理员**：角色含 `admin`（大小写不敏感）→ 不受知识域限制，可进入管理接口（也仍兼容 `X-Admin-Token`）。
- **普通用户**：可见知识域 = 用户直接授权 ∪ 其角色授权；管理接口（`/api/admin/**`、`/api/knowledge/**`、
  `/api/mcp/**`、`/api/logs/**`）对已登录普通用户返回 403，其知识可见性只体现在对话自动 RAG 与 Agent 工具调用里。
- **未授权用户**：默认回退 `KNOWLEDGE_DEFAULT_DOMAINS`（默认 `default`），保持升级兼容；
  设置 `KNOWLEDGE_DENY_ALL=true` 后改为**无授权即不可检索**（推荐生产启用）。
- 传给检索器的域集合语义：`null`=不限（仅管理员）、空集=拒绝、非空=按 `domain in [...]` 过滤。
- 强制点：
  - `ChatService.retrieveKnowledge`：自动 RAG 注入；
  - `KnowledgeSearchTool`：Agent 主动检索 + `readChunk` 回读 + `knowledgeOverview` 清单；
  - `KnowledgeController`：知识管理接口（上传/列表/搜索/诊断）由 `AdminGuard` 限定管理员；管理员检索仍按显式
    `domain` 过滤，普通用户不直接访问管理接口；
  - `MilvusHybridRetriever`：稠密向量、BM25、查询扩展三处过滤 + 融合后兜底过滤，避免漏网。

知识库文档上传时的 `domain` 就是授权粒度；建议按业务域命名（如 `default`、`equipment`、`finance`），
在管理台「知识库」上传时填写，再在「用户与角色」里授权。

## 6. 画像、记忆与个性化

- 每轮成功对话后 `UserProfileService.recordTurn` 记录：累计轮次、最近模式/模型、问题关键词计数（最多 40 个主题）。
- 注入顺序（`app.context.profile-max-chars` 限制总长）：
  `[用户画像]`（称呼/角色/常用模式/常用模型/关注主题/手动偏好）→ `[用户长期记忆]`（Redis 偏好 top 5）→ RAG 片段 → 附件摘要。
- 用户说「请记住/我习惯/我偏好…」时仍由 `UserMemoryService` 抽取到 `mem:{userId}:pref`；
  手动记忆会镜像到 `USR_PROFILE(SOURCE_TYPE=manual)`，可在「我的画像」页删除。
- 匿名用户不写 DM（避免匿名垃圾数据），画像只在当前浏览器会话内体现。

## 7. 界面隔离

| 状态 | 存储 | 隔离方式 |
| --- | --- | --- |
| 主题、模型选择、并行开关、地球 chatId | localStorage | `agentchat.u:<userId>:<name>` 分键；退出/切换用户时清空当前用户分键 |
| 地球窗口 | `window.open` 窗口名 | `agentchat-earth-<userId>`，不同用户不共用窗口 |
| 地球消息 | `BroadcastChannel('agentchat-earth-v1')` | 消息自带 `chatId`，接收端按绑定 `chatId` 过滤；退出时广播 `clear` |
| 对话 DOM、附件、输入框 | 页面内存 | `resetUserSurface()` / `resetEarthSession()` 在登出、会话过期时清空 |

> 同一浏览器同一时刻只应有一个用户身份。切换用户请走「退出登录」再登录，
> 不要复用标签页直接改 cookie。

## 8. API 参考

认证（匿名可访问）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | `{username,password}` → `{authenticated,userId,roles,domains,sessionToken,expiresAt,embed}` + `Set-Cookie` |
| POST | `/api/auth/logout` | 清理会话与 Cookie |
| POST | `/api/auth/sso/exchange` | `{token}` → 同登录响应 |
| GET | `/api/auth/me` | 当前身份；`loginRequired` 表示是否必须登录；`ssoEnabled`；`embed.allowedOrigins` |

用户侧：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/profile` | 我的画像（身份、角色、知识域、轮次、偏好、主题、记忆） |
| DELETE | `/api/profile/topics/{keyword}` | 删除关注主题 |
| GET/POST/DELETE | `/api/memory`… | 长期记忆（原有） |

管理端（`admin` 角色、本机访问或 `X-Admin-Token`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/admin/identity/users` | 列表（`query/limit/offset`）/ 新建 |
| PATCH | `/api/admin/identity/users/{id}` | 改昵称、邮箱、电话、启用状态 |
| PUT | `/api/admin/identity/users/{id}/password` | 重置密码（旧会话失效） |
| PUT | `/api/admin/identity/users/{id}/roles` | 设置角色（`{roles:[code]}`） |
| PUT | `/api/admin/identity/users/{id}/domains` | 设置直接授权知识域（`{domains:[domain]}`） |
| DELETE | `/api/admin/identity/users/{id}` | 软删除（停用 + 清授权） |
| GET | `/api/admin/identity/users/{id}/profile` | 指定用户画像 |
| GET/POST | `/api/admin/identity/roles` | 角色列表 / 新建 |
| PATCH/DELETE | `/api/admin/identity/roles/{id}` | 改名/描述 / 删除（内置或有成员时拒绝） |
| PUT | `/api/admin/identity/roles/{id}/domains` | 角色知识域 |
| GET | `/api/admin/identity/domains` | 知识域清单（含文件/分段统计） |
| GET | `/api/admin/identity/audit?limit=100` | 审计日志（最多 500） |

## 9. 配置

```yaml
spring:
  datasource:
    url: jdbc:dm://127.0.0.1:5236?schema=DBTXZNT
    username: DBTXZNT
    password: SYSDBAsysdba23
    driver-class-name: dm.jdbc.driver.DmDriver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
app:
  auth:
    session-ttl-seconds: 28800
    cookie-name: dsh_sid
    anonymous:
      enabled: true
    bootstrap-admin:
      username: admin
      password: admin123      # 生产必须用 AUTH_ADMIN_PASSWORD 覆盖
    login-max-failures: 8
    login-fail-window-seconds: 300
    sso:
      shared-secret: ""       # 生产用 SSO_SHARED_SECRET
      tolerance-seconds: 300
      trusted-header:
        enabled: false
        user-header: X-Auth-User
        name-header: X-Auth-Name
        roles-header: X-Auth-Roles
        domains-header: X-Auth-Domains
        trusted-proxies: ""
    schema-init:
      enabled: true
  knowledge:
    default-read-domains: default
    deny-all-when-unassigned: false
  embed:
    allowed-origins: ""       # 例：https://erp.example.com
```

环境变量（`deploy/env.conf`）：`DB_HOST DB_PORT DB_SCHEMA DB_USER DB_PASSWORD`、
`AUTH_SESSION_TTL AUTH_COOKIE_NAME AUTH_ANONYMOUS AUTH_ADMIN_USERNAME AUTH_ADMIN_PASSWORD`、
`AUTH_LOGIN_MAX_FAILURES AUTH_LOGIN_FAIL_WINDOW AUTH_SCHEMA_INIT`、
`SSO_SHARED_SECRET SSO_TOLERANCE_SECONDS SSO_TRUSTED_HEADER SSO_USER_HEADER SSO_NAME_HEADER SSO_ROLES_HEADER SSO_DOMAINS_HEADER SSO_TRUSTED_PROXIES`、
`KNOWLEDGE_DEFAULT_DOMAINS KNOWLEDGE_DENY_ALL EMBED_ALLOWED_ORIGINS`。

## 10. 部署与初始化

1. 安装达梦 JDBC 驱动到本地 Maven 仓库（离线环境）：

   ```powershell
   mvn install:install-file "-Dfile=D:\dm\drivers\jdbc\DmJdbcDriver8.jar" `
     "-DgroupId=com.dameng" "-DartifactId=DmJdbcDriver18" "-Dversion=8.1.5.60" "-Dpackaging=jar"
   ```

2. 确认 `DBTXZNT` 用户可登录、对自身模式有建表权限；
3. `mvn -DskipTests package` 后按 `deploy/env.conf.example` 配置并启动；
4. 检查 `GET /api/health`：`dm=up`、`identity=ready（local,sso/anonymous）`；
5. 用引导管理员登录 → 「用户与角色」创建业务角色、勾选知识域 → 创建用户或等待 SSO 首次登录自动建号；
6. 用 `curl -H "Authorization: Bearer ..." /api/profile` 验证会话；
7. 生产上线前设置 `KNOWLEDGE_DENY_ALL=true`、修改引导管理员密码、配置 `SSO_SHARED_SECRET` 与 `EMBED_ALLOWED_ORIGINS`。

## 11. 自测与验收

```bash
# 本地登录
curl -i -X POST http://127.0.0.1:8092/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'

# SSO 令牌交换（TOKEN 由大系统或 scripts/gen-sso-token.ps1 生成）
curl -i -X POST http://127.0.0.1:8092/api/auth/sso/exchange \
  -H 'Content-Type: application/json' -d "{\"token\":\"$TOKEN\"}"

# 当前身份（带会话令牌）
curl http://127.0.0.1:8092/api/auth/me -H "Authorization: Bearer $SESSION"
```

验收清单：

- [ ] 未登录且 `AUTH_ANONYMOUS=false` 时访问首页跳转 `/login`，登录后回到原地址；
- [ ] 带 `?sso=<token>` 打开页面可直接进入，地址栏不含令牌，`/api/auth/me` 返回大系统用户；
- [ ] 同一浏览器 A/B 两个账号的会话列表、记忆条目互不可见；
- [ ] 角色 A 只授 `default`、角色 B 只授 `equipment`：同问题引用来源不同，越权域 `403`/空结果；
- [ ] 对话后 `/api/profile` 出现轮次、常用模式、关注主题；清空后不再注入；
- [ ] 数字地球：切换账号后旧窗口不再接收新消息，新窗口标题/内容对应用户；
- [ ] 改密/停用用户后旧 `Bearer` 立即 401。

## 12. 故障排查

| 现象 | 排查 |
| --- | --- |
| 启动时报 `dm.jdbc.driver.DmDriver` 找不到 | 未安装驱动或 jar 未打进包；执行第 10 节安装命令并确认 `pom.xml` 有 runtime 依赖 |
| `/api/health` 的 `dm=down` | 检查 DBTXZNT 密码、`?schema=` 参数、Hikari 连接超时日志 |
| `identity=schema_error: …` | 看日志中的 DDL 失败表名；常见为建表权限不足或字符长度超限（按字节计长） |
| 页面反复跳登录 | `AUTH_ANONYMOUS=false` 且 SSO 未配置；配置 `SSO_SHARED_SECRET` 或临时开启匿名 |
| iframe 内登录后仍 401 | 浏览器拦截第三方 Cookie；确认前端用 `/api/auth/me` 返回的 `sessionToken` 走 `Authorization`，或把系统部署为同站 |
| 检索结果与角色不符 | 检查角色/用户授权是否并集、`KNOWLEDGE_DENY_ALL`、文档 `domain` 是否为空（空归 `default`） |
| 管理接口 403 | 需要 `admin` 角色登录，或本机访问，或带正确的 `X-Admin-Token` |

## 13. 回滚

- 关闭新能力：`AUTH_ANONYMOUS=true`（恢复匿名可用）、`SSO_TRUSTED_HEADER=false`；
- 完全回滚：还原前端静态资源与 `pom.xml`/`application.yml`，保留 DM 新表不影响旧版本运行；
- 数据清理：`DROP TABLE AUTH_AUDIT/AUTH_KB_GRANT/AUTH_USER_ROLE/AUTH_ROLE/AUTH_USER/USR_PROFILE`，
  Redis 删除 `{prefix}auth:*`。
