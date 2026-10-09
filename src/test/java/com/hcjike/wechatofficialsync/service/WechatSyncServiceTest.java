package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.client.WechatApiException;
import com.hcjike.wechatofficialsync.client.WechatMpClient;
import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.OngoingStubbing;
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

    private final WechatSyncService service = new WechatSyncService(null, null, null, null);

    /** 本次测试专属的临时目录：媒体缓存库建在这里，测试之间互不干扰。 */
    @TempDir
    Path tempDirectory;

    /**
     * 构造待测服务：媒体缓存服务指向本次测试专属的临时库（每次新建调用即一个空库）。
     *
     * <p>附件转存用例会真实上传（微信客户端是 mock），因此缓存必须真的可用，才能覆盖
     * 「上传后写缓存」的路径；预览 / 预检类用例不走上传，缓存不会被触发。</p>
     */
    private WechatSyncService syncService(WechatMpClient wechatMpClient, ReactiveExtensionClient client,
        ExternalUrlSupplier supplier) {
        WechatMediaCacheStore store =
            new WechatMediaCacheStore(() -> tempDirectory.resolve("plugins"));
        return new WechatSyncService(wechatMpClient, client, supplier,
            new WechatMediaCacheService(wechatMpClient, store));
    }

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
        WechatSyncService previewService = syncService(null, client, supplier);

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
        WechatSyncService validateService = syncService(null, client, supplier);

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

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
            syncService(null, client, mock(ExternalUrlSupplier.class));

        SyncRequest request = new SyncRequest();
        request.setCover("https://cdn.example.com/cover.jpg");
        WechatSetting setting = new WechatSetting();
        setting.setAppId("wx123456");
        setting.setAppSecretName("wechat-app-secret");

        List<String> errors = validateService.validate(request, setting).block();

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("未包含 AppSecret");
    }

    // ---------- 封面与草稿：封面素材失效（40007 invalid media_id）后的自愈重试 ----------
    //
    // 封面素材虽经缓存校验，仍可能已在微信侧失效：素材被删除后它的图片地址往往依旧可访问（地址是 CDN 上的
    // 副本，不等于素材库里的条目），「接口地址」代理没转发 material/get_material 时也拿不到结论、只能保守
    // 复用。这类情况下 draft/add 会以 40007 invalid media_id 拒绝，插件应重传一次封面再建一次草稿，
    // 而不是把同一个失效 id 反复丢给用户。

    /** 外部访问地址（本站地址）。 */
    private static final String BASE_URL = "https://blog.example.com";

    /** 封面图地址（绝对地址，提交时无需外部访问地址兜底）。 */
    private static final String COVER_URL = BASE_URL + "/upload/cover.png";

    @Test
    void retriesDraftOnceWithReuploadedCoverWhenThumbMediaIdIsInvalid() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD", "MEDIA-NEW");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any()))
            .thenReturn(Mono.error(invalidMediaId()), Mono.just("DRAFT-NEW"));

        assertThat(createDraft(client)).isEqualTo("DRAFT-NEW");

        // 封面重传了一次拿到新的 media_id，草稿因此提交了两次
        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        verify(client, times(2)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void reusesReuploadedCoverOnNextSync() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD", "MEDIA-NEW");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any()))
            .thenReturn(Mono.error(invalidMediaId()), Mono.just("DRAFT-NEW"), Mono.just("DRAFT-NEXT"));
        // 重传拿到的 media_id 校验通过：下次同步直接复用，不会再撞上那个失效的 id
        when(client.checkMaterialAvailability(API_BASE, "TOKEN", "MEDIA-NEW"))
            .thenReturn(Mono.just(WechatMpClient.ImageAvailability.AVAILABLE));

        assertThat(createDraft(client)).isEqualTo("DRAFT-NEW");
        assertThat(createDraft(client)).isEqualTo("DRAFT-NEXT");

        // 两次同步合计只上传了两次封面（首次上传 + 自愈重传），第二次同步是命中缓存复用新 media_id
        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void doesNotReuploadCoverForUnrelatedDraftErrors() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any()))
            .thenReturn(Mono.error(new WechatApiException("创建公众号草稿失败：{errcode=45009}", "45009")));

        assertThatThrownBy(() -> createDraft(client))
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("45009");

        // 与封面素材无关的失败：不重传封面、不重试草稿（避免无谓地占用素材库）
        verify(client, times(1)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void retriesDraftAtMostOnce() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD", "MEDIA-NEW");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.error(invalidMediaId()));

        assertThatThrownBy(() -> createDraft(client))
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("40007");

        // 只重试一次：再次被拒说明问题不在封面，不再继续重传
        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        verify(client, times(2)).addDraft(anyString(), anyString(), any());
    }

    // ---------- 重复同步：优先更新既有草稿 ----------
    //
    // 首次同步（任务里没有已知草稿）直接新建草稿；重复同步先校验上次写入的那份草稿是否仍在——
    // 在则更新（draft/update，草稿 media_id 不变），不在则新建（draft/add）。校验过程发生任何错误
    // 都按「草稿不存在」处理、直接新建，不因为一次校验失败打断同步；更新被 40007 拒绝（草稿已被删除，
    // 或草稿引用的封面素材已失效）时同样回退到新建（封面若已失效，新建路径会再触发一次封面重传）。

    @Test
    void createsNewDraftOnRepeatSyncWhenUpdateExistingDraftDisabled() {
        // 设置里关闭「重复同步更新草稿」：不做任何校验、也不更新，每次同步都新建一份草稿
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-NEW"));

        assertThat(createDraftResult(client, "DRAFT-1", false).mediaId()).isEqualTo("DRAFT-NEW");

        verify(client, never()).draftExists(anyString(), anyString(), anyString());
        verify(client, never()).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void firstSyncCreatesDraftWithoutCheckingExistingOne() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-1"));

        assertThat(createDraft(client)).isEqualTo("DRAFT-1");

        // 首次同步没有已知草稿：不校验草稿（省掉一次接口调用），直接新建
        verify(client, never()).draftExists(anyString(), anyString(), anyString());
        verify(client, never()).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void updatesExistingDraftOnRepeatSync() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(true));
        when(client.updateDraft(eq(API_BASE), eq("TOKEN"), eq("DRAFT-1"), any()))
            .thenReturn(Mono.just("DRAFT-1"));

        WechatSyncService.DraftResult result = createDraftResult(client, "DRAFT-1", true);

        assertThat(result.mediaId()).isEqualTo("DRAFT-1");
        // 草稿还在：更新这一份，而不是再新建一份（重复同步不会在草稿箱里越积越多）；
        // 实际动作如实回报「更新」，MCP 状态工具据此告诉用户草稿是被更新的
        assertThat(result.draftAction()).isEqualTo(SyncRecord.DRAFT_ACTION_UPDATE);
        verify(client, times(1)).updateDraft(eq(API_BASE), eq("TOKEN"), eq("DRAFT-1"), any());
        verify(client, never()).addDraft(anyString(), anyString(), any());
    }

    @Test
    void createsNewDraftWhenExistingDraftIsGone() {
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-GONE")).thenReturn(Mono.just(false));
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-NEW"));

        assertThat(createDraft(client, "DRAFT-GONE")).isEqualTo("DRAFT-NEW");

        verify(client, never()).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void createsNewDraftWhenDraftCheckFails() {
        // 校验草稿出错（代理未转发 draft/get、限流、网络异常等）同样按「不存在」处理、直接新建：
        // 一次校验失败不能打断同步（客户端已把可预期的错误收敛为 false，这里兜底异常信号）
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1"))
            .thenReturn(Mono.error(new WechatApiException("校验草稿失败")));
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-NEW"));

        assertThat(createDraft(client, "DRAFT-1")).isEqualTo("DRAFT-NEW");

        verify(client, never()).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void createsNewDraftWhenUpdateIsRejectedWithInvalidMediaId() {
        // 更新被 40007 拒绝：草稿已被删除，或草稿引用的封面素材已失效——改走新建草稿
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(true));
        when(client.updateDraft(eq(API_BASE), eq("TOKEN"), eq("DRAFT-1"), any()))
            .thenReturn(Mono.error(invalidMediaId()));
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-NEW"));

        assertThat(createDraft(client, "DRAFT-1")).isEqualTo("DRAFT-NEW");

        verify(client, times(1)).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    @Test
    void doesNotCreateNewDraftWhenUpdateFailsForUnrelatedReason() {
        // 与「草稿/封面素材失效」无关的更新失败（如频控 45011）：照原样抛错，不做无谓重试
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(true));
        when(client.updateDraft(eq(API_BASE), eq("TOKEN"), eq("DRAFT-1"), any()))
            .thenReturn(Mono.error(new WechatApiException("更新公众号草稿失败：{errcode=45011}", "45011")));

        assertThatThrownBy(() -> createDraft(client, "DRAFT-1"))
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("45011");

        verify(client, never()).addDraft(anyString(), anyString(), any());
    }

    @Test
    void createsNewDraftWhenUpdateIsRejectedByGateway() {
        // 更新被网关 / 代理层拒绝（如腾讯云 WAF 内容风控的 501：请求没到微信业务层、更新确定没有生效）：
        // 改为新建草稿，而不是让整次同步失败（用户否则只能关掉「重复同步更新草稿」开关绕开）；
        // 实际动作如实回报「新建」——草稿箱里确实多出了一份
        WechatMpClient client = mock(WechatMpClient.class);
        stubCoverUpload(client, "MEDIA-OLD");
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(true));
        when(client.updateDraft(eq(API_BASE), eq("TOKEN"), eq("DRAFT-1"), any()))
            .thenReturn(Mono.error(gatewayRejected()));
        when(client.addDraft(eq(API_BASE), eq("TOKEN"), any())).thenReturn(Mono.just("DRAFT-NEW"));

        WechatSyncService.DraftResult result = createDraftResult(client, "DRAFT-1", true);

        assertThat(result.mediaId()).isEqualTo("DRAFT-NEW");
        assertThat(result.draftAction()).isEqualTo(SyncRecord.DRAFT_ACTION_CREATE);
        verify(client, times(1)).updateDraft(anyString(), anyString(), anyString(), any());
        verify(client, times(1)).addDraft(anyString(), anyString(), any());
    }

    // ---------- 草稿存在性校验（供 MCP 提交前判定本次是更新还是新建） ----------

    @Test
    void existingDraftExistsReturnsFalseWithoutDraftId() {
        WechatMpClient client = mock(WechatMpClient.class);
        WechatSyncService service = syncService(client, null, null);

        // 没有可校验的草稿：直接回 false，不调用任何微信接口
        assertThat(service.existingDraftExists(new WechatSetting(), null).block()).isFalse();
        assertThat(service.existingDraftExists(new WechatSetting(), "  ").block()).isFalse();
        verify(client, never()).getAccessToken(anyString(), anyString(), anyString());
        verify(client, never()).draftExists(anyString(), anyString(), anyString());
    }

    @Test
    void existingDraftExistsQueriesWechatWithConfiguredAccount() {
        WechatMpClient client = mock(WechatMpClient.class);
        ReactiveExtensionClient extensionClient = mock(ReactiveExtensionClient.class);
        when(extensionClient.fetch(eq(Secret.class), eq("wechat-app-secret")))
            .thenReturn(Mono.just(secret("s3cret")));
        when(client.getAccessToken(API_BASE, APP_ID, "s3cret")).thenReturn(Mono.just("TOKEN"));
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(true));

        WechatSetting setting = new WechatSetting();
        setting.setAppId(APP_ID);
        setting.setAppSecretName("wechat-app-secret");
        WechatSyncService service = syncService(client, extensionClient, null);

        // 草稿仍在微信侧：true（MCP 提交时才据此说「本次会更新草稿」）
        assertThat(service.existingDraftExists(setting, "DRAFT-1").block()).isTrue();

        // 草稿已被删除：如实回 false，调用方按「新建」处理
        when(client.draftExists(API_BASE, "TOKEN", "DRAFT-1")).thenReturn(Mono.just(false));
        assertThat(service.existingDraftExists(setting, "DRAFT-1").block()).isFalse();
    }

    @Test
    void existingDraftExistsReturnsFalseWhenCheckFails() {
        WechatMpClient client = mock(WechatMpClient.class);
        // 取 access_token 失败（AppSecret 配置有误等）：按「已不在」处理，不向调用方抛出异常
        when(client.getAccessToken(anyString(), anyString(), anyString()))
            .thenReturn(Mono.error(new WechatApiException("获取 access_token 失败")));
        ReactiveExtensionClient extensionClient = mock(ReactiveExtensionClient.class);
        when(extensionClient.fetch(eq(Secret.class), eq("wechat-app-secret")))
            .thenReturn(Mono.just(secret("s3cret")));

        WechatSetting setting = new WechatSetting();
        setting.setAppId(APP_ID);
        setting.setAppSecretName("wechat-app-secret");

        assertThat(syncService(client, extensionClient, null).existingDraftExists(setting, "DRAFT-1").block())
            .isFalse();
    }

    /** 含约定键的 AppSecret Secret。 */
    private static Secret secret(String appSecret) {
        Secret secret = new Secret();
        secret.setStringData(Map.of(WechatSetting.APP_SECRET_KEY, appSecret));
        return secret;
    }

    /** 走一遍「封面 → 草稿」链路（正文不含图片，不涉及转存）：{@code draftMediaId} 为空表示首次同步。 */
    private String createDraft(WechatMpClient client) {
        return createDraft(client, null);
    }

    /** 默认开启「重复同步更新草稿」：非空 draftMediaId 时先校验、在则更新。 */
    private String createDraft(WechatMpClient client, String draftMediaId) {
        return createDraftResult(client, draftMediaId, true).mediaId();
    }

    /**
     * 走一遍「封面 → 草稿」链路（正文不含图片，不涉及转存），返回草稿写入结果（media_id + 实际动作）。
     *
     * @param draftMediaId         上次成功同步写入的草稿 media_id；为空表示首次同步（直接新建草稿）
     * @param updateExistingDraft  插件设置「重复同步更新草稿」开关：关闭时每次同步都新建草稿
     */
    private WechatSyncService.DraftResult createDraftResult(WechatMpClient client, String draftMediaId,
        boolean updateExistingDraft) {
        WechatSetting setting = new WechatSetting();
        setting.setAppId(APP_ID);
        setting.setUpdateExistingDraft(updateExistingDraft);
        SyncRequest request = new SyncRequest();
        request.setTitle("测试文章");
        request.setCover(COVER_URL);
        request.setContent("<p>正文</p>");
        return syncService(client, null, null)
            .createDraft(API_BASE, setting, "TOKEN", request, new BeautifySetting(),
                BASE_URL + "/archives/hello-world", BASE_URL, draftMediaId)
            .block();
    }

    /** 预置封面下载与上传结果：上传按顺序返回给定的 media_id（首个为旧素材，重传后为新素材）。 */
    private static void stubCoverUpload(WechatMpClient client, String... mediaIds) {
        when(client.download(COVER_URL)).thenReturn(Mono.just(pngMagicBytes()));
        OngoingStubbing<Mono<WechatMpClient.PermanentImage>> stubbing = when(
            client.uploadPermanentImage(anyString(), anyString(), any(), anyString()));
        for (String mediaId : mediaIds) {
            stubbing = stubbing.thenReturn(
                Mono.just(new WechatMpClient.PermanentImage(mediaId, COVER_URL)));
        }
    }

    /** 微信以「封面素材已失效」拒绝草稿（{@code errcode=40007 invalid media_id}）。 */
    private static WechatApiException invalidMediaId() {
        return new WechatApiException("创建公众号草稿失败：{errcode=40007, errmsg=invalid media_id}",
            WechatApiException.INVALID_MEDIA_ID_ERRCODE);
    }

    /**
     * 网关 / 代理层以非 2xx 拒绝（如腾讯云 WAF 内容风控拦截页 {@code 501}）：没有 errcode，
     * 但可确定请求没有到达微信业务层、这次调用没有生效。
     */
    private static WechatApiException gatewayRejected() {
        return new WechatApiException("微信接口返回 HTTP 501 Not Implemented"
            + "（POST /cgi-bin/draft/update）；请求被腾讯云 WAF 按内容风控拦截（未到达微信接口，故无 errcode）",
            null, 501);
    }

    // ---------- 正文附件链接 ----------
    //
    // 规则：先按真实字节判定，只有微信支持的图片才转存为微信图片；其余（非图片附件、字节不符、下载失败）
    // 一律不调用微信接口，把链接替换为纯文本——显示「原始地址」还是「链接自身的文字」由「附件链接显示」
    // 配置决定；普通网页链接不视为附件、原样保留。

    /** 微信接口基址（与插件默认基址一致，断言 mock 调用时用）。 */
    private static final String API_BASE = "https://api.weixin.qq.com";

    /** 公众号 AppID：媒体缓存按公众号分区，仅用于拼接缓存键。 */
    private static final String APP_ID = "wx-test-app";

    /** 仅需文件魔数正确：附件转存的判定按真实字节，与文件是否完整、能否解码无关。 */
    private static byte[] pngMagicBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }

    /** 处理正文附件链接（默认配置：不可提交到微信的链接显示原始地址）。 */
    private String transferAttachments(WechatMpClient client, String html, String baseUrl) {
        return transferAttachments(client, html, baseUrl, new BeautifySetting());
    }

    /** 处理正文附件链接（可指定「附件链接显示」等正文美化配置）。 */
    private String transferAttachments(WechatMpClient client, String html, String baseUrl,
        BeautifySetting beautify) {
        return syncService(client, null, null)
            .transferAttachments(API_BASE, APP_ID, "TOKEN", html, baseUrl, beautify)
            .block();
    }

    @Test
    void imageAttachmentLinkIsUploadedAndRenderedAsWechatImage() {
        WechatMpClient client = mock(WechatMpClient.class);
        when(client.download("https://blog.example.com/upload/2026/09/pic.png"))
            .thenReturn(Mono.just(pngMagicBytes()));
        when(client.uploadContentImage(eq(API_BASE), eq("TOKEN"), any(), eq("pic.png")))
            .thenReturn(Mono.just("https://mmbiz.qpic.cn/mmbiz_png/WECHAT.png"));

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/pic.png\">图片附件</a></p>", "https://blog.example.com");

        assertThat(result)
            .contains("<img src=\"https://mmbiz.qpic.cn/mmbiz_png/WECHAT.png\"")
            .doesNotContain("/upload/2026/09/pic.png");
    }

    @Test
    void nonImageAttachmentLinkIsNotUploadedAndShownAsPlainAddress() {
        WechatMpClient client = mock(WechatMpClient.class);

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/manual.pdf\">下载手册</a></p>", "https://blog.example.com");

        assertThat(result).contains("/upload/2026/09/manual.pdf").doesNotContain("<a ");
        verify(client, never()).download(anyString());
        verify(client, never()).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void nonImageAttachmentLinkShowsLinkContentWhenConfigured() {
        // 「附件链接显示」选「显示链接内容」：用链接自身的文字呈现，而不是原始地址
        WechatMpClient client = mock(WechatMpClient.class);
        BeautifySetting beautify = new BeautifySetting();
        beautify.setAttachmentLinkDisplay(BeautifySetting.ATTACHMENT_LINK_DISPLAY_CONTENT);

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/manual.pdf\">下载手册</a></p>", "https://blog.example.com",
            beautify);

        assertThat(result).contains("下载手册")
            .doesNotContain("/upload/2026/09/manual.pdf")
            .doesNotContain("<a ");
        verify(client, never()).download(anyString());
        verify(client, never()).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void linkContentFallsBackToAddressWhenLinkHasNoText() {
        // 「显示链接内容」但链接没有可见文字（如纯图标卡片）时回退原始地址，避免产出空白
        WechatMpClient client = mock(WechatMpClient.class);
        BeautifySetting beautify = new BeautifySetting();
        beautify.setAttachmentLinkDisplay(BeautifySetting.ATTACHMENT_LINK_DISPLAY_CONTENT);

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/manual.pdf\"></a></p>", "https://blog.example.com", beautify);

        assertThat(result).contains("/upload/2026/09/manual.pdf");
    }

    @Test
    void unknownLinkDisplayValueFallsBackToAddress() {
        // 取值非法（如升级残留的旧值）时按「显示链接地址」处理
        WechatMpClient client = mock(WechatMpClient.class);
        BeautifySetting beautify = new BeautifySetting();
        beautify.setAttachmentLinkDisplay("mystery");

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/manual.pdf\">下载手册</a></p>", "https://blog.example.com",
            beautify);

        assertThat(result).contains("/upload/2026/09/manual.pdf").doesNotContain("下载手册");
    }

    @Test
    void attachmentWithImageExtensionButNonImageBytesIsShownAsPlainAddress() {
        // 扩展名像图片、真实字节不是：不得上传（微信图片接口只会返回 40005/40113），改为纯文本呈现
        WechatMpClient client = mock(WechatMpClient.class);
        when(client.download("https://blog.example.com/upload/2026/09/fake.png"))
            .thenReturn(Mono.just(new byte[] {1, 2, 3}));

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/fake.png\">附件</a></p>", "https://blog.example.com");

        assertThat(result).contains("/upload/2026/09/fake.png").doesNotContain("<img");
        verify(client, never()).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void attachmentDownloadFailureDegradesToPlainAddress() {
        WechatMpClient client = mock(WechatMpClient.class);
        when(client.download("https://blog.example.com/upload/2026/09/pic.png"))
            .thenReturn(Mono.error(new WechatApiException("下载失败")));

        String result = transferAttachments(client,
            "<p><a href=\"/upload/2026/09/pic.png\">图片附件</a></p>", "https://blog.example.com");

        assertThat(result).contains("/upload/2026/09/pic.png").doesNotContain("<img");
        verify(client, never()).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void unresolvableAttachmentLinkWithoutBaseUrlIsShownAsPlainAddress() {
        // 相对地址且未配置「外部访问地址」：无法解析地址，不下载，显示原始地址（正文里写的是什么就显示什么）
        WechatMpClient client = mock(WechatMpClient.class);

        String result = transferAttachments(client, "<p><a href=\"/upload/x.pdf\">附件</a></p>", "");

        assertThat(result).contains("/upload/x.pdf").doesNotContain("<a ");
        verify(client, never()).download(anyString());
    }

    @Test
    void downloadLinkWithFileExtensionIsShownAsPlainAddress() {
        // 「下载链接」等自定义元素在美化阶段转换出的链接（无 /upload/ 前缀，靠文件扩展名识别）：同样只显示原始地址
        WechatMpClient client = mock(WechatMpClient.class);

        String result = transferAttachments(client,
            "<p><a href=\"https://x/f.zip\" target=\"_blank\">https://x/f.zip</a></p>",
            "https://blog.example.com");

        assertThat(result).contains("https://x/f.zip").doesNotContain("<a ");
        verify(client, never()).download(anyString());
    }

    @Test
    void plainWebLinksAreLeftUntouched() {
        // 站内文章路由、无扩展名的分享页等普通链接不是「附件」，不参与处理（微信外链不可点击并不等于要改成纯文本）
        WechatMpClient client = mock(WechatMpClient.class);
        String html = "<p><a href=\"/archives/hello-world\">相关文章</a>"
            + "<a href=\"https://github.com/hcjike/plugin-wechat-official-sync\">仓库</a></p>";

        String result = transferAttachments(client, html, "https://blog.example.com");

        assertThat(result)
            .contains("/archives/hello-world")
            .contains("https://github.com/hcjike/plugin-wechat-official-sync")
            .contains("<a href");
        verify(client, never()).download(anyString());
        verify(client, never()).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void previewShowsUnsubmittableAttachmentLinkAsPlainText() throws Exception {
        // 预览与草稿保持一致：无需下载即可判定提交不到微信的附件链接，在预览里就按配置显示为纯文本
        WechatSyncService previewService = previewServiceWithExternalUrl("https://blog.example.com/");
        SyncRequest request = new SyncRequest();
        request.setContent("<p><a href=\"/upload/2026/09/manual.pdf\">下载手册</a></p>");
        BeautifySetting beautify = new BeautifySetting();
        beautify.setAttachmentLinkDisplay(BeautifySetting.ATTACHMENT_LINK_DISPLAY_CONTENT);

        Map<String, Object> result = previewService.preview(request, new WechatSetting(), beautify).block();

        assertThat(result).isNotNull();
        assertThat((String) result.get("content")).contains("下载手册").doesNotContain("<a ");
    }

    @Test
    void previewKeepsImageAttachmentLinkForSubmitTimeTransfer() throws Exception {
        // 图片型附件能不能转存要下载后按真实字节判定，与正文图片一样只在提交时处理，
        // 预览中保留该链接（仅把相对地址补全为完整链接，见 previewUsesCompleteUrlsForImagesAndLinks）
        WechatSyncService previewService = previewServiceWithExternalUrl("https://blog.example.com/");
        SyncRequest request = new SyncRequest();
        request.setContent("<p><a href=\"/upload/2026/09/pic.png\">图片附件</a></p>");

        Map<String, Object> result =
            previewService.preview(request, new WechatSetting(), new BeautifySetting()).block();

        assertThat(result).isNotNull();
        assertThat((String) result.get("content"))
            .contains("<a href=\"https://blog.example.com/upload/2026/09/pic.png\"");
    }

    @Test
    void previewUsesCompleteUrlsForImagesAndLinks() throws Exception {
        // 预览正文里的相对地址补全为完整链接：Console 预览有站点页面可解析相对地址，
        // 而 MCP 客户端拿到的是脱离站点的 HTML 片段，相对地址会让图片打不开
        WechatSyncService previewService = previewServiceWithExternalUrl("https://blog.example.com/");
        SyncRequest request = new SyncRequest();
        request.setContent("<p><img src=\"/upload/2026/09/pic.png\" alt=\"图\">"
            + "<a href=\"/archives/hello\">站内文章</a>"
            + "<a href=\"https://github.com/hcjike/plugin-wechat-official-sync\">仓库</a>"
            + "<a href=\"#top\">回到顶部</a>"
            + "<img src=\"data:image/gif;base64,R0lGOD\"></p>");

        Map<String, Object> result =
            previewService.preview(request, new WechatSetting(), new BeautifySetting()).block();

        assertThat(result).isNotNull();
        assertThat((String) result.get("content"))
            .contains("src=\"https://blog.example.com/upload/2026/09/pic.png\"")
            .contains("href=\"https://blog.example.com/archives/hello\"")
            // 已是绝对地址与非文件类地址（锚点、data:）原样保留
            .contains("href=\"https://github.com/hcjike/plugin-wechat-official-sync\"")
            .contains("href=\"#top\"")
            .contains("src=\"data:image/gif;base64,R0lGOD\"");
    }

    @Test
    void previewKeepsRelativeUrlsWhenExternalUrlNotConfigured() throws Exception {
        // 未配置「外部访问地址」时没有可用基址：保持相对地址（Console 预览仍由页面自身解析）
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG))).thenReturn(Mono.empty());
        // ExternalUrlSupplier 未配置时 getRaw() 返回 null，即没有可用的站点地址
        ExternalUrlSupplier supplier = mock(ExternalUrlSupplier.class);
        WechatSyncService previewService = syncService(null, client, supplier);
        SyncRequest request = new SyncRequest();
        request.setContent("<p><img src=\"/upload/2026/09/pic.png\" alt=\"图\"></p>");

        Map<String, Object> result =
            previewService.preview(request, new WechatSetting(), new BeautifySetting()).block();

        assertThat(result).isNotNull();
        assertThat((String) result.get("content")).contains("src=\"/upload/2026/09/pic.png\"");
    }

    /** 构造一个「外部访问地址」为给定值的预览服务（ConfigMap 未配置时回退外部地址供应器）。 */
    private WechatSyncService previewServiceWithExternalUrl(String externalUrl) throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG))).thenReturn(Mono.empty());
        ExternalUrlSupplier supplier = mock(ExternalUrlSupplier.class);
        when(supplier.getRaw()).thenReturn(URI.create(externalUrl).toURL());
        return syncService(null, client, supplier);
    }
}
