package com.cetc28.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 相关配置（application.yml 中 rag.*）
 *
 * <p>2026 迁移到 Milvus 后，切块长度依然必须与 embedding 的 token 预算匹配：当前 embedding 模型
 * max-tokens=512，中文约 1 token/字符，块目标 360 字符 + 标题前缀仍留有余量；超过
 * {@link Chunk#maxEstimatedTokens} 的块会先被切小再嵌入，不再依赖 EmbeddingService 静默截断。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 总开关：关闭后不做检索、不注入、不暴露检索工具 */
    private boolean enabled = true;

    private Chunk chunk = new Chunk();
    private Embed embed = new Embed();
    private Retrieve retrieve = new Retrieve();
    private Inject inject = new Inject();
    private Verify verify = new Verify();
    private Agentic agentic = new Agentic();
    private Rerank rerank = new Rerank();
    private QueryExpansion queryExpansion = new QueryExpansion();
    private Milvus milvus = new Milvus();
    private Storage storage = new Storage();
    private Edit edit = new Edit();

    @Data
    public static class Chunk {
        /** 单个检索块目标字符数（见类注释：需与 embedding token 预算匹配） */
        private int targetChars = 360;
        /** 相邻块重叠字符数，避免答案正好被切在边界上 */
        private int overlapChars = 48;
        /** 单文件最大块数，防止超大文件把向量库写满 */
        private int maxChunksPerFile = 5000;
        /** 父块（注入模型时展开的上下文）最大字符数 */
        private int parentMaxChars = 1200;
        /** 估算 token 上限，超过则先切小再嵌入（避免 embedding 静默截断导致块尾丢失） */
        private int maxEstimatedTokens = 480;
        /** 小于该字符数的碎片块直接丢弃 */
        private int minChars = 20;
    }

    @Data
    public static class Embed {
        /** 批量 embedding 的批大小 */
        private int batchSize = 16;
        /** 向量维度；0 = 探测（探测失败则退化为纯 BM25 检索） */
        private int dimension = 0;
        /** 单条 embedding 失败后的重试次数 */
        private int retries = 2;
    }

    @Data
    public static class Retrieve {
        /** 最终注入/返回的条数 */
        private int topK = 6;
        /** 单路召回候选条数（稠密与 BM25 各取这么多，再融合） */
        private int candidateLimit = 40;
        /** RRF 融合参数 */
        private int rrfK = 60;
        /** 稠密路在 RRF 中的权重 */
        private double denseWeight = 1.0;
        /** BM25 路在 RRF 中的权重 */
        private double bm25Weight = 0.8;
        /** 同一文件最多保留几条，避免一个文件刷屏 */
        private int maxPerDoc = 4;
        /** 单次检索超时（毫秒） */
        private long timeoutMs = 2500;
        /** 融合分数下限（0 = 不过滤） */
        private double minScore = 0.0;
        /** 向量路的余弦相似度下限：低于它的块直接丢弃 */
        private double minVectorSimilarity = 0.2;
    }

    @Data
    public static class Inject {
        /** 是否自动检索并注入对话上下文 */
        private boolean enabled = true;
        /** 注入的检索内容最大字符数 */
        private int maxChars = 6000;
        /** 有对话附件时跳过自动注入（附件已是主要证据，避免两套互相干扰） */
        private boolean skipWhenAttachments = true;
    }

    @Data
    public static class Verify {
        /** 句子与证据块的相似度阈值，低于该值判为"无依据" */
        private double supportThreshold = 0.55;
        /** 逐句核查的最大句子数 */
        private int maxSentences = 40;
    }

    @Data
    public static class Agentic {
        /** 是否给 KnowledgeAgent 注册检索工具（agentic 多轮检索） */
        private boolean enabled = true;
    }

    @Data
    public static class Rerank {
        /** 精排开关；接口不可达时自动退回 RRF 顺序 */
        private boolean enabled = false;
        /** DashScope text-rerank 兼容地址 */
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "gte-rerank-v2";
        /** 送去精排的候选条数 */
        private int topN = 20;
        private long timeoutMs = 1500;
    }

    @Data
    public static class QueryExpansion {
        /** 低分时是否用模型改写查询，提升口语化问题的召回 */
        private boolean enabled = false;
        /** 融合后最高分低于该值时触发改写 */
        private double triggerScore = 0.45;
        /** 最多生成几条改写（含原查询） */
        private int maxQueries = 2;
        private long timeoutMs = 1000;
    }

    @Data
    public static class Milvus {
        private String uri = "http://127.0.0.1:19530";
        private String database = "default";
        private String token = "";
        private String username = "";
        private String password = "";
        /** 集合版本后缀：变更 embedding 模型/维度时改版本号，旧集合保留可回退 */
        private String collectionVersion = "v1";
        /** 稠密集合名 */
        private String denseCollection = "rag_dense";
        /** BM25 稀疏集合名 */
        private String bm25Collection = "rag_bm25";
        private String metric = "COSINE";
        private long connectTimeoutMs = 5000;
        /** 中文分词器：chinese（jieba）| 其它 Milvus analyzer 类型 */
        private String analyzer = "chinese";
        /** 是否在启动后建集合（Milvus 不可用时不阻塞启动） */
        private boolean ensureOnStartup = true;
    }

    @Data
    public static class Storage {
        /** 原始文件留存目录（相对应用工作目录）：重新解析、原文预览依赖它 */
        private String dir = "data/knowledge";
    }

    @Data
    public static class Edit {
        /** 单次编辑/重排/重命名/重解析允许重新向量化的分段上限（同步请求，防超时） */
        private int maxReembedChunks = 500;
        /** 分段分页每页上限 */
        private int maxPageSize = 100;
        /** 单个分段内容字符上限（BM25 集合 content 字段 VarChar(8192)） */
        private int maxChunkChars = 8000;
    }

    /**
     * 评测开关只通过 JVM 系统属性 {@code -Drag.eval=true} 控制（见 RagEvalTest 的
     * {@code @EnabledIfSystemProperty}），不放进本配置类：否则系统属性会被
     * {@code @ConfigurationProperties(prefix = "rag")} 当成 {@code rag.eval} 去绑定。
     */
}
