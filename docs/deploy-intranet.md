# AgentChat-v2 内网部署与升级手册

本文覆盖：内网离线构建、部署、启动/停止、健康检查、升级与回滚、常见故障排查。

---

## 1. 本次变更（AgentScope 2.0.0 → 2.0.5）

| 文件 | 变更 |
|---|---|
| `pom.xml` | `<agentscope.version>2.0.0</agentscope.version>` → `2.0.5` |
| `deploy/env.conf.example` | 新增，环境变量模板 |
| `deploy/agentchat.sh` | 新增，Linux 运维脚本 |
| `deploy/agentchat.ps1` | 新增，Windows 运维脚本 |
| `docs/deploy-intranet.md` | 本文 |

无 Java 源码改动，无接口变更。

### 为什么要升

生产聊天一进来就 400，报错形如：

```
HTTP transport error during streaming: HTTP request failed with status 400 |
{"error":{"message":"1 validation error:\n {'type': 'missing', 'loc': ('body',),
 'msg': 'Field required', 'input': None}","type":"Bad Request","param":"body","code":400}}
```

`loc: ('body',)` 且 `input: None` 表示**整个请求体是空的**（不是缺某个字段）。

根因是 AgentScope Java 2.0.0 的已知缺陷：`HttpTransportConfig` 默认 `httpVersion = HTTP_2`，而 JDK `HttpClient` 在**明文 `http://`** 地址上会先尝试 h2c upgrade（`Upgrade: h2c` + `Connection: Upgrade, HTTP2-Settings`）。uvicorn/h11（Python 侧 FastAPI/vLLM 的服务端栈）会把带 upgrade 标记的请求当成「没有请求体」处理，于是 FastAPI 收到空 body → 400。

