package com.hcjike.wechatofficialsync;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同步状态记录存储。以插件专用的 {@link ConfigMap} 持久化每篇文章的最近一次同步结果，
 * 记录整体以 JSON 字符串保存在 {@value #DATA_KEY} 键下。
 *
 * <p>写入为「读取-合并-写回」，鉴于同步为低频操作，此处不加分布式锁；如出现并发写，
 * 后写入者以最新读取到的快照为准。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatSyncRecordStore {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncRecordStore.class);

    static final String CONFIGMAP_NAME = "wechat-official-sync-records";

    static final String DATA_KEY = "records";

    private final ReactiveExtensionClient client;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public WechatSyncRecordStore(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 读取全部同步记录，键为文章 name。
     */
    public Mono<Map<String, SyncRecord>> findAll() {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
            .map(this::parse)
            .defaultIfEmpty(new LinkedHashMap<>());
    }

    /**
     * 保存（覆盖）某篇文章的同步记录。
     */
    public Mono<Void> save(String postName, SyncRecord record) {
        if (postName == null || postName.isBlank()) {
            return Mono.empty();
        }
        return getOrCreate()
            .flatMap(configMap -> {
                Map<String, SyncRecord> all = parse(configMap);
                all.put(postName, record);
                Map<String, String> data = configMap.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(configMap.getData());
                data.put(DATA_KEY, write(all));
                configMap.setData(data);
                return client.update(configMap);
            })
            .onErrorResume(e -> {
                log.warn("保存文章 [{}] 同步状态失败：{}", postName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    private Mono<ConfigMap> getOrCreate() {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                ConfigMap configMap = new ConfigMap();
                Metadata metadata = new Metadata();
                metadata.setName(CONFIGMAP_NAME);
                configMap.setMetadata(metadata);
                configMap.setData(new LinkedHashMap<>());
                // 并发创建时若已存在，回退为再次读取
                return client.create(configMap)
                    .onErrorResume(e -> client.fetch(ConfigMap.class, CONFIGMAP_NAME));
            }));
    }

    private Map<String, SyncRecord> parse(ConfigMap configMap) {
        Map<String, SyncRecord> result = new LinkedHashMap<>();
        if (configMap == null || configMap.getData() == null) {
            return result;
        }
        String json = configMap.getData().get(DATA_KEY);
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                SyncRecord record = objectMapper.convertValue(entry.getValue(), SyncRecord.class);
                result.put(String.valueOf(entry.getKey()), record);
            }
        } catch (RuntimeException e) {
            log.warn("解析同步状态记录失败：{}", e.getMessage());
        }
        return result;
    }

    private String write(Map<String, SyncRecord> all) {
        try {
            return objectMapper.writeValueAsString(all);
        } catch (RuntimeException e) {
            log.warn("序列化同步状态记录失败：{}", e.getMessage());
            return "{}";
        }
    }
}
