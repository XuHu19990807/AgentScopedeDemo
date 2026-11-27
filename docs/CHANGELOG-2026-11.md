# 变更说明（2026-11）

本次提交汇总了 2026-11 期间的五个主题改动：RAG 存储迁移 Milvus、全屏管理控制台与知识库工作台、模型/Embedding 动态配置与流量监控、千万级分段检索精度优化、多格式解析与切片优化。以下按主题说明改动范围、关键文件与验收数据。

---

## 1. RAG 存储：Redis Stack（RediSearch）→ Milvus 3.0

**背景**：原知识库把 BM25 与向量都存在 Redis Stack 的 `idx_knowledge` 索引里，受单机内存与 RediSearch 能力限制。

**改动**
- 新增 `com.cetc28.rag.milvus` 包：`MilvusClientProvider`（懒连接、集合名带版本）、`MilvusEmbeddingModel`（桥接 OpenAI 兼容 embedding 到 AgentScope `EmbeddingModel`）、`MilvusRagStore`（稠密集合，底层 AgentScope `SimpleKnowledge` + `MilvusStore`）、`MilvusBm25Store`（BM25 Function + `SPARSE_INVERTED_INDEX`）、`MilvusHybridRetriever`（双路召回 + 加权 RRF）、`StoredChunk`。
- 引入依赖 `io.agentscope:agentscope-extensions-rag-simple:2.0.5`，并用 `dependencyManagement` 把 `milvus-sdk-java` 覆盖到 `3.0.13` 以匹配 Milvus 3.0.5 服务端。
- 集合结构：`rag_dense_<version>`（id/vector/doc_id/chunk_id/content/payload）与 `rag_bm25_<version>`（id/doc_id/chunk_id/filename/heading_path/line_start/line_end/chunk_index/parent_text/content/domain/sparse）。
- 检索接口抽象为 `KnowledgeRetriever`（`Mode{ HYBRID, BM25, VECTOR }`），`HybridRetriever`/`KnowledgeIndex` 保留为 Redis 时代参考实现但不再装配。
- 上传改为「自己批量 embedding + `MilvusStore.add`」，避免 `SimpleKnowledge.addDocuments` 逐条串行 embedding。

**关键文件**：`rag/milvus/*`、`rag/KnowledgeRetriever.java`、`rag/ChunkView.java`、`rag/RrfUtil.java`、`rag/KnowledgeBaseService.java`、`rag/RagContextBuilder.java`、`config/AIConfig.java`、`pom.xml`、`application.yml`。

---

## 2. 全屏管理控制台 + 知识库工作台

**改动**
- 前端从 880px 弹窗改为全屏控制台（`static/css/console.css`、`static/js/console.js`）：hash 路由 `#/admin/<section>`，七个分区——概览 / 知识库 / 模型 / MCP / 技能 / 记忆 / 日志 / 设置，Esc 返回聊天且保留聊天状态。删除旧 `admin.js`，`app.js` 改为 `initConsole()`。
- 知识库工作台（`static/js/kb.js`）：多文件拖拽上传带进度、文档列表搜索与拖拽排序、分段分页/搜索/拖拽重排/启停/在线编辑、文档重命名/重新解析/原文预览下载/删除、检索测试。
- 后端新增：
  - `rag/KnowledgeStorage.java`：原文落盘 `rag.storage.dir`（默认 `data/knowledge`），支持重解析与非文本格式回退。
  - `rag/KnowledgeChunkState.java`：分段停用墓碑（Redis `knowledge:chunks:disabled`），停用=双集合删行+写墓碑，启用=重嵌+写回。
  - `rag/KnowledgeEditService.java`：分页/详情/编辑/启停/重排/重命名/重解析/文档排序，按 docId 加写锁，单段编辑只重嵌该段。
  - `controller/AdminController.java`：`GET /api/admin/agents`。
  - `KnowledgeController` 新增 11 个端点：`files/{name}/chunks`、`chunk`、`files/{name}/original`、`settings`、`chunk/update`、`chunk/enabled`、`files/{name}/rename`、`files/{name}/reparse`、`files/{name}/chunks/reorder`、`files/order`，并统一 `@ExceptionHandler`（400/409/503）。
