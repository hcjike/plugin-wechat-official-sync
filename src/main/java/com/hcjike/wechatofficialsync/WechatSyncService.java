package com.hcjike.wechatofficialsync;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
 * @author hcjike
 * @since 1.0.0
 */
@Service
public class WechatSyncService {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncService.class);

    private final WechatMpClient wechatMpClient;

    private final ReactiveExtensionClient client;

    private final ExternalUrlSupplier externalUrlSupplier;

    public WechatSyncService(WechatMpClient wechatMpClient, ReactiveExtensionClient client,
        ExternalUrlSupplier externalUrlSupplier) {
        this.wechatMpClient = wechatMpClient;
        this.client = client;
        this.externalUrlSupplier = externalUrlSupplier;
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
                                .flatMap(token -> uploadCover(apiBase, token, request.getCover(), baseUrl)
                                    .doOnNext(thumbMediaId -> log.info("文章《{}》封面素材上传成功，thumb_media_id={}",
                                        request.getTitle(), thumbMediaId))
                                    .flatMap(thumbMediaId ->
                                        transferImages(apiBase, token, request.getContent(), baseUrl)
                                            .flatMap(content -> beautifyContent(content, beautify)
                                                .flatMap(beautified -> wechatMpClient.addDraft(apiBase, token,
                                                    buildArticle(request, setting, thumbMediaId, beautified,
                                                        sourceUrl)))))));
                    }))));
    }

    /**
     * 构建「同步预览」：按与 {@link #submit} 一致的规则解析正文美化效果与草稿元信息，
     * 但不做任何写操作——不下载/转存图片、不上传封面、不调用微信接口、不写同步记录。
     *
     * <p>返回的 {@code content}（美化后的正文）、{@code digest}（草稿摘要，按与提交一致的规则
     * 去除首尾空白、原样同步；为空表示未填写摘要、微信默认抓取正文前 54 个字）、{@code author}
     * （草稿作者，设置的「默认作者」优先、留空回退文章作者，与 {@link #buildArticle} 一致）、{@code sourceUrl}
     * （草稿「阅读原文」链接，为空表示不会生成）与 {@code commentMode}（留言设置）即提交后
     * 实际写入草稿的值。</p>
     */
    public Mono<Map<String, Object>> preview(SyncRequest request, WechatSetting setting,
        BeautifySetting beautify) {
        // 预览允许在未配置公众号信息时使用：按空配置解析，作者回退文章作者、留言按关闭展示
        WechatSetting cfg = setting == null ? new WechatSetting() : setting;
        return resolveExternalBaseUrl()
            .flatMap(baseUrl -> resolvePermalink(request)
                .flatMap(permalink -> beautifyContent(request.getContent(), beautify)
                    .map(html -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("content", html == null ? "" : html);
                        // 摘要按与提交一致的规则解析：空表示不传 digest（微信默认抓取正文前 54 个字）
                        result.put("digest", trimDigest(request.getDigest()));
                        result.put("author", firstNonBlank(cfg.getAuthor(), request.getAuthor()));
                        result.put("sourceUrl", resolveSourceUrl(permalink, baseUrl));
                        result.put("commentMode", resolveCommentMode(cfg));
                        return result;
                    })));
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
     * （与提交时的封面校验同一规则，见 {@link #uploadCover(String, String, String, String)}）。
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
        article.put("title", request.getTitle() == null ? "" : request.getTitle());
        // 作者优先级：插件设置的「默认作者」优先，留空时才回退到文章作者（与配置项 help「留空则使用文章作者」一致）
        article.put("author", firstNonBlank(setting.getAuthor(), request.getAuthor()));
        // 摘要：读取文章摘要并原样同步（不截断，由用户发布时自行取舍）；为空时不传 digest（微信默认抓取正文前 54 个字）
        String digest = trimDigest(request.getDigest());
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
     * 整理草稿摘要：去除首尾空白；为空返回空串（{@link #buildArticle} 据此不向微信传
     * {@code digest}，由微信默认抓取正文前 54 个字）；非空则原样返回、不做长度截断，
     * 超长摘要完整同步到公众号草稿，由用户在发布时自行取舍保留哪部分。
     */
    static String trimDigest(String digest) {
        return digest == null ? "" : digest.trim();
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
     */
    private Mono<String> uploadCover(String apiBase, String token, String cover, String baseUrl) {
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
                return wechatMpClient.uploadPermanentImage(apiBase, token, bytes, filenameFrom(url))
                    .onErrorMap(e -> !(e instanceof WechatApiException),
                        e -> new WechatApiException("封面图上传到微信失败「" + url + "」：" + e.getMessage()));
            });
    }

    /**
     * 将正文中的图片逐一转存到微信，并替换为微信返回的图片地址。
     */
    private Mono<String> transferImages(String apiBase, String token, String html, String baseUrl) {
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
                    .flatMap(bytes -> wechatMpClient.uploadContentImage(apiBase, token, bytes, filenameFrom(url)))
                    .doOnNext(newSrc -> image.attr("src", newSrc))
                    .thenReturn(image)
                    .onErrorResume(e -> {
                        log.warn("正文图片 [{}] 转存失败，保留原地址：{}", url, e.getMessage());
                        return Mono.just(image);
                    });
            })
            .then(Mono.fromSupplier(() -> document.body().html()));
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
