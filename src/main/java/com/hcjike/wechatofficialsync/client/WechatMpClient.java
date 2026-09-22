package com.hcjike.wechatofficialsync.client;

import com.hcjike.wechatofficialsync.service.WechatSyncService;
import com.hcjike.wechatofficialsync.ssrf.SsrfGuard;
import com.hcjike.wechatofficialsync.ssrf.SsrfPolicy;
import com.hcjike.wechatofficialsync.ssrf.SsrfSafeAddressResolverGroup;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 微信公众号接口客户端，基于响应式 {@link WebClient} 实现。
 *
 * <p>响应统一使用 {@code Map<String, Object>} 反序列化，避免 Halo 2.26 迁移到 Jackson 3
 * （{@code tools.jackson}）后与 {@code com.fasterxml.jackson} 的 {@code JsonNode} 产生冲突。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatMpClient {

    private static final Logger log = LoggerFactory.getLogger(WechatMpClient.class);

    /** 微信官方接口默认地址；用户可在插件设置里改为自建反向代理地址。 */
    private static final String DEFAULT_BASE_URL = "https://api.weixin.qq.com";

    /** 微信图片素材支持的格式，其余（如 webp）需转换后再上传。 */
    private static final Set<String> WECHAT_IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    /**
     * 按真实字节判定时认可的图片格式：微信素材直接支持 jpg/png/gif/bmp，webp 由本类转码为 png/jpg 后上传。
     * 用于判断正文里的附件（图片型附件链接等）能否转存为微信图片。
     */
    private static final Set<String> WECHAT_IMAGE_FORMATS = Set.of("jpg", "png", "gif", "bmp", "webp");

    /**
     * 图片归一化规则（{@link #normalizeImage}：格式支持范围、解码与重编码方式等）的版本号，
     * 作为素材缓存键的一个维度（见 {@code WechatMediaCacheService} 与 {@code CachedMedia}）。
     *
     * <p>素材缓存的指纹取的是<b>转换前</b>的原始字节（转换后的字节受 JDK 编码器实现影响、跨版本并不稳定，
     * 不适合做键），因此归一化逻辑本身的改动不会改变指纹：改了转码规则后同一张原图仍会命中旧缓存，
     * 出现「转码 bug 已修、草稿里却还是旧产物」的情况。故把本版本号一并写入缓存并参与匹配：
     * <b>任何会改变上传产物或上传成功率的归一化改动（如新增/移除支持格式、修正透明通道处理、
     * 调整重编码质量、更换 webp 解码器）都必须递增此值</b>，旧缓存随之自动失效并重新上传。</p>
     */
    public static final String NORMALIZE_VERSION = "1";

    /** 下载图片的响应体上限，超出即中止，避免出站请求耗尽内存。 */
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    /**
     * 校验素材是否存在时最多读取的响应体字节数：{@code get_material} 在素材存在时返回图片二进制流，
     * 只需读到足够判断「返回的是 JSON 还是图片」即可，没必要为一次校验把整张图拉回来。
     */
    private static final int RESPONSE_PEEK_BYTES = 256;

    /** 微信错误响应中的 {@code errcode}（响应可能被截断，故用正则取值而不是整体反序列化）。 */
    private static final Pattern ERRCODE_PATTERN = Pattern.compile("\"errcode\"\\s*:\\s*(-?\\d+)");

    /** 下载连接超时。 */
    private static final Duration DOWNLOAD_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 下载整体响应超时（含读写空闲）。 */
    private static final Duration DOWNLOAD_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final WebClient webClient;

    /**
     * 专用于下载封面 / 正文图片的客户端，与调用微信接口的 {@link #webClient} 分离。
     *
     * <p>下载目标来自用户可控的请求体与正文 HTML，故施加 SSRF 防护：禁止自动重定向、连接期经
     * {@link SsrfSafeAddressResolverGroup} 过滤受限 IP、设置连接与响应超时并限制响应体大小。</p>
     */
    private final WebClient downloadWebClient;

    private final AtomicReference<TokenCache> tokenCache = new AtomicReference<>();

    /**
     * 当前的图片下载内网白名单策略，由 {@code WechatSyncService} 在每次同步开始时根据插件设置下发。
     * 属全局配置（对所有同步一致），预检与连接期解析器共享同一引用。默认为空白名单（不放行任何内网）。
     */
    private final AtomicReference<SsrfPolicy> ssrfPolicy = new AtomicReference<>(SsrfPolicy.EMPTY);

    public WechatMpClient() {
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_DOWNLOAD_BYTES))
            .build();
        HttpClient httpClient = HttpClient.create()
            // 禁止自动跟随重定向：避免公网地址 302 跳转到内网从而绕过校验
            .followRedirect(false)
            // 连接期再次校验实际目标 IP（同时尊重白名单），防止 DNS rebinding
            .resolver(new SsrfSafeAddressResolverGroup(ssrfPolicy::get))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) DOWNLOAD_CONNECT_TIMEOUT.toMillis())
            .responseTimeout(DOWNLOAD_RESPONSE_TIMEOUT)
            .doOnConnected(connection -> connection
                .addHandlerLast(new ReadTimeoutHandler((int) DOWNLOAD_RESPONSE_TIMEOUT.toSeconds()))
                .addHandlerLast(new WriteTimeoutHandler((int) DOWNLOAD_RESPONSE_TIMEOUT.toSeconds())));
        this.downloadWebClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_DOWNLOAD_BYTES))
            .build();
    }

    /**
     * 下发图片下载的内网白名单策略。由 {@code WechatSyncService} 在同步开始时根据插件设置解析后调用。
     *
     * @param policy 白名单策略，{@code null} 视为空白名单
     */
    public void setSsrfPolicy(SsrfPolicy policy) {
        this.ssrfPolicy.set(policy == null ? SsrfPolicy.EMPTY : policy);
    }

    private record TokenCache(String appId, String token, long expireAt) {
    }

    /**
     * 解析微信接口基址。
     *
     * <p>留空则直连官方 {@value #DEFAULT_BASE_URL}；否则使用用户配置的地址——常见于用一台有
     * <b>固定公网 IP</b> 的低配服务器做反向代理：把该 IP 加入微信「IP 白名单」，Halo 即便没有
     * 固定公网 IP（动态 IP / 家用宽带 / NAT 之后），也能经代理稳定获取 access_token 并完成同步。
     * 仅接受 http/https 绝对地址，统一去除尾部 '/'（允许带路径前缀，如
     * {@code https://example.com/wechat-proxy}）；非法时告警并回退默认地址。</p>
     *
     * @param configured 用户在插件设置中填写的接口地址，可为空
     * @return 规范化后的接口基址（不含尾部 '/'）
     */
    public static String resolveApiBase(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = configured.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            log.warn("接口地址「{}」不是合法的 http/https 绝对地址，回退到默认 {}", trimmed, DEFAULT_BASE_URL);
            return DEFAULT_BASE_URL;
        }
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * 获取（并缓存）公众号全局 access_token。
     *
     * @param apiBase    已规范化的微信接口基址
     * @param appId      公众号 AppID
     * @param appSecret  已从 Halo Secret 解析出的 AppSecret 明文（仅在内存中传递，不写日志/不落盘）
     */
    public Mono<String> getAccessToken(String apiBase, String appId, String appSecret) {
        if (isBlank(appId) || isBlank(appSecret)) {
            return Mono.error(new WechatApiException("请先在插件设置中配置公众号 AppID 与 AppSecret"));
        }
        long now = System.currentTimeMillis();
        TokenCache cache = tokenCache.get();
        if (cache != null && cache.appId().equals(appId) && cache.expireAt() > now) {
            return Mono.just(cache.token());
        }
        return webClient.get()
            .uri(apiBase + "/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}",
                appId, appSecret)
            .retrieve()
            .bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(this::parseMap)
            .flatMap(body -> {
                Object token = body.get("access_token");
                if (token != null) {
                    long expiresIn = toLong(body.get("expires_in"), 7200L);
                    tokenCache.set(new TokenCache(appId, token.toString(),
                        now + Math.max(expiresIn - 300, 60) * 1000));
                    return Mono.just(token.toString());
                }
                return Mono.error(new WechatApiException("获取 access_token 失败：" + body));
            });
    }

    /**
     * 下载远程资源字节，用于封面图和正文图片转存。
     *
     * <p>目标地址来自用户可控的封面与正文 HTML，属于典型 SSRF 面。此处为统一下载入口，先经
     * {@link SsrfGuard#validateAndResolve(String, SsrfPolicy)} 校验协议、主机与解析后的 IP（拒绝环回/内网/
     * 链路本地/元数据等受限网段，仅放行命中内网白名单的目标），再由专用 {@link #downloadWebClient} 在连接期
     * 二次校验实际目标 IP（同样尊重白名单）并禁止重定向，两层防护共同阻断非预期的内网访问。</p>
     */
    public Mono<byte[]> download(String url) {
        return Mono.fromCallable(() -> SsrfGuard.validateAndResolve(url, ssrfPolicy.get()))
            // getAllByName 为阻塞式 DNS 解析，切到 boundedElastic，不占用 Netty 事件循环
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(uri -> downloadWebClient.get()
                .uri(uri)
                .retrieve()
                // 已禁用自动重定向；若目标返回 3xx 则显式拒绝，不跟随到其他主机
                .onStatus(HttpStatusCode::is3xxRedirection,
                    response -> Mono.error(new WechatApiException(
                        "已拒绝下载：目标发生重定向（" + SsrfGuard.describe(uri) + "），出于安全考虑不跟随")))
                .bodyToMono(byte[].class));
    }

    /**
     * 上传正文图片（uploadimg），返回微信域名下的图片 URL。
     */
    public Mono<String> uploadContentImage(String apiBase, String token, byte[] data, String filename) {
        return postMultipart(apiBase + "/cgi-bin/media/uploadimg?access_token={token}", token, data, filename)
            .flatMap(body -> {
                Object url = body.get("url");
                if (url != null) {
                    return Mono.just(url.toString());
                }
                return Mono.error(new WechatApiException("上传正文图片失败：" + body));
            });
    }

    /** 永久图片素材的上传结果。 */
    public record PermanentImage(String mediaId, String url) {
    }

    /**
     * 上传永久图片素材（add_material），返回素材 id 与图片地址。
     *
     * <p>微信对图片类型会同时返回 {@code url}（素材图片地址）。它只作留档排查用，<b>不能</b>用来判断素材
     * 是否还在：素材在公众号后台被删除后这个地址往往仍然访问得到（地址是 CDN 上的图片副本，不等于素材库里
     * 的条目），据此复用会拿到已失效的 {@code media_id}。判断素材是否还在请用
     * {@link #checkMaterialAvailability(String, String, String)} 查 {@code media_id} 本身。</p>
     */
    public Mono<PermanentImage> uploadPermanentImage(String apiBase, String token, byte[] data,
        String filename) {
        return postMultipart(apiBase + "/cgi-bin/material/add_material?access_token={token}&type=image",
                token, data, filename)
            .flatMap(body -> {
                Object mediaId = body.get("media_id");
                if (mediaId != null) {
                    Object url = body.get("url");
                    return Mono.just(new PermanentImage(mediaId.toString(),
                        url == null ? null : url.toString()));
                }
                return Mono.error(new WechatApiException("上传封面素材失败：" + body));
            });
    }

    /** 微信侧图片资源的可得性判定结果：图片地址（CDN）与永久图片素材（{@code media_id}）共用。 */
    public enum ImageAvailability {

        /** 明确可得：地址返回 2xx / 已确证取回素材本体，资源还在。 */
        AVAILABLE,

        /** 明确不可得：地址 404、410 / 微信明确回 {@code 40007}，资源已被删除。 */
        MISSING,

        /**
         * 给不出结论：3xx、5xx、CDN 不支持 HEAD 返回的 405、代理没转发该接口、限流、鉴权失败、
         * 响应无法解析、空响应体、网络异常、SSRF 拦截等。
         *
         * <p>调用方按<b>不可信</b>处理：既不要据此认定资源仍在、也不要据此认定已失效——
         * 本项目的处置是「不确定就重传」（见 {@code WechatMediaCacheService#reuseOrUpload}）。</p>
         */
        UNKNOWN
    }

    /**
     * 校验微信侧图片地址是否仍可访问（复用正文图片缓存前调用）。
     *
     * <p>地址是 {@code uploadimg} 返回的微信 CDN 公开地址，<b>不经过插件设置的「接口地址」代理</b>，
     * 因此比任何经代理转发的接口都可靠。只发 HEAD（只要响应头，不把整张图下载回来）。</p>
     *
     * <p>只有 2xx 判「存在」、{@code 404/410} 判「已删除」，其余状态码与请求失败一律
     * {@link ImageAvailability#UNKNOWN}——不给出结论，由调用方决定怎么处置。</p>
     *
     * <p>永久图片素材不走本方法：它复用值是 {@code media_id}，地址可访问并不代表素材还在，
     * 见 {@link #checkMaterialAvailability(String, String, String)}。</p>
     */
    public Mono<ImageAvailability> checkImageAvailability(String url) {
        return Mono.fromCallable(() -> SsrfGuard.validateAndResolve(url, ssrfPolicy.get()))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(uri -> downloadWebClient.head()
                .uri(uri)
                .exchangeToMono(response -> Mono.just(availabilityOf(response.statusCode().value()))))
            .onErrorResume(e -> {
                log.warn("校验微信图片地址 [{}] 失败，无法判定：{}", url, e.getMessage());
                return Mono.just(ImageAvailability.UNKNOWN);
            });
    }

    /** 按 HTTP 状态码判定可访问性：只有明确的 404/410 才算已不存在，其余（3xx/5xx/405 等）无法得出确定结论。 */
    private static ImageAvailability availabilityOf(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return ImageAvailability.AVAILABLE;
        }
        return statusCode == 404 || statusCode == 410
            ? ImageAvailability.MISSING : ImageAvailability.UNKNOWN;
    }

    /**
     * 查询永久图片素材是否仍然存在（复用缓存里的 {@code media_id} 前调用）。
     *
     * <p>走 {@code material/get_material}：素材仍在时返回图片二进制流，素材已被删除时返回
     * {@code {"errcode":40007,"errmsg":"invalid media_id"}}（两种情形的 HTTP 状态码都是 200，故必须看一眼
     * 响应体；这里只读响应体头部 {@value #RESPONSE_PEEK_BYTES} 字节，拿到首个数据块即中止传输）。</p>
     *
     * <p><b>返回三态而非「还在 / 不在」两态</b>：只有微信明确回
     * {@value WechatApiException#INVALID_MEDIA_ID_ERRCODE} 才判 {@link ImageAvailability#MISSING}；只有确证拿到
     * 素材本体（非 JSON 的 2xx 响应体）才判 {@link ImageAvailability#AVAILABLE}；其余情况——代理没转发该接口
     * （返回 404、HTML 或它自己的错误 JSON）、限流、鉴权失败、响应无法解析、空响应体、网络异常——一律
     * {@link ImageAvailability#UNKNOWN}，由调用方决定怎么处置。</p>
     */
    public Mono<ImageAvailability> checkMaterialAvailability(String apiBase, String token, String mediaId) {
        if (isBlank(mediaId)) {
            // 没有 id 可查：说不了「还在」也说不了「不在」，只能如实回「给不出结论」
            return Mono.just(ImageAvailability.UNKNOWN);
        }
        return webClient.post()
            .uri(apiBase + "/cgi-bin/material/get_material?access_token={token}", token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("media_id", mediaId))
            .exchangeToMono(response -> response.bodyToFlux(DataBuffer.class)
                .take(1)
                .map(WechatMpClient::peek)
                .defaultIfEmpty(new byte[0])
                .map(head -> materialAvailability(response.statusCode(), head))
                .next())
            .onErrorResume(e -> {
                log.warn("校验永久素材 [{}] 失败，无法判定：{}", mediaId, e.getMessage());
                return Mono.just(ImageAvailability.UNKNOWN);
            });
    }

    /**
     * 由 {@code get_material} 的响应判定素材可得性：JSON 里 {@code errcode} 是
     * {@value WechatApiException#INVALID_MEDIA_ID_ERRCODE} 即素材已被删除，是其他错误码（代理未转发、
     * 限流等）给不出结论；非 JSON 的 2xx 响应体是素材本体（图片二进制流），说明素材仍在；
     * 其余（空响应体、非 2xx 的非 JSON）同样给不出结论。
     */
    private static ImageAvailability materialAvailability(HttpStatusCode status, byte[] head) {
        String text = new String(head, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return ImageAvailability.UNKNOWN;
        }
        if (!text.startsWith("{")) {
            return status.is2xxSuccessful() ? ImageAvailability.AVAILABLE : ImageAvailability.UNKNOWN;
        }
        Matcher matcher = ERRCODE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return ImageAvailability.UNKNOWN;
        }
        return WechatApiException.INVALID_MEDIA_ID_ERRCODE.equals(matcher.group(1))
            ? ImageAvailability.MISSING : ImageAvailability.UNKNOWN;
    }

    /** 取出响应体头部的若干字节，并释放该数据块（Netty 池化缓冲区必须显式释放）。 */
    private static byte[] peek(DataBuffer buffer) {
        try {
            byte[] head = new byte[Math.min(buffer.readableByteCount(), RESPONSE_PEEK_BYTES)];
            buffer.read(head);
            return head;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /**
     * 新建图文草稿，返回草稿 media_id。
     *
     * <p>失败时把微信返回的 {@code errcode} 一并带进异常：调用方要据此做补救——草稿被 {@code 40007
     * invalid media_id} 拒绝，说明封面素材已在微信侧失效，需要重传封面再试（见 {@code WechatSyncService}）。</p>
     */
    public Mono<String> addDraft(String apiBase, String token, Map<String, Object> article) {
        return webClient.post()
            .uri(apiBase + "/cgi-bin/draft/add?access_token={token}", token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("articles", List.of(article)))
            .retrieve()
            .bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(this::parseMap)
            .flatMap(body -> {
                Object mediaId = body.get("media_id");
                if (mediaId != null) {
                    return Mono.just(mediaId.toString());
                }
                Object errcode = body.get("errcode");
                return Mono.error(new WechatApiException("创建公众号草稿失败：" + body,
                    errcode == null ? null : errcode.toString()));
            });
    }

    /**
     * 以 multipart/form-data 上传单个媒体文件。
     *
     * <p>微信服务器会校验请求的 {@code Content-Length}；而 Spring 6.1+ 的 WebClient 用
     * {@code fromMultipartData} 时以 {@code Transfer-Encoding: chunked} 发送、不带
     * {@code Content-Length}，会被微信网关直接拒绝并返回 {@code 412 Precondition Failed}。
     * 因此这里手动拼出 multipart 字节体、显式设置 {@code Content-Length}，禁用分块传输。</p>
     */
    private Mono<Map<String, Object>> postMultipart(String uriTemplate, String token, byte[] data,
        String filename) {
        String boundary = "----WechatSyncBoundary" + Long.toHexString(System.nanoTime());
        // normalizeImage（图片解码/重编码）是 CPU 密集的阻塞操作。此前它在 WebClient 下载完成的
        // Netty 事件循环线程上同步执行，一旦抛错（如 NoClassDefFoundError）会破坏 netty pipeline，
        // 连带触发 ByteBuf 双重释放（IllegalReferenceCountException）。这里显式切到 boundedElastic
        // 线程做图片处理与报文构造：任何 Throwable 都只会成为 Mono 的 error 信号，网络 I/O 仍由
        // WebClient 在事件循环上完成。
        return Mono.fromCallable(() -> {
                NormalizedImage image = normalizeImage(data, filename);
                return buildMultipartBody(boundary, image.filename(), image.contentType(), image.data());
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(body -> webClient.post()
                .uri(uriTemplate, token)
                .headers(headers -> {
                    headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
                    headers.setContentLength(body.length);
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(this::parseMap));
    }

    /**
     * 手动构造仅含 {@code media} 一个分部的 multipart/form-data 报文。
     */
    private static byte[] buildMultipartBody(String boundary, String filename, String contentType, byte[] fileData) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"media\"; filename=\"" + sanitize(filename) + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(fileData);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private record NormalizedImage(byte[] data, String filename, String contentType) {
    }

    /**
     * 微信仅接受 bmp/png/jpeg/jpg/gif 图片；对 webp 等其他格式先解码再重新编码为 png（含透明）
     * 或 jpg（不含透明）。无法解码时按原始字节上传，交由微信返回明确错误。
     *
     * <p><b>改动本方法（含格式支持范围）时必须递增 {@link #NORMALIZE_VERSION}</b>：素材缓存按
     * 「转换前的原始字节」做键，不递增版本号的话，已被缓存的图片不会用新规则重新上传。</p>
     */
    private NormalizedImage normalizeImage(byte[] data, String filename) {
        String ext = extensionOf(filename);
        // 扩展名不可信：站点若做过批量 WebP 转换却保留原扩展名（xxx.png 里实为 WebP 字节），
        // 仅凭后缀放行会把 WebP 原始字节直传素材接口，微信返回 errcode=40113 unsupported file type。
        // 故改为「后缀 + 真实字节」双判定，WebP 仍走下方解码转码路径。
        if (WECHAT_IMAGE_EXTS.contains(ext) && !looksLikeWebp(data)) {
            return new NormalizedImage(data, filename, imageMime(ext));
        }
        if (looksLikeWebp(data)) {
            log.info("图片 [{}] 后缀为 {} 但真实格式是 WebP，将转码后上传以适配微信素材要求", filename, ext);
        }
        try {
            BufferedImage image = decodeImage(data);
            if (image == null) {
                log.warn("无法解码图片 [{}]，按原始字节上传，微信可能拒绝该格式", filename);
                return new NormalizedImage(data, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE);
            }
            boolean hasAlpha = image.getColorModel().hasAlpha();
            String format = hasAlpha ? "png" : "jpg";
            BufferedImage target = image;
            if (!hasAlpha) {
                target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = target.createGraphics();
                graphics.drawImage(image, 0, 0, Color.WHITE, null);
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(target, format, out)) {
                return new NormalizedImage(data, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE);
            }
            log.info("图片 [{}] 转换为 {} 格式以适配微信素材要求", filename, format);
            return new NormalizedImage(out.toByteArray(), baseNameOf(filename) + "." + format,
                hasAlpha ? "image/png" : "image/jpeg");
        } catch (IOException e) {
            log.warn("图片 [{}] 格式转换失败，按原始字节上传：{}", filename, e.getMessage());
            return new NormalizedImage(data, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
    }

    /**
     * 解码图片字节为 {@link BufferedImage}。
     *
     * <p>webp 走<b>直接实例化</b>的 TwelveMonkeys 解码器（先按 RIFF/WEBP 魔数判定），刻意绕开
     * {@code ImageIO.read}：{@code ImageIO} 的 SPI 注册表（IIORegistry）是 JVM 级单例，插件热重载/
     * 重装后仍会残留由<b>旧插件类加载器</b>注册的 webp SPI；遍历到该陈旧 SPI 时
     * {@code createReaderInstance} 会因旧 classloader 已关闭、无法再加载 {@code WebPImageMetadata}
     * 而抛 {@link NoClassDefFoundError}。由当前插件类加载器 {@code new} 出的 SPI 能正常解析全部相关类。
     * 非 webp 再回退 {@code ImageIO.read}（JDK 内建 png/jpg/gif/bmp 解码器；注册表里陈旧的 webp SPI
     * 因魔数不符会被 {@code canDecodeInput} 过滤，不会触发）。</p>
     */
    private BufferedImage decodeImage(byte[] data) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) {
                return null;
            }
            if (looksLikeWebp(data)) {
                ImageReader reader = new WebPImageReaderSpi().createReaderInstance();
                try {
                    reader.setInput(iis, true, true);
                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
            return ImageIO.read(iis);
        }
    }

    /** 按 RIFF....WEBP 魔数判断是否为 webp，避免依赖可能被污染的 ImageIO 全局注册表。 */
    private static boolean looksLikeWebp(byte[] data) {
        return data != null && data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /**
     * 按<b>真实字节</b>判定是否为微信可转存的图片：jpg/png/gif/bmp 由微信素材直接支持，
     * webp 由本类解码后转码为 png/jpg 再上传，故这五种格式都算可转存。
     *
     * <p>只看文件魔数，不看扩展名：站点批量转 WebP 却保留 {@code .png} 后缀、附件链接指向的文件被改过名
     * 等情况都很常见，凭后缀放行只会换来微信的 {@code 40005/40113}。调用方（如正文附件转存）据此决定
     * 「能不能上传」，避免把非图片字节交给微信图片接口。</p>
     */
    public static boolean isWechatSupportedImage(byte[] data) {
        return WECHAT_IMAGE_FORMATS.contains(imageFormatOf(data));
    }

    /**
     * 按文件魔数识别图片真实格式（{@code jpg}/{@code png}/{@code gif}/{@code bmp}/{@code webp}），
     * 无法识别时返回空串。
     */
    static String imageFormatOf(byte[] data) {
        if (data == null) {
            return "";
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        if (startsWith(data, 'G', 'I', 'F', '8')) {
            return "gif";
        }
        if (startsWith(data, 'B', 'M')) {
            return "bmp";
        }
        return looksLikeWebp(data) ? "webp" : "";
    }

    /** 字节是否以给定魔数序列开头（int 与 char 可混写，统一按低 8 位比较）。 */
    private static boolean startsWith(byte[] data, int... magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xFF) != (magic[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String filename) {
        return filename == null ? "image" : filename.replaceAll("[\"\\r\\n]", "");
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
            ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String baseNameOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        return base.isBlank() ? "image" : base;
    }

    private static String imageMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    /**
     * 微信接口常以 {@code text/plain} 返回 JSON 正文，直接 {@code bodyToMono(Map)} 会因
     * 媒体类型不匹配抛 UnsupportedMediaTypeException；故先按 String 读取（StringDecoder
     * 支持任意媒体类型），再自行解析 JSON。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (RuntimeException e) {
            throw new WechatApiException("解析微信响应失败：" + text);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static long toLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
