# RAG 检索基线（Milvus 3.0.5 + rag-simple 2.0.5，集合 v3）

评测命令（需要本地 Milvus 与 Embedding 服务可用）：

```bash
mvn test -Dtest=RagEvalDatasetTest -Drag.eval=true
```

语料与题目由 `scripts/gen_rag_eval.py` 生成：3 个域 × 6 种格式 = 18 个文件，120 道题
（每个域 40 题，覆盖 docx / xlsx / pptx / md / csv / json）。

## 2026-11-27 基线（切片优化后）

| 域 | 题数 | Recall@6 | Recall@20 | MRR@6 | nDCG@10 |
| --- | --- | --- | --- | --- | --- |
| network | 40 | 0.950 | 0.950 | 0.794 | 0.831 |
| ops | 40 | 0.925 | 0.975 | 0.804 | 0.850 |
| budget | 40 | 1.000 | 1.000 | 0.812 | 0.857 |
| **ALL** | **120** | **0.958** | **0.975** | **0.803** | **0.846** |

检索耗时（含精排）：avg=336ms，p95=467ms，max=626ms（单机 Milvus 3.0.5，18 文件 / 120 题）。

本轮改动：解析层 docx/pptx 段落改空行分隔、超长 Excel 单元格截断、rtf/odt 等走 Tika 兜底；
切片层换块模型（段落/表格/代码整块保留，`hard=max(target-chars, max-chars)=400`，表格分片重复表头，
切点优先句末/换行，父块按行边界截断）。Recall@6 与 nDCG 持平，Recall@20 由 0.983 → 0.975（噪声级），
MISS 由 2 题变成 3 题（network-017 / network-021 / ops-019），均为同域跨文件串档，与切片无关。

## 2026-11-26 基线（切片优化前）

| 域 | 题数 | Recall@6 | Recall@20 | MRR@6 | nDCG@10 |
| --- | --- | --- | --- | --- | --- |
| network | 40 | 0.975 | 0.975 | 0.799 | 0.841 |
| ops | 40 | 0.925 | 1.000 | 0.802 | 0.857 |
| budget | 40 | 0.975 | 0.975 | 0.803 | 0.844 |
| **ALL** | **120** | **0.958** | **0.983** | **0.801** | **0.847** |

检索耗时（含精排）：avg=361ms，p95=517ms，max=619ms（单机 Milvus 3.0.5，18 文件 / 120 题）。

测试阈值（ALL）：Recall@6 ≥ 0.80、Recall@20 ≥ 0.90、MRR@6 ≥ 0.70、nDCG@10 ≥ 0.70，
各域 Recall@6 ≥ 0.70；可用 `-Drag.eval.recall6=0.95` 等系统属性覆盖。

## 当前流水线

1. 稠密召回：Milvus HNSW（M=32 / efConstruction=200 / efSearch=128），候选 200 条；`dense-mode=auto`
   时走原生 SDK 检索，支持 `payload["domain"]` 过滤。
2. 词法召回：Milvus BM25 Function + SPARSE_INVERTED_INDEX，候选 300 条，支持 `domain == "x"` 过滤。
3. 加权 RRF 融合（k=60，dense=1.0 / bm25=0.8）。
4. 回读元数据后做分数下限过滤，再按 `rerank.max-candidates=200` 分批评分
   （`gte-rerank-v2`，batch=50，top-n=100）。
5. 后置相似度过滤（`similarity-filter=post`、`min-vector-similarity=0.15`）。
6. `RagSelector`：内容哈希去重、同标题 ≤2、同文档 ≤3，截断到 `top-k=8`。

## 已知短板（下一轮优先级）

- 跨文件同域问题仍会串档：例如"备件包单价"会命中 `plan.docx` 里同样出现的合同段落，
  2/120 题因此 MISS（network-021、budget-019）。需要更细的分段边界（表格行级切块）
  或在 payload 里区分"正文/附件/合同"来源。
- 长表格单元格被折进父块后，rerank 输入变长，精排分差变小；可给表格块单独打 `table` 标记，
  对表格类问题走"行级块优先"策略。
- 目前域是文件级属性，会话级域绑定尚未接入；多租户场景建议再加 `tenant` 字段做二级过滤。
