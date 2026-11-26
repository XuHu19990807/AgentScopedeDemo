# AgentChat v2

基于 **AgentScope Java 2.0** 的多智能体对话平台。v2 是对 `AgentChat` 的重建版本：保留原有 9 个 Agent、RAG、附件分析、MCP、技能库、Nacos 提示词与 SSE 前端，同时把会话状态、上下文注入、多智能体编排重新收敛到 AgentScope 2.0 的官方扩展点上。

- 技术栈：Java 17、Spring Boot 3.5.0、AgentScope 2.0.5（core / harness / openai extension / rag-simple）、Milvus 3.0（知识库向量 + BM25）、Redis Stack（会话状态 / 记忆 / 文件元数据）、Nacos（可选）、MCP Java SDK
- 默认端口：`8092`
- 前端：`src/main/resources/static`（原生 ES Module，SSE 事件契约与 v1 完全一致）

## 1. 与 v1 的关键差异

| 主题 | v1 | v2 |
| --- | --- | --- |
| 身份键 | `userId = sessionId = conversationId` | 真实 `userId` + `conversationId` 分离 |
| 会话状态 | 三个 Agent 共用一个 `agent_state` | 每个 Agent 独立 `AgentStateStore`，key 含 `agentId` |
| 动态上下文 | 拼进 system prompt | `onReasoning` 以 USER 消息注入，system prompt 保持静态 |
| 多智能体编排 | Java 解析计划 JSON / 计划文件 / 关键词 | 模型通过 `dispatch_agents` 工具显式派发 |
| 停止生成 | 只释放 SSE 订阅 | 先 `interrupt(userId, sessionId)`，再释放 |
| 记忆 | 自建 Redis 偏好，框架记忆流水线空转 | 官方 `MEMORY.md` 流水线 + Redis 结构化偏好（混合） |
| MCP | 自管 Toolkit，连接后全量重建 Agent | 沿用管理面，注册收敛到统一 Toolkit 生命周期 |
| 计划模式 | 提示词要求输出 JSON 计划 | 计划模式可选，仅 orchestrator 开启 |

## 2. 快速启动

前置：JDK 17、Maven 3.9+、Milvus 3.0（默认 `http://127.0.0.1:19530`）、Redis Stack（会话状态与记忆；知识库检索已不再使用 RediSearch）、可选 Nacos。

> **必须用 UTF-8 启动 JVM**：Milvus Java SDK 依赖 JVM 默认字符集编码查询串，中文 Windows 上默认 GBK 会让 BM25 中文检索报
> `string data must be utf8 format` 并长时间重试。启动参数要带 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`，
> 应用启动时 `EncodingGuard` 会在字符集不对时打印 ERROR 提示。

```bash
# 1) 配置模型（也可直接改 application.yml）
export AI_API_KEY=sk-xxx
export AI_BASE_URL=https://api.deepseek.com
export AI_CHAT_MODEL=deepseek-v4-pro

# 2) 构建
mvn -DskipTests package

# 3) 运行（UTF-8 参数不可省）
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar target/agentchat-v2-2.1.0.jar
# 或
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
```

生产/内网部署用 `deploy/agentchat.sh start` 或 `deploy/agentchat.ps1 start`，`JAVA_OPTS` 已在 `env.conf.example` 里带上 UTF-8 参数。

健康检查：

```bash
curl http://127.0.0.1:8092/api/health
# {"status":"up","redis":"up","redisSearch":"up","milvus":"up","vectorStore":"milvus","model":"configured","nacos":"up","agents":9}
```

其中 `redisSearch` 现在表示“知识库检索可用”（由 Milvus 状态推导，字段名保留以兼容旧前端），`milvus` 是 Milvus 连通性，`vectorStore` 标明当前检索后端。

浏览器打开 `http://127.0.0.1:8092/`。

## 3. 目录结构

```
src/main/java/com/cetc28/
├── AgentChatApplication.java        # 启动类
├── agentchat/
│   ├── base/                        # AgentBase + 9 个 Agent + AgentRegistry
│   ├── state/                       # 每 Agent 独立的 Redis AgentStateStore
│   ├── middleware/                  # ContextInjectionMiddleware（USER 消息注入）
│   └── orchestration/               # dispatch_agents 工具与编排服务
├── chat/                            # 会话、SSE、附件、导出、Redis 会话存储
├── config/                          # 模型、Redis、Nacos、Agent 装配
├── mcp/                             # MCP 连接管理与工具适配
├── memory/                          # Redis 用户偏好（与官方 MEMORY.md 并存）
├── rag/                             # 多格式解析、父子分块、Milvus 双集合、混合检索、rerank、引用构建
├── skill/                           # 本地 / Nacos / 组合技能仓库
├── tool/                            # 业务工具（知识检索、组网、文件、天气等）
└── workflow/                        # 固定流水线，作为 run_workflow 工具暴露
```

