package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.cache.CachedMedia;
import com.hcjike.wechatofficialsync.cache.MediaCacheKind;
import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.client.WechatMpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 微信媒体上传缓存：正文图片与永久图片素材在上传前先查缓存，命中且微信侧资源仍然存在时直接复用
 * 上一次的上传结果，避免同一张图被反复上传、把微信素材库（永久素材有数量上限）占满。
 *
 * <p><b>缓存键</b>为「公众号 AppID + 上传类型 + 文件指纹 + 归一化版本」：AppID 让不同公众号互不串用
 * （换号后旧记录不会命中）；上传类型区分两个接口（返回内容不同，见 {@link MediaCacheKind}）；
 * 文件指纹是 <b>格式转换前</b>原始文件内容的 SHA-256，是文件真正的唯一属性——同一张图无论出现在
 * 哪篇文章、文件名与来源地址如何变化，指纹都一致；归一化版本让转码规则的变更能作废旧缓存
 * （原因见 {@link WechatMpClient#NORMALIZE_VERSION}）。</p>
 *
 * <p><b>指纹为何取转换前的字节</b>：图片归一化（webp 转码等，见 {@link WechatMpClient}）依赖 JDK 的
 * 图像编解码器，转换后的字节在同一 JDK 内是确定的，跨 JDK 版本/平台却<b>不保证</b>一致——拿它做键会
 * 让 Halo/JDK 升级后缓存全部失效。取原始字节只会「漏命中」（同一张图字节不同则各上传一次），
 * 不会误命中（字节不同即不同键），方向是安全的。指纹也不含文件名：同一份字节配不同文件名会复用同一
 * 结果，这是正确的——缓存记录的是「这张源图在微信上的位置」，与上传时的文件名无关。</p>
 *
 * <p><b>处理流程</b>（见 {@link #resolve}）：</p>
 * <ol>
 *   <li>算出原始字节的文件指纹并查缓存；</li>
 *   <li>命中 → 校验资源是否还在（首选图片地址 HEAD 一次、不经代理；地址缺失或给不出结论时回落到永久素材的
 *       {@code media_id} 校验），两道校验都只在「明确不存在」时判失效；</li>
 *   <li>未命中或校验发现已失效 → 调用对应上传接口，成功后把「指纹 → url / media_id」写入或覆盖缓存。</li>
 * </ol>
 *
 * <p>上传失败照常向上抛出（不吞异常）；只有「缓存读写失败」「校验请求本身失败」这类不影响正确性的
 * 环节才会降级处理，详见 {@link WechatMediaCacheStore} 与 {@link #verify}。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Service
public class WechatMediaCacheService {

    private static final Logger log = LoggerFactory.getLogger(WechatMediaCacheService.class);

    private static final String SHA_256 = "SHA-256";

    private final WechatMpClient wechatMpClient;

    private final WechatMediaCacheStore store;

    public WechatMediaCacheService(WechatMpClient wechatMpClient, WechatMediaCacheStore store) {
        this.wechatMpClient = wechatMpClient;
        this.store = store;
    }

    /**
     * 取得正文图片的微信地址：缓存命中且微信侧图片仍在时复用，否则上传并写缓存。
     *
     * @param apiBase   已规范化的微信接口基址
     * @param appId     公众号 AppID（缓存分区键）
     * @param token     access_token
     * @param data      待上传的图片字节（原始字节，指纹即由其计算）
     * @param filename  上传时使用的文件名
     * @param sourceUrl 图片来源地址（仅用于记录来源，便于排查）
     */
    public Mono<String> resolveContentImage(String apiBase, String appId, String token, byte[] data,
        String filename, String sourceUrl) {
        return resolve(MediaCacheKind.CONTENT_IMAGE, apiBase, appId, token, data, filename, sourceUrl);
    }

    /**
     * 取得永久图片素材的 {@code media_id}：缓存命中且素材仍在时复用，否则上传并写缓存。
     *
     * @param data 待上传的图片字节（原始字节，指纹即由其计算）
     * @see #resolveContentImage
     */
    public Mono<String> resolvePermanentImage(String apiBase, String appId, String token, byte[] data,
        String filename, String sourceUrl) {
        return resolve(MediaCacheKind.PERMANENT_IMAGE, apiBase, appId, token, data, filename, sourceUrl);
    }

    /**
     * 缓存优先的上传流程：先按「原始字节指纹 + 当前归一化版本」查缓存，命中则校验后复用，
     * 未命中（或已失效、或归一化规则已变更）则上传后写缓存。
     */
    private Mono<String> resolve(MediaCacheKind kind, String apiBase, String appId, String token,
        byte[] data, String filename, String sourceUrl) {
        String fingerprint = fingerprint(data);
        return store.find(appId, kind, fingerprint, WechatMpClient.NORMALIZE_VERSION)
            .flatMap(cached -> reuseOrUpload(kind, apiBase, appId, token, data, filename, sourceUrl,
                fingerprint, cached))
            .switchIfEmpty(Mono.defer(() -> uploadAndCache(kind, apiBase, appId, token, data, filename,
                sourceUrl, fingerprint)));
    }

    /**
     * 命中缓存：校验微信侧资源是否仍然存在，存在则复用，否则重新上传并覆盖缓存。
     *
     * <p>注意「复用值」与「校验依据」不是同一个字段：正文图片复用的就是它的图片地址；永久素材复用是的
     * {@code media_id}，而校验优先用它上传时返回的素材图片地址（见 {@link #verify}）。</p>
     */
    private Mono<String> reuseOrUpload(MediaCacheKind kind, String apiBase, String appId, String token,
        byte[] data, String filename, String sourceUrl, String fingerprint, CachedMedia cached) {
        String remoteValue = cached.remoteValue();
        if (remoteValue == null || remoteValue.isBlank()) {
            // 数据异常（记录存在但没存下微信返回的标识）：直接重新上传，不做无意义的校验
            log.warn("{}[{}] 的缓存记录缺少微信返回的资源标识，重新上传", label(kind), filename);
            return uploadAndCache(kind, apiBase, appId, token, data, filename, sourceUrl, fingerprint);
        }
        return verify(kind, apiBase, token, cached)
            .flatMap(alive -> {
                if (alive) {
                    log.info("{}[{}] 命中缓存且微信侧资源仍存在，直接复用：{}", label(kind), filename,
                        remoteValue);
                    return touchAndReturn(kind, appId, fingerprint, cached, remoteValue);
                }
                log.info("{}[{}] 的缓存已失效（微信侧资源已不存在），重新上传", label(kind), filename);
                return Mono.defer(() -> uploadAndCache(kind, apiBase, appId, token, data, filename,
                    sourceUrl, fingerprint));
            });
    }

    /** 复用缓存：刷新「最近一次使用时间」后返回复用值（清理任务按使用时间算保留期，见 {@link WechatMediaCacheStore}）。 */
    private Mono<String> touchAndReturn(MediaCacheKind kind, String appId, String fingerprint,
        CachedMedia cached, String remoteValue) {
        return store.touch(appId, kind, fingerprint, cached.normalizeVersion()).thenReturn(remoteValue);
    }

    /**
     * 校验缓存里的微信侧资源是否仍然存在，两道校验按可靠性排序使用：
     *
     * <ol>
     *   <li><b>图片地址</b>（首选）：素材/图片在微信 CDN 上的公开地址，<b>不经过插件设置的「接口地址」代理</b>，
     *       因而代理转发不全时依然可用；2xx 即可复用、404/410 判失效；</li>
     *   <li><b>media_id</b>（回落到永久素材）：地址缺失或地址校验给不出结论时，用 {@code material/get_material}
     *       直接问素材本身是否还在（经代理，代理没转发该接口时无法给出结论）。</li>
     * </ol>
     *
     * <p>两道校验都只在「<b>明确</b>不存在」时返回 {@code false}，其余情况（非 2xx 也非 404/410、无法解析、
     * 网络异常、代理未实现接口等）一律保守复用：一次误判就会把已上传的素材重传一遍，正好违背缓存的意义。</p>
     */
    private Mono<Boolean> verify(MediaCacheKind kind, String apiBase, String token, CachedMedia cached) {
        String url = cached.contentUrl();
        if (url == null || url.isBlank()) {
            return verifyByMediaId(kind, apiBase, token, cached.mediaId());
        }
        return wechatMpClient.checkImageAvailability(url)
            .flatMap(availability -> switch (availability) {
                case AVAILABLE -> Mono.just(true);
                case MISSING -> Mono.just(false);
                case UNKNOWN -> verifyByMediaId(kind, apiBase, token, cached.mediaId());
            });
    }

    /** 用 {@code media_id} 校验永久素材；正文图片没有该标识（或标识为空）时视为「仍可用」。 */
    private Mono<Boolean> verifyByMediaId(MediaCacheKind kind, String apiBase, String token, String mediaId) {
        if (kind != MediaCacheKind.PERMANENT_IMAGE || mediaId == null || mediaId.isBlank()) {
            return Mono.just(true);
        }
        return wechatMpClient.permanentImageExists(apiBase, token, mediaId);
    }

    /** 调用对应上传接口，成功后把文件指纹与微信返回的内容写入（或覆盖）缓存。 */
    private Mono<String> uploadAndCache(MediaCacheKind kind, String apiBase, String appId, String token,
        byte[] data, String filename, String sourceUrl, String fingerprint) {
        String normalizeVersion = WechatMpClient.NORMALIZE_VERSION;
        Mono<CachedMedia> uploaded = kind == MediaCacheKind.PERMANENT_IMAGE
            // 永久素材：media_id 供草稿引用，url 留作下次复用前的校验依据
            ? wechatMpClient.uploadPermanentImage(apiBase, token, data, filename)
                .map(image -> CachedMedia.uploaded(appId, kind, fingerprint, normalizeVersion, sourceUrl,
                    filename, sizeOf(data), image.mediaId(), image.url()))
            : wechatMpClient.uploadContentImage(apiBase, token, data, filename)
                .map(url -> CachedMedia.uploaded(appId, kind, fingerprint, normalizeVersion, sourceUrl,
                    filename, sizeOf(data), null, url));
        return uploaded.flatMap(media -> {
            log.info("{}[{}] 上传成功并写入缓存（{} 字节）：{}", label(kind), filename, sizeOf(data),
                media.remoteValue());
            return store.save(media).thenReturn(media.remoteValue());
        });
    }

    /**
     * 计算文件的唯一属性：<b>格式转换前</b>原始文件内容的 SHA-256（小写十六进制）。
     *
     * <p>与文件名、来源地址无关，因此同一张图在不同文章、不同链接下只会被上传一次；
     * 传入 {@code null} / 空数组时按空内容的指纹处理（调用方本就不会上传空数据）。
     * 刻意不取归一化之后的字节：转换后的字节在同一 JDK 内确定、跨 JDK 版本不保证一致，
     * 用它做键会让 Halo/JDK 升级后缓存全部失效（详见类注释）。</p>
     */
    static String fingerprint(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的摘要算法，正常不会走到这里
            throw new IllegalStateException("当前 JVM 不支持 SHA-256 摘要", e);
        }
    }

    private static int sizeOf(byte[] data) {
        return data == null ? 0 : data.length;
    }

    /** 日志中使用的资源类型名称。 */
    private static String label(MediaCacheKind kind) {
        return kind == MediaCacheKind.PERMANENT_IMAGE ? "永久图片素材" : "正文图片";
    }
}