- 元数据扩展：`FileMeta` 增 `sortOrder/hasOriginal/disabledCount/updatedAt/domain`，`KnowledgeStats` 增 `disabledChunks/storedOriginals`。
- Milvus 写后读一致性：集合建为 `STRONG`，检索/查询显式 `consistencyLevel(STRONG)`，修复上传后立刻查分段为空的问题。

---

## 3. 模型与 Embedding 动态配置 + 流量监控

**改动**
- 新包 `com.cetc28.modelconfig`：`ChatProfile`/`EmbeddingProfile`/`ModelConfig`（含 `agentProfiles`、`collectionVersion`）、`ModelConfigStore`（Redis `model:config:v1` + 文件兜底 `data/model-config.json`，首次用 yml 播种）、`ModelRuntime`（快照 + 按 agent 解析 + 实例缓存按 revision 失效）、`TrafficModel`（Model 装饰，采集 usage/延迟/错误）、`ModelTrafficService`、`TrafficCall`、`RagReindexService`。
- 会话级模型切换：`ChatStreamRequest.modelId` → `ChatService.resolveModelSelection` → SSE `meta` 带 `modelId/modelName/(modelFallbackReason)`；`Conversation.model` + `ConversationStore.updateModel` 持久化；`AgentBase.variant(profileId)` 生成按模型隔离的 HarnessAgent 变体。
- Embedding 动态生效：`EmbeddingService` 从 `ModelRuntime.defaultEmbedding()` 取配置（yml 兜底）+ 流量埋点；`POST /api/admin/models/embedding/apply` 探测维度，维度变化未 rebuild 返回 409；rebuild 自增 `collectionVersion`、`MilvusRagStore.resetHolder()`、后台 `RagReindexService` 重建索引。
- 用量回传：`AgentBase.mapEvent` 新增 `MODEL_CALL_END` → `model_usage` 事件，`RunContext.addUsage` 累加并发 SSE `usage`（此前 usage 从不触发）。
- 流量存储：内存最近 500 条 + 近 48h 小时桶 + Redis `model:traffic:day:{yyyyMMdd}` HASH（8 天），支持 1h/24h/7d 聚合、p95、RPM、byKind/byProfile/最近调用。
- 接口：`GET /api/models`（对话侧，只读、不鉴权）；`/api/admin/models` 下配置 CRUD、连通性测试、默认模型、Agent 映射、Embedding 应用、重建进度、流量查询/重置。
- 前端：`static/js/models.js`（语言模型 / Embedding / 流量监控三 tab）、console 新增「模型」分区、顶栏 `#modelPicker` 会话级切换、`chat.js` 传 `modelId` 并处理 `model_usage`。

---

## 4. 千万级分段下的检索精度优化

**改动**
- 配置（`RagProperties` + `application.yml`）：`top-k 8`、`dense-candidate-limit 200`、`bm25-candidate-limit 300`、`max-per-doc 3`、`max-per-heading 2`、`dedupe true`、`similarity-filter post`、`min-vector-similarity 0.15`；`rerank` 默认开启、`top-n 100`、`max-candidates 200`、`batch-size 50`、`timeout 3000`。
- 检索流水线：`双路召回 → 加权 RRF → getByIds 回读 → floor → 分批 rerank → post 相似度过滤 → RagSelector（内容哈希去重/同标题≤2/同文档≤3）→ 截断`。
- 索引：`rag.milvus.index{type=HNSW,m=32,ef-construction=200,ef-search=128}`、`dense-mode=auto`；首次建集合前用原生 SDK 预建 HNSW 同 schema 集合，`MilvusRagStore.searchNative` 直接走 `MilvusClientV2.search`（支持 `filter` 与 `ef`），失败自动回退框架 `MilvusStore`。
- 域隔离：`StoredChunk/ChunkView/FileMeta` 增 `domain`，BM25 集合增 `domain` 字段，稠密 payload 写 `domain`，检索/上传/诊断支持 domain 过滤，原生检索用 `payload["domain"] == "..."`。
- 诊断：`KnowledgeRetriever.diagnose` + `GET /api/knowledge/diagnostics?q&mode&topK&domain`，返回 dense/bm25/fused/reranked/selected 各阶段数量、耗时与前 10 条明细。
- 集合版本：默认 `v3`，`ModelConfigStore` 只升不降地采纳 yml 新版本，配合 `RagReindexService` 蓝绿重建。
- 评测：`scripts/gen_rag_eval.py` 生成 3 域 × 6 格式共 18 个文件与 120 题 `src/test/resources/rag-eval/dataset.jsonl`；`RagEvalDatasetTest`（`@Tag("eval")`，`-Drag.eval=true`）输出 Recall@6/@20、MRR@6、nDCG@10，支持阈值参数覆盖。
- 基线（120 题，真实 Milvus）：**Recall@6 0.958、Recall@20 0.975、MRR@6 0.803、nDCG@10 0.846**，avg 336ms / p95 467ms。
- 修复：`RagRerankService` 在 `rag.rerank.api-key` 为空时回退到默认 Embedding 配置的 key（此前 rerank 静默不生效，MRR 仅 0.66）。