## 4. 核心设计

### 4.1 身份与会话状态

- 所有 Agent 调用显式传入 `(userId, sessionId)`，`sessionId` 使用 `conversationId`；`userId` 来自请求上下文，空值回退 `anonymous`。
- 每个 Agent 在构建时通过 `AgentStateStoreFactory.create(agentId)` 获得独立 store，key 形如：
  `agentchat:v2:agentstate:{agentId}:{userId}:{sessionId}:agent_state`
- 因此同一会话切换 `chat` / `deep` / `orchestrator` 不会互相覆盖历史；同一用户的不同会话也彼此隔离。
- 工作区由官方 `WorkspaceManager` 按 `userId` 命名空间隔离：
  `.agentscope/workspace/{userId}/agents/{AgentName}/...`

### 4.2 上下文注入

`ContextInjectionMiddleware` 实现 `HarnessRuntimeMiddleware`，在 `onReasoning` 阶段：

1. 读取 `RuntimeContext` 中的 `agentContext`（RAG 片段、附件摘要、用户偏好）；
2. 生成一条带 `injectedContext=true` 元数据的 USER 消息；
3. 插入到**最后一条 USER 消息之前**，再交给下游 `ReasoningInput`。

system prompt 只保留角色、规则、工具说明等静态内容，不再随轮次变化，从而保证前缀缓存与 compaction 的稳定性。注入预算由 `app.context.*` 控制：

```yaml
app:
  context:
    knowledge-max-chars: 6000
    attachment-max-chars: 12000
    profile-max-chars: 1500
```

### 4.3 记忆

两层并存：

- **Agent 记忆（官方流水线）**：`MemoryConfig` 开启，`FlushTrigger.throttled(10m)` 节流；每次 flush 追加到 `MEMORY.md` / 当日 `memory/YYYY-MM-DD.md`，并 offload 会话日志；`memory_*` 与 `session_search` 工具可用，`WorkspaceContextMiddleware` 会在预算内加载 `MEMORY.md`。
- **用户偏好（应用层）**：`UserMemoryService` 继续使用 Redis HASH `mem:{userId}:pref`，由 `ChatService` 抽取并作为上下文注入，供 UI 的“记忆”管理页使用。

worker Agent 关闭记忆流水线与工作区上下文，避免每个子任务都触发 flush。

### 4.4 多智能体编排

`OrchestratorAgent` 的 Toolkit 只注册一个工具 `dispatch_agents`：

```json
{
  "parallel": true,
  "tasks": [
    {"agentId": "basic-info", "instruction": "查询北京今天天气，输出温度/湿度/空气质量"},
    {"agentId": "network-planning", "instruction": "为 2000 终端园区给出组网建议"}
  ]
}
```

`DispatchAgentsTool` 从 `RuntimeContext` 取出 `userId` / `sessionId` / 共享上下文，按 `agent.orchestration.parallel-max-agents` 并发调用子 Agent 的 `process(instruction, userId, sessionId, sharedContext)`，把各子 Agent 的结果聚合为 JSON 作为工具结果返回，由模型完成最终综合。子 Agent 的流式事件通过 `RunEventSink` 以 `agent_start` / `agent_result` 转发到同一条 SSE。

超时或异常时调用 `agent.interrupt(userId, sessionId)`，避免后台继续消耗 token。

### 4.5 停止与重新生成

- `POST /api/chat/stop`：先对全部启用 Agent 执行 `interrupt(run.userId, run.conversationId)`，再释放 SSE 并落库 `cancelled`。
- 重新生成：UI 截断到指定消息后重新发起；会话状态按 `(agentId, userId, conversationId)` 独立存放，不会把已删除轮次继续喂给模型（如需彻底重置，可删除该会话的 `agentstate:*` key）。

### 4.6 知识库 RAG（Milvus + AgentScope rag-simple）

检索后端由 Redis Stack（RediSearch）切换为 **Milvus 3.0**，并在其上用 AgentScope 框架自带的 RAG 能力：

