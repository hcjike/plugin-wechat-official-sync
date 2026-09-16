package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同步任务存储：以 {@link WechatSyncTask} 自定义模型持久化任务输入与状态（保存在 Halo 数据库，
 * 随插件/服务重启保留），替代旧版本把状态放在插件专用 ConfigMap 的方案。
 *
 * <p>一篇文章固定一条任务（任务名由文章 name 确定性推导），重复提交即重置同一条记录、
 * 天然去重，不存在「整包读-改-写」式的并发争用。任务更新仍按 Halo 扩展的乐观锁冲突设计
 * 重试：冲突后重新读取最新记录再写回，避免瞬时并发导致状态更新丢失。</p>
 *
 * <p>旧版本保存在 ConfigMap（{@value #LEGACY_CONFIGMAP_NAME}）中的同步记录由
 * {@link #migrateLegacyRecords()} 一次性补迁到任务模型：只补建不存在的任务，可重复执行。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatSyncTaskStore {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncTaskStore.class);

    /** 任务名统一前缀。任务名 = 前缀 + 规范化后的文章 name，一篇文章固定一个任务。 */
    static final String TASK_NAME_PREFIX = "wechat-sync-";

    /** 旧版本保存同步记录的 ConfigMap 名称（仅迁移时读取）。 */
    static final String LEGACY_CONFIGMAP_NAME = "wechat-official-sync-records";

    /** 旧版本 ConfigMap 中保存全部记录的数据键。 */
    static final String LEGACY_DATA_KEY = "records";

    /** 保存遇到乐观锁等瞬时失败时的最大尝试次数。 */
    private static final int MAX_SAVE_ATTEMPTS = 3;

    private final ReactiveExtensionClient client;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public WechatSyncTaskStore(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 状态投影：文章 name → 最近一次同步状态，供 Console 文章列表渲染状态列
     * （返回结构与旧版 ConfigMap 记录一致，前端无感知）。
     */
    public Mono<Map<String, SyncRecord>> findStatusMap() {
        return listAll().collect(LinkedHashMap::new, (map, task) -> {
            WechatSyncTask.WechatSyncTaskSpec spec = task.getSpec();
            if (spec == null || spec.getPostName() == null || spec.getPostName().isBlank()) {
                return;
            }
            SyncRecord record = new SyncRecord();
            record.setStatus(spec.getStatus());
            record.setMessage(spec.getMessage());
            record.setTime(spec.getTime());
            record.setMediaId(spec.getMediaId());
            map.put(spec.getPostName(), record);
        });
    }

    /** 未完成（PENDING）的任务，供插件启动时自动重放、恢复推送。 */
    public Mono<List<WechatSyncTask>> findPending() {
        return listAll()
            .filter(task -> task.getSpec() != null
                && SyncRecord.STATUS_PENDING.equals(task.getSpec().getStatus()))
            .collectList();
    }

    /**
     * 提交登记：把任务写入（或重置）为「同步中」并保存输入快照（存在同文章的旧任务时在其上重置，
     * 同时把尝试次数清零、供重启后的自动恢复使用）。
     */
    public Mono<Void> savePending(String postName, SyncRequest request) {
        return withRetry(() -> upsert(newTask(postName, spec -> {
            spec.setStatus(SyncRecord.STATUS_PENDING);
            spec.setMessage("同步任务已提交，正在处理…");
            spec.setTime(Instant.now().toString());
            spec.setAttempts(0);
            spec.setRequest(request);
        })), MAX_SAVE_ATTEMPTS)
            .onErrorResume(e -> {
                log.warn("登记文章 [{}] 的同步任务失败：{}", postName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 同步前即被拒绝（如未配置公众号信息）时直接登记失败状态，不保存输入快照
     * （没有可重放的输入，用户需手动重新同步）。
     */
    public Mono<Void> saveFailed(String postName, String message) {
        return withRetry(() -> upsert(newTask(postName, spec -> {
            spec.setStatus(SyncRecord.STATUS_FAILED);
            spec.setMessage(message);
            spec.setTime(Instant.now().toString());
            spec.setAttempts(0);
        })), MAX_SAVE_ATTEMPTS)
            .onErrorResume(e -> {
                log.warn("登记文章 [{}] 的同步失败状态失败：{}", postName, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 任务开始执行：尝试次数 +1（{@code message} 非空时同时更新状态说明），返回最新尝试次数；
     * 任务记录已不存在时返回 {@code 0}（调用方据此跳过执行）。
     */
    public Mono<Integer> startAttempt(String postName, String message) {
        return withRetry(() -> client.fetch(WechatSyncTask.class, taskName(postName))
            .flatMap(task -> {
                WechatSyncTask.WechatSyncTaskSpec spec = task.getSpec();
                int attempts = spec.getAttempts() == null ? 0 : spec.getAttempts();
                spec.setAttempts(attempts + 1);
                spec.setTime(Instant.now().toString());
                if (message != null && !message.isBlank()) {
                    spec.setMessage(message);
                }
                return client.update(task).thenReturn(attempts + 1);
            })
            .defaultIfEmpty(0), MAX_SAVE_ATTEMPTS);
    }

    /**
     * 任务落到终态：写入状态/说明/mediaId，并清空输入快照——终态后不再需要重放，
     * 清空可避免正文 HTML 长期占用数据库空间。
     */
    public Mono<Void> complete(String postName, SyncRecord record) {
        return withRetry(() -> client.fetch(WechatSyncTask.class, taskName(postName))
            .flatMap(task -> {
                WechatSyncTask.WechatSyncTaskSpec spec = task.getSpec();
                spec.setStatus(record.getStatus());
                spec.setMessage(record.getMessage());
                spec.setTime(record.getTime());
                spec.setMediaId(record.getMediaId());
                spec.setRequest(null);
                return client.update(task);
            })
            .then(), MAX_SAVE_ATTEMPTS)
            .onErrorResume(e -> {
                log.warn("保存文章 [{}] 的同步结果失败：{}", postName, e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 一次性迁移旧版本存放在插件 ConfigMap 中的同步记录：只补建缺失的任务（可重复执行，
     * 已有任务的文章跳过）。旧记录只存状态不存输入，遗留的「同步中」已随进程终止、无法重放，
     * 按「因升级中断」标记为失败。
     *
     * <p>全部条目迁移成功后删除旧 ConfigMap——迁移是一次性升级动作，删除后后续启动只需一次
     * 轻量 fetch（返回不存在）即可确认无遗留，不再逐条扫描比对；任一条失败或内容无法解析时
     * 保留旧记录，下次启动重试（已迁移的条目幂等跳过）。</p>
     */
    public Mono<Void> migrateLegacyRecords() {
        return client.fetch(ConfigMap.class, LEGACY_CONFIGMAP_NAME)
            .flatMap(configMap -> {
                Map<String, SyncRecord> records = parseLegacy(configMap);
                if (records.isEmpty()) {
                    // 没有可迁移的记录：清理空壳，避免每次启动重复读取
                    return deleteLegacy(configMap);
                }
                return Flux.fromIterable(records.entrySet())
                    .concatMap(entry -> migrateOne(entry.getKey(), entry.getValue()))
                    .collectList()
                    .flatMap(results -> results.stream().allMatch(Boolean.TRUE::equals)
                        ? deleteLegacy(configMap)
                        : Mono.empty());
            })
            .onErrorResume(e -> {
                log.warn("迁移旧版同步记录失败（保留旧记录，下次启动重试）：{}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 迁移单条记录：任务已存在（含迁移过的）视为成功；失败时不中断其余条目，
     * 返回 {@code false} 供调用方决定是否保留旧记录。
     */
    private Mono<Boolean> migrateOne(String postName, SyncRecord record) {
        return client.fetch(WechatSyncTask.class, taskName(postName))
            .switchIfEmpty(Mono.defer(() -> {
                log.info("迁移文章 [{}] 的历史同步记录到同步任务模型", postName);
                return client.create(newTask(postName, spec -> {
                    if (SyncRecord.STATUS_PENDING.equals(record.getStatus())) {
                        // 旧记录遗留的「同步中」无法重放：按因升级中断处理，提示重新同步
                        spec.setStatus(SyncRecord.STATUS_FAILED);
                        spec.setMessage("同步任务因插件升级中断，请重新同步");
                        spec.setTime(Instant.now().toString());
                    } else {
                        spec.setStatus(record.getStatus());
                        spec.setMessage(record.getMessage());
                        spec.setTime(record.getTime());
                        spec.setMediaId(record.getMediaId());
                    }
                }));
            }))
            .thenReturn(true)
            .onErrorResume(e -> {
                log.warn("迁移文章 [{}] 的历史同步记录失败：{}", postName, e.getMessage());
                return Mono.just(false);
            });
    }

    /** 删除已迁移完成的旧版 ConfigMap；删除失败仅记日志（下次启动重试删除，不影响启动流程）。 */
    private Mono<Void> deleteLegacy(ConfigMap configMap) {
        return client.delete(configMap)
            .doOnNext(deleted -> log.info("旧版同步记录迁移完成，已删除旧 ConfigMap [{}]", LEGACY_CONFIGMAP_NAME))
            .onErrorResume(e -> {
                log.warn("删除旧 ConfigMap [{}] 失败（下次启动将重试）：{}", LEGACY_CONFIGMAP_NAME, e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 构造任务骨架：确定性任务名 + 归属文章名，具体字段由 {@code applier} 填充。
     */
    private static WechatSyncTask newTask(String postName, Consumer<WechatSyncTask.WechatSyncTaskSpec> applier) {
        WechatSyncTask task = new WechatSyncTask();
        Metadata metadata = new Metadata();
        metadata.setName(taskName(postName));
        task.setMetadata(metadata);
        WechatSyncTask.WechatSyncTaskSpec spec = new WechatSyncTask.WechatSyncTaskSpec();
        spec.setPostName(postName == null ? "" : postName);
        applier.accept(spec);
        task.setSpec(spec);
        return task;
    }

    /**
     * 由文章 name 推导任务名：确定性命名（同一篇文章恒得同一任务名），重复提交即更新同一记录，
     * 也避免并发提交时重复建任务。Halo 扩展名只允许小写字母、数字、`-` 与 `.`，故先做规范化。
     */
    static String taskName(String postName) {
        String normalized = postName == null ? ""
            : postName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
        normalized = normalized.replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        if (normalized.length() > 200) {
            // 扩展名长度上限 253，截断后去掉可能残留的分隔符
            normalized = normalized.substring(0, 200).replaceAll("[^a-z0-9]+$", "");
        }
        return TASK_NAME_PREFIX + normalized;
    }

    /** 读取全部任务。 */
    private Flux<WechatSyncTask> listAll() {
        return client.listAll(WechatSyncTask.class, ListOptions.builder().build(), Sort.unsorted());
    }

    /** 写入任务：已有同任务名的记录时在其上替换 spec，否则新建。 */
    private Mono<WechatSyncTask> upsert(WechatSyncTask fresh) {
        return client.fetch(WechatSyncTask.class, fresh.getMetadata().getName())
            .flatMap(existing -> {
                existing.setSpec(fresh.getSpec());
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(fresh)));
    }

    /**
     * 执行带重试的写入：遇到乐观锁冲突等瞬时失败时重新执行整个「读取-修改-写回」操作
     * （每次重试都会重新读取最新快照），重试耗尽后把异常抛给调用方。
     */
    private <T> Mono<T> withRetry(Supplier<Mono<T>> operation, int attemptsLeft) {
        return operation.get()
            .onErrorResume(e -> {
                if (attemptsLeft <= 1) {
                    return Mono.error(e);
                }
                return Mono.delay(Duration.ofMillis(200))
                    .then(withRetry(operation, attemptsLeft - 1));
            });
    }

    /** 解析旧版 ConfigMap 中的记录 JSON（与旧版存储的格式一致）；内容损坏时抛出异常，中止迁移以保留原始数据。 */
    private Map<String, SyncRecord> parseLegacy(ConfigMap configMap) {
        Map<String, SyncRecord> result = new LinkedHashMap<>();
        if (configMap == null || configMap.getData() == null) {
            return result;
        }
        String json = configMap.getData().get(LEGACY_DATA_KEY);
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                    objectMapper.convertValue(entry.getValue(), SyncRecord.class));
            }
        } catch (RuntimeException e) {
            // 内容损坏时终止迁移（保留原始数据待下次重试/人工检查），避免误删
            throw new IllegalStateException("解析旧版同步记录失败：" + e.getMessage(), e);
        }
        return result;
    }
}
