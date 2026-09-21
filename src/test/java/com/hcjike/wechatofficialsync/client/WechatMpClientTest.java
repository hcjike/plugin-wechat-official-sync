package com.hcjike.wechatofficialsync.client;

import com.hcjike.wechatofficialsync.ssrf.SsrfPolicy;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WechatMpClient} 的行为验证。
 *
 * <p>接口调用统一打到 {@code 127.0.0.1} 上的本地 {@code HttpServer}（JDK 自带，不引入新依赖），
 * 既能断言请求的方法/路径/报文，也能驱动真实的 WebClient 链路，避免依赖外网与真实公众号凭据。</p>
 *
 * <p>重点覆盖素材上传前的文件格式归一化：微信素材接口只接受 bmp/png/jpeg/jpg/gif，<b>扩展名不可信</b>
 * ——站点批量转 WebP 后常保留原扩展名（{@code xxx.png} 里实为 WebP 字节），仅凭后缀放行会把 WebP
 * 字节直传素材接口，微信返回 {@code errcode=40113 unsupported file type}。故须按「后缀 + 真实字节」
 * 双判定，命中 WebP 魔数的一律先解码转码再上传。</p>
 */
class WechatMpClientTest {

    /** 官方接口地址，用于断言回退逻辑（与 {@code WechatMpClient} 内部默认值保持一致）。 */
    private static final String OFFICIAL_BASE = "https://api.weixin.qq.com";

    /** 1x1 的无损 WebP（VP8L）字节，作为「真实格式与扩展名不一致」的测试用例。 */
    private static final String WEBP_BASE64 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private FakeWechatServer server;

    private WechatMpClient client;

