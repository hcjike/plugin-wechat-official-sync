package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.client.WechatApiException;
import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * {@link WechatSyncTaskRunner} 的行为验证：执行成功/失败把终态写回任务记录、尝试次数上限、
 * 插件启动后对中断任务的自动重放（恢复推送）与异常任务的收敛（无快照 / 超次数时直接落到失败态）。
 */
class WechatSyncTaskRunnerTest {

    private final WechatSyncService syncService = mock(WechatSyncService.class);

    private final WechatSyncTaskStore taskStore = mock(WechatSyncTaskStore.class);

    private final ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);

    private final WechatSyncTaskRunner runner =
        new WechatSyncTaskRunner(syncService, taskStore, settingFetcher);

    @Test
    void runTaskWritesSuccessWithMediaId() {
        when(taskStore.startAttempt(eq("post-a"), any())).thenReturn(Mono.just(1));
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(new WechatSetting()));
        when(settingFetcher.fetch(eq(BeautifySetting.GROUP), eq(BeautifySetting.class)))
            .thenReturn(Mono.just(new BeautifySetting()));
        when(syncService.submit(any(), any(), any())).thenReturn(Mono.just("media-1"));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.runTask("post-a", request("文章 A"), null).block();

        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(captor.getValue().getMediaId()).isEqualTo("media-1");
    }

    @Test
    void runTaskWritesFailureMessageWhenSubmitFails() {
        when(taskStore.startAttempt(eq("post-a"), any())).thenReturn(Mono.just(1));
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(new WechatSetting()));
        // 未配置「正文美化」分组：走内置默认值
        when(settingFetcher.fetch(eq(BeautifySetting.GROUP), eq(BeautifySetting.class)))
            .thenReturn(Mono.empty());
        when(syncService.submit(any(), any(), any()))
            .thenReturn(Mono.error(new WechatApiException("封面图下载失败")));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.runTask("post-a", request("文章 A"), null).block();

        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(captor.getValue().getMessage()).contains("封面图下载失败");
    }

    @Test
    void runTaskSkipsWhenTaskRecordMissing() {
        when(taskStore.startAttempt(eq("post-a"), any())).thenReturn(Mono.just(0));

        runner.runTask("post-a", request("文章 A"), null).block();

        verify(syncService, never()).submit(any(), any(), any());
        verify(taskStore, never()).complete(anyString(), any());
    }

    @Test
    void runTaskStopsWhenAttemptsExceeded() {
        when(taskStore.startAttempt(eq("post-a"), any()))
            .thenReturn(Mono.just(WechatSyncTaskRunner.MAX_ATTEMPTS + 1));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.runTask("post-a", request("文章 A"), null).block();

        verify(syncService, never()).submit(any(), any(), any());
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
    }

    @Test
    void resumeInterruptedReplaysPendingTask() {
        WechatSyncTask task = task("post-a", SyncRecord.STATUS_PENDING, 1, request("文章 A"));
        when(taskStore.findPending()).thenReturn(Mono.just(List.of(task)));
        // 重放时带「正在自动恢复」的状态说明，供状态列悬停展示
        when(taskStore.startAttempt(eq("post-a"), contains("自动恢复"))).thenReturn(Mono.just(2));
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(new WechatSetting()));
        when(settingFetcher.fetch(eq(BeautifySetting.GROUP), eq(BeautifySetting.class)))
            .thenReturn(Mono.just(new BeautifySetting()));
        when(syncService.submit(any(), any(), any())).thenReturn(Mono.just("media-2"));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.resumeInterrupted().block();

        // 重放使用的是持久化输入快照：摘要 / 作者 / 原文链接等草稿元信息随快照完整恢复
        ArgumentCaptor<SyncRequest> requestCaptor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(syncService).submit(requestCaptor.capture(), any(), any());
        assertThat(requestCaptor.getValue().getDigest()).isEqualTo("文章摘要");
        assertThat(requestCaptor.getValue().getAuthor()).isEqualTo("张三");
        assertThat(requestCaptor.getValue().getPermalink()).isEqualTo("/archives/post-a");
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(captor.getValue().getMediaId()).isEqualTo("media-2");
    }

    @Test
    void resumeInterruptedFailsTaskAtMaxAttemptsWithoutReplaying() {
        WechatSyncTask task = task("post-a", SyncRecord.STATUS_PENDING,
            WechatSyncTaskRunner.MAX_ATTEMPTS, request("文章 A"));
        when(taskStore.findPending()).thenReturn(Mono.just(List.of(task)));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.resumeInterrupted().block();

        verify(syncService, never()).submit(any(), any(), any());
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(captor.getValue().getMessage()).contains("手动重新同步");
    }

    @Test
    void resumeInterruptedFailsTaskWithoutSnapshot() {
        WechatSyncTask task = task("post-a", SyncRecord.STATUS_PENDING, 0, null);
        when(taskStore.findPending()).thenReturn(Mono.just(List.of(task)));
        when(taskStore.complete(anyString(), any())).thenReturn(Mono.empty());

        runner.resumeInterrupted().block();

        verify(syncService, never()).submit(any(), any(), any());
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(taskStore).complete(eq("post-a"), captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(captor.getValue().getMessage()).contains("缺少任务数据");
    }

    private WechatSyncTask task(String postName, String status, int attempts, SyncRequest request) {
        WechatSyncTask task = new WechatSyncTask();
        Metadata metadata = new Metadata();
        metadata.setName(WechatSyncTaskStore.taskName(postName));
        task.setMetadata(metadata);
        WechatSyncTask.WechatSyncTaskSpec spec = new WechatSyncTask.WechatSyncTaskSpec();
        spec.setPostName(postName);
        spec.setStatus(status);
        spec.setAttempts(attempts);
        spec.setRequest(request);
        task.setSpec(spec);
        return task;
    }

    private SyncRequest request(String title) {
        SyncRequest request = new SyncRequest();
        request.setPostName("post-a");
        request.setTitle(title);
        request.setDigest("文章摘要");
        request.setAuthor("张三");
        request.setPermalink("/archives/post-a");
        return request;
    }
}
