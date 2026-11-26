# RAG 能力说明（知识库检索增强）

本文档描述 AgentChat 的知识库 RAG 链路：**写入 → 索引 → 混合检索 → 注入对话 → 引用核对 → 评测**。
目标是把「模型不知道的知识」变成运行时可检索、可查询、可验证的外部上下文，让模型基于证据推理，
并让统筹 Agent 能根据检索结果决定是否派发知识型任务。

## 1. 能力边界

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 文档写入 + 结构化切块 | ✅ | 标题路径、表格整组、长段按句切分，块间重叠 48 字符 |
| 全文检索（BM25 中文） | ✅ | RediSearch `TEXT` 字段，中文二元组回退 |
| 向量检索（HNSW） | ✅ | 索引内 `VECTOR` 字段，1024 维，余弦距离 |
| 混合检索 + RRF 融合 | ✅ | BM25 与向量各取 30 条，RRF(k=60) 融合后归一化 |
| 对话自动注入 | ✅ | `ChatService` 注入 `[知识库检索结果]`，模型以 `[K1]` 引用 |
| 引用可核对 | ✅ | SSE `sources` 事件 + `ChatMessage.sources` 持久化 |
| 检索质量评测 | ✅ | `RagEvalTest`，Recall@5 / MRR@5 |
| 答案依据核查 | ✅ | `POST /api/knowledge/verify` 逐句打分 |
| 多轮 Agentic 检索 | ✅ | `KnowledgeAgent` 可用 `searchKnowledge`/`readChunk`/`knowledgeOverview` |
| rerank / 查询改写 | ❌（预留） | `rag.rerank.enabled=false`，默认关闭 |
| 多租户 / 文档级权限 | ❌ | 索引里保留 `userId` 字段但暂不过滤 |
| OCR / 图片 | ❌ | 仅文本与可抽取文本的文档 |

## 2. 链路

### 写入

```
Upload(MultipartFile)
  → DocumentTextExtractor.extract            文本（PDF/Word/Excel/TXT/Markdown…）
  → DocumentChunker.chunk                    360 字符目标、48 重叠、标题路径、表格整组
  → EmbeddingService.embedBatch(16/批)       DashScope 兼容接口，失败逐条退化
  → KnowledgeIndex.storeChunk                HSET doc:knowledge:{docId}:{chunkIndex}
  → knowledge:files                          FileMeta（最后写，失败回滚已写块）
```

- `docId = sha1(filename)[0:16]`：同一文件重传会覆盖旧块（先算 `oldKeys`，写完后删除 stale 键）。
- `contentHash` 相同的块在重传时直接复用，避免重复计费 embedding。
- 元数据最后写：块数据或向量写失败时会回滚，不会出现「有块没元数据」的中间态。
- 上传返回 `warnings`：`覆盖同名文件旧块 N 个` / `向量生成失败，语义检索暂不可用（全文检索正常）`。

### 索引（schema v2）

```
idx_knowledge
  TEXT    content  (weight 1)    TEXT headingPath (weight 0.5)
  TAG     filename, docId
  NUMERIC chunkIndex, uploadTime, fileSize
  VECTOR  vector  HNSW FLOAT32 DIM=1024 DISTANCE_METRIC=COSINE
```

- 版本写在 `knowledge:index:version`，维度写在 `knowledge:index:dim`。
- 维度变化（换 embedding 模型）时自动 `FT.DROPINDEX` 重建，并在后台回填向量。
- 启动后台任务：`migrateLegacyChunks`（旧逐行块 → docId/chunkIndex 结构）、`backfillMissingVectors`。

### 检索

```
query
  ├─ BM25:  @content:(terms) | @headingPath:(terms)    top 30   （长中文串失败时用二元组 OR 回退）
  └─ KNN:   *=>[KNN 30 @vector $BLOB AS vector_score]  top 30   （相似度 = 1 - distance，按相似度降序）
  → RRF(k=60) 融合 → 归一化到 1.0 → 过滤 minScore / 每文档 ≤4 条 / topK
  → RetrievalResult(hits, degraded, tookMs, bm25Count, vectorCount)
```

失败时绝不抛出：`degraded=true` + 原因，对话继续走全文检索或纯模型回答。

### 注入与引用

`ChatService` 在调用 Agent 前检索，把结果拼成：

```
[知识库检索结果]
只依据下列片段回答；引用时在句末标注编号 [K1]；证据不足时明确说明…
[K1] 来源: test-chat-data.txt · 第 6 行 (相关度 0.83)
五、预算：约285万元，含光纤铺设、设备采购、施工调试
```

同时通过 SSE 发 `sources` 事件（`id/chunkId/filename/location/score/snippet`），并在 `finish` 时写入
`ChatMessage.sources`，刷新页面仍可核对出处。

编排模式下：只把**摘要**给统筹 Agent（用于决定是否派 knowledge），完整片段只给 `knowledge` worker，
避免其他 Agent 复述引用块、稀释各自职责。