    @BeforeEach
    void setUp() {
        server = new FakeWechatServer();
        client = new WechatMpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void resolveApiBaseFallsBackToOfficialWhenNotUsable() {
        // 未配置 / 纯空白：直连官方
        assertThat(WechatMpClient.resolveApiBase(null)).isEqualTo(OFFICIAL_BASE);
        assertThat(WechatMpClient.resolveApiBase("")).isEqualTo(OFFICIAL_BASE);
        assertThat(WechatMpClient.resolveApiBase("   ")).isEqualTo(OFFICIAL_BASE);
        // 非 http/https 绝对地址（如误填域名或 file://）：告警并回退
        assertThat(WechatMpClient.resolveApiBase("api.weixin.qq.com")).isEqualTo(OFFICIAL_BASE);
        assertThat(WechatMpClient.resolveApiBase("ftp://proxy.example.com")).isEqualTo(OFFICIAL_BASE);
    }

    @Test
    void resolveApiBaseNormalizesConfiguredAddress() {
        // 反向代理场景：保留路径前缀，去除尾部 '/'，避免拼出 //cgi-bin/token 之类的错误路径
        assertThat(WechatMpClient.resolveApiBase("https://proxy.example.com"))
            .isEqualTo("https://proxy.example.com");
        assertThat(WechatMpClient.resolveApiBase("  https://proxy.example.com/wechat-proxy///  "))
            .isEqualTo("https://proxy.example.com/wechat-proxy");
        assertThat(WechatMpClient.resolveApiBase("http://10.0.0.5:8080"))
            .isEqualTo("http://10.0.0.5:8080");
    }

    @Test
    void accessTokenRequiresAppIdAndAppSecret() {
        assertThatThrownBy(() -> client.getAccessToken(server.baseUrl(), "", "s3cret").block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("AppID 与 AppSecret");
        assertThatThrownBy(() -> client.getAccessToken(server.baseUrl(), "wx123", null).block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("AppID 与 AppSecret");
        // 凭据缺失时不应发起任何请求
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void accessTokenIsFetchedAndThenCached() {
        server.plan("{\"access_token\":\"TOKEN-1\",\"expires_in\":7200}");

        assertThat(client.getAccessToken(server.baseUrl(), "wx123", "s3cret").block()).isEqualTo("TOKEN-1");
        RecordedRequest request = server.lastRequest();
        assertThat(request.uri()).contains("/cgi-bin/token")
            .contains("grant_type=client_credential")
            .contains("appid=wx123");

        // 缓存期内（expires_in 提前 5 分钟失效）复用同一 token，不再请求接口
        server.plan("{\"access_token\":\"TOKEN-2\",\"expires_in\":7200}");
        assertThat(client.getAccessToken(server.baseUrl(), "wx123", "s3cret").block()).isEqualTo("TOKEN-1");
        assertThat(server.requestCount()).isEqualTo(1);

        // 换了公众号（AppID 变化）必须重新获取，不能复用其它公众号的 token
        assertThat(client.getAccessToken(server.baseUrl(), "wx456", "s3cret").block()).isEqualTo("TOKEN-2");
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    void accessTokenFailsWhenResponseHasNoToken() {
        server.plan("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        assertThatThrownBy(() -> client.getAccessToken(server.baseUrl(), "wx123", "s3cret").block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("获取 access_token 失败")
            .hasMessageContaining("40001");
    }

    @Test
    void addDraftPostsArticlesAndReturnsMediaId() {
        server.plan("{\"media_id\":\"MEDIA_ID_1\"}");

        String mediaId = client.addDraft(server.baseUrl(), "TOKEN", Map.of("title", "hello")).block();

        assertThat(mediaId).isEqualTo("MEDIA_ID_1");
        RecordedRequest request = server.lastRequest();
        assertThat(request.uri()).contains("/cgi-bin/draft/add").contains("access_token=TOKEN");
        assertThat(request.contentType()).startsWith("application/json");
        // 草稿接口要求 articles 为数组
        assertThat(request.bodyText()).contains("\"articles\"").contains("hello");
    }

    @Test
    void addDraftFailsWhenResponseHasNoMediaId() {
        server.plan("{\"errcode\":45009,\"errmsg\":\"reach max quota\"}");
        assertThatThrownBy(() -> client.addDraft(server.baseUrl(), "TOKEN", Map.of("title", "hello")).block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("创建公众号草稿失败");
    }

    @Test
    void permanentImageUploadReturnsMediaId() {
        server.plan("{\"media_id\":\"COVER_MEDIA_ID\"}");

        String mediaId = client.uploadPermanentImage(server.baseUrl(), "TOKEN", pngBytes(), "cover.png").block();

        assertThat(mediaId).isEqualTo("COVER_MEDIA_ID");
        assertThat(server.lastRequest().uri())
            .contains("/cgi-bin/material/add_material")
            .contains("type=image");
    }

    @Test
    void pngIsUploadedAsIsWithExplicitContentLength() {
        server.plan("{\"url\":\"https://mmbiz.qpic.cn/mmbiz_jpg/a.png\"}");
        byte[] png = pngBytes();

        String url = client.uploadContentImage(server.baseUrl(), "TOKEN", png, "cover.png").block();

        assertThat(url).isEqualTo("https://mmbiz.qpic.cn/mmbiz_jpg/a.png");
        RecordedRequest request = server.lastRequest();
        assertThat(request.uri()).contains("/cgi-bin/media/uploadimg");
        assertThat(request.contentType()).startsWith("multipart/form-data").contains("boundary=");
        // 微信网关校验 Content-Length：分块传输（chunked）会被直接拒绝
        assertThat(request.header("Transfer-Encoding")).isNull();
        assertThat(request.contentLength()).isEqualTo(request.body().length);
        // 微信原生格式：按原字节、原文件名上传
        assertThat(request.partFileName()).isEqualTo("cover.png");
        assertThat(request.partContentType()).isEqualTo("image/png");
        assertThat(request.partBytes()).isEqualTo(png);
    }

    @Test
    void jpegIsUploadedAsIs() {
        server.plan("{\"url\":\"https://mmbiz.qpic.cn/mmbiz_jpg/a.jpg\"}");
        byte[] jpeg = jpegBytes();

        client.uploadContentImage(server.baseUrl(), "TOKEN", jpeg, "photo.jpg").block();

        RecordedRequest request = server.lastRequest();
        assertThat(request.partFileName()).isEqualTo("photo.jpg");
        assertThat(request.partContentType()).isEqualTo("image/jpeg");
        assertThat(request.partBytes()).isEqualTo(jpeg);
    }

    @Test
    void webpBytesWithWebpExtensionAreTranscoded() {
        server.plan("{\"url\":\"https://mmbiz.qpic.cn/mmbiz_jpg/a.jpg\"}");
        byte[] webp = webpBytes();

        client.uploadContentImage(server.baseUrl(), "TOKEN", webp, "cover.webp").block();

        RecordedRequest request = server.lastRequest();
        assertTranscodedToWechatFormat(request, webp);
    }

    @Test
    void webpBytesDisguisedAsPngAreTranscoded() {
        // 回归用例：站点做过批量 WebP 转换却保留原扩展名，仅凭后缀放行会把 WebP 原始字节直传
        // 素材接口，微信返回 errcode=40113 unsupported file type
        server.plan("{\"url\":\"https://mmbiz.qpic.cn/mmbiz_jpg/a.jpg\"}");
        byte[] webp = webpBytes();

        client.uploadContentImage(server.baseUrl(), "TOKEN", webp, "cover.png").block();

        RecordedRequest request = server.lastRequest();
        assertTranscodedToWechatFormat(request, webp);
    }

    @Test
    void undecodableBytesAreUploadedAsOctetStream() {
        server.plan("{\"url\":\"https://mmbiz.qpic.cn/mmbiz_jpg/a.bin\"}");
        byte[] garbage = "not-an-image-at-all".getBytes(StandardCharsets.UTF_8);

        client.uploadContentImage(server.baseUrl(), "TOKEN", garbage, "a.bin").block();

        RecordedRequest request = server.lastRequest();
        // 无法解码时不丢弃（交由微信返回明确错误），但按二进制流上传
        assertThat(request.partBytes()).isEqualTo(garbage);
        assertThat(request.partFileName()).isEqualTo("a.bin");
        assertThat(request.partContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void wechatSupportedImageIsDecidedByRealBytes() {
        // 正文附件转存据此决定「能不能上传」：jpg/png/gif/bmp 微信素材直接支持，webp 由客户端转码后上传
        assertThat(WechatMpClient.isWechatSupportedImage(pngBytes())).isTrue();
        assertThat(WechatMpClient.isWechatSupportedImage(jpegBytes())).isTrue();
        assertThat(WechatMpClient.isWechatSupportedImage(
            encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "gif"))).isTrue();
        assertThat(WechatMpClient.isWechatSupportedImage(new byte[] {'B', 'M', 0, 0})).isTrue();
        assertThat(WechatMpClient.isWechatSupportedImage(webpBytes())).isTrue();

        // 非图片与空数据一律不放行：把非图片字节交给微信图片接口只会换来 40005/40113
        assertThat(WechatMpClient.isWechatSupportedImage("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(WechatMpClient.isWechatSupportedImage("not-an-image".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(WechatMpClient.isWechatSupportedImage(new byte[0])).isFalse();
        assertThat(WechatMpClient.isWechatSupportedImage(null)).isFalse();
    }

    @Test
    void downloadRejectsRestrictedAddressByDefault() {
        // 默认空白名单：环回地址（典型 SSRF 目标）在预检阶段即被拒绝，不会发起连接
        assertThatThrownBy(() -> client.download(server.baseUrl() + "/upload/a.png").block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("受限网络");
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void downloadAllowsWhitelistedInternalTarget() {
        client.setSsrfPolicy(SsrfPolicy.parse("127.0.0.1"));
        server.plan("image-bytes");

        byte[] data = client.download(server.baseUrl() + "/upload/a.png").block();

        assertThat(data).isEqualTo("image-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(server.lastRequest().uri()).isEqualTo("/upload/a.png");
    }

    @Test
    void downloadRefusesToFollowRedirect() {
        client.setSsrfPolicy(SsrfPolicy.parse("127.0.0.1"));
        // 公网地址 302 跳转到内网是常见绕过手法：已禁用自动重定向，遇到 3xx 直接报错
        server.planRedirect("http://127.0.0.1:22/internal");

        assertThatThrownBy(() -> client.download(server.baseUrl() + "/upload/a.png").block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("重定向");
    }

    @Test
    void nullPolicyIsTreatedAsEmptyAllowlist() {
        client.setSsrfPolicy(null);
        assertThatThrownBy(() -> client.download(server.baseUrl() + "/upload/a.png").block())
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("受限网络");
    }

    /**
     * 断言上传报文里的图片已被转码为微信可接受的格式：字节不再是原始 WebP，且声明的文件名后缀与
     * 媒体类型都与转码后的真实字节一致（含透明通道转 png，否则转 jpg）。
     */
    private static void assertTranscodedToWechatFormat(RecordedRequest request, byte[] original) {
        byte[] uploaded = request.partBytes();
        String magic = magicOf(uploaded);
        // 原始字节不得被直传：否则微信素材接口会返回 errcode=40113 unsupported file type
        assertThat(magic).as("上传字节的真实格式").isIn("jpg", "png");
        assertThat(uploaded).isNotEqualTo(original);
        assertThat(request.partFileName()).endsWith("." + magic);
        assertThat(request.partContentType()).isEqualTo("png".equals(magic) ? "image/png" : "image/jpeg");
    }

    private static byte[] pngBytes() {
        return encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png");
    }

    private static byte[] jpegBytes() {
        return encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private static byte[] webpBytes() {
        return Base64.getDecoder().decode(WEBP_BASE64);
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("JDK 不支持写出格式：" + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造测试图片失败", e);
        }
    }

    /** 依据文件头魔数判断上传字节的真实格式，用于断言 WebP 已被转码。 */
    private static String magicOf(byte[] data) {
        if (data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        if (data.length > 3 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8) {
            return "jpg";
        }
        if (data.length > 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        return "unknown";
    }

    /** 本地伪造的「微信接口」：记录所有请求并按预置队列应答。 */
    private static final class FakeWechatServer {

        private final HttpServer server;

        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

        private final Queue<PlannedResponse> responses = new ConcurrentLinkedQueue<>();

        FakeWechatServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("无法启动本地测试服务器", e);
            }
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                requests.add(new RecordedRequest(exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), body));
                PlannedResponse planned = responses.poll();
                if (planned == null) {
                    planned = new PlannedResponse(200, "{}", null);
                }
                if (planned.location != null) {
                    exchange.getResponseHeaders().add("Location", planned.location);
                    exchange.sendResponseHeaders(planned.status, -1);
                    exchange.close();
                    return;
                }
                // 微信常以 text/plain 返回 JSON 正文，客户端须自行解析
                byte[] payload = planned.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(planned.status, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** 预置一次应答的 JSON 正文（先进先出）。 */
        void plan(String body) {
            responses.add(new PlannedResponse(200, body, null));
        }

        /** 预置一次 302 应答，用于验证下载不跟随重定向。 */
        void planRedirect(String location) {
            responses.add(new PlannedResponse(302, "", location));
        }

        int requestCount() {
            return requests.size();
        }

        RecordedRequest lastRequest() {
            List<RecordedRequest> snapshot = new ArrayList<>(requests);
            assertThat(snapshot).as("客户端未发起任何请求").isNotEmpty();
            return snapshot.get(snapshot.size() - 1);
        }

        void stop() {
            server.stop(0);
        }

        private record PlannedResponse(int status, String body, String location) {
        }
    }

    /** 记录一次到达本地服务器的请求，并提供 multipart 报文的简易解析。 */
    private record RecordedRequest(String uri, Headers headers, byte[] body) {

        private static final Pattern FILE_NAME = Pattern.compile("filename=\"([^\"]*)\"");

        private static final Pattern PART_CONTENT_TYPE = Pattern.compile("Content-Type: ([^\\r\\n]+)");

        String header(String name) {
            return headers.getFirst(name);
        }

        String contentType() {
            return header("Content-Type");
        }

        long contentLength() {
            String value = header("Content-Length");
            return value == null ? -1L : Long.parseLong(value);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** multipart 中 {@code media} 分部声明的文件名。 */
        String partFileName() {
            Matcher matcher = FILE_NAME.matcher(new String(body, StandardCharsets.ISO_8859_1));
            assertThat(matcher.find()).as("报文缺少 filename 分部：%s", bodyText()).isTrue();
            return matcher.group(1);
        }

        /** multipart 中 {@code media} 分部声明的媒体类型。 */
        String partContentType() {
            Matcher matcher = PART_CONTENT_TYPE.matcher(new String(body, StandardCharsets.ISO_8859_1));
            assertThat(matcher.find()).as("报文缺少分部 Content-Type").isTrue();
            return matcher.group(1).trim();
        }

        /** multipart 中 {@code media} 分部的原始字节（去掉分部头与结束边界）。 */
        byte[] partBytes() {
            int headEnd = indexOf(body, new byte[] {'\r', '\n', '\r', '\n'});
            int tailStart = lastIndexOf(body, new byte[] {'\r', '\n', '-', '-'});
            assertThat(headEnd).as("报文缺少分部头").isGreaterThanOrEqualTo(0);
            assertThat(tailStart).as("报文缺少结束边界").isGreaterThan(headEnd);
            return Arrays.copyOfRange(body, headEnd + 4, tailStart);
        }

        private static int indexOf(byte[] data, byte[] target) {
            for (int i = 0; i <= data.length - target.length; i++) {
                if (matches(data, target, i)) {
                    return i;
                }
            }
            return -1;
        }

        private static int lastIndexOf(byte[] data, byte[] target) {
            for (int i = data.length - target.length; i >= 0; i--) {
                if (matches(data, target, i)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean matches(byte[] data, byte[] target, int offset) {
            for (int i = 0; i < target.length; i++) {
                if (data[offset + i] != target[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
