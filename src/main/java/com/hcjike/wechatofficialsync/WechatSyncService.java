package com.hcjike.wechatofficialsync;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
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
     */
    public Mono<String> submit(SyncRequest request, WechatSetting setting) {
        return resolveExternalBaseUrl()
            .flatMap(baseUrl -> wechatMpClient.getAccessToken(setting)
                .flatMap(token -> uploadCover(token, request.getCover(), baseUrl)
                    .doOnNext(thumbMediaId -> log.info("文章《{}》封面素材上传成功，thumb_media_id={}",
                        request.getTitle(), thumbMediaId))
                    .flatMap(thumbMediaId -> transferImages(token, request.getContent(), baseUrl)
                        .flatMap(content -> wechatMpClient.addDraft(token,
                            buildArticle(request, setting, thumbMediaId, content))))));
    }

    private Map<String, Object> buildArticle(SyncRequest request, WechatSetting setting, String thumbMediaId,
        String content) {
        Map<String, Object> article = new HashMap<>();
        article.put("title", request.getTitle() == null ? "" : request.getTitle());
        article.put("author", firstNonBlank(request.getAuthor(), setting.getAuthor()));
        article.put("digest", request.getDigest() == null ? "" : request.getDigest());
        article.put("content", content);
        article.put("content_source_url", "");
        article.put("need_open_comment", setting.isOpenComment() ? 1 : 0);
        article.put("only_fans_can_comment", 0);
        if (!thumbMediaId.isBlank()) {
            article.put("thumb_media_id", thumbMediaId);
        }
        return article;
    }

    /**
     * 上传封面为永久素材，返回 thumb_media_id。
     *
     * <p>微信公众号草稿（draft/add）强制要求有效的封面素材 id，缺失会被拒绝并返回
     * {@code errcode=40007 invalid media_id}。因此这里不再吞掉错误、静默跳过封面，而是把
     * 「无封面 / 地址无法解析 / 下载失败 / 上传失败」的真实原因清晰抛出，便于用户定位。</p>
     */
    private Mono<String> uploadCover(String token, String cover, String baseUrl) {
        String url = resolveUrl(cover, baseUrl);
        if (url == null) {
            String reason = (cover == null || cover.isBlank())
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
                return wechatMpClient.uploadPermanentImage(token, bytes, filenameFrom(url))
                    .onErrorMap(e -> !(e instanceof WechatApiException),
                        e -> new WechatApiException("封面图上传到微信失败「" + url + "」：" + e.getMessage()));
            });
    }

    /**
     * 将正文中的图片逐一转存到微信，并替换为微信返回的图片地址。
     */
    private Mono<String> transferImages(String token, String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return Mono.just(html == null ? "" : html);
        }
        Document document = Jsoup.parseBodyFragment(html);
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
                    .flatMap(bytes -> wechatMpClient.uploadContentImage(token, bytes, filenameFrom(url)))
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
