package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;

/**
 * {@link WechatSyncService} 的行为验证：原文链接始终按「外部访问地址 + 文章路由」拼接；留言设置按选项映射
 * 并兼容旧开关；摘要去除首尾空白后原样同步（不做长度截断、空白按未填写处理）；预览按与提交一致
 * 的规则解析（作者优先级 / 原文链接 / 留言设置 / 正文美化）；同步前预检汇总微信配置与封面图等已知问题。
 */
class WechatSyncServiceTest {

    private final WechatSyncService service = new WechatSyncService(null, null, null);

    @Test
    void joinsExternalBaseUrlAndPermalink() {
        String url = service.resolveSourceUrl("/archives/hello-world", "https://blog.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void toleratesPermalinkWithoutLeadingSlash() {
        String url = service.resolveSourceUrl("archives/hello-world", "https://blog.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void keepsAbsolutePermalinkAsIs() {
        // Halo 配置了绝对地址策略时，status.permalink 本身即完整地址，不再叠加外部访问地址
        String url = service.resolveSourceUrl("https://blog.example.com/archives/hello-world",
            "https://internal.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void emptyWhenPermalinkOrBaseUrlMissing() {
        assertThat(service.resolveSourceUrl(null, "https://blog.example.com")).isEmpty();
        assertThat(service.resolveSourceUrl("   ", "https://blog.example.com")).isEmpty();
        assertThat(service.resolveSourceUrl("/archives/hello-world", "")).isEmpty();
    }

    @Test
    void commentModeKeepsExplicitSelection() {
        WechatSetting setting = new WechatSetting();
        setting.setCommentMode(WechatSetting.COMMENT_MODE_ALL);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_ALL);
        setting.setCommentMode(WechatSetting.COMMENT_MODE_FANS);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_FANS);
        setting.setCommentMode(WechatSetting.COMMENT_MODE_CLOSE);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }

    @Test
    void commentModeFallsBackToLegacySwitch() {
        // 升级前保存的「开启评论」开关：true 等价于所有人可留言，false / 未保存按关闭处理
        WechatSetting enabled = new WechatSetting();
        enabled.setOpenComment(true);
        assertThat(WechatSyncService.resolveCommentMode(enabled)).isEqualTo(WechatSetting.COMMENT_MODE_ALL);

        WechatSetting disabled = new WechatSetting();
        disabled.setOpenComment(false);
        assertThat(WechatSyncService.resolveCommentMode(disabled)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
        assertThat(WechatSyncService.resolveCommentMode(new WechatSetting()))
            .isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }

    @Test
    void commentModeIgnoresUnknownValue() {
        WechatSetting setting = new WechatSetting();
        setting.setCommentMode("mystery");
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }

    @Test
    void previewResolvesDraftMetaWithSubmitRules() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        ExternalUrlSupplier supplier = mock(ExternalUrlSupplier.class);
        when(supplier.getRaw()).thenReturn(URI.create("https://blog.example.com/").toURL());
        WechatSyncService previewService = new WechatSyncService(null, client, supplier);

        SyncRequest request = new SyncRequest();
        request.setContent("<p>正文</p>");
        request.setAuthor("文章作者");
        request.setPermalink("/archives/hello");
        request.setDigest("  文章摘要  ");
        WechatSetting setting = new WechatSetting();
        setting.setAuthor("默认作者");
        setting.setCommentMode(WechatSetting.COMMENT_MODE_FANS);

        Map<String, Object> result = previewService.preview(request, setting, new BeautifySetting()).block();

        assertThat(result).isNotNull();
        // 正文已按美化规则处理（段落被注入内联样式）
        assertThat((String) result.get("content")).contains("<p");
        // 摘要按与提交一致的规则处理（去除首尾空白），供预览展示
        assertThat(result.get("digest")).isEqualTo("文章摘要");
        // 作者与提交一致：设置的「默认作者」优先
        assertThat(result.get("author")).isEqualTo("默认作者");
        assertThat(result.get("sourceUrl")).isEqualTo("https://blog.example.com/archives/hello");
        assertThat(result.get("commentMode")).isEqualTo(WechatSetting.COMMENT_MODE_FANS);
    }

    @Test
    void digestIsTrimmedAndSyncedWithoutTruncation() {
        // 未填写（null / 纯空白）视为空摘要：返回空串，草稿不传 digest，由微信默认抓取正文前 54 个字
        assertThat(WechatSyncService.trimDigest(null)).isEmpty();
        assertThat(WechatSyncService.trimDigest("   ")).isEmpty();
        assertThat(WechatSyncService.trimDigest("  简短摘要  ")).isEqualTo("简短摘要");
        // 不做长度截断：超长摘要（含 emoji 等增补字符）完整原样同步，由用户发布时自行取舍
        String longText = "摘要".repeat(100) + "😀".repeat(20);
        assertThat(WechatSyncService.trimDigest(longText)).isEqualTo(longText);
    }

    @Test
    void previewFallsBackWhenSettingIsNull() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        // 外部访问地址未配置（ExternalUrlSupplier 返回 null），原文链接应为空
        WechatSyncService previewService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setContent("<p>正文</p>");
        request.setAuthor("文章作者");
        request.setPermalink("/archives/hello");

        Map<String, Object> result = previewService.preview(request, null, new BeautifySetting()).block();

        assertThat(result).isNotNull();
        // 未配置公众号信息时：作者回退文章作者、留言按关闭展示
        assertThat(result.get("author")).isEqualTo("文章作者");
        assertThat(result.get("commentMode")).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
        assertThat(result.get("sourceUrl")).isEqualTo("");
        // 未填写摘要时预览返回空串：弹窗不展示摘要条目
        assertThat(result.get("digest")).isEqualTo("");
    }