---

## 5. 多格式解析与切片优化

**解析层（`DocumentTextExtractor`）**
- docx/pptx 每个段落以空行分隔，段内文本不再被合并成一个巨型单元；docx 标题样式转 Markdown 标题、表格转 Markdown。
- Excel 单元格上限 `chat.files.max-excel-cell-chars=1000`（超长截断加 `…`），每 Sheet 输出 `## Sheet:` + Markdown 表。
- 新增 `.rtf/.odt/.ods/.odp/.epub/.eml/.msg/.wps/.et/.dps`，复用 AgentScope `TikaReader`；现有格式解析失败自动回退 Tika（`chat.files.tika-fallback`）。不新增 Maven 依赖（Tika 随 rag-simple 传递）。

**切片层（`DocumentChunker`）**
- 块模型：`HEADING / PARAGRAPH / LIST / CODE / TABLE / SEPARATOR`。
- 硬上限 `hard = max(target-chars, max-chars)`（默认 400）：不超硬上限绝不切分，整段段落与整行表格完整保留。
- 切分点：先前向 `[hard, hard+80]` 找换行/句末标点，再后向查找，最后才硬切并告警；分片之间不叠加尾重叠。
- 表格：分片重复表头（缺分隔行自动补 `|---|`），超宽单行按未转义 `|` 拆单元格重建。
- 父块截断改为 `truncateAtBoundary`（优先行/句边界），不再直接 `substring`。

**配置**：`rag.chunk.max-chars=400`；`KnowledgeSettings.chunkMaxChars`；控制台设置页新增「分段硬上限」；启动时若 `maxChars+120 > embedding.maxTokens-60` 打印告警。

---

## 6. 缺陷修复

- **中文查询卡死**：surefire JVM 默认 GBK 时，Milvus 拒绝非 UTF-8 中文查询并触发 SDK 无限重试（线程卡在 `RpcUtils.retry`）。修复：surefire 固定 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`；`MilvusClientProvider` 增加 `rpcDeadlineMs`；新增 `config/EncodingGuard` 启动告警；`deploy/env.conf.example` 与 README 补充 UTF-8 启动要求。
- **模型配置**：`ModelConfig.chatForAgent(null)` 的 NPE；`ModelAdminController.testChat` 流末尾取到空文本（改为拼接全文）；`applyEmbedding` 未落盘探测维度。
- **切块**：`fitRow` 换行判断导致超长行；旧重载 `maxChars` 误取 `target+80` 破坏既有契约。
- 约定：含中文的源码只用 UTF-8 编辑器写入，禁止用 PowerShell 管道改文件（会引入 BOM/乱码）。

---

## 7. 验证

| 项 | 结果 |
| --- | --- |
| 全量测试 | `mvn test` 256 通过 / 0 失败 / 2 skipped |
| RAG 评测 | Recall@6 0.958、Recall@20 0.975、MRR 0.803、nDCG 0.846，p95 467ms |
| 切片冒烟 | 26 段 + 41 行表的 docx → 10 块，最长 399 字，表格分片均带表头 |
| 服务启动 | `/api/health` 全绿（Redis/Milvus/模型/9 个 Agent） |

启动命令（本地）：

```bash
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar target/agentchat-v2-2.1.0.jar
```

> `data/`、`_backup_*/`、`*.log`、`earth-ui/node_modules/` 已加入 `.gitignore`，不随仓库提交。