- **向量写入/检索**：`SimpleKnowledge` + 框架 `MilvusStore`（`io.agentscope.core.rag.store.MilvusStore`，集合 `rag_dense_v1`）。
- **BM25 词法召回**：框架 `MilvusStore` 的 schema 不能加稀疏字段，因此另建集合 `rag_bm25_v1`，用 Milvus 3.0 的 `FunctionType.BM25` + `SPARSE_INVERTED_INDEX` 自动生成稀疏向量，查询用 `EmbeddedText`，中文分词 `analyzer_params = {"type": "chinese"}`（jieba）。
- **融合**：两路各取 `retrieve.candidate-limit`，应用层加权 RRF（`rrf-k` / `dense-weight` / `bm25-weight`），先按 `max-per-doc` 限每文件条数，再取 `top-k`。
- **父子块**：子块（约 `chunk.target-chars` 字）入向量库，检索命中后注入同章节拼成的父块（`chunk.parent-max-chars`），提升答案完整性。
- **解析**：PDF / DOC / DOCX / PPT / PPTX / XLSX / XLS / CSV / TXT / MD / HTML / JSON 等；DOCX/PPTX 转 Markdown 标题与表格，XLSX 每 Sheet 转 Markdown 表，PDF 去页眉页脚与断词连字符。
- **可选增强**（默认关闭，按需在 `application.yml` 打开）：
  - `rag.rerank.enabled=true`：DashScope `gte-rerank-v2` 对召回结果重排（需要 `rag.rerank.api-key`）。
  - `rag.query-expansion.enabled=true`：向量分低于 `trigger-score` 时用 chat 模型改写 1–2 条查询补 BM25。
- **降级**：Milvus/embedding 任一不可用时返回空上下文并标记 `degraded`，对话不中断；SSE `sources` 事件与 `[K1]` 引用格式不变。

关键配置（`application.yml`）：

```yaml
rag:
  chunk:
    target-chars: 360
    overlap-chars: 48
    parent-max-chars: 1200
  retrieve:
    top-k: 6
    candidate-limit: 40
    rrf-k: 60
    dense-weight: 1.0
    bm25-weight: 0.8
    max-per-doc: 4
    min-vector-similarity: 0.2
  milvus:
    uri: http://127.0.0.1:19530
    database: default
    collection-version: v1         # 集合名后缀：换 embedding 维度时改版本即可并存
    dense-collection: rag_dense
    bm25-collection: rag_bm25
    metric: COSINE
    analyzer: chinese
```

> 换 embedding 模型（维度变化）时：改 `rag.milvus.collection-version` 后重新上传文档，旧集合可保留回滚。
> Milvus Java SDK 版本由 `pom.xml` 的 `dependencyManagement` 固定为 3.0.13（与 Milvus 3.0.x 服务端匹配）。
> Redis 时代的 `KnowledgeIndex` / `HybridRetriever` 仅作为参考与回归对照保留在代码中，已不参与组件装配；如需旧行为请整体回退应用版本，而不是切配置开关。



与 v1 保持一致，前端无需修改：

| 事件 | 说明 |
| --- | --- |
| `meta` | 会话 id、runId、助手消息序号、模型名 |
| `sources` | 知识库引用 `[K1]` 列表 |
| `delta` | 正文增量 |
| `reasoning` | 思考增量 |
| `tool_start` / `tool_exec` / `tool_wait` / `tool_end` | 工具调用生命周期与耗时 |
| `agent_start` / `agent_result` | 子 Agent 开始/结束 |
| `map` | 地图指令（`replace` / `view` / `clear`），前端转发给独立数字地球窗口并随助手消息持久化 |
| `file_created` | 生成文件（下载/预览） |
| `usage` | token 用量 |
| `error` | 错误码与消息 |
| `done` | `assistantSeq`、`status`、`elapsedMs`、`toolsWallMs`、`toolsCount` |

### 4.7 管理控制台与知识库工作台

侧栏的 📚（知识库）与 ⚙（管理控制台）现在进入同一个**全屏控制台**，不再弹 880px 的小模态框：