    @Test
    void validateReportsConfigAndCoverIssuesTogether() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        WechatSyncService validateService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        // 完全未配置公众号信息、文章也没有封面：配置与封面两条问题一并返回
        List<String> errors = validateService.validate(new SyncRequest(), null).block();

        assertThat(errors).isNotNull().hasSize(2);
        assertThat(errors.get(0)).contains("AppID / AppSecret");
        assertThat(errors.get(1)).contains("当前文章未设置封面图");
    }

    @Test
    void validatePassesWhenConfiguredAndCoverResolvable() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        Secret secret = new Secret();
        secret.setStringData(Map.of(WechatSetting.APP_SECRET_KEY, "s3cret"));
        when(client.fetch(eq(Secret.class), eq("wechat-app-secret"))).thenReturn(Mono.just(secret));
        ExternalUrlSupplier supplier = mock(ExternalUrlSupplier.class);
        when(supplier.getRaw()).thenReturn(URI.create("https://blog.example.com/").toURL());
        WechatSyncService validateService = new WechatSyncService(null, client, supplier);

        SyncRequest request = new SyncRequest();
        request.setCover("/upload/cover.png");
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx123456");
        setting.setAppSecretName("wechat-app-secret");

        // 配置完整、Secret 可解析、相对封面可经外部访问地址补全：校验通过
        List<String> errors = validateService.validate(request, setting).block();

        assertThat(errors).isEmpty();
    }

    @Test
    void validateReportsUnresolvableRelativeCover() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        Secret secret = new Secret();
        secret.setStringData(Map.of(WechatSetting.APP_SECRET_KEY, "s3cret"));
        when(client.fetch(eq(Secret.class), eq("wechat-app-secret"))).thenReturn(Mono.just(secret));
        // 外部访问地址未配置（ExternalUrlSupplier 返回 null），相对封面无法补全为绝对地址
        WechatSyncService validateService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setCover("/upload/cover.png");
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx123456");
        setting.setAppSecretName("wechat-app-secret");

        List<String> errors = validateService.validate(request, setting).block();

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("无法解析封面图地址");
    }

    @Test
    void validateReportsMissingAppSecret() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        // Secret 资源不存在（如被手动删除或改名）
        when(client.fetch(eq(Secret.class), eq("wechat-app-secret"))).thenReturn(Mono.empty());
        WechatSyncService validateService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setCover("https://cdn.example.com/cover.jpg");
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx123456");
        setting.setAppSecretName("wechat-app-secret");

        List<String> errors = validateService.validate(request, setting).block();

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("未找到保存 AppSecret 的 Secret");
    }

    @Test
    void validateReportsSecretWithoutAppSecretValue() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());
        // Secret 存在但不含约定键（如重新配置后键值丢失）
        when(client.fetch(eq(Secret.class), eq("wechat-app-secret")))
            .thenReturn(Mono.just(new Secret()));
        WechatSyncService validateService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setCover("https://cdn.example.com/cover.jpg");
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx123456");
        setting.setAppSecretName("wechat-app-secret");

        List<String> errors = validateService.validate(request, setting).block();

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("未包含 AppSecret");
    }
}