- 修复：[PR #3214](https://github.com/agentscope-ai/agentscope-java/pull/3214)，从 **2.0.4** 起生效：默认改为按请求决定协议——`http://` 走 HTTP/1.1，`https://` 仍走 ALPN HTTP/2。
- 对应 issue：[#628](https://github.com/agentscope-ai/agentscope-java/issues/628)、[#1121](https://github.com/agentscope-ai/agentscope-java/issues/1121)（报错文本与本次完全一致）。
- 发布记录：[Release Notes 2.0.4](https://java.agentscope.io/v2/zh/docs/others/release-notes) 修复项原文「明文 HTTP 请求默认使用 HTTP/1.1，避免 h2c upgrade 导致 vLLM / uvicorn 丢失 POST 请求体」。

2.0.5 是 2.0.x 维护线最新稳定版（2026-11-14 发布），官方声明 "no new modules and no removed public APIs"，因此升级无源码改动。顺带获得：SSE 长连接不再被绝对超时切断（2.0.1 起）、无 HTTP status 的流式错误可重试。

---

## 2. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17（`java -version` 确认） |
| Redis | 6.x+，建议同机；知识库检索需要 RediSearch 模块 |
| 端口 | 8092（`server.port`，可用 `SERVER_PORT` 改） |
| 磁盘 | 应用 + 日志，建议 ≥2 GB 余量 |
| 系统 | Linux（x86_64）或 Windows Server 2016+ |
| 命令 | Linux：`curl`、`ss` 或 `netstat`；Windows：PowerShell 5.1+ |

---

## 3. 目录约定

```
/opt/agentchat-v2/
├── agentchat-v2-2.1.0.jar      # 可执行 fat jar
├── deploy/
│   ├── agentchat.sh            # Linux 运维脚本
│   ├── agentchat.ps1           # Windows 运维脚本
│   └── env.conf                # 环境配置（含密钥，600 权限，不入库）
├── logs/
│   ├── console.log             # 标准输出
│   └── console.err.log         # 标准错误（Windows）
├── run/
│   └── agentchat.pid
└── uploads/                    # 附件上传目录（首次启动自动创建）
```

---

## 4. 离线构建

内网无法访问公网 Maven 仓库时，用**外网机器构建好 fat jar** 直接拷入内网最省事；若必须在内网构建，按下面第 2 步准备离线仓库。

### 4.1 外网机器（推荐路径）

```bash
cd AgentChat-v2
mvn -DskipTests package
# 产物：target/agentchat-v2-2.1.0.jar
```

把该 jar 拷到内网 `/opt/agentchat-v2/`。

### 4.2 内网自建（需要内网构建时）

外网机器同版本 JDK、同版本 mvn，先拉齐依赖：

```bash
mvn -DskipTests dependency:go-offline
```

把 `~/.m2/repository` 整体（或至少 `io/agentscope/`、`com/fasterxml/`、`io/modelcontextprotocol/` 等本次新增项）拷入内网机器的 `~/.m2/repository`，然后在内网验证：

```bash
mvn -o -DskipTests package        # -o = 离线模式
```

`go-offline` 偶有漏项，报 `Could not resolve dependencies` 时按报错把对应的 `groupId/artifactId/version` 目录补拷后重试。

> 注意：本次升级新增了 `io.modelcontextprotocol` 相关依赖，离线仓库若只拷了 `io/agentscope/` 会构建失败。

---

## 5. 部署

### 5.1 Linux

```bash
cd /opt/agentchat-v2/deploy
cp env.conf.example env.conf
vim env.conf                       # 至少改 AI_BASE_URL / AI_API_KEY / AI_CHAT_MODEL
chmod 600 env.conf
chmod +x agentchat.sh

./agentchat.sh verify              # 部署前自检：版本、网关可达、鉴权
./agentchat.sh start
./agentchat.sh status
```

### 5.2 Windows

```powershell
cd D:\agentchat-v2\deploy
Copy-Item env.conf.example env.conf
notepad env.conf
.\agentchat.ps1 verify
.\agentchat.ps1 start
.\agentchat.ps1 status
```

> `agentchat.ps1` 必须以 **UTF-8 with BOM** 保存（否则 Windows PowerShell 5.1 会按 GBK 解析导致中文乱码、语法报错）。若用记事本编辑过脚本，请确认编码。

### 5.3 env.conf 关键项

| 变量 | 说明 |
|---|---|
| `APP_HOME` | 应用根目录，相对脚本目录或绝对路径 |
| `JAR` | jar 路径，相对 `APP_HOME` |
| `JAVA_BIN` | `java`，或 JDK 绝对路径 |
| `JAVA_OPTS` | JVM 参数，含 `-Xmx`、时区 |
| `SERVER_PORT` | 与 `server.port` 一致，默认 8092 |
| `AI_BASE_URL` | 模型网关地址。**明文 `http://` 时必须用 2.0.4+ 版本** |
| `AI_API_KEY` | 网关密钥 |
| `AI_CHAT_MODEL` | 主模型名 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | Redis 连接 |
| `NACOS_ENABLED` | 内网没有 Nacos 时设 `false`，避免启动反复重试 |
| `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_MAX_TOKENS` | 知识库向量模型，不配则退化为纯全文检索；本地无鉴权模型的写法见 5.4 |

这些变量会直接作为进程环境变量传给 JVM，对应 `application.yml` 里的 `${AI_BASE_URL}` 等占位符。

### 5.4 Embedding：本地无 key 的模型

`EmbeddingService` 判定「可用」的条件是 `base-url` 与 `model` **都非空**（`src/main/java/com/cetc28/service/EmbeddingService.java`）。`api-key` 允许留空：留空时请求里不会带 `Authorization` 头，正好匹配内网无鉴权的本地模型；填了密钥则按 `Bearer <key>` 发送。

`env.conf` 写法（以 Ollama 为例）：

```ini
EMBEDDING_API_KEY=
EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1
EMBEDDING_MODEL=bge-m3
EMBEDDING_MAX_TOKENS=512
```

三个硬性约束：

1. `base-url` 必须是 **OpenAI 兼容根路径**（带 `/v1`），且**不带尾斜杠**：代码是 `baseUrl + "/embeddings"`，带斜杠会拼成 `//embeddings` 而被 404。
2. `model` 必须与服务端注册名完全一致；Ollama 要带 tag，如 `bge-m3:latest`。
3. `EMBEDDING_MAX_TOKENS` 要与模型上下文一致（BERT 系多为 512）。它只影响**截断**：超出部分会被裁掉再送模型，裁掉的内容检索不到也看不出来。RAG 切块默认 360 字符正是按 512 token 预算定的，见 `RagProperties`。

常见本地服务的 base-url：

| 服务 | base-url |
|---|---|
| Ollama | `http://127.0.0.1:11434/v1` |
| vLLM | `http://127.0.0.1:8000/v1` |
| LM Studio | `http://127.0.0.1:1234/v1` |
| Xinference | `http://127.0.0.1:9997/v1` |
| TEI（text-embeddings-inference） | `http://127.0.0.1:8080/v1` |

上线前先直接打服务端，确认模型名和维度：

```bash
curl -s -X POST http://127.0.0.1:11434/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":"测试"}' | head -c 200
```

应用侧验收：启动日志出现 `Embedding 维度探测为 N（模型 xxx）` 即通；`GET /api/knowledge/stats` 里的 `embeddingEnabled` / `embeddingTarget` / `lastEmbeddingError` 可在线核对。

换模型时若维度变了，`KnowledgeIndex` 会检测到不一致并重建向量索引、给存量块补向量，首次会有一段补向量耗时（日志 `知识库索引初始化` 前缀）。

---

## 6. 运维脚本

Linux `agentchat.sh` 与 Windows `agentchat.ps1` 子命令与退出码完全一致。

| 命令 | 行为 | 退出码 |
|---|---|---|
| `start` | 后台启动；已在运行则直接返回；轮询 `/api/health` 最多 `START_TIMEOUT` 秒 | 0 成功；1 失败 |
| `stop` | 校验 PID 归属后优雅停机，超时再强杀 | 0；1 |
| `restart` | stop → start | 同 start |
| `status` | 运行状态 / PID / 端口 / 健康摘要 | 0 运行且 up；2 运行但 degraded；1 未运行 |
| `health` | 打印 `/api/health` 原始 JSON | 0 up；2 degraded；1 不可达 |
| `verify` | 配置、网关探针、日志错误计数、本地健康 | 0 通过；1 不通过 |
| `logs [n]` | 控制台日志最后 n 行（默认 200） | 0 |
| `tail` | 实时跟随日志 | 0 |
| `env` | 打印生效配置，`AI_API_KEY` 脱敏 | 0 |

启动失败时脚本会自动打印最后 40 行日志，不需要再手工去翻。

### health 字段含义

```json
{"status":"up","redis":"up","redisSearch":"up","model":"configured",
 "nacos":"disabled","mcpServers":0,"agents":3}
```

| 字段 | 取值 | 含义 |
|---|---|---|
| `status` | `up` / `degraded` | 仅由 Redis 决定；`degraded` 表示 Redis 不通，会话与记忆会失效 |
| `redis` | `up` / `down` | Redis PING |
| `redisSearch` | `up` / `missing` / `unknown` | 知识库索引 `idx_knowledge` 是否存在；`missing` 只影响知识库检索 |
| `model` | `configured` / `missing_key` | `agentscope.model.openai.api-key` 是否为空 |
| `nacos` | 描述字符串 / `disabled` | Nacos 可用性门控状态 |
| `mcpServers` | 数字 | 已连接 MCP 服务数 |
| `agents` | 数字 | 启用的 Agent 数 |

`/api/health` 不要求鉴权，脚本用它判断就绪。

---

## 7. 升级与回滚

### 升级（以 2.0.0 → 2.0.5 为例）

1. 备份当前 jar：`cp agentchat-v2-2.1.0.jar agentchat-v2-2.1.0.jar.2.0.0.bak`
2. 拷贝新 jar 覆盖 `agentchat-v2-2.1.0.jar`
3. `./agentchat.sh verify` 看到 `agentscope : 2.0.5` 且网关探针 200/401
4. `./agentchat.sh restart`
5. `./agentchat.sh status` → `status=up`
6. 回归：前端发起一次对话，流式输出正常；再测一次长回答（≥2 分钟）

`verify` 读取的是 **fat jar 内 `BOOT-INF/lib/agentscope-core-*.jar` 的实际版本**，能确认部署的到底是不是新版本。

### 回滚

```bash
cp agentchat-v2-2.1.0.jar.2.0.0.bak agentchat-v2-2.1.0.jar
./agentchat.sh restart
```

回滚后会重新暴露 h2c 缺陷。若此时 `AI_BASE_URL` 仍是明文 `http://`，聊天会再次 400；临时止血手段：把 `AI_BASE_URL` 指向 `https://` 的网关地址（`https` 走 ALPN，不受该缺陷影响）。

---

## 8. 故障排查

| 现象 | 判断 | 处理 |
|---|---|---|
| 聊天 400，`1 validation error ... loc ('body',)` | 请求体在网关侧为空 | `./agentchat.sh verify` 看 `agentscope` 版本；< 2.0.4 且 `http://` 即命中 h2c 缺陷 → 升级 jar |
| 聊天 401 | 密钥无效 | 检查 `AI_API_KEY`；`verify` 的网关探针会直接返回 401 |
| 聊天 400 但 `param` 指向 `model` | 模型名不被网关识别 | 改 `AI_CHAT_MODEL`，用网关 `/v1/models` 核对 |
| 回答到一半停住 | 长连接被切断 | 2.0.1 起已修 SSE 绝对超时；确认版本 ≥2.0.1，并检查前置 nginx 的 `proxy_read_timeout`（建议 ≥300s） |
| `status=degraded` | Redis 不通 | `redis-cli -h <host> ping`；`redisSearch=missing` 只影响知识库 |
| `model=missing_key` | 没配密钥 | 检查 `env.conf` 的 `AI_API_KEY` 是否为空 |
| 启动即退出 | 端口占用/配置错误 | 脚本已打印日志尾部；看 `APPLICATION FAILED TO START` 的具体原因 |
| `端口 8092 已被占用` | 残留进程 | `ss -ltnp \| grep :8092` 找到 PID 后处理 |
| `PID 不是本服务进程` | PID 被系统复用 | 脚本拒绝误杀；人工核对 `ps -p <pid> -o args=` 后清理 PID 文件 |
| 内网 `mvn -o` 报缺包 | 离线仓库不全 | 按报错补拷对应 `groupId/artifactId/version` 目录 |

### 日志

| 文件 | 内容 | 由谁产生 |
|---|---|---|
| `logs/console.log` | 控制台输出（含启动失败原因） | 运维脚本重定向 |
| `logs/console.err.log` | 标准错误 | 运维脚本（Windows） |
| `${LOG_PATH}/agentscope-demo.log` | 应用文件日志，按天滚动、保留 7 天，`/api/logs` 在线查看的就是它 | logback（`logback-spring.xml`） |

`LOG_PATH` 未设置时，应用文件日志落在 `java.io.tmpdir` 下，重启/清理可能丢失。建议在 `env.conf` 的 `JAVA_OPTS` 里显式指定：

```bash
JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -DLOG_PATH=/opt/agentchat-v2/logs/app"
```

排障期可临时把 AgentScope 的 HTTP 日志打开（`application.yml` 第 366 行附近）：

```yaml
logging:
  level:
    io.agentscope: DEBUG
```

会打印 `OpenAI request to {}: {}`，能直接看到请求 URL 与请求体，用于确认到底是「没发 body」还是「body 被中间层吃掉」。

### 确认实际使用的 HTTP 版本

升级到 2.0.4+ 后，`http://` 应走 HTTP/1.1、`https://` 走 ALPN HTTP/2。四种确认手段，按代价从低到高：

**1. 自带探针（推荐，服务器上直接跑，无需动 jar）**

```bash
java tools/HttpProtocolProbe.java http://10.0.0.10:8000/v1/chat/completions
# mode: auto -> HTTP_1_1（http）/ HTTP_2（https，ALPN）
# negotiated: HTTP_1_1        <- 客户端真实协商结果
```

加 `--mode h2` 可以故意重放 2.0.0 的 h2c 行为做对照：

```bash
java tools/HttpProtocolProbe.java http://10.0.0.10:8000/v1/chat/completions --mode h2
```

**2. 抓包看 h2c 指纹（最硬的证据）**

```bash
tcpdump -i any -s0 -A -n 'tcp port 8000' | grep -a -E 'Upgrade: h2c|HTTP2-Settings'
```

有输出 = 请求里带了 h2c upgrade（旧行为，会被 uvicorn/h11 当成无 body）；无输出且能看到 `POST /v1/chat/completions` = 已是 HTTP/1.1。

**3. 服务端日志**

- nginx：`log_format` 里加 `$server_protocol`，正常值为 `HTTP/1.1`，开了 http2 的 vhost 会记 `HTTP/2.0`。
- FastAPI / LiteLLM 自研网关：中间件里打印 `request.scope.get("http_version")`（`"1.1"` / `"2"`）。

**4. JDK 客户端日志**

在 `env.conf` 的 `JAVA_OPTS` 里加：

```
-Djdk.httpclient.HttpClient.log=requests,headers,frames
```

`frames` 只在 HTTP/2 上出现；如果只有 `REQUEST:`/`RESPONSE:` 而没有帧日志，说明走的是 1.1。排障完记得去掉，日志量很大。

判定对照：

| 观察到的现象 | 结论 |
|---|---|
| 抓包见 `Upgrade: h2c` / `HTTP2-Settings` | 还在 h2c（版本 < 2.0.4，或显式设了 `HTTP_2`） |
| 探针 `negotiated: HTTP_1_1` + 400 消失 | 修复生效 |
| 探针 `negotiated: HTTP_2` 且 URL 是 `http://` | 服务端接受了 h2c upgrade，需确认对端是否真支持 |
| 探针报 `Connection refused` / 超时 | 网络或端口不通，与协议无关 |

> 注意：`curl` 默认不会对明文地址尝试 h2c，所以「curl 能通」不能证明应用侧没问题；要看应用实际发了什么，用第 1、2、4 种手段。


### logrotate 示例

```
/opt/agentchat-v2/logs/console.log {
    daily
    rotate 14
    compress
    missingok
    notifempty
    copytruncate
}
```

---

## 9. 多实例说明

脚本按「一个 `env.conf` → 一个 PID 文件」管理单实例。同机多实例请各自复制一份目录与 `env.conf`，并在每个 `env.conf` 里设置不同的 `SERVER_PORT`、`PID_FILE`、`LOG_DIR`，再分别调用脚本（可用 `ENV_FILE=/path/env.conf ./agentchat.sh start` 指定配置）。