- 地址栏 hash 路由 `#/admin/<section>`，section ∈ `overview` / `knowledge` / `mcp` / `skills` / `memory` / `logs` / `settings`，刷新或后退可直接落到对应页面。
- 概览页并发汇总 `/api/health`、`/api/knowledge/stats`、`/api/admin/agents`、`/api/mcp/servers`、`/api/skills`，给出运行时状态、知识库规模、Agent 清单与快捷入口。
- MCP / 技能 / 记忆 / 日志保留原有能力，改为控制台内的大画布渲染（日志支持"加载更多"与"刷新尾部"）。
- 设置页只读展示 `GET /api/knowledge/settings`（集合名、维度、融合权重、编辑上限等），并提供管理令牌（`X-Admin-Token`）的本地保存/清除。
- Esc 或"返回聊天"关闭控制台，聊天状态（会话、输入框、滚动位置）不受影响。

知识库工作台（参考 Dify 的两栏布局）：

- **上传**：按钮或整页拖拽，多文件顺序上传并显示进度；上传后用强一致读立即刷新，避免"上传成功但列表为空"的错觉。
- **文档列表**：搜索、点击切换、拖拽排序（顺序写入 `FileMeta.sortOrder`）；每项显示分段数、大小、停用数。
- **分段列表**：分页（20/50/100）、分段内搜索、拖拽重排、启用/停用、编辑后即时重嵌；停用不删除数据，只是从两个集合摘除并写入 Redis 墓碑（`knowledge:chunks:disabled`），可随时恢复。
- **文档操作**：重命名（生成新 docId，原文复制、旧元数据清理）、重新解析（用留存原文重新走解析+切块+重嵌）、原文预览、删除、检索测试（hybrid/bm25/vector 三档，展示 matchedBy 与耗时）。

新增/调整的接口（`KnowledgeController`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/knowledge/files/{name}/chunks?page&pageSize&q` | 分段分页视图（含停用段） |
| GET | `/api/knowledge/chunk?chunkId` | 分段详情（content + parentText） |
| POST | `/api/knowledge/chunk/update` | 编辑分段正文并重嵌该段 |
| POST | `/api/knowledge/chunk/enabled` | 启用/停用分段 |
| POST | `/api/knowledge/files/{name}/chunks/reorder` | 分段拖拽重排（整篇重建） |
| POST | `/api/knowledge/files/{name}/rename` | 重命名文档（docId 变化，历史 chunkId 失效） |
| POST | `/api/knowledge/files/{name}/reparse` | 用留存原文重新解析 |
| POST | `/api/knowledge/files/order` | 文档列表排序 |
| GET | `/api/knowledge/files/{name}/original` | 下载留存原文 |
| GET | `/api/knowledge/settings` | 只读运行配置 |
| GET | `/api/admin/agents` | Agent 清单（控制台概览用） |

新增配置：

```yaml
rag:
  storage:
    dir: ${RAG_STORAGE_DIR:data/knowledge}   # 上传原文留存目录：重新解析/原文预览依赖它
  edit:
    max-reembed-chunks: 500                  # 单次整篇重建（重排/重命名/重解析）的分段上限
    max-page-size: 100                       # 分段分页 pageSize 上限
    max-chunk-chars: 8000                    # 单段正文编辑上限
