package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRequest;
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
 * 并兼容旧开关；标题（64 字）/ 作者（8 字）/ 摘要（120 字）均按公众号编辑器计字口径（汉字 1 字、
 * 半角 0.5 字、emoji 2 字）截断到各自上限（超长会被微信接口拒绝）、空白按未填写处理；预览按与提交
 * 一致的规则解析（标题 / 作者优先级 / 原文链接 / 留言设置 / 正文美化）并回传被截断的字段名；
 * 同步前预检汇总微信配置与封面图等已知问题。
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
        // 正好 120 字保持原样（不能移除截断：超长摘要会被微信接口拒绝）
        String exact = "摘".repeat(WechatSyncService.MAX_DIGEST_LENGTH);
        assertThat(WechatSyncService.truncateDigest(exact)).isEqualTo(exact);
    }

    @Test
    void digestTruncationCountsEmojiAsTwoCharacters() {
        // emoji 按公众号摘要计数器实测的 2 个字计：118 个汉字 + 1 个 emoji = 120 字，恰好保留
        String full = "摘".repeat(118) + "😀";
        assertThat(WechatSyncService.truncateDigest(full)).isEqualTo(full);
        // 再加 1 个 emoji 超出 120 字：整个 emoji 被截掉，不会截成半个代理对
        String truncated = WechatSyncService.truncateDigest("摘".repeat(118) + "😀😀");
        assertThat(truncated).isEqualTo(full);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(119);
        // 119 个汉字后只剩 1 字空间，emoji（2 字）放不下，只能截到 119 个汉字
        assertThat(WechatSyncService.truncateDigest("摘".repeat(119) + "😀"))
            .isEqualTo("摘".repeat(119));
    }

    @Test
    void digestTruncationAppliesEditorWidthRule() {
        // 摘要按公众号编辑器计字口径：半角字符（英文/数字/符号）按 0.5 字计，
        // 240 个英文字符 = 120 字，整段保留（该口径实测能准确截到微信允许的 120 字）
        String ascii = "a".repeat(WechatSyncService.MAX_DIGEST_LENGTH * 2);
        assertThat(WechatSyncService.truncateDigest(ascii)).isEqualTo(ascii);
        // 超出 120 字（半角单位 240）时在边界处截断：第 241 个半角字符被丢弃
        assertThat(WechatSyncService.truncateDigest(ascii + "b")).isEqualTo(ascii);
        // 混合内容：119 个汉字（119 字）+ 2 个半角字符 = 120 字，边界保留；后续字符被截断
        String mixed = "中".repeat(119) + "ab" + "cd";
        assertThat(WechatSyncService.truncateDigest(mixed)).isEqualTo("中".repeat(119) + "ab");
        // 半角符号同英文一样按 0.5 字计：239 个半角符号 = 119.5 字，再加 1 个汉字（1 字）超出 120 字被截断
        String symbols = "!".repeat(239) + "中";
        assertThat(WechatSyncService.truncateDigest(symbols)).isEqualTo("!".repeat(239));
    }

    @Test
    void titleIsTruncatedToWechatTitleLimit() {
        // 正好 64 字保持原样；65 字截到 64 字（超长标题会被 draft/add 拒绝）
        String exact = "标".repeat(WechatSyncService.MAX_TITLE_LENGTH);
        assertThat(WechatSyncService.truncateToWechatLength(exact, WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo(exact);
        assertThat(WechatSyncService.truncateToWechatLength(exact + "题", WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo(exact);
        // 与摘要同一套编辑器计字：半角 0.5 字（128 个英文字符 = 64 字）、emoji 2 字
        String ascii = "a".repeat(WechatSyncService.MAX_TITLE_LENGTH * 2);
        assertThat(WechatSyncService.truncateToWechatLength(ascii, WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo(ascii);
        assertThat(WechatSyncService.truncateToWechatLength(ascii + "b", WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo(ascii);
        String withEmoji = "标".repeat(WechatSyncService.MAX_TITLE_LENGTH) + "😀";
        assertThat(WechatSyncService.truncateToWechatLength(withEmoji, WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo(exact);
        // 首尾空白去除、空白视为未设置
        assertThat(WechatSyncService.truncateToWechatLength("  标题  ", WechatSyncService.MAX_TITLE_LENGTH))
            .isEqualTo("标题");
        assertThat(WechatSyncService.truncateToWechatLength("   ", WechatSyncService.MAX_TITLE_LENGTH)).isEmpty();
        assertThat(WechatSyncService.truncateToWechatLength(null, WechatSyncService.MAX_TITLE_LENGTH)).isEmpty();
    }

    @Test
    void authorIsTruncatedToWechatAuthorLimit() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG))).thenReturn(Mono.empty());
        WechatSyncService previewService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        String truncatedAtLimit = "作".repeat(WechatSyncService.MAX_AUTHOR_LENGTH);

        // 未配置「默认作者」：回退文章作者并按 8 字截断（超过 8 字会被 draft/add 拒绝并返回 45110）
        SyncRequest request = new SyncRequest();
        request.setContent("<p>正文</p>");
        request.setAuthor(truncatedAtLimit + "者名");
        Map<String, Object> result = previewService.preview(request, new WechatSetting(), new BeautifySetting())
            .block();

        assertThat(result).isNotNull();
        assertThat((String) result.get("author")).isEqualTo(truncatedAtLimit);

        // 配置了「默认作者」：同样按 8 字截断（超长的配置值不再原样提交，避免整次同步失败）
        WechatSetting setting = new WechatSetting();
        setting.setAuthor("默认作者名".repeat(10));
        Map<String, Object> withSettingAuthor =
            previewService.preview(request, setting, new BeautifySetting()).block();

        assertThat(withSettingAuthor).isNotNull();
        // 「默认作者名」重复 10 次 = 50 字，截到前 8 字
        assertThat((String) withSettingAuthor.get("author")).isEqualTo("默认作者名默认作");
        assertThat(WechatSyncService.wechatHalfUnits((String) withSettingAuthor.get("author")))
            .isEqualTo(WechatSyncService.MAX_AUTHOR_LENGTH * 2);
    }

    @Test
    void wechatLengthUsesEditorCountingRule() {
        // 计字规则（半角单位）：汉字/全角 2 个单位（1 字）、半角 1 个单位（0.5 字）、emoji 4 个单位（2 字）
        assertThat(WechatSyncService.wechatHalfUnits("中文")).isEqualTo(4);
        assertThat(WechatSyncService.wechatHalfUnits("ab")).isEqualTo(2);
        assertThat(WechatSyncService.wechatHalfUnits("😀")).isEqualTo(4);
        assertThat(WechatSyncService.wechatHalfUnits(null)).isZero();
    }

    @Test
    void previewReturnsTitleTruncatedLikeSubmit() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG))).thenReturn(Mono.empty());
        WechatSyncService previewService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setContent("<p>正文</p>");
        request.setTitle("标".repeat(WechatSyncService.MAX_TITLE_LENGTH + 10));
        Map<String, Object> result = previewService.preview(request, new WechatSetting(), new BeautifySetting())
            .block();

        assertThat(result).isNotNull();
        // 预览展示的标题即最终写入草稿的标题（截断到 64 字）
        assertThat((String) result.get("title")).isEqualTo("标".repeat(WechatSyncService.MAX_TITLE_LENGTH));
    }

    @Test
    void previewReportsWhichFieldsWereTruncated() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG))).thenReturn(Mono.empty());
        WechatSyncService previewService =
            new WechatSyncService(null, client, mock(ExternalUrlSupplier.class));

        // 标题 / 摘要 / 文章作者均超上限：truncatedFields 按截断顺序列出，供预览界面标识
        SyncRequest request = new SyncRequest();
        request.setContent("<p>正文</p>");
        request.setTitle("标".repeat(WechatSyncService.MAX_TITLE_LENGTH + 1));
        request.setDigest("摘".repeat(WechatSyncService.MAX_DIGEST_LENGTH + 1));
        request.setAuthor("作".repeat(WechatSyncService.MAX_AUTHOR_LENGTH + 1));

        Map<String, Object> result =
            previewService.preview(request, new WechatSetting(), new BeautifySetting()).block();

        assertThat(result).isNotNull();
        assertThat(result.get("truncatedFields")).isEqualTo(List.of("title", "digest", "author"));

        // 都在上限内（摘要未填写）时列表为空：预览不展示任何截断标识
        SyncRequest shortRequest = new SyncRequest();
        shortRequest.setContent("<p>正文</p>");
        shortRequest.setTitle("标题");
        shortRequest.setAuthor("文章作者");
        Map<String, Object> noTruncation =
            previewService.preview(shortRequest, new WechatSetting(), new BeautifySetting()).block();

        assertThat(noTruncation).isNotNull();
        assertThat(noTruncation.get("truncatedFields")).isEqualTo(List.of());
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
