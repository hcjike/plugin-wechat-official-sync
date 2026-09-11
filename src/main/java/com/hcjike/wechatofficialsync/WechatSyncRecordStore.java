package com.hcjike.wechatofficialsync;

import java.time.Duration;
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
 * <p>写入为「读取-合并-写回」。ConfigMap 的更新带乐观锁（{@code metadata.version}），
 * 多篇文章的同步任务同时完成时会撞版本冲突；这里在冲突后重新读取最新快照重试，
 * 避免并发写入时状态更新被静默丢弃（文章卡在「同步中」）。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatSyncRecordStore {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncRecordStore.class);

    static final String CONFIGMAP_NAME = "wechat-official-sync-records";

    static final String DATA_KEY = "records";

    /** 保存遇到乐观锁等瞬时失败时的最大尝试次数。 */
    private static final int MAX_SAVE_ATTEMPTS = 3;

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
        return saveWithRetry(postName, record, MAX_SAVE_ATTEMPTS)
            .onErrorResume(e -> {
                log.warn("保存文章 [{}] 同步状态失败：{}", postName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 「读取-合并-写回」保存记录；失败时重新读取最新快照后重试。
     *
     * <p>并发场景：多篇文章的同步任务几乎同时完成，两次「读取-合并-写回」会撞 ConfigMap 的
     * 乐观锁（后提交时 {@code metadata.version} 已过期，更新被拒）；重试会基于最新快照重新
     * 合并，保证并发写入互不丢失。</p>
     */
    private Mono<ConfigMap> saveWithRetry(String postName, SyncRecord record, int attemptsLeft) {
        return getOrCreate()
            .flatMap(configMap -> {
                Map<String, SyncRecord> all = parse(configMap);
                all.put(postName, record);
                return writeAll(configMap, all);
            })
            .onErrorResume(e -> {
                if (attemptsLeft <= 1) {
                    return Mono.error(e);
                }
                return Mono.delay(Duration.ofMillis(200))
                    .then(saveWithRetry(postName, record, attemptsLeft - 1));
            });
    }

    /**
     * 把全部仍处于「同步中」的记录标记为失败，返回被清理的记录数。
     *
     * <p>用于插件启动时清理因服务重启而中断、不会再产生结果的同步任务，
     * 避免文章状态永远停留在「同步中」。</p>
     */
    public Mono<Integer> failAllPending(String message) {
        SyncRecord failed = SyncRecord.failed(message);
        return getOrCreate()
            .flatMap(configMap -> {
                Map<String, SyncRecord> all = parse(configMap);
                int count = 0;
                for (Map.Entry<String, SyncRecord> entry : all.entrySet()) {
                    if (SyncRecord.STATUS_PENDING.equals(entry.getValue().getStatus())) {
                        entry.setValue(failed);
                        count++;
                    }
                }
                if (count == 0) {
                    return Mono.just(0);
                }
                return writeAll(configMap, all).thenReturn(count);
            })
            .doOnNext(count -> {
                if (count > 0) {
                    log.info("插件启动清理：{} 条中断的同步记录已标记为失败", count);
                }
            })
            .onErrorResume(e -> {
                log.warn("清理中断的同步记录失败：{}", e.getMessage());
                return Mono.just(0);
            });
    }

    /**
     * 将整份记录 JSON 写回 ConfigMap（带快照替换）。
     */
    private Mono<ConfigMap> writeAll(ConfigMap configMap, Map<String, SyncRecord> all) {
        Map<String, String> data = configMap.getData() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(configMap.getData());
        data.put(DATA_KEY, write(all));
        configMap.setData(data);
        return client.update(configMap);
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