```

> 原文留存是"重新解析"和"原文预览"的前提；历史（迁移前）上传的文档没有原文，只能删除后重传。
> 知识库的 BM25 查询/检索已开 `consistencyLevel=Strong`（集合属性 + 请求级双保险），保证上传或编辑后立刻可读；代价是每次读多一次同步开销，本机实测 BM25 <50ms。
> 允许"编辑分段/停用分段"这类破坏性操作，写锁按 docId 串行，避免并发互相覆盖。

### 4.8 模型动态配置与流量监控

语言模型与 Embedding 模型不再只能改 `application.yml` 后重启：控制台的「模型」页可以增删改配置、设默认、按 Agent 绑定、应用 Embedding 并重建索引，对话页顶栏还能针对**当前会话**切换语言模型。

- **配置存储**：`ModelConfigStore` 优先读写 Redis `model:config:v1`，Redis 不可用时回退文件 `data/model-config.json`；两者都没有时用 `agentscope.model.openai.*` / `agentscope.embedding.*` 播种一条默认配置。每次写入都会递增 `version`，`ModelRuntime` 据此重建模型实例。
- **运行时解析**：所有 Agent 的模型调用都经过 `TrafficModel` 代理，每次调用时按 `agentProfiles[agentId] → defaultChat` 实时解析，因此后台改配置无需重启、也无需重建 Agent；会话级切换通过 `AgentBase.variant(profileId)` 生成并缓存一个使用固定模型的 Agent 变体。
- **按会话切换**：对话页顶栏的模型下拉把 `modelId` 放进 `/api/chat/stream` 的请求体；后端把选中的配置写进会话（`Conversation.model`）与助手消息（`ChatMessage.modelId`），未知/停用配置会回退默认模型并在 `meta` 事件里带 `modelFallbackReason`。编排模式只让 orchestrator 跟随会话模型，worker 仍按各自的 Agent 映射。
- **Embedding 应用**：切换默认 Embedding 前会先用该配置探测维度；维度变化必须勾选"同时重建索引"，此时 `collectionVersion` 自增（`v1 → v2`），新集合名随之变化，后台 `RagReindexService` 用留存原文逐篇重新解析入库，进度可在页面轮询查看（无原文的历史文档会被跳过并计入 failed）。
- **流量监控**：`TrafficModel`、`EmbeddingService`、`RagRerankService` 三个调用点统一记录 `TrafficCall`；内存保留最近 500 条 + 按天聚合，Redis HASH `model:traffic:day:{yyyyMMdd}` 保存 8 天历史。页面支持 1h/24h/7d 窗口、按配置/按类型分解、趋势柱状图与最近 50 条明细。
- **用量回传**：以前 `usage` 事件实际从不触发；现在每轮 `MODEL_CALL_END` 会累加输入/输出/缓存 token 到 `RunContext`，收尾时随 `usage` 事件与助手消息一起持久化。

新增接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/models` | 对话页模型下拉（只暴露 id/name/modelName/default） |
| GET | `/api/admin/models` | 管理端完整配置（密钥脱敏）+ 集合版本/重建进度 |
| POST | `/api/admin/models/chat` | 新增或更新语言模型配置 |
| DELETE | `/api/admin/models/chat/{id}` | 删除配置（至少保留一个可用配置） |
| POST | `/api/admin/models/chat/{id}/test` | 用该配置发一次真实请求 |
| POST | `/api/admin/models/default` | 设置默认语言模型 / Embedding（`kind` + `id`） |
| POST | `/api/admin/models/agent-map` | 按 Agent 绑定语言模型 |
| POST | `/api/admin/models/embedding` | 新增或更新 Embedding 配置 |
| POST | `/api/admin/models/embedding/{id}/test` | 探测 Embedding 维度 |
| POST | `/api/admin/models/embedding/apply` | 应用为默认 Embedding，`rebuild=true` 时重建索引 |
| GET | `/api/admin/models/embedding/reindex` | 索引重建进度 |
| GET | `/api/admin/models/traffic?window=1h\|24h\|7d` | 流量聚合 |
| POST | `/api/admin/models/traffic/reset` | 清空流量统计 |

新增配置：

```yaml
agentchat:
  model:
    config-file: data/model-config.json   # 模型配置的本地兜底文件
```

> 模型配置里的密钥在接口返回时一律脱敏为 `sk-****`，前端回传 `****` 或空值表示"沿用原密钥"。
> 切换 Embedding 并重建索引期间，检索仍可工作：新集合是空的，旧集合保留到新集合写完为止；重建完成后旧集合不再被引用，可手工清理。

## 6. 主要配置


```yaml
app:
  state:
    prefix: "agentchat:v2:"        # 与 v1 数据隔离
  sse:
    timeout-seconds: 120
  memory:
    extract-enabled: true

agent:
  harness:
    workspace-enabled: true
    memory-enabled: true
    plan-mode-enabled: true
    max-iters: 12
  orchestration:
    parallel-max-agents: 6
    parallel-worker-timeout-seconds: 300
  permission:
    mode: BYPASS                   # BYPASS / DEFAULT / STRICT
```

模型、Redis、Nacos、MCP、RAG、上传目录等沿用 v1 的配置键，见 `application.yml` 注释。

### Nacos 提示词兼容

`NacosPromptManager` 仍按 `Nacos → 本地 prompts/{agentId}.txt → 硬编码` 的顺序加载。v2 对 orchestrator 增加能力校验：远端模板若不含 `dispatch_agents`（v1 的“只输出 JSON 计划”版本），会记录 warning 并回退本地 `prompts/orchestrator.txt`，避免旧提示词让编排失效。建议同时在 Nacos 控制台更新 `orchestrator.json`。

## 7. 数字地球（Cesium 3D / OpenLayers 2D）

