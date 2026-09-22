package com.hcjike.wechatofficialsync.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.service.WechatCacheCleanupService;
import com.hcjike.wechatofficialsync.service.WechatSyncService;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskRunner;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * {@link WechatMcpSyncService} 的行为验证：按文章 name 组装与 Console 一致的同步请求
 * （标题 / 摘要 / 作者 / 封面 / 原文链接 / 正文），以及预览与提交两条路径的
 * 成功、预检失败、重复提交冲突、文章与正文缺失等分支。
 */
class WechatMcpSyncServiceTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);

    private final PostContentService postContentService = mock(PostContentService.class);

    private final ReactiveSettingFetcher settingFetcher = mock(ReactiveSettingFetcher.class);

    private final WechatSyncService syncService = mock(WechatSyncService.class);

    private final WechatSyncTaskStore taskStore = mock(WechatSyncTaskStore.class);

    private final WechatSyncTaskRunner taskRunner = mock(WechatSyncTaskRunner.class);

    private final WechatCacheCleanupService cacheCleanupService = mock(WechatCacheCleanupService.class);

    private final WechatMcpSyncService mcpSyncService = new WechatMcpSyncService(client,
        postContentService, settingFetcher, syncService, taskStore, taskRunner, cacheCleanupService);

    @Test
    void previewBuildsRequestFromPostAndReturnsPreviewResult() {
        givenPost();
        givenSettings();
        when(syncService.preview(any(), any(), any()))
            .thenReturn(Mono.just(Map.of("title", "文章标题", "content", "<p>美化后正文</p>")));

        Map<String, Object> result = mcpSyncService.preview("post-a").block();

        assertThat(result).containsEntry("title", "文章标题");
        ArgumentCaptor<SyncRequest> captor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(syncService).preview(captor.capture(), any(), any());
        SyncRequest request = captor.getValue();
        assertThat(request.getPostName()).isEqualTo("post-a");
        assertThat(request.getTitle()).isEqualTo("文章标题");
        // 摘要去除首尾空白后原样上送，长度截断由服务端按微信计字规则处理
        assertThat(request.getDigest()).isEqualTo("摘要");
        assertThat(request.getCover()).isEqualTo("/upload/cover.png");
        // 作者取文章所属用户的显示名（插件设置的「默认作者」非空时以它为准）
        assertThat(request.getAuthor()).isEqualTo("宏尘极客");
        assertThat(request.getPermalink()).isEqualTo("/archives/post-a");
        assertThat(request.getContent()).isEqualTo("<p>正文</p>");
    }

    @Test
    void previewFallsBackToRawContentWhenRenderedContentBlank() {
        givenPost();
        givenSettings();
        when(postContentService.getHeadContent("post-a"))
            .thenReturn(Mono.just(ContentWrapper.builder().raw("<p>原始正文</p>").build()));
        when(syncService.preview(any(), any(), any())).thenReturn(Mono.just(Map.of()));

        mcpSyncService.preview("post-a").block();

        ArgumentCaptor<SyncRequest> captor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(syncService).preview(captor.capture(), any(), any());
        assertThat(captor.getValue().getContent()).isEqualTo("<p>原始正文</p>");
    }

    @Test
    void previewKeepsEmptyAuthorWhenPostOwnerMissing() {
        givenPost();
        givenSettings();
        // 文章作者对应的用户不存在（数据异常）：作者留空，不阻断预览
        when(client.fetch(eq(User.class), eq("admin"))).thenReturn(Mono.empty());
        when(syncService.preview(any(), any(), any())).thenReturn(Mono.just(Map.of()));

        mcpSyncService.preview("post-a").block();

        ArgumentCaptor<SyncRequest> captor = ArgumentCaptor.forClass(SyncRequest.class);
        verify(syncService).preview(captor.capture(), any(), any());
        assertThat(captor.getValue().getAuthor()).isNull();
        // 作者缺失不影响正文与其余字段（插件设置的「默认作者」会在服务端生效）
        assertThat(captor.getValue().getContent()).isEqualTo("<p>正文</p>");
    }

    @Test
    void previewUsesEmptySettingAndDefaultBeautifyWhenNotConfigured() {
        givenPost();
        // 未配置公众号信息 / 正文美化分组时仍可预览：空配置 + 内置默认美化
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.empty());
        when(settingFetcher.fetch(eq(BeautifySetting.GROUP), eq(BeautifySetting.class)))
            .thenReturn(Mono.empty());
        when(syncService.preview(any(), any(), any())).thenReturn(Mono.just(Map.of()));

        mcpSyncService.preview("post-a").block();

        ArgumentCaptor<WechatSetting> settingCaptor = ArgumentCaptor.forClass(WechatSetting.class);
        ArgumentCaptor<BeautifySetting> beautifyCaptor = ArgumentCaptor.forClass(BeautifySetting.class);
        verify(syncService).preview(any(), settingCaptor.capture(), beautifyCaptor.capture());
        assertThat(settingCaptor.getValue()).isNotNull();
        assertThat(beautifyCaptor.getValue()).isNotNull();
    }

    @Test
    void previewFailsWhenPostMissing() {
        when(client.fetch(eq(Post.class), eq("missing"))).thenReturn(Mono.empty());

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.preview("missing").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_NOT_FOUND);
        assertThat(error.getMessage()).contains("missing");
    }

    @Test
    void previewFailsWhenContentBlank() {
        givenPost();
        when(postContentService.getHeadContent("post-a")).thenReturn(Mono.empty());

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.preview("post-a").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_PRECONDITION_FAILED);
        assertThat(error.getMessage()).contains("正文为空");
    }

    @Test
    void previewFailsWhenPostNameBlank() {
        WechatMcpSyncException error = failureOf(() -> mcpSyncService.preview("  ").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_INVALID_ARGUMENT);
    }

    @Test
    void submitSavesTaskAndStartsRunner() {
        givenPost();
        givenNotSyncing();
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(setting()));
        when(syncService.validate(any(), any())).thenReturn(Mono.just(List.of()));
        when(taskStore.savePending(eq("post-a"), any())).thenReturn(Mono.empty());

        Map<String, Object> result = mcpSyncService.submit("post-a").block();

        assertThat(result).containsEntry("status", SyncRecord.STATUS_PENDING);
        assertThat(result).containsEntry("postName", "post-a");
        // 与 MCP 工具声明的 outputSchema 对齐：多出/缺少字段都会被 MCP Server 判为契约不一致
        assertThat(result).containsOnlyKeys("postName", "title", "status", "message");
        // 与 Console 一致的顺序：先落库任务（含输入快照）再异步执行
        verify(taskStore).savePending(eq("post-a"), any());
        verify(taskRunner).start(eq("post-a"), any());
    }

    @Test
    void submitRejectsWhenPrecheckFails() {
        givenPost();
        givenNotSyncing();
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(setting()));
        when(syncService.validate(any(), any()))
            .thenReturn(Mono.just(List.of("当前文章未设置封面图，请先为文章设置封面后再同步")));

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.submit("post-a").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_PRECONDITION_FAILED);
        assertThat(error.getMessage()).contains("封面图");
        // 预检不通过时不产生任务、不启动执行
        verify(taskStore, never()).savePending(anyString(), any());
        verify(taskRunner, never()).start(anyString(), any());
    }

    @Test
    void submitRejectsWhenSettingMissing() {
        givenPost();
        givenNotSyncing();
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.empty());

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.submit("post-a").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_PRECONDITION_FAILED);
        assertThat(error.getMessage()).contains("尚未配置微信公众号信息");
        verify(taskStore, never()).savePending(anyString(), any());
    }

    @Test
    void submitRejectsWhileSamePostIsSyncing() {
        givenPost();
        SyncRecord pending = new SyncRecord();
        pending.setStatus(SyncRecord.STATUS_PENDING);
        when(taskStore.findStatusMap()).thenReturn(Mono.just(Map.of("post-a", pending)));

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.submit("post-a").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_CONFLICT);
        verify(taskStore, never()).savePending(anyString(), any());
    }

    @Test
    void statusReturnsNoneWhenNeverSynced() {
        givenPost();
        when(taskStore.findStatusMap()).thenReturn(Mono.just(Map.of()));

        Map<String, Object> result = mcpSyncService.status("post-a").block();

        // 「尚未同步过」是正常结果而非错误，四个字段都在，time 为空串
        assertThat(result).containsOnlyKeys("postName", "status", "message", "time");
        assertThat(result).containsEntry("status", WechatMcpSyncService.STATUS_NONE);
        assertThat(result).containsEntry("message", "该文章尚未同步过");
        assertThat(result).containsEntry("time", "");
    }

    @Test
    void statusReturnsLatestRecord() {
        givenPost();
        SyncRecord failed = new SyncRecord();
        failed.setStatus(SyncRecord.STATUS_FAILED);
        failed.setMessage("40001 invalid credential");
        failed.setTime("2026-09-22T00:00:00Z");
        when(taskStore.findStatusMap()).thenReturn(Mono.just(Map.of("post-a", failed)));

        Map<String, Object> result = mcpSyncService.status("post-a").block();

        assertThat(result).containsEntry("status", SyncRecord.STATUS_FAILED);
        assertThat(result).containsEntry("message", "40001 invalid credential");
        assertThat(result).containsEntry("time", "2026-09-22T00:00:00Z");
        assertThat(result).containsEntry("postName", "post-a");
    }

    @Test
    void statusDoesNotRequireContent() {
        // 正文为空（甚至没有快照）也不影响状态查询：只读任务记录，不解析正文
        givenPost();
        when(postContentService.getHeadContent("post-a")).thenReturn(Mono.empty());
        when(taskStore.findStatusMap()).thenReturn(Mono.just(Map.of()));

        Map<String, Object> result = mcpSyncService.status("post-a").block();

        assertThat(result).containsEntry("status", WechatMcpSyncService.STATUS_NONE);
    }

    @Test
    void statusFailsWhenPostMissing() {
        when(client.fetch(eq(Post.class), eq("missing"))).thenReturn(Mono.empty());

        WechatMcpSyncException error = failureOf(() -> mcpSyncService.status("missing").block());

        assertThat(error.code()).isEqualTo(WechatMcpSyncException.CODE_NOT_FOUND);
    }

    @Test
    void cleanupCacheReturnsCountsAndRetention() {
        when(cacheCleanupService.cleanupNow())
            .thenReturn(Mono.just(new WechatCacheCleanupService.CleanupResult(
                "30", "2026-08-23T10:00:00Z", 3L, 12L)));

        Map<String, Object> result = mcpSyncService.cleanupCache().block();

        assertThat(result).containsOnlyKeys("deletedRecords", "remainingRecords", "retentionDays",
            "cutoff");
        assertThat(result).containsEntry("deletedRecords", 3L);
        assertThat(result).containsEntry("remainingRecords", 12L);
        assertThat(result).containsEntry("retentionDays", "30");
        assertThat(result).containsEntry("cutoff", "2026-08-23T10:00:00Z");
    }

    /** 断言调用以 {@link WechatMcpSyncException} 失败，并返回该异常以便断言错误码。 */
    private static WechatMcpSyncException failureOf(Runnable invocation) {
        Throwable error = catchThrowable(invocation::run);
        assertThat(error).isInstanceOf(WechatMcpSyncException.class);
        return (WechatMcpSyncException) error;
    }

    private void givenNotSyncing() {
        when(taskStore.findStatusMap()).thenReturn(Mono.just(Map.of()));
    }

    /** 已配置公众号信息与正文美化分组的常规场景。 */
    private void givenSettings() {
        when(settingFetcher.fetch(eq(WechatSetting.GROUP), eq(WechatSetting.class)))
            .thenReturn(Mono.just(setting()));
        when(settingFetcher.fetch(eq(BeautifySetting.GROUP), eq(BeautifySetting.class)))
            .thenReturn(Mono.just(new BeautifySetting()));
    }

    private static WechatSetting setting() {
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx-app-id");
        setting.setAppSecretName("wechat-app-secret");
        return setting;
    }

    /** 文章 + 作者 + 渲染正文的公共桩数据（与 Console 前端取值的字段一一对应）。 */
    private void givenPost() {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName("post-a");
        post.setMetadata(metadata);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle("文章标题");
        spec.setOwner("admin");
        spec.setCover("/upload/cover.png");
        Post.Excerpt excerpt = new Post.Excerpt();
        excerpt.setRaw("  摘要  ");
        spec.setExcerpt(excerpt);
        post.setSpec(spec);
        Post.PostStatus status = new Post.PostStatus();
        status.setPermalink("/archives/post-a");
        post.setStatus(status);
        when(client.fetch(eq(Post.class), eq("post-a"))).thenReturn(Mono.just(post));

        User user = new User();
        User.UserSpec userSpec = new User.UserSpec();
        userSpec.setDisplayName("宏尘极客");
        user.setSpec(userSpec);
        when(client.fetch(eq(User.class), eq("admin"))).thenReturn(Mono.just(user));

        when(postContentService.getHeadContent("post-a"))
            .thenReturn(Mono.just(ContentWrapper.builder().content("<p>正文</p>").build()));
    }
}
