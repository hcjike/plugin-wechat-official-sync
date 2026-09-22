package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link WechatSyncTaskStore} 的行为验证：任务提交/重置、撞乐观锁时重试、尝试次数递增、
 * 终态写入（含清空输入快照）、状态投影、旧 ConfigMap 记录迁移与任务名规范化。
 */
class WechatSyncTaskStoreTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void savePendingCreatesTaskWithSnapshotWhenAbsent() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(WechatSyncTask.class), anyString())).thenReturn(Mono.empty());
        when(client.create(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        SyncRequest request = request("文章 A");
        new WechatSyncTaskStore(client).savePending("post-a", request).block();

        ArgumentCaptor<WechatSyncTask> captor = ArgumentCaptor.forClass(WechatSyncTask.class);
        verify(client).create(captor.capture());
        WechatSyncTask task = captor.getValue();
        assertThat(task.getMetadata().getName()).isEqualTo("wechat-sync-post-a");
        assertThat(task.getSpec().getPostName()).isEqualTo("post-a");
        assertThat(task.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_PENDING);
        assertThat(task.getSpec().getAttempts()).isZero();
        assertThat(task.getSpec().getRequest()).isSameAs(request);
        // 另留存一份文章标题：输入快照落终态后会被清空，标题留在 spec 上才能长期认出文章
        assertThat(task.getSpec().getPostTitle()).isEqualTo("文章 A");
    }

    @Test
    void saveFailedRecordsTitleWithoutSnapshot() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(WechatSyncTask.class), anyString())).thenReturn(Mono.empty());
        when(client.create(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        new WechatSyncTaskStore(client)
            .saveFailed("post-a", "文章 A", "插件尚未配置微信公众号信息").block();

        ArgumentCaptor<WechatSyncTask> captor = ArgumentCaptor.forClass(WechatSyncTask.class);
        verify(client).create(captor.capture());
        WechatSyncTask task = captor.getValue();
        assertThat(task.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        // 失败登记不保存输入快照，标题是记录里唯一的文章线索
        assertThat(task.getSpec().getPostTitle()).isEqualTo("文章 A");
        assertThat(task.getSpec().getRequest()).isNull();
    }

    @Test
    void completeBackfillsTitleFromSnapshotBeforeClearingIt() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_PENDING, 1);
        existing.getSpec().setRequest(request("文章 A"));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.update(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        new WechatSyncTaskStore(client).complete("post-a", SyncRecord.success("media-9")).block();

        // 升级前登记、升级后才落终态的任务：快照清空前补记标题
        assertThat(existing.getSpec().getPostTitle()).isEqualTo("文章 A");
        assertThat(existing.getSpec().getRequest()).isNull();
    }

    @Test
    void savePendingResetsExistingTask() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_FAILED, 3);
        existing.getSpec().setPostTitle("上一次同步时的标题");
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.update(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        SyncRequest request = request("文章 A");
        new WechatSyncTaskStore(client).savePending("post-a", request).block();

        verify(client, never()).create(any(WechatSyncTask.class));
        verify(client).update(existing);
        // 重复提交即在旧任务上重置：状态回到「同步中」、尝试次数清零、输入快照与标题刷新
        assertThat(existing.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_PENDING);
        assertThat(existing.getSpec().getAttempts()).isZero();
        assertThat(existing.getSpec().getRequest()).isSameAs(request);
        assertThat(existing.getSpec().getPostTitle()).isEqualTo("文章 A");
    }

    @Test
    void savePendingRetriesAfterOptimisticLockConflict() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_FAILED, 1);
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        // 模拟并发写冲突：首次更新被乐观锁拒绝，重试（重新读取最新快照）后成功
        when(client.update(any(WechatSyncTask.class)))
            .thenReturn(Mono.error(new OptimisticLockingFailureException("conflict")))
            .thenReturn(Mono.just(existing));

        new WechatSyncTaskStore(client).savePending("post-a", request("文章 A")).block();

        verify(client, times(2)).update(any(WechatSyncTask.class));
        assertThat(existing.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_PENDING);
    }

    @Test
    void startAttemptIncrementsAttemptsAndAppliesMessage() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_PENDING, 1);
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.update(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        Integer attempts = new WechatSyncTaskStore(client)
            .startAttempt("post-a", "同步任务因插件重启中断，正在自动恢复…").block();

        assertThat(attempts).isEqualTo(2);
        assertThat(existing.getSpec().getAttempts()).isEqualTo(2);
        assertThat(existing.getSpec().getMessage()).contains("自动恢复");
    }

    @Test
    void startAttemptReturnsZeroWhenTaskMissing() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(WechatSyncTask.class), anyString())).thenReturn(Mono.empty());

        Integer attempts = new WechatSyncTaskStore(client).startAttempt("post-x", null).block();

        assertThat(attempts).isZero();
    }

    @Test
    void completeWritesTerminalStateAndClearsSnapshot() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_PENDING, 1);
        existing.getSpec().setRequest(request("文章 A"));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.update(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        new WechatSyncTaskStore(client).complete("post-a", SyncRecord.success("media-9")).block();

        assertThat(existing.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(existing.getSpec().getMediaId()).isEqualTo("media-9");
        // 终态后清空输入快照，避免正文 HTML 长期占用数据库空间
        assertThat(existing.getSpec().getRequest()).isNull();
    }

    @Test
    void findStatusMapProjectsTasksByPostName() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask success = task("post-a", SyncRecord.STATUS_SUCCESS, 1);
        success.getSpec().setMediaId("media-1");
        WechatSyncTask failed = task("post-b", SyncRecord.STATUS_FAILED, 1);
        failed.getSpec().setMessage("封面下载失败");
        when(client.listAll(eq(WechatSyncTask.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(success, failed));

        Map<String, SyncRecord> statusMap = new WechatSyncTaskStore(client).findStatusMap().block();

        assertThat(statusMap).containsOnlyKeys("post-a", "post-b");
        assertThat(statusMap.get("post-a").getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(statusMap.get("post-a").getMediaId()).isEqualTo("media-1");
        assertThat(statusMap.get("post-b").getMessage()).isEqualTo("封面下载失败");
    }

    @Test
    void findPendingReturnsOnlyPendingTasks() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask pendingTask = task("post-a", SyncRecord.STATUS_PENDING, 1);
        WechatSyncTask failed = task("post-b", SyncRecord.STATUS_FAILED, 1);
        when(client.listAll(eq(WechatSyncTask.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(pendingTask, failed));

        List<WechatSyncTask> pending = new WechatSyncTaskStore(client).findPending().block();

        assertThat(pending).extracting((WechatSyncTask t) -> t.getSpec().getPostName())
            .containsExactly("post-a");
    }

    @Test
    void migrateLegacyRecordsImportsMissingOnlyFailsLegacyPendingAndDeletesConfigMap() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ConfigMap legacy = legacyConfigMap();
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.just(legacy));
        // post-a 已迁移过：跳过；post-b 未迁移：补建
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(task("post-a", SyncRecord.STATUS_SUCCESS, 1)));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-b"))).thenReturn(Mono.empty());
        when(client.create(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.delete(eq(legacy))).thenReturn(Mono.just(legacy));

        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        ArgumentCaptor<WechatSyncTask> captor = ArgumentCaptor.forClass(WechatSyncTask.class);
        verify(client).create(captor.capture());
        WechatSyncTask migrated = captor.getValue();
        assertThat(migrated.getMetadata().getName()).isEqualTo("wechat-sync-post-b");
        assertThat(migrated.getSpec().getPostName()).isEqualTo("post-b");
        // 旧记录中的 PENDING 已随进程终止、无法重放：按因升级中断标记为失败
        assertThat(migrated.getSpec().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(migrated.getSpec().getMessage()).contains("升级");
        // 全部条目迁移成功后删除旧 ConfigMap：后续启动不再重复扫描
        verify(client).delete(legacy);
    }

    @Test
    void migrateLegacyRecordsKeepsConfigMapWhenEntryFails() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.just(legacyConfigMap()));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(task("post-a", SyncRecord.STATUS_SUCCESS, 1)));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-b"))).thenReturn(Mono.empty());
        when(client.create(any(WechatSyncTask.class))).thenReturn(Mono.error(new IllegalStateException("db down")));

        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        // 有条目迁移失败：保留旧 ConfigMap 供下次启动重试（已迁移的条目幂等跳过）
        verify(client, never()).delete(any(ConfigMap.class));
    }

    @Test
    void migrateLegacyRecordsKeepsConfigMapWhenContentUnparsable() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ConfigMap broken = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME);
        broken.setMetadata(metadata);
        broken.setData(Map.of(WechatSyncTaskStore.LEGACY_DATA_KEY, "not-a-json"));
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.just(broken));

        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        // 旧记录无法解析：保留原始数据（下次启动重试 / 人工检查），不误删
        verify(client, never()).delete(any(ConfigMap.class));
    }

    @Test
    void migrateLegacyRecordsDeletesEmptyConfigMap() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ConfigMap empty = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME);
        empty.setMetadata(metadata);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.just(empty));
        when(client.delete(eq(empty))).thenReturn(Mono.just(empty));

        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        // 没有记录可迁移的空壳同样清理，避免每次启动重复读取
        verify(client).delete(empty);
    }

    @Test
    void migrateLegacyRecordsSkipsWhenConfigMapAbsent() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.empty());

        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        // 旧 ConfigMap 已删除（或从未存在）：启动时一次 fetch 即确认无遗留
        verify(client, never()).delete(any(ConfigMap.class));
        verify(client, never()).create(any(WechatSyncTask.class));
    }

    @Test
    void migrateLegacyRecordsToleratesDeleteFailure() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ConfigMap legacy = legacyConfigMap();
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME)))
            .thenReturn(Mono.just(legacy));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(task("post-a", SyncRecord.STATUS_SUCCESS, 1)));
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-b"))).thenReturn(Mono.empty());
        when(client.create(any(WechatSyncTask.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.delete(eq(legacy))).thenReturn(Mono.error(new IllegalStateException("conflict")));

        // 删除失败不抛出：迁移结果不受影响，下次启动重试删除
        new WechatSyncTaskStore(client).migrateLegacyRecords().block();

        verify(client).delete(legacy);
    }

    @Test
    void taskSnapshotSurvivesJsonRoundTrip() {
        // 任务以 JSON 形式存入 Halo 数据库、插件重启后再读回：草稿元信息（摘要 / 作者 /
        // 原文链接等）必须完整保留——插件重启后的自动重放完全依赖这份输入快照
        SyncRequest request = request("文章 A");
        request.setDigest("文章摘要");
        request.setAuthor("张三");
        request.setPermalink("/archives/post-a");
        request.setCover("/upload/cover.png");
        WechatSyncTask task = task("post-a", SyncRecord.STATUS_PENDING, 1);
        task.getSpec().setRequest(request);
        task.getSpec().setPostTitle("文章 A");

        String json = objectMapper.writeValueAsString(task);
        WechatSyncTask restored = objectMapper.readValue(json, WechatSyncTask.class);

        assertThat(restored.getSpec().getPostTitle()).isEqualTo("文章 A");
        assertThat(restored.getSpec().getRequest()).isNotNull();
        assertThat(restored.getSpec().getRequest().getPostName()).isEqualTo("post-a");
        assertThat(restored.getSpec().getRequest().getTitle()).isEqualTo("文章 A");
        assertThat(restored.getSpec().getRequest().getDigest()).isEqualTo("文章摘要");
        assertThat(restored.getSpec().getRequest().getAuthor()).isEqualTo("张三");
        assertThat(restored.getSpec().getRequest().getPermalink()).isEqualTo("/archives/post-a");
        assertThat(restored.getSpec().getRequest().getCover()).isEqualTo("/upload/cover.png");
        assertThat(restored.getSpec().getRequest().getContent()).isEqualTo("<p>正文</p>");
    }

    @Test
    void deleteRemovesTaskByPostName() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_SUCCESS, 1);
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.delete(eq(existing))).thenReturn(Mono.just(existing));

        new WechatSyncTaskStore(client).delete("post-a").block();

        verify(client).delete(existing);
    }

    @Test
    void deleteSkipsWhenAlreadyMarkedDeleted() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_SUCCESS, 1);
        // 上一次 PostDeletedEvent 已触发删除：deletionTimestamp 已设置、等待 GcReconciler 真正移除
        existing.getMetadata().setDeletionTimestamp(java.time.Instant.now());
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));

        new WechatSyncTaskStore(client).delete("post-a").block();

        // 重复事件不再次删除（幂等），避免重复日志
        verify(client, never()).delete(any(WechatSyncTask.class));
    }

    @Test
    void deleteIsNoOpWhenTaskAbsent() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(WechatSyncTask.class), anyString())).thenReturn(Mono.empty());

        new WechatSyncTaskStore(client).delete("post-x").block();

        verify(client, never()).delete(any(WechatSyncTask.class));
    }

    @Test
    void deleteToleratesFailure() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        WechatSyncTask existing = task("post-a", SyncRecord.STATUS_SUCCESS, 1);
        when(client.fetch(eq(WechatSyncTask.class), eq("wechat-sync-post-a")))
            .thenReturn(Mono.just(existing));
        when(client.delete(eq(existing))).thenReturn(Mono.error(new IllegalStateException("db down")));

        // 删除失败不抛出：避免影响 Halo 的文章删除主流程
        new WechatSyncTaskStore(client).delete("post-a").block();

        verify(client).delete(existing);
    }

    @Test
    void taskNameNormalizesPostName() {
        assertThat(WechatSyncTaskStore.taskName("Post-A")).isEqualTo("wechat-sync-post-a");
        assertThat(WechatSyncTaskStore.taskName("")).isEqualTo("wechat-sync-unknown");
        assertThat(WechatSyncTaskStore.taskName(null)).isEqualTo("wechat-sync-unknown");
    }

    private WechatSyncTask task(String postName, String status, int attempts) {
        WechatSyncTask task = new WechatSyncTask();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncTaskStore.taskName(postName));
        task.setMetadata(metadata);
        WechatSyncTask.WechatSyncTaskSpec spec = new WechatSyncTask.WechatSyncTaskSpec();
        spec.setPostName(postName);
        spec.setStatus(status);
        spec.setAttempts(attempts);
        task.setSpec(spec);
        return task;
    }

    private SyncRequest request(String title) {
        SyncRequest request = new SyncRequest();
        request.setPostName("post-a");
        request.setTitle(title);
        request.setContent("<p>正文</p>");
        return request;
    }

    private ConfigMap legacyConfigMap() {
        Map<String, Object> records = new LinkedHashMap<>();
        records.put("post-a", Map.of("status", "SUCCESS", "message", "已同步", "mediaId", "m1"));
        records.put("post-b", Map.of("status", "PENDING", "message", "正在处理"));
        Map<String, String> data = new LinkedHashMap<>();
        data.put(WechatSyncTaskStore.LEGACY_DATA_KEY, objectMapper.writeValueAsString(records));
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncTaskStore.LEGACY_CONFIGMAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        return configMap;
    }
}
