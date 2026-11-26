package com.cetc28.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 启动编码守卫。
 *
 * <p>Milvus Java SDK 在把查询文本写入 gRPC 请求时依赖 JVM 默认字符集；JDK 17 在中文
 * Windows 上默认是 GBK，中文 BM25 查询会被 Milvus 判为非法 UTF-8
 * （服务端报 {@code string data must be utf8 format}），客户端还会无限重试。
 *
 * <p>这里在启动阶段明确告警，避免问题只在线上检索时暴露。
 */
@Component
public class EncodingGuard {

    private static final Logger log = LoggerFactory.getLogger(EncodingGuard.class);

    @PostConstruct
    public void check() {
        Charset charset = Charset.defaultCharset();
        if ("UTF-8".equalsIgnoreCase(charset.name())) {
            return;
        }
        log.error("""
                JVM 默认字符集为 {}（非 UTF-8）。Milvus 的 BM25 中文检索会失败（服务端报 \
                "string data must be utf8 format"），并可能让请求长时间重试。
                修复：启动参数加 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
                （deploy/env.conf 的 JAVA_OPTS 已包含；IDE 里请加到 VM options）。""", charset);
    }
}
