package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.client.WechatApiException;
import com.hcjike.wechatofficialsync.client.WechatMpClient;
import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.content.WechatContentBeautifier;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.ssrf.SsrfPolicy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;

/**
 * 文章同步到微信公众号的核心业务：获取 token、转存图片、上传封面、创建草稿。
 *
 * <p>封面与正文图片的上传都经 {@link WechatMediaCacheService} 走素材缓存：同一份文件（按内容指纹判定）
 * 只会向微信上传一次，之后直接复用上次返回的 media_id / 图片地址，避免挤占微信素材库。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Service
public class WechatSyncService {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncService.class);

    /** 微信可转存的图片扩展名（webp 由客户端转码）：带这些扩展名的附件才值得下载后按真实字节复核。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    /** 页面类扩展名：站内文章路由可能带这些后缀（如 {@code /archives/x.html}），不当作附件链接处理。 */
    private static final Set<String> PAGE_EXTENSIONS =
        Set.of("html", "htm", "shtml", "php", "asp", "aspx", "jsp");

    private final WechatMpClient wechatMpClient;

    private final ReactiveExtensionClient client;

    private final ExternalUrlSupplier externalUrlSupplier;

    /** 媒体上传缓存：避免同一张图被反复上传（永久素材会占用微信素材库）。 */
    private final WechatMediaCacheService mediaCacheService;

    public WechatSyncService(WechatMpClient wechatMpClient, ReactiveExtensionClient client,
        ExternalUrlSupplier externalUrlSupplier, WechatMediaCacheService mediaCacheService) {
        this.wechatMpClient = wechatMpClient;
        this.client = client;
        this.externalUrlSupplier = externalUrlSupplier;
        this.mediaCacheService = mediaCacheService;
    }

    /**
     * 执行一次完整的同步流程，成功后返回草稿 media_id。
     *
     * @param request  同步请求（文章标题/正文/封面等）
     * @param setting  公众号凭据与基本配置
     * @param beautify 正文美化配置（独立设置分组，缺省时用内置默认值）
     */
    public Mono<String> submit(SyncRequest request, WechatSetting setting, BeautifySetting beautify) {
        // 微信接口基址：留空直连官方，或指向用户自建的反向代理（用固定公网 IP 过微信白名单）
        String apiBase = WechatMpClient.resolveApiBase(setting.getBaseUrl());
        // 先在 boundedElastic 上解析图片下载内网白名单并下发给下载客户端：SsrfPolicy.parse 可能
        // 触发阻塞式地址解析，故不放在事件循环；随后再执行同步主流程。
        return Mono.fromCallable(() -> SsrfPolicy.parse(setting.getImageHostAllowlist()))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(wechatMpClient::setSsrfPolicy)
            .then(Mono.defer(() -> resolveExternalBaseUrl()
                .flatMap(baseUrl -> resolvePermalink(request)
                    .flatMap(permalink -> {
                        // 原文链接（「阅读原文」）：外部访问地址 + 文章路由；无法解析时为空（不显示阅读原文）
                        String sourceUrl = resolveSourceUrl(permalink, baseUrl);
                        logSourceUrl(request, permalink, baseUrl, sourceUrl);
                        return resolveAppSecret(setting)
                            .flatMap(appSecret -> wechatMpClient
                                .getAccessToken(apiBase, setting.getAppId(), appSecret)
                                .flatMap(token -> uploadCover(apiBase, setting.getAppId(), token,
                                        request.getCover(), baseUrl)
                                    .doOnNext(thumbMediaId -> log.info("文章《{}》封面素材已就绪，thumb_media_id={}",
                                        request.getTitle(), thumbMediaId))
                                    .flatMap(thumbMediaId ->
                                        prepareContent(apiBase, setting.getAppId(), token, request, beautify,
                                                baseUrl)
                                            .flatMap(content -> wechatMpClient.addDraft(apiBase, token,
                                                buildArticle(request, setting, thumbMediaId, content,
                                                    sourceUrl))))));
                    }))));
    }

    /**
     * 构建「同步预览」：按与 {@link #submit} 一致的规则解析正文美化效果与草稿元信息，
     * 但不做任何写操作——不下载/转存图片、不上传封面、不调用微信接口、不写同步记录。
     *
     * <p>返回的 {@code content}（美化后的正文）、{@code title}（草稿标题，按与提交一致的编辑器计字
     * 截断到 64 字）、{@code digest}（草稿摘要，同样口径去除首尾空白并截断到 120 字；为空表示未填写
     * 摘要、微信默认抓取正文前 54 个字）、{@code author}（草稿作者，设置的「默认作者」优先、留空回退
     * 文章作者，两者都按编辑器计字截断到 8 字，与 {@link #buildArticle} 一致）、{@code sourceUrl}
     * （草稿「阅读原文」链接，为空表示不会生成）与 {@code commentMode}（留言设置）即提交后实际写入
     * 草稿的值；{@code truncatedFields} 列出其中**因超过微信长度上限被截断**的字段名
     * （{@code title} / {@code author} / {@code digest}），供 Console 在预览中给出明确标识。</p>
     */
    public Mono<Map<String, Object>> preview(SyncRequest request, WechatSetting setting,
        BeautifySetting beautify) {
        // 预览允许在未配置公众号信息时使用：按空配置解析，作者回退文章作者、留言按关闭展示
        WechatSetting cfg = setting == null ? new WechatSetting() : setting;
        return resolveExternalBaseUrl()
            .flatMap(baseUrl -> resolvePermalink(request)
                .flatMap(permalink -> beautifyContent(request.getContent(), beautify)
                    // 附件链接处理在预览里也要跑一遍（只做无需下载即可判定的降级），
                    // 否则「附件链接显示」开关在预览中看不出任何效果
                    .flatMap(html -> showUnsubmittableLinksAsPlainText(html, baseUrl, beautify))
                    .map(html -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("content", html == null ? "" : html);
                        // 标题 / 作者 / 摘要按与提交一致的口径（编辑器计字）截断到各自上限：
                        // 预览中显示的即最终写入草稿的值；被截断的字段名一并返回，供预览界面给出明确标识
                        String title = truncateToWechatLength(request.getTitle(), MAX_TITLE_LENGTH);
                        String digest = truncateDigest(request.getDigest());
                        String rawAuthor = resolveAuthor(cfg, request.getAuthor());
                        String author = truncateToWechatLength(rawAuthor, MAX_AUTHOR_LENGTH);
                        result.put("title", title);
                        result.put("digest", digest);
                        result.put("author", author);
                        List<String> truncatedFields = new ArrayList<>();
                        addIfTruncated(truncatedFields, "title", request.getTitle(), title);
                        addIfTruncated(truncatedFields, "digest", request.getDigest(), digest);
                        addIfTruncated(truncatedFields, "author", rawAuthor, author);
                        result.put("truncatedFields", truncatedFields);
                        result.put("sourceUrl", resolveSourceUrl(permalink, baseUrl));
                        result.put("commentMode", resolveCommentMode(cfg));
                        return result;
                    })));
    }

    /**
     * 该字段确实被截断时把字段名收集到 {@code truncatedFields}（预览不写日志——打开弹窗时调用，
     * 记日志会反复输出；提交路径的截断日志见 {@link #truncateWithNotice(String, String, int)}）。
     */
    private static void addIfTruncated(List<String> truncatedFields, String field, String original,
        String truncated) {
        if (isTruncated(original, truncated)) {
            truncatedFields.add(field);
        }
    }

    /**
     * 同步前的本地预检（不调用微信接口、不写同步记录）：收集「微信配置缺失（AppID / AppSecret）、
     * 封面图缺失或无法解析」等提交前即可发现的已知错误，供 Console 在打开预览前直接报告；
     * 返回空列表表示校验通过、可继续进入预览 / 同步流程。
     *
     * <p>校验规则与提交时一致：AppSecret 按「配置的 Secret 名称 → Secret 资源 → 约定键」解析（仅读取）；
     * 封面按「存在且可解析为绝对地址」检查（相对地址需要站点「外部访问地址」兜底拼接）。</p>
     */
    public Mono<List<String>> validate(SyncRequest request, WechatSetting setting) {
        WechatSetting cfg = setting == null ? new WechatSetting() : setting;
        List<String> errors = new ArrayList<>();
        boolean hasAppId = !isBlank(cfg.getAppId());
        boolean hasSecretName = !isBlank(cfg.getAppSecretName());
        if (!hasAppId && !hasSecretName) {
            // AppID 与 AppSecret 都未配置：合并为一条提示，避免两条几乎相同的消息
            errors.add("插件尚未配置微信公众号信息，请先在插件设置中配置 AppID / AppSecret");
            return collectCoverIssues(request, errors);
        }
        if (!hasAppId) {
            errors.add("请先在插件设置中配置公众号 AppID");
        }
        return appSecretIssue(cfg.getAppSecretName())
            .flatMap(issue -> {
                if (!issue.isEmpty()) {
                    errors.add(issue);
                }
                return collectCoverIssues(request, errors);
            });
    }

    /**
     * AppSecret 预检：返回问题说明；配置可正常解析出 AppSecret 明文时返回空串。
     * 规则与 {@link #resolveAppSecret(WechatSetting)} 一致（名称 → Secret → 约定键），仅读取校验。
     */
    private Mono<String> appSecretIssue(String secretName) {
        if (isBlank(secretName)) {
            return Mono.just("请先在插件设置中配置公众号 AppSecret");
        }
        return client.fetch(Secret.class, secretName)
            // 无问题用空串表示：Reactor 的 map 返回 null 会变成空信号，与下方「Secret 不存在」混淆
            .map(secret -> {
                String value = extractAppSecret(secret);
                return value == null || value.isBlank()
                    ? "Secret「" + secretName + "」中未包含 AppSecret（键 " + WechatSetting.APP_SECRET_KEY
                        + "），请在插件设置中重新配置"
                    : "";
            })
            .defaultIfEmpty(
                "未找到保存 AppSecret 的 Secret「" + secretName + "」，请在插件设置中重新填写 AppSecret");
    }

    /**
     * 封面预检：封面缺失或无法解析为绝对地址时追加问题，返回收集的问题列表
     * （与提交时的封面校验同一规则，见 {@link #uploadCover}）。
     */
    private Mono<List<String>> collectCoverIssues(SyncRequest request, List<String> errors) {
        return resolveExternalBaseUrl().map(baseUrl -> {
            String cover = request.getCover();
            if (isBlank(cover)) {
                errors.add("当前文章未设置封面图，请先为文章设置封面后再同步");
            } else if (resolveUrl(cover, baseUrl) == null) {
                errors.add("无法解析封面图地址「" + cover + "」，相对地址需先在 Halo 基本设置中配置「外部访问地址」");
            }
            return errors;
        });
    }

    /**
     * 按名称从 Halo {@code Secret} 中解析出 AppSecret 明文。
     *
     * <p>AppSecret 不保存在 Setting/ConfigMap，而是由用户在插件设置的 {@code secret} 组件写入 Halo
     * {@code Secret} 资源，配置项仅保留 Secret 名称。此处按名称拉取 Secret 并取出约定的
     * {@link WechatSetting#APP_SECRET_KEY} 键：Halo 读取 Secret 时只返回 {@code data}（base64 已解码为字节），
     * {@code stringData} 通常为空，故优先取 {@code stringData}、回退 {@code data}。</p>
     *
     * <p><b>安全</b>：解析出的明文只在响应式链路中向下传递给获取 token 的调用，绝不写入日志、异常消息或
     * 任何持久化位置；缺失或读取失败时以明确错误中断，不会以空值继续。</p>
     */
    private Mono<String> resolveAppSecret(WechatSetting setting) {
        String secretName = setting.getAppSecretName();
        if (isBlank(secretName)) {
            return Mono.error(new WechatApiException("请先在插件设置中配置公众号 AppSecret"));
        }
        return client.fetch(Secret.class, secretName)
            .switchIfEmpty(Mono.error(new WechatApiException(
                "未找到保存 AppSecret 的 Secret「" + secretName + "」，请在插件设置中重新填写 AppSecret")))
            .map(this::extractAppSecret)
            .flatMap(secret -> (secret == null || secret.isBlank())
                ? Mono.error(new WechatApiException("Secret「" + secretName + "」中未包含 AppSecret（键 "
                    + WechatSetting.APP_SECRET_KEY + "），请在插件设置中重新配置"))
                : Mono.just(secret));
    }

    /** 从 Secret 中取出 AppSecret：优先 {@code stringData}，回退 {@code data}（字节按 UTF-8 解码）。 */
    private String extractAppSecret(Secret secret) {
        Map<String, String> stringData = secret.getStringData();
        if (stringData != null) {
            String value = stringData.get(WechatSetting.APP_SECRET_KEY);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        Map<String, byte[]> data = secret.getData();
        if (data != null) {
            byte[] value = data.get(WechatSetting.APP_SECRET_KEY);
            if (value != null && value.length > 0) {
                return new String(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 美化正文：为常见标签注入微信友好的内联样式（微信会剥离 class 与外部 CSS，只保留行内 style）。
     *
     * <p>按 {@link BeautifySetting} 的主题色、代码块主题与 H1–H6 颜色生成样式。jsoup 解析与 DOM 改写
     * 属 CPU 操作，切到 {@link Schedulers#boundedElastic()} 执行，避免占用 Netty 事件循环线程；规则见
     * {@link WechatContentBeautifier}。</p>
     */
    private Mono<String> beautifyContent(String content, BeautifySetting beautify) {
        return Mono.fromCallable(() -> WechatContentBeautifier.beautify(content, beautify))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildArticle(SyncRequest request, WechatSetting setting, String thumbMediaId,
        String content, String sourceUrl) {
        Map<String, Object> article = new HashMap<>();
        // 标题：微信上限 64 字，超长会被 draft/add 拒绝，按编辑器计字口径截断（截断时记日志提示）
        article.put("title", truncateWithNotice("标题", request.getTitle(), MAX_TITLE_LENGTH));
        // 作者：插件设置的「默认作者」优先，留空时才回退到文章作者（与配置项 help「留空则使用文章作者」一致）；
        // 两者同样按 8 字上限截断——微信服务端按平台规则校验作者名长度，超过 8 字会返回 45110（截断时记日志）
        article.put("author",
            truncateWithNotice("作者", resolveAuthor(setting, request.getAuthor()), MAX_AUTHOR_LENGTH));
        // 摘要：读取文章摘要并截断到 120 字（超长会被微信接口拒绝提交）；
        // 为空时不传 digest（微信默认抓取正文前 54 个字）
        String digest = truncateWithNotice("摘要", request.getDigest(), MAX_DIGEST_LENGTH);
        if (!digest.isEmpty()) {
            article.put("digest", digest);
        }
        article.put("content", content);
        // 原文链接：图文底部的「阅读原文」；为空时微信不显示该入口
        article.put("content_source_url", sourceUrl == null ? "" : sourceUrl);
        // 留言设置：关闭时不开启评论；开启时按「所有人 / 已关注的人」映射 only_fans_can_comment
        String commentMode = resolveCommentMode(setting);
        article.put("need_open_comment", WechatSetting.COMMENT_MODE_CLOSE.equals(commentMode) ? 0 : 1);
        article.put("only_fans_can_comment", WechatSetting.COMMENT_MODE_FANS.equals(commentMode) ? 1 : 0);
        if (!thumbMediaId.isBlank()) {
            article.put("thumb_media_id", thumbMediaId);
        }
        return article;
    }

    /**
     * 解析草稿作者：插件设置的「默认作者」优先（{@code null} / 空白视为未设置），留空时回退文章作者。
     *
     * <p>只做取值、不截断：长度统一由 {@link #buildArticle} 交给
     * {@link #truncateWithNotice(String, String, int)} 处理——「默认作者」与「文章作者」都按微信
     * {@link #MAX_AUTHOR_LENGTH} 字上限截断，避免超长导致整次提交被拒（截断会记日志提示）。</p>
     */
    private static String resolveAuthor(WechatSetting setting, String postAuthor) {
        String configured = setting == null ? null : setting.getAuthor();
        if (!isBlank(configured)) {
            return configured;
        }
        return postAuthor == null ? "" : postAuthor;
    }

    /**
     * 截断微信草稿字段，并在<b>确实发生截断</b>时记 warn：字段超长虽已被兜底截断、不会导致提交失败，
     * 但提交到微信的内容被改动，需在日志里留下「哪个字段、原始多长、上限多少、截断后的值」以便核对。
     *
     * @param field            字段中文名（仅用于日志提示）
     * @param value            原始值，可为 {@code null}
     * @param maxWechatLength  微信计字口径的长度上限（字）
     * @return 去除首尾空白并截断后的值；原始值为 {@code null} / 空白时返回空串
     */
    private String truncateWithNotice(String field, String value, int maxWechatLength) {
        String truncated = truncateToWechatLength(value, maxWechatLength);
        if (isTruncated(value, truncated)) {
            log.warn("微信草稿字段「{}」超过 {} 字上限（原始 {} 字），已截断后提交：{}",
                field, maxWechatLength, wechatLengthText(value), truncated);
        }
        return truncated;
    }

    /** 字段是否因超过微信长度上限被截断（截断结果与原始值去首尾空白后比较）。 */
    private static boolean isTruncated(String original, String truncated) {
        return !truncated.equals(original == null ? "" : original.trim());
    }

    /** 微信图文摘要（{@code digest}）总长度上限：120 个字。 */
    static final int MAX_DIGEST_LENGTH = 120;

    /**
     * 微信图文标题（{@code title}）总长度上限：64 个字。
     *
     * <p>公众号编辑器与平台规则均为 64 字（接口文档中曾写作 32 字，实测以编辑器的 64 字为准）。</p>
     */
    static final int MAX_TITLE_LENGTH = 64;

    /**
     * 微信图文作者（{@code author}）总长度上限：8 个字。
     *
     * <p>接口文档中写作 16 字，但服务端按平台规则（作者名 8 字）校验：超过 8 字会返回
     * {@code 45110 author size out of limit}（该码未收录在官方返回码表中），故按 8 字截断。</p>
     */
    static final int MAX_AUTHOR_LENGTH = 8;

    /**
     * 规范化草稿摘要：去除首尾空白；为空返回空串（{@link #buildArticle} 据此不向微信传
     * {@code digest}，由微信默认抓取正文前 54 个字）；超长时按 {@link #truncateToWechatLength}
     * 的计字规则截断到 {@link #MAX_DIGEST_LENGTH} 个字。
     *
     * <p><b>必须保留截断</b>：超长摘要会被微信 {@code draft/add} 接口拒绝，导致整次同步失败；
     * 不能把超长摘要原样交给微信。</p>
     */
    static String truncateDigest(String digest) {
        return truncateToWechatLength(digest, MAX_DIGEST_LENGTH);
    }

    /**
     * 按微信长度上限把文本截断到 {@code maxWechatLength} 个字：先去除首尾空白，为空返回空串。
     *
     * <p><b>计字口径与公众号编辑器一致</b>：一个汉字 / 全角字符计 1 个字，一个半角字符（英文字符、
     * 数字、符号）计 0.5 个字，一个 emoji 等增补字符（UTF-16 代理对）计 2 个字（与公众号编辑器的
     * 标题 / 作者 / 摘要计数器一致，实测可准确截到微信允许的长度）；按码点整体取舍，避免把 emoji
     * 截成半个代理对。</p>
     *
     * <p>用于微信 {@code draft/add} 有明确上限、超长即整次提交失败的字段：
     * {@code title}（{@link #MAX_TITLE_LENGTH} 字）、{@code author}（{@link #MAX_AUTHOR_LENGTH} 字）、
     * {@code digest}（{@link #MAX_DIGEST_LENGTH} 字）。</p>
     *
     * @param text             待截断的文本，可为 {@code null}
     * @param maxWechatLength  长度上限（字）
     */
    static String truncateToWechatLength(String text, int maxWechatLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        // 以「半角单位」计数避免浮点：半角字符 1 个单位（=0.5 字）、汉字/全角 2 个单位（=1 字）、
        // emoji 等增补字符 4 个单位（=2 字，对应 2 个 UTF-16 编码单元）
        int maxHalfUnits = maxWechatLength * 2;
        int halfUnits = 0;
        int end = 0;
        while (end < trimmed.length()) {
            int codePoint = trimmed.codePointAt(end);
            int units = wechatHalfUnits(codePoint);
            if (halfUnits + units > maxHalfUnits) {
                break;
            }
            halfUnits += units;
            end += Character.charCount(codePoint);
        }
        return trimmed.substring(0, end);
    }

    /** 文本的微信计字长度（半角单位），与 {@link #truncateToWechatLength} 同一套规则。 */
    static int wechatHalfUnits(String text) {
        if (text == null) {
            return 0;
        }
        int units = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            units += wechatHalfUnits(codePoint);
            index += Character.charCount(codePoint);
        }
        return units;
    }

    /** 单个码点的微信计字折算（半角单位）：ASCII 半角字符 1 个单位（0.5 字）；BMP 汉字/全角 2 个单位（1 字）；emoji 等增补字符 4 个单位（2 字）。 */
    private static int wechatHalfUnits(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        return Character.charCount(codePoint) * 2;
    }

    /** 把半角单位计字格式化为「字」（半角字符 0.5 字，故保留一位小数）。 */
    private static String wechatLengthText(String text) {
        double length = wechatHalfUnits(text) / 2.0;
        return length == (long) length
            ? String.valueOf((long) length)
            : String.format(Locale.ROOT, "%.1f", length);
    }

    /**
     * 解析草稿的「原文链接」（{@code content_source_url}，即图文底部的「阅读原文」）：
     * 站点「外部访问地址」+ 文章路由（{@code status.permalink}，如 {@code /archives/xxx}）。
     *
     * <p>始终自动拼接，无需人工配置；路由缺失、外部访问地址未配置，或路由本身已是绝对地址
     * （Halo 配置了绝对地址策略时）均由 {@link #resolveUrl} 兜底：无法拼接时返回空串，
     * 草稿不显示「阅读原文」。</p>
     */
    String resolveSourceUrl(String permalink, String baseUrl) {
        String url = resolveUrl(permalink, baseUrl);
        return url == null ? "" : url;
    }

    /**
     * 解析文章的站点路由（{@code status.permalink}，如 {@code /archives/xxx}）：
     * 优先使用前端上送的值（取自 Console 文章列表数据）；缺失时按 {@code postName} 回查
     * Halo {@code Post} 扩展兜底（兼容前端未上送或列表中该字段为空的情况）；都拿不到时返回空串。
     */
    private Mono<String> resolvePermalink(SyncRequest request) {
        String permalink = request.getPermalink();
        if (permalink != null && !permalink.isBlank()) {
            return Mono.just(permalink);
        }
        String postName = request.getPostName();
        if (postName == null || postName.isBlank()) {
            return Mono.just("");
        }
        return client.fetch(Post.class, postName)
            .map(post -> post.getStatus() == null || post.getStatus().getPermalink() == null
                ? "" : post.getStatus().getPermalink())
            .defaultIfEmpty("")
            .onErrorResume(e -> {
                log.warn("读取文章 {} 的 permalink 失败：{}", postName, e.getMessage());
                return Mono.just("");
            });
    }

    /**
     * 记录原文链接解析结果，便于排查「草稿未带阅读原文」类问题；
     * 路由存在但站点未配置「外部访问地址」时给出明确告警（此时无法拼出绝对地址）。
     */
    private void logSourceUrl(SyncRequest request, String permalink, String baseUrl, String sourceUrl) {
        if (sourceUrl.isBlank() && !permalink.isBlank() && baseUrl.isBlank()) {
            log.warn("文章《{}》的原文链接无法生成：未在 Halo「基本设置」中配置「外部访问地址」（文章路由 {}）",
                request.getTitle(), permalink);
            return;
        }
        log.info("文章《{}》原文链接解析：permalink={}，baseUrl={}，content_source_url={}",
            request.getTitle(), permalink, baseUrl, sourceUrl.isBlank() ? "（空）" : sourceUrl);
    }

    /**
     * 解析草稿的留言设置：{@link WechatSetting#COMMENT_MODE_CLOSE} / {@link WechatSetting#COMMENT_MODE_ALL}
     * / {@link WechatSetting#COMMENT_MODE_FANS}。
     *
     * <p>未保存过该项或取值非法时按旧版「开启评论」开关兼容：{@code openComment=true}
     * 等价于所有人可留言，其余按关闭处理。</p>
     */
    static String resolveCommentMode(WechatSetting setting) {
        String mode = setting.getCommentMode();
        if (WechatSetting.COMMENT_MODE_CLOSE.equals(mode) || WechatSetting.COMMENT_MODE_ALL.equals(mode)
            || WechatSetting.COMMENT_MODE_FANS.equals(mode)) {
            return mode;
        }
        return Boolean.TRUE.equals(setting.getOpenComment())
            ? WechatSetting.COMMENT_MODE_ALL : WechatSetting.COMMENT_MODE_CLOSE;
    }

    /**
     * 上传封面为永久素材，返回 thumb_media_id。
     *
     * <p>微信公众号草稿（draft/add）强制要求有效的封面素材 id，缺失会被拒绝并返回
     * {@code errcode=40007 invalid media_id}。因此这里不再吞掉错误、静默跳过封面，而是把
     * 「无封面 / 地址无法解析 / 下载失败 / 上传失败」的真实原因清晰抛出，便于用户定位。</p>
     *
     * <p>永久素材会占用微信素材库，同一张封面图（按文件内容指纹判定）此前若已上传过，
     * 由 {@link WechatMediaCacheService} 直接复用其 media_id，不再重复上传。</p>
     */
    private Mono<String> uploadCover(String apiBase, String appId, String token, String cover,
        String baseUrl) {
        String url = resolveUrl(cover, baseUrl);
        if (url == null) {
            String reason = isBlank(cover)
                ? "当前文章未设置封面图"
                : "无法解析封面图地址「" + cover + "」（相对地址需先在 Halo 基本设置中配置「外部访问地址」）";
            return Mono.error(new WechatApiException("微信公众号草稿必须包含封面图，但" + reason + "，请处理后重试"));
        }
        return wechatMpClient.download(url)
            .onErrorMap(e -> new WechatApiException("封面图下载失败「" + url + "」：" + e.getMessage()))
            .flatMap(bytes -> {
                if (bytes == null || bytes.length == 0) {
                    return Mono.error(new WechatApiException("封面图下载内容为空「" + url + "」，请确认该地址可正常访问"));
                }
                return mediaCacheService.resolvePermanentImage(apiBase, appId, token, bytes,
                        filenameFrom(url), url)
                    .onErrorMap(e -> !(e instanceof WechatApiException),
                        e -> new WechatApiException("封面图上传到微信失败「" + url + "」：" + e.getMessage()));
            });
    }

    /**
     * 提交草稿前的正文处理链：转存正文图片 → 美化排版 → 处理附件链接。
     *
     * <p>三步顺序不可调换：图片转存在美化前，转存后的 {@code <img>} 会照常参与样式注入；附件链接处理在
     * 美化<b>之后</b>，此时插件自定义元素（下载链接 / 附件卡片）已转换为标准 {@code <a>}，可一并按
     * 「图片转存为微信图片 / 非图片显示原始地址」处理（见 {@link #transferAttachments}）。</p>
     */
    private Mono<String> prepareContent(String apiBase, String appId, String token, SyncRequest request,
        BeautifySetting beautify, String baseUrl) {
        return transferImages(apiBase, appId, token, request.getContent(), baseUrl)
            .flatMap(content -> beautifyContent(content, beautify))
            .flatMap(beautified -> transferAttachments(apiBase, appId, token, beautified, baseUrl, beautify));
    }

    /**
     * 将正文中的图片逐一转存到微信，并替换为微信返回的图片地址。
     *
     * <p>同一张图（按文件内容指纹判定）此前若已转存过，由 {@link WechatMediaCacheService} 直接复用
     * 微信返回的地址，不再重复上传。</p>
     */
    private Mono<String> transferImages(String apiBase, String appId, String token, String html,
        String baseUrl) {
        if (html == null || html.isBlank()) {
            return Mono.just(html == null ? "" : html);
        }
        Document document = Jsoup.parseBodyFragment(html);
        // 关闭美化缩进，保留正文原有的空白/换行，避免引入多余空格
        document.outputSettings().prettyPrint(false);
        Elements images = document.select("img[src]");
        if (images.isEmpty()) {
            return Mono.just(document.body().html());
        }
        return Flux.fromIterable(images)
            .concatMap(image -> {
                String url = resolveUrl(image.attr("src"), baseUrl);
                if (url == null) {
                    return Mono.just(image);
                }
                return wechatMpClient.download(url)
                    .flatMap(bytes -> mediaCacheService.resolveContentImage(apiBase, appId, token, bytes,
                        filenameFrom(url), url))
                    .doOnNext(newSrc -> image.attr("src", newSrc))
                    .thenReturn(image)
                    .onErrorResume(e -> {
                        log.warn("正文图片 [{}] 转存失败，保留原地址：{}", url, e.getMessage());
                        return Mono.just(image);
                    });
            })
            .then(Mono.fromSupplier(() -> document.body().html()));
    }

    /**
     * 处理正文里的附件链接：{@code <a href>}（Halo 附件库文件、带文件扩展名的下载链接，以及
     * 「下载链接」等插件自定义元素在美化阶段转换出的链接）。
     *
     * <p>微信图文不支持外链跳转，附件链接在草稿里既点不开，非图片文件也无法转存；而把微信图片接口不认的
     * 字节交给它，只会换来 {@code 40005/40113}。故一律先按真实字节判定，再决定是否调用微信接口：</p>
     * <ul>
     *   <li>是微信支持的图片（jpg/png/gif/bmp，webp 由客户端转码）→ 转存为微信图片（{@code <img>}）；</li>
     *   <li>其余（pdf/zip 等非图片、字节不符合、下载失败）→ <b>不调用微信接口</b>，把链接替换为
     *       <b>纯文本</b>（见 {@link #showAsPlainText}：显示原始地址还是链接自身的文字由配置决定）。</li>
     * </ul>
     *
     * <p>须在正文美化之后执行：此时插件自定义元素已转为标准 {@code <a>}，可一并处理；普通网页链接
     * （站内文章路由、无扩展名的分享页等，见 {@link #isAttachmentLink}）不视为附件，原样保留。</p>
     *
     * <p>退化为纯文本时显示「原始地址」还是「链接自身的文字」由
     * {@link BeautifySetting#getAttachmentLinkDisplay()} 配置，见 {@link #showAsPlainText}。</p>
     */
    Mono<String> transferAttachments(String apiBase, String appId, String token, String html,
        String baseUrl, BeautifySetting beautify) {
        if (html == null || html.isBlank()) {
            return Mono.just(html == null ? "" : html);
        }
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        Elements links = document.select("a[href]");
        if (links.isEmpty()) {
            return Mono.just(document.body().html());
        }
        boolean showLinkContent = showLinkContent(beautify);
        return Flux.fromIterable(links)
            .filter(WechatSyncService::isAttachmentLink)
            .concatMap(link -> transferAttachment(apiBase, appId, token, link, baseUrl, showLinkContent))
            .then(Mono.fromSupplier(() -> document.body().html()));
    }

    /** 单个附件链接的转存决策与处理：能转存成微信图片就转存，否则退化为纯文本（转存同样走媒体缓存）。 */
    private Mono<Void> transferAttachment(String apiBase, String appId, String token, Element link,
        String baseUrl, boolean showLinkContent) {
        String href = link.attr("href").trim();
        String url = resolveUrl(href, baseUrl);
        if (url == null) {
            // 相对地址且未配置「外部访问地址」：没有可下载的地址，直接退化为纯文本
            log.info("正文附件「{}」无法解析为可下载的地址，不转存，改为{}", href, plainTextHint(showLinkContent));
            showAsPlainText(link, href, showLinkContent);
            return Mono.empty();
        }
        if (!mayBeImage(url)) {
            // 非图片附件（/upload/x.pdf、x.zip 等）：连下载都省了，直接退化为纯文本
            log.info("正文附件「{}」不是微信支持的图片格式，不转存，改为{}", href, plainTextHint(showLinkContent));
            showAsPlainText(link, href, showLinkContent);
            return Mono.empty();
        }
        return wechatMpClient.download(url)
            .flatMap(bytes -> {
                if (bytes == null || bytes.length == 0 || !WechatMpClient.isWechatSupportedImage(bytes)) {
                    return Mono.error(new WechatApiException("内容不是微信支持的图片格式"));
                }
                return mediaCacheService.resolveContentImage(apiBase, appId, token, bytes,
                    filenameFrom(url), url);
            })
            .doOnNext(newSrc -> link.replaceWith(contentImage(newSrc)))
            // 下载 / 字节判定 / 上传任一环节失败都退回纯文本：
            // 留下微信不认的链接（外链在图文里不可点击）只会变成死链
            .onErrorResume(e -> {
                log.warn("正文附件 [{}] 转存为微信图片失败，改为{}：{}", url, plainTextHint(showLinkContent),
                    e.getMessage());
                showAsPlainText(link, href, showLinkContent);
                return Mono.empty();
            })
            .then();
    }

    /**
     * 预览用：只做「无需下载即可判定」的附件链接降级，让预览与草稿在这类链接上保持一致——否则
     * 「附件链接显示」开关在预览里看不出任何效果。
     *
     * <p>只处理两类确定的情况：扩展名明确为非图片的附件、地址无法解析的附件。图片型附件
     * （图片扩展名，或无扩展名的附件库路径）能不能转存，要下载后按真实字节判定，与正文图片一样
     * 只在<b>提交时</b>才处理，预览中保持原链接（预览不下载任何资源）。</p>
     */
    Mono<String> showUnsubmittableLinksAsPlainText(String html, String baseUrl, BeautifySetting beautify) {
        if (html == null || html.isBlank()) {
            return Mono.just(html == null ? "" : html);
        }
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        boolean showLinkContent = showLinkContent(beautify);
        for (Element link : document.select("a[href]")) {
            if (!isAttachmentLink(link)) {
                continue;
            }
            String href = link.attr("href").trim();
            String url = resolveUrl(href, baseUrl);
            // 地址无法解析，或扩展名明确为非图片：无需下载即可确定提交不到微信
            if (url == null || !mayBeImage(url)) {
                showAsPlainText(link, href, showLinkContent);
            }
        }
        return Mono.just(document.body().html());
    }

    /**
     * 是否把该链接当作「附件链接」处理：带 {@code download} 属性的链接、站点附件库路径
     * （Halo 本地存储为 {@code /upload/...}）、URL 末段带文件扩展名的下载链接（如 {@code x.zip}）。
     *
     * <p>锚点、{@code mailto:}/{@code data:} 等非文件链接，以及站内文章路由等普通网页链接（无文件扩展名，
     * 或 {@code .html} 之类的页面后缀）都会原样保留，避免把正常超链接误改成纯文本。</p>
     */
    private static boolean isAttachmentLink(Element link) {
        String href = link.attr("href").trim();
        if (href.isEmpty() || isNonFileHref(href)) {
            return false;
        }
        if (link.hasAttr("download")) {
            return true;
        }
        String path = urlPath(href);
        if (path.contains("/upload/")) {
            return true;
        }
        String extension = fileExtensionOf(path);
        return !extension.isEmpty() && !PAGE_EXTENSIONS.contains(extension);
    }

    /** 非文件类链接（页内锚点、邮件、电话、脚本、内嵌数据与自定义协议）不参与附件处理。 */
    private static boolean isNonFileHref(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#") || lower.startsWith("mailto:") || lower.startsWith("tel:")
            || lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return true;
        }
        // 仅放行 http/https 与相对路径：其余带自定义协议的地址（weixin:// 等）直接跳过
        int colon = lower.indexOf(':');
        int slash = lower.indexOf('/');
        return colon >= 0 && (slash < 0 || colon < slash)
            && !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    /**
     * 该地址是否「可能是微信支持的图片」，决定要不要下载后按真实字节复核：扩展名明确为非图片
     * （pdf/zip 等）时无需下载；无扩展名时仅站点附件库路径（{@code /upload/}）值得下载判定。
     */
    private static boolean mayBeImage(String url) {
        String path = urlPath(url);
        String extension = fileExtensionOf(path);
        return extension.isEmpty() ? path.contains("/upload/") : IMAGE_EXTENSIONS.contains(extension);
    }

    /** 取 URL 的路径部分（去掉查询串与片段），用于判断附件库路径与文件扩展名。 */
    private static String urlPath(String url) {
        int end = url.length();
        int hash = url.indexOf('#');
        if (hash >= 0 && hash < end) {
            end = hash;
        }
        int query = url.indexOf('?');
        if (query >= 0 && query < end) {
            end = query;
        }
        return url.substring(0, end);
    }

    /**
     * 取路径末段的文件扩展名（小写、不含点）：以字母开头、仅含字母数字且不超过 8 位，否则视为没有扩展名
     * （如版本号 {@code /v1.2} 不当成 {@code .2} 后缀）。
     */
    private static String fileExtensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot + 1);
        if (extension.length() > 8 || !Character.isLetter(extension.charAt(0))
            || !extension.chars().allMatch(Character::isLetterOrDigit)) {
            return "";
        }
        return extension.toLowerCase(Locale.ROOT);
    }

    /**
     * 把链接替换为纯文本：微信图文里的外链本就不可点击，不可提交到微信的附件（非图片文件）也转存不了，
     * 故以文本呈现，避免草稿里留下微信不认的死链。
     *
     * <p>显示内容由「附件链接显示」配置决定（{@link BeautifySetting#getAttachmentLinkDisplay()}）：
     * 默认显示<b>原始地址</b>（正文里写的是什么就显示什么，如 {@code /upload/2026/09/manual.pdf}），
     * 也可改为显示<b>链接自身的文字</b>（如「下载手册」）；链接没有可显示文字时一律回退原始地址，
     * 避免产出空白。</p>
     */
    private static void showAsPlainText(Element link, String href, boolean showLinkContent) {
        String content = showLinkContent ? link.text().trim() : "";
        link.replaceWith(new TextNode(content.isEmpty() ? href : content));
    }

    /** 不可提交到微信的链接退化为纯文本时，是否显示链接内容（否则显示原始地址）。 */
    private static boolean showLinkContent(BeautifySetting beautify) {
        return beautify != null && BeautifySetting.ATTACHMENT_LINK_DISPLAY_CONTENT
            .equals(beautify.getAttachmentLinkDisplay());
    }

    /** 纯文本呈现的日志提示语（写明当前生效的显示方式，便于核对配置是否已生效）。 */
    private static String plainTextHint(boolean showLinkContent) {
        return showLinkContent ? "显示链接内容" : "显示原始地址";
    }

    /**
     * 按正文图片的既有样式（{@link WechatContentBeautifier#IMG_STYLE}）新建 {@code <img>}：
     * 附件转存发生在正文美化之后，新建的图片不会经样式注入流程，故此处显式带上内联样式。
     */
    private static Element contentImage(String src) {
        Element image = new Element(Tag.valueOf("img"), "");
        image.attr("src", src);
        image.attr("style", WechatContentBeautifier.IMG_STYLE);
        return image;
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url == null || url.isBlank() || url.startsWith("data:")) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        // 相对地址需要用外部访问地址补全；baseUrl 已去除尾部 '/'，无可用基址时放弃转存
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        return baseUrl + (url.startsWith("/") ? url : "/" + url);
    }

    /**
     * 读取 Halo 的「外部访问地址」作为拼接相对图片地址的基址：优先取 Console 基本设置
     * （{@code system} ConfigMap 的 {@code basic} 组 {@code externalUrl}），为空时回退到
     * {@link ExternalUrlSupplier}。返回前统一去除尾部 '/'；未配置或非绝对地址时返回空串。
     */
    private Mono<String> resolveExternalBaseUrl() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .map(configMap -> {
                Map<String, String> data = configMap.getData();
                if (data == null) {
                    return "";
                }
                SystemSetting.Basic basic =
                    SystemSetting.get(data, SystemSetting.Basic.GROUP, SystemSetting.Basic.class);
                return basic == null || basic.getExternalUrl() == null ? "" : basic.getExternalUrl();
            })
            .filter(url -> !url.isBlank())
            .switchIfEmpty(Mono.fromSupplier(() -> {
                URL raw = externalUrlSupplier.getRaw();
                return raw == null ? "" : raw.toString();
            }))
            .map(this::normalizeBaseUrl)
            .defaultIfEmpty("");
    }

    /**
     * 规范化外部访问地址：仅接受 http/https 绝对地址，并去除尾部一个或多个 '/'，
     * 以便拼接图片相对路径时统一补 '/'（兼容配置里带或不带尾部斜杠两种写法）。
     */
    private String normalizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "";
        }
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    private String filenameFrom(String url) {
        String path = url;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int slashIndex = path.lastIndexOf('/');
        String name = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
        return name.isBlank() ? "image.jpg" : name;
    }

    /** 空值判断（null 或纯空白）。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