数字地球已从问答页左侧拆成**独立窗口**，适合双屏：左屏放地球，右屏放对话。智能体回答地点/区域/路线类问题时自动上图；地球窗口可由问答页顶栏地球按钮打开，也可直接访问 `/earth/index.html`。详见 [docs/digital-earth.md](docs/digital-earth.md)。

- **前端**：Vue 2 子应用在 `earth-ui/`，构建到 `src/main/resources/static/earth/`，作为独立页面打开；主页面仍是原生 ES Module，不迁移框架，聊天区恢复全宽。
- **跨窗口联动**：问答页 `js/map-bridge.js` 与地球窗口用同源 `BroadcastChannel('agentchat-earth-v1')` 通信；窗口带 `?chatId=`，通过 `hello/sync` 补齐当前标注；关闭后重开、刷新问答页都能重新对齐。要求同一浏览器同一 origin/profile。
- **3D/2D 自动切换**：地球窗口不再有 3D/2D 按钮。3D 滚轮放大到相机高度 ≤ `autoSwitch.to2dHeight`（默认 1.5M 米）自动切 2D；2D 滚轮缩小到层级 ≤ `autoSwitch.to3dZoom`（默认 3.5）自动切回 3D；中间留滞回区，程序化 `fit` 不触发。阈值在 `earth/config.js` 可改。
- **SDK 外置**：Cesium 的 `Build/Cesium/*` 放到 `map-assets/cesium/`，OpenLayers v6.15.1 的 `ol.js`/`ol.css` 放到 `map-assets/ol/`；通过 `app.map.assets-dir`（默认 `./map-assets`）提供，不打进 jar。当前工作区已放入 Cesium 1.95.0、OL 6.15.1 单文件包，便于本机直接验收。
- **后端**：`map_render` / `map_view` / `map_clear` 三个工具；工具经 `RuntimeContext` 取 `conversationId`，用 `RunRegistry` 找到当前 SSE 并推送 `map` 事件，Multi Agent worker 同样生效。后端只下发语义 `style` 与几何，不下发颜色/图标/线宽/图例文案；线要素可直接写 `cities` 由后端解析。
- **标绘样式可扩展**：外观集中在 `earth-ui/public/plot-styles.json` 与 `public/plot-icons/`；改颜色、线型、图标、图例文案只改前端文件，后端不需要加白名单。内网已有图片可放到 jar 外部的 `./plot-images/`（`PLOT_IMAGES_DIR` 可改），通过 `/plot-images/xxx.png` 引用；也支持完整 URL、根路径和 `data:`。新增样式、继承、别名与校验见 [docs/plot-protocol.md](docs/plot-protocol.md)。
- **历史回放**：`ChatMessage.mapCommands` 持久化本轮地图命令；打开历史会话时回放最后一条带命令的助手消息，并同步到已打开的地球窗口。
- **离线底图**：默认用 `map-assets/basemap/natural-earth-ii/` 的 z0–z5 瓦片（Natural Earth 1:50m，公共领域），不依赖 Cesium Ion；留空 `basemap.baseUrl` 时回退 Cesium 自带瓦片，也可在 `earth/config.js` 切换内网 XYZ/单张影像。
- **运行期**：Win7 使用 Chrome/Edge 109（不支持 IE11）；Linux 无特殊要求。运行期不需要 Node，只有修改 `earth-ui/` 后重新构建时需要（建议 Node 16）。独立窗口被浏览器拦截时需允许本站弹窗，或手动打开 `/earth/index.html`。

```bash
# 仅在修改 earth-ui 后需要
cd earth-ui && npm ci && npm run build
```

Nginx 必须对 `/api/chat/stream` 关闭 `proxy_buffering`，否则 `map` 事件会被缓冲；CSP 需允许 `worker-src 'self' blob:`。

## 8. 测试

```bash
mvn test          # 单元测试（含状态隔离、注入、编排、SSE 映射、RAG、MCP 适配、地图等）
mvn verify        # 打包 + 测试
```

当前基线：235 个测试通过（1 skipped），其中地图/标绘相关 28 个。

## 9. 已知限制

- 记忆 consolidation 默认 30 分钟一次，会额外调用模型；可通过 `MemoryConfig.consolidationMinGap` 调整。
- MCP 服务连接变化仍会触发 Agent 快照重建（在途请求使用旧快照，完成后关闭）。
- 计划模式仅 orchestrator 启用，计划文件位于用户工作区 `plans/PLAN.md`。
- `reasoning` 事件的粒度取决于模型供应商；部分模型会把分析文本混入正文增量。
