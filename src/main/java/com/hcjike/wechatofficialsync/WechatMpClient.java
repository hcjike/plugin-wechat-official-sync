package com.hcjike.wechatofficialsync;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final WebClient webClient;

    private final AtomicReference<TokenCache> tokenCache = new AtomicReference<>();

    public WechatMpClient() {
        this.webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
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
     */
    public Mono<String> getAccessToken(WechatSetting setting) {
        if (isBlank(setting.getAppId()) || isBlank(setting.getAppSecret())) {
            return Mono.error(new WechatApiException("请先在插件设置中配置公众号 AppID 与 AppSecret"));
        }
        long now = System.currentTimeMillis();
        TokenCache cache = tokenCache.get();
        if (cache != null && cache.appId().equals(setting.getAppId()) && cache.expireAt() > now) {
            return Mono.just(cache.token());
        }
        String apiBase = resolveApiBase(setting.getBaseUrl());
        return webClient.get()
            .uri(apiBase + "/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}",
                setting.getAppId(), setting.getAppSecret())
            .retrieve()
            .bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(this::parseMap)
            .flatMap(body -> {
                Object token = body.get("access_token");
                if (token != null) {
                    long expiresIn = toLong(body.get("expires_in"), 7200L);
                    tokenCache.set(new TokenCache(setting.getAppId(), token.toString(),
                        now + Math.max(expiresIn - 300, 60) * 1000));
                    return Mono.just(token.toString());
                }
                return Mono.error(new WechatApiException("获取 access_token 失败：" + body));
            });
    }

    /**
     * 下载远程资源字节，用于封面图和正文图片转存。
     */
    public Mono<byte[]> download(String url) {
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(byte[].class);
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

    /**
     * 上传永久图片素材（add_material），返回可用于草稿封面的 media_id。
     */
    public Mono<String> uploadPermanentImage(String apiBase, String token, byte[] data, String filename) {
        return postMultipart(apiBase + "/cgi-bin/material/add_material?access_token={token}&type=image",
                token, data, filename)
            .flatMap(body -> {
                Object mediaId = body.get("media_id");
                if (mediaId != null) {
                    return Mono.just(mediaId.toString());
                }
                return Mono.error(new WechatApiException("上传封面素材失败：" + body));
            });
    }

    /**
     * 新建图文草稿，返回草稿 media_id。
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
                return Mono.error(new WechatApiException("创建公众号草稿失败：" + body));
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
     */
    private NormalizedImage normalizeImage(byte[] data, String filename) {
        String ext = extensionOf(filename);
        if (WECHAT_IMAGE_EXTS.contains(ext)) {
            return new NormalizedImage(data, filename, imageMime(ext));
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
