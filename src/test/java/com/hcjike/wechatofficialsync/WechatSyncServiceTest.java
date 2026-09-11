package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;

/**
 * {@link WechatSyncService} 的行为验证：原文链接始终按「外部访问地址 + 文章路由」拼接；留言设置按选项映射
 * 并兼容旧开关；摘要按 120 字上限截断、空白按未填写处理；预览按与提交一致的规则解析
 * （作者优先级 / 原文链接 / 留言设置 / 正文美化）。
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
    void digestIsTrimmedAndKeptWholeWithinLimit() {
        // 未填写（null / 纯空白）视为空摘要：返回空串，草稿不传 digest，由微信默认抓取正文前 54 个字
        assertThat(WechatSyncService.truncateDigest(null)).isEmpty();
        assertThat(WechatSyncService.truncateDigest("   ")).isEmpty();
        assertThat(WechatSyncService.truncateDigest("  简短摘要  ")).isEqualTo("简短摘要");
        // 正好 120 字保持原样
        String exact = "摘".repeat(WechatSyncService.MAX_DIGEST_LENGTH);
        assertThat(WechatSyncService.truncateDigest(exact)).isEqualTo(exact);
    }

    @Test
    void digestTruncationCountsCodePoints() {
        // 超长摘要截断到 120 个字；emoji（代理对）按 1 个字计，且不会被截成半个字符
        String longText = "摘".repeat(119) + "😀😀";
        String truncated = WechatSyncService.truncateDigest(longText);
        assertThat(truncated).isEqualTo("摘".repeat(119) + "😀");
        assertThat(truncated.codePointCount(0, truncated.length()))
            .isEqualTo(WechatSyncService.MAX_DIGEST_LENGTH);
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
}
