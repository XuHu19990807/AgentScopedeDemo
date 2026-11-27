package com.cetc28.rag.milvus;

import com.cetc28.modelconfig.ModelRuntime;
import com.cetc28.rag.RagProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.AlterCollectionReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Milvus 客户端持有者：懒连接、集合命名、关闭。
 *
 * <p>Milvus 不可用时不阻塞应用启动：调用方拿到异常后按降级处理，下一次请求会重试连接。
 */
@Component
public class MilvusClientProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusClientProvider.class);

    private final RagProperties props;
    private volatile MilvusClientV2 client;
    private volatile boolean closed;
    private volatile String lastError;

    /** 动态配置：集合版本来自管理控制台（embedding 维度变化时自增，切换到新集合）。 */
    @Autowired(required = false)
    private ModelRuntime modelRuntime;

    public MilvusClientProvider(RagProperties props) {
        this.props = props;
    }

    public MilvusClientV2 client() {
        MilvusClientV2 current = client;
        if (current != null && !closed) return current;
        synchronized (this) {
            if (client != null && !closed) return client;
            ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                    .uri(props.getMilvus().getUri())
                    .connectTimeoutMs(props.getMilvus().getConnectTimeoutMs())
                    // 单次 RPC 必须有截止时间：否则服务端拒绝（如非 UTF-8 字符串）时 SDK 会长时间重试
                    .rpcDeadlineMs(Math.max(3000L, props.getRetrieve().getTimeoutMs()));
            if (!isBlank(props.getMilvus().getToken())) {
                builder.token(props.getMilvus().getToken());
            } else if (!isBlank(props.getMilvus().getUsername())) {
                builder.username(props.getMilvus().getUsername())
                        .password(props.getMilvus().getPassword());
            }
            if (!isBlank(props.getMilvus().getDatabase())) {
                builder.dbName(props.getMilvus().getDatabase());
            }
            try {
                MilvusClientV2 created = new MilvusClientV2(builder.build());
                client = created;
                closed = false;
                lastError = null;
                log.info("Milvus 客户端已连接：{}（database={}）", props.getMilvus().getUri(),
                        props.getMilvus().getDatabase());
                return created;
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                throw e;
            }
        }
    }

    public String denseCollection() {
        return collectionName(props.getMilvus().getDenseCollection());
    }

    public String bm25Collection() {
        return collectionName(props.getMilvus().getBm25Collection());
    }

    private String collectionName(String base) {
        String version = props.getMilvus().getCollectionVersion();
        if (modelRuntime != null) {
            try {
                version = modelRuntime.collectionVersion();
            } catch (Exception e) {
                log.debug("读取动态集合版本失败，回退配置：{}", e.getMessage());
            }
        }
        if (version == null || version.isBlank()) return base;
        return base + "_" + version;
    }

    public boolean available() {
        try {
            MilvusClientV2 c = client();
            return c != null && !closed;
        } catch (Exception e) {
            return false;
        }
    }

    public String describe() {
        return props.getMilvus().getUri() + " / " + denseCollection();
    }

    /**
     * 把集合一致性级别调成 Strong：知识库存在"写入后立刻列表/检索"的场景，
     * 默认 Bounded 会出现上传完成但分段列表暂时为空、编辑后读到旧内容的假象。
     *
     * <p>Milvus 2.4+ 支持 collection.consistencyLevel 属性；低版本/不支持时忽略并记日志。
     */
    public void ensureStrongConsistency(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return;
        try {
            AlterCollectionReq request = AlterCollectionReq.builder()
                    .collectionName(collectionName)
                    .property("collection.consistencyLevel", "Strong")
                    .build();
            client().alterCollection(request);
        } catch (Exception e) {
            log.debug("设置集合一致性级别失败（{}）：{}", collectionName, e.getMessage());
        }
    }

    public String getLastError() {
        return lastError;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void close() {
        MilvusClientV2 current = client;
        client = null;
        closed = true;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                log.debug("关闭 Milvus 客户端失败：{}", e.getMessage());
            }
        }
    }
}
