package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.client.WechatApiException;
import com.hcjike.wechatofficialsync.client.WechatMpClient;
import com.hcjike.wechatofficialsync.client.WechatMpClient.ImageAvailability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * {@link WechatMediaCacheService} 的行为验证：同一份文件只上传一次（命中缓存且微信侧资源仍存在时直接复用），
 * 缓存失效时重新上传并覆盖缓存，永久素材与正文图片分别缓存，校验依据必须与复用值一致
 * （永久素材查 {@code media_id}、正文图片查图片地址），以及「校验给不出结论也重传」的处置策略。
 *
 * <p>微信客户端为 mock（不发真实请求），但缓存是真实 SQLite（每个用例一个临时库），
 * 因而「上传 → 写缓存 → 再次同步复用」的完整链路都被覆盖。</p>
 */
class WechatMediaCacheServiceTest {

    private static final String API_BASE = "https://api.weixin.qq.com";

    private static final String APP_ID = "wx-app-1";

    private static final String OTHER_APP_ID = "wx-app-2";

    private static final String TOKEN = "TOKEN";

    /** 同一份文件的原始字节：指纹由它算出，与文件名、来源地址无关。 */
    private static final byte[] IMAGE = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);

    private static final String SOURCE_URL = "https://blog.example.com/upload/2026/09/a.png";

    /** 正文图片转存后微信返回的图片地址。 */
    private static final String CONTENT_URL = "https://mmbiz.qpic.cn/mmbiz_png/a.png";

    /**
     * 永久素材上传时微信返回的素材图片地址：只作为记录留档，<b>不参与复用前的校验</b>——素材被删除后这个
     * 地址往往仍然访问得到，拿它判定「素材还在」会放过已经失效的 {@code media_id}（见下面永久素材的用例）。
     */
    private static final String COVER_URL = "https://mmbiz.qpic.cn/mmbiz_jpg/cover.jpg";

    @TempDir
    Path tempDirectory;

    private WechatMpClient client;

    private WechatMediaCacheService service;

    @BeforeEach
    void setUp() {
        client = mock(WechatMpClient.class);
        WechatMediaCacheStore store =
            new WechatMediaCacheStore(() -> tempDirectory.resolve("plugins"));
        service = new WechatMediaCacheService(client, store);
    }

    @Test
    void contentImageIsUploadedOnceAndThenReusedFromCache() {
        when(client.uploadContentImage(eq(API_BASE), eq(TOKEN), any(), eq("a.png")))
            .thenReturn(Mono.just(CONTENT_URL));
        planAvailability(CONTENT_URL, ImageAvailability.AVAILABLE);

        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo(CONTENT_URL);
        verify(client, times(1)).uploadContentImage(anyString(), anyString(), any(), anyString());

        // 同一张图再次同步（文件名与来源地址都变了）：按内容指纹命中缓存，不再上传
        assertThat(contentImage("renamed.png", "https://cdn.example.com/other.png"))
            .isEqualTo(CONTENT_URL);
        verify(client, times(1)).uploadContentImage(anyString(), anyString(), any(), anyString());
        // 复用的前提是校验过图片地址仍可访问
        verify(client, times(1)).checkImageAvailability(CONTENT_URL);
    }

    @Test
    void permanentImageIsUploadedOnceAndThenReusedFromCache() {
        when(client.uploadPermanentImage(eq(API_BASE), eq(TOKEN), any(), eq("cover.png")))
            .thenReturn(Mono.just(new WechatMpClient.PermanentImage("MEDIA-ID-1", COVER_URL)));
        planMaterial("MEDIA-ID-1", ImageAvailability.AVAILABLE);

        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-ID-1");
        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-ID-1");

        // 永久素材会占用素材库：同一份封面只上传一次，后续直接复用 media_id
        verify(client, times(1)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        verify(client, times(1)).checkMaterialAvailability(API_BASE, TOKEN, "MEDIA-ID-1");
        // 复用的是 media_id，校验的就必须是素材本身：素材图片的 CDN 地址可访问并不代表素材还在素材库里
        verify(client, never()).checkImageAvailability(anyString());
    }

    @Test
    void reuploadsWhenMaterialIsGoneEvenIfItsCdnUrlIsStillReachable() {
        when(client.uploadPermanentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new WechatMpClient.PermanentImage("MEDIA-OLD", COVER_URL)),
                Mono.just(new WechatMpClient.PermanentImage("MEDIA-NEW", COVER_URL)));
        // 素材在公众号后台被删除，但它的图片地址仍然访问得到：不能据此复用——
        // 唯一可靠的判据是 material/get_material（此前正是这里放过了失效的 media_id，草稿因此报 40007）
        planMaterial("MEDIA-OLD", ImageAvailability.MISSING);

        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-OLD");
        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-NEW");

        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        verify(client, never()).checkImageAvailability(anyString());
    }

    @Test
    void reuploadsContentImageWhenAddressCheckGivesNoConclusion() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(CONTENT_URL), Mono.just("https://mmbiz.qpic.cn/mmbiz_png/b.png"));
        // 地址校验给不出结论（3xx/405/5xx、网络抖动等）：同样重传——
        // 多传一张不占素材库，而复用一个可能已失效的地址会让这幅图在微信里显示不出来
        planAvailability(CONTENT_URL, ImageAvailability.UNKNOWN);

        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo(CONTENT_URL);
        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo("https://mmbiz.qpic.cn/mmbiz_png/b.png");

        verify(client, times(2)).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void reuploadsWhenMaterialCheckGivesNoConclusion() {
        when(client.uploadPermanentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new WechatMpClient.PermanentImage("MEDIA-OLD", COVER_URL)),
                Mono.just(new WechatMpClient.PermanentImage("MEDIA-NEW", COVER_URL)));
        // 代理没转发 material/get_material（或限流、网络异常）：说不清素材还在不在 → 重传。
        // 保守复用碰上素材真的失效就是整篇草稿发不出去，多传一份素材的代价小得多
        planMaterial("MEDIA-OLD", ImageAvailability.UNKNOWN);

        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-OLD");
        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-NEW");

        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void distinguishesContentImageAndPermanentImageForTheSameFile() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(CONTENT_URL));
        when(client.uploadPermanentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new WechatMpClient.PermanentImage("MEDIA-ID-1", COVER_URL)));
        when(client.checkImageAvailability(anyString())).thenReturn(Mono.just(ImageAvailability.AVAILABLE));
        when(client.checkMaterialAvailability(anyString(), anyString(), anyString()))
            .thenReturn(Mono.just(ImageAvailability.AVAILABLE));

        // 同一份文件走两个上传接口：返回内容不同（url / media_id），必须各存一份、互不干扰
        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo(CONTENT_URL);
        assertThat(permanentImage("a.png")).isEqualTo("MEDIA-ID-1");
        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo(CONTENT_URL);
        assertThat(permanentImage("a.png")).isEqualTo("MEDIA-ID-1");

        verify(client, times(1)).uploadContentImage(anyString(), anyString(), any(), anyString());
        verify(client, times(1)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void reuploadsAndOverwritesCacheWhenCachedImageDisappeared() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just("https://mmbiz.qpic.cn/old.png"),
                Mono.just("https://mmbiz.qpic.cn/new.png"));
        // 微信侧图片已被删除：缓存失效，必须重新上传
        planAvailability("https://mmbiz.qpic.cn/old.png", ImageAvailability.MISSING);

        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo("https://mmbiz.qpic.cn/old.png");
        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo("https://mmbiz.qpic.cn/new.png");

        verify(client, times(2)).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void reuploadPermanentImageSkipsCacheAndOverwritesRecord() {
        when(client.uploadPermanentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just(new WechatMpClient.PermanentImage("MEDIA-OLD", COVER_URL)),
                Mono.just(new WechatMpClient.PermanentImage("MEDIA-NEW", COVER_URL)));
        planMaterial("MEDIA-NEW", ImageAvailability.AVAILABLE);

        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-OLD");
        // 草稿被 40007 拒绝后走的就是这条路：跳过缓存校验直接重传，并覆盖那条已失效的记录
        assertThat(service.reuploadPermanentImage(API_BASE, APP_ID, TOKEN, IMAGE, "cover.png", SOURCE_URL)
            .block()).isEqualTo("MEDIA-NEW");

        verify(client, times(2)).uploadPermanentImage(anyString(), anyString(), any(), anyString());
        // 覆盖后同一张封面再同步即复用新的 media_id：不会再撞上那个失效的 id
        assertThat(permanentImage("cover.png")).isEqualTo("MEDIA-NEW");
        verify(client, times(1)).checkMaterialAvailability(API_BASE, TOKEN, "MEDIA-NEW");
    }

    @Test
    void doesNotReuseCacheAcrossAccounts() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just("URL-1"), Mono.just("URL-2"));

        assertThat(service.resolveContentImage(API_BASE, APP_ID, TOKEN, IMAGE, "a.png", SOURCE_URL)
            .block()).isEqualTo("URL-1");
        // 换了公众号：素材空间不同，旧缓存不得复用（也不会去校验它的存在性）
        assertThat(service.resolveContentImage(API_BASE, OTHER_APP_ID, TOKEN, IMAGE, "a.png", SOURCE_URL)
            .block()).isEqualTo("URL-2");

        verify(client, times(2)).uploadContentImage(anyString(), anyString(), any(), anyString());
        verifyNoMoreInteractions(client);
    }

    @Test
    void uploadsDifferentFilesSeparately() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.just("URL-1"), Mono.just("URL-2"));

        assertThat(service.resolveContentImage(API_BASE, APP_ID, TOKEN, IMAGE, "a.png", SOURCE_URL)
            .block()).isEqualTo("URL-1");
        assertThat(service.resolveContentImage(API_BASE, APP_ID, TOKEN, new byte[] {9, 9}, "b.png",
            SOURCE_URL).block()).isEqualTo("URL-2");

        verify(client, times(2)).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void uploadFailureIsPropagatedAndNotCached() {
        when(client.uploadContentImage(anyString(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new WechatApiException("上传正文图片失败")), Mono.just("URL-OK"));

        // 上传失败照常向上抛出（由调用方决定如何处置），不得写缓存
        assertThatThrownBy(() -> contentImage("a.png", SOURCE_URL))
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("上传正文图片失败");

        // 未写缓存：下次同步重新上传并成功
        assertThat(contentImage("a.png", SOURCE_URL)).isEqualTo("URL-OK");
        verify(client, times(2)).uploadContentImage(anyString(), anyString(), any(), anyString());
    }

    @Test
    void fingerprintDependsOnFileContentOnly() {
        byte[] data = "same-bytes".getBytes(StandardCharsets.UTF_8);

        String fingerprint = WechatMediaCacheService.fingerprint(data);

        // 文件的唯一属性：内容相同即指纹相同（文件名/来源地址不参与），内容不同则指纹不同
        assertThat(fingerprint).hasSize(64).isEqualTo(WechatMediaCacheService.fingerprint(data.clone()));
        assertThat(WechatMediaCacheService.fingerprint("other-bytes".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo(fingerprint);
        assertThat(WechatMediaCacheService.fingerprint(null)).hasSize(64);
    }

    /** 预置图片地址校验结果。 */
    private void planAvailability(String url, ImageAvailability availability) {
        when(client.checkImageAvailability(url)).thenReturn(Mono.just(availability));
    }

    /** 预置永久素材校验结果。 */
    private void planMaterial(String mediaId, ImageAvailability availability) {
        when(client.checkMaterialAvailability(API_BASE, TOKEN, mediaId))
            .thenReturn(Mono.just(availability));
    }

    private String contentImage(String filename, String sourceUrl) {
        return service.resolveContentImage(API_BASE, APP_ID, TOKEN, IMAGE, filename, sourceUrl).block();
    }

    private String permanentImage(String filename) {
        return service.resolvePermanentImage(API_BASE, APP_ID, TOKEN, IMAGE, filename, SOURCE_URL).block();
    }
}
