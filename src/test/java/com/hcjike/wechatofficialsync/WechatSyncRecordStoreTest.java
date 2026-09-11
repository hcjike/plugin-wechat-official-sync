package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link WechatSyncRecordStore} 的行为验证：并发保存撞 ConfigMap 乐观锁时重试、插件启动时清理
 * 遗留的「同步中」记录。
 */
class WechatSyncRecordStoreTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void saveRetriesAfterConflictAndKeepsRecord() {
        ConfigMap configMap = configMapWith(recordsData(Map.of()));
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncRecordStore.CONFIGMAP_NAME)))
            .thenReturn(Mono.just(configMap));
        // 模拟并发写冲突：首次更新被乐观锁拒绝，重试后成功
        when(client.update(any(ConfigMap.class)))
            .thenReturn(Mono.error(new OptimisticLockingFailureException("conflict")))
            .thenReturn(Mono.just(configMap));

        new WechatSyncRecordStore(client).save("post-a", SyncRecord.pending()).block();

        verify(client, times(2)).update(any(ConfigMap.class));
        assertThat(recordsOf(configMap)).containsKey("post-a");
    }

    @Test
    void saveGivesUpAfterMaxAttemptsWithoutThrowing() {
        ConfigMap configMap = configMapWith(recordsData(Map.of()));
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncRecordStore.CONFIGMAP_NAME)))
            .thenReturn(Mono.just(configMap));
        when(client.update(any(ConfigMap.class)))
            .thenReturn(Mono.error(new OptimisticLockingFailureException("always-conflict")));

        // 重试耗尽后仅记录日志，不让异常冒泡影响调用方（如异步同步任务的完成回调）
        new WechatSyncRecordStore(client).save("post-b", SyncRecord.success("media-1")).block();

        verify(client, times(3)).update(any(ConfigMap.class));
    }

    @Test
    void failAllPendingMarksOnlyPendingRecords() {
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("post-a", Map.of("status", "PENDING", "message", "正在处理"));
        initial.put("post-b", Map.of("status", "SUCCESS", "message", "已同步", "mediaId", "m1"));
        ConfigMap configMap = configMapWith(recordsData(initial));
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncRecordStore.CONFIGMAP_NAME)))
            .thenReturn(Mono.just(configMap));
        when(client.update(any(ConfigMap.class))).thenReturn(Mono.just(configMap));

        Integer cleaned = new WechatSyncRecordStore(client)
            .failAllPending("同步任务因插件重启中断，请重新同步").block();

        assertThat(cleaned).isEqualTo(1);
        Map<String, SyncRecord> records = recordsOf(configMap);
        assertThat(records.get("post-a").getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(records.get("post-a").getMessage()).contains("中断");
        // 已落到终态的记录保持不变
        assertThat(records.get("post-b").getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(records.get("post-b").getMediaId()).isEqualTo("m1");
    }

    @Test
    void failAllPendingSkipsWriteWhenNothingPending() {
        ConfigMap configMap = configMapWith(recordsData(Map.of()));
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(WechatSyncRecordStore.CONFIGMAP_NAME)))
            .thenReturn(Mono.just(configMap));

        Integer cleaned = new WechatSyncRecordStore(client).failAllPending("msg").block();

        assertThat(cleaned).isZero();
        verify(client, never()).update(any(ConfigMap.class));
    }

    private ConfigMap configMapWith(Map<String, String> data) {
        ConfigMap configMap = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncRecordStore.CONFIGMAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        return configMap;
    }

    private Map<String, String> recordsData(Map<String, Object> records) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(WechatSyncRecordStore.DATA_KEY, objectMapper.writeValueAsString(records));
        return data;
    }

    private Map<String, SyncRecord> recordsOf(ConfigMap configMap) {
        Map<?, ?> raw = objectMapper.readValue(configMap.getData().get(WechatSyncRecordStore.DATA_KEY),
            Map.class);
        Map<String, SyncRecord> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()),
                objectMapper.convertValue(entry.getValue(), SyncRecord.class));
        }
        return result;
    }
}