## 3. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge/upload` | 上传并建索引，返回 `success/message/chunkCount/docId/warnings` |
| GET | `/api/knowledge/files` | 文件列表（含块数、大小、上传时间） |
| GET | `/api/knowledge/content?filename=` | 某文件的全部块 |
| GET | `/api/knowledge/search?q=&topK=6&mode=hybrid\|bm25\|vector&minScore=` | 检索，返回 `hits[]` 含 score/location/matchedBy |
| GET | `/api/knowledge/stats` | `chunks/vectors/dimension/vectorReady/schemaVersion/files/embedding*` |
| POST | `/api/knowledge/verify` | `{query, answer, chunkIds?}` → 逐句 `supported/score` + `unsupportedRatio` |
| GET | `/api/knowledge/ftsearch?q=&limit=` | 纯全文检索（兼容旧前端） |
| GET | `/api/knowledge/semantic?q=&topK=` | 纯向量检索（兼容旧前端） |
| DELETE | `/api/knowledge/files/{filename}` | 删除块 + 向量 + 元数据（失败保留元数据并返回 false） |

示例：

```bash
curl -s "http://127.0.0.1:8091/api/knowledge/search?q=%E7%BB%84%E7%BD%91%E9%9C%80%E8%A6%81%E5%93%AA%E4%BA%9B%E8%AE%BE%E5%A4%87&topK=3&mode=hybrid"
curl -s -X POST "http://127.0.0.1:8091/api/knowledge/verify" \
  -H "Content-Type: application/json" \
  -d '{"query":"项目预算多少","answer":"预算约285万元 [K1]"}'
```

> `/api/knowledge/**` 属管理接口：默认仅本机可访问，远程需带 `X-Admin-Token`（见 `WebConfig`）。

## 4. 配置（`application.yml` → `rag.*`）

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `rag.enabled` | true | 总开关（关闭后不检索、不建索引） |
| `chunk.target-chars` | 360 | 块目标长度；受 embedding `max-tokens` 约束（中文≈1 token/字） |
| `chunk.overlap-chars` | 48 | 块间重叠 |
| `chunk.max-chunks-per-file` | 5000 | 单文件块上限 |
| `embed.batch-size` | 16 | 批量 embedding 条数 |
| `embed.dimension` | 0 | 0=启动探测，非 0 时强制该维度 |
| `retrieve.top-k` | 6 | 最终返回块数 |
| `retrieve.candidate-limit` | 30 | 单路召回上限 |
| `retrieve.rrf-k` | 60 | RRF 融合常数 |
| `retrieve.max-per-doc` | 4 | 单文档最多保留块数 |
| `retrieve.min-score` | 0.0 | 融合分下限 |
| `retrieve.min-vector-similarity` | 0.2 | 向量相似度下限 |
| `retrieve.timeout-ms` | 2500 | 检索超时 |
| `inject.enabled` | true | 是否自动注入对话 |
| `inject.max-chars` | 6000 | 注入块预算 |
| `inject.skip-when-attachments` | true | 有对话附件时跳过自动检索（附件上下文优先） |
| `verify.support-threshold` | 0.55 | 逐句「有依据」阈值 |
| `agentic.enabled` | true | KnowledgeAgent 工具式检索 |
| `rerank.enabled` | false | 预留 |

全部支持环境变量覆盖（`RAG_ENABLED`、`RAG_RETRIEVE_TOP_K` 等）。

## 5. 降级矩阵

| 故障 | 行为 |
| --- | --- |
| Redis 无 RediSearch 模块 | 索引不可用，`/stats` 显示 `indexExists=false`，对话照常 |
| embedding 服务不可用 | `degraded=true`，仅 BM25；上传返回 warning，不阻断写入 |
| 索引为空 | 不注入任何块，模型按普通对话回答（并说明没有检索到） |
| 向量维度与索引不一致 | 自动重建索引并回填；重建失败则只用 BM25 |
| 检索超时 | 返回已拿到的部分结果并标 `degraded` |

## 6. 评测

```bash
# 需要 Redis Stack + embedding 可用；测试会临时写入并删除评测文档
mvn -o test -Dtest=RagEvalTest -Drag.eval=true
```

当前基线（单文件语料、12 条事实型问题、hybrid top-5）：

```
Recall@5 = 1.000    MRR@5 = 0.651
```

MRR 0.65 说明「能召回」但排序不够靠前——同一份 6 行方案文档里事实高度相关，
下一步应扩展语料（多文档、多主题）后再看 MRR，并考虑开启 rerank。

## 7. 运维

- 重建索引：删除 `idx_knowledge` 后重启即可（`KnowledgeIndex` 会重建并回填向量）；
  或改 `rag.embed.dimension` 触发自动重建。
- 查看状态：`GET /api/knowledge/stats`；看日志 `知识库索引/迁移/补向量` 关键字。
- 删除文件：`DELETE /api/knowledge/files/{filename}`，先删块与向量再删元数据，失败保留元数据可重试。
- 提示词：`prompts/knowledge.txt`、`prompts/orchestrator.txt` 为本地兜底；
  **若 Nacos 下发了同名 dataId，会覆盖本地文件**，改提示词需同步 Nacos。

## 8. 已知限制

1. 旧版逐行块迁移后仍是「一行一块」（`headingPath` 为空），重新上传同一文件才会走结构化切块。
2. `test-chat-data.txt` 等历史文件的块边界不理想，检索排序受影响（见 MRR）。
3. 检索质量目前只测事实型问题，缺「改写/同义/跨段组合」样本。
4. 对话附件仍走 `FileContextBuilder` 注入，不并入知识库（设计如此，避免临时文件污染知识库）。
5. `dsh_uid` 仍是匿名 cookie，多用户共用同一知识库。
6. `agentscope.embedding.api-key` 明文写在 `application.yml`，生产应改为环境变量并轮换。
