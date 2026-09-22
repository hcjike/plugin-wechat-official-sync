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
 *   <li>命中 → <b>按复用值本身</b>校验资源是否还在（见 {@link #verify}）：正文图片校验微信图片地址，
 *       永久素材用 {@code media_id} 查素材本身；<b>只有明确存在才复用</b>；</li>
 *   <li>未命中、已失效、或校验<b>给不出结论</b> → 调用对应上传接口，成功后把「指纹 → url / media_id」
 *       写入或覆盖缓存（见 {@link #reuseOrUpload}：多传一份的代价小于复用失败导致草稿发不出去）。</li>
 * </ol>
 *
 * <p>上传失败照常向上抛出（不吞异常）；只有「缓存读写失败」「校验请求本身失败」这类不会让结果变错的
 * 环节才降级处理——缓存读写失败按未命中处理，校验失败按「不可信」处理（即重传），
 * 详见 {@link WechatMediaCacheStore} 与 {@link #verify}。</p>
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
     * 跳过缓存校验、直接重新上传永久图片素材，返回新的 {@code media_id} 并覆盖缓存记录。
     *
     * <p>用于「草稿被微信以 {@code 40007 invalid media_id} 拒绝」后的自愈重试：此时缓存里那条记录已被证明
     * 不可用，重传会把它覆盖掉，之后再同步同一张封面就能复用新的 {@code media_id}。</p>
     */
    public Mono<String> reuploadPermanentImage(String apiBase, String appId, String token, byte[] data,
        String filename, String sourceUrl) {
        return uploadAndCache(MediaCacheKind.PERMANENT_IMAGE, apiBase, appId, token, data, filename,
            sourceUrl, fingerprint(data));
    }

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
     * 命中缓存：校验微信侧资源是否仍然存在，<b>只有明确存在才复用</b>，否则重新上传并覆盖缓存。
     *
     * <p>「已失效」与「给不出结论」在这里同等对待——都重传：判定不出结论时重传最多浪费一次上传
     * （正文图片不占素材库，永久素材多一份），而保守复用一旦碰上资源真的失效，整篇草稿就发不出去。
     * 两边代价不对等，故宁可多传一份（更彻底的自愈见 {@code WechatSyncService} 对 40007 的重试）。</p>
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
            .flatMap(state -> {
                if (state == WechatMpClient.ImageAvailability.AVAILABLE) {
                    log.info("{}[{}] 命中缓存且微信侧资源确实还在，直接复用：{}", label(kind), filename,
                        remoteValue);
                    return touchAndReturn(kind, appId, fingerprint, cached, remoteValue);
                }
                log.info("{}[{}] 的缓存不再可信（{}），重新上传并覆盖缓存：{}", label(kind), filename,
                    describe(state), remoteValue);
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
     * 查询缓存里那项资源在微信侧是否仍然存在，<b>校验依据必须与复用值一致</b>：
     *
     * <ul>
     *   <li><b>正文图片</b>：复用值就是它的微信图片地址，故校验这个地址（{@code HEAD} 一次，不经插件设置的
     *       「接口地址」代理）；</li>
     *   <li><b>永久图片素材</b>：复用值是 {@code media_id}，故用 {@code material/get_material} 直接查
     *       <b>素材本身</b>。这里<b>不能</b>拿素材的图片地址来判断素材还在不在——素材在公众号后台被删除后，
     *       它的图片地址往往仍可访问（地址代表 CDN 上的图片副本，不等于素材库里的条目），据此复用会拿到已失效
     *       的 {@code media_id}，直到 {@code draft/add} 报 {@code 40007 invalid media_id} 才暴露。</li>
     * </ul>
     *
     * <p>两道校验都是三态（{@link WechatMpClient.ImageAvailability}）：明确存在 / 明确不存在 / 给不出结论。
     * 怎么用由 {@link #reuseOrUpload} 决定——只有「明确存在」才复用。</p>
     */
    private Mono<WechatMpClient.ImageAvailability> verify(MediaCacheKind kind, String apiBase, String token,
        CachedMedia cached) {
        return kind == MediaCacheKind.PERMANENT_IMAGE
            ? wechatMpClient.checkMaterialAvailability(apiBase, token, cached.mediaId())
            : wechatMpClient.checkImageAvailability(cached.contentUrl());
    }

    /** 日志用：说清楚为什么「不再可信」，便于排查（如代理没转发 {@code material/get_material}）。 */
    private static String describe(WechatMpClient.ImageAvailability state) {
        return state == WechatMpClient.ImageAvailability.MISSING
            ? "微信侧已不存在"
            : "校验给不出结论（代理未转发该接口 / 限流 / 网络异常等）";
    }

    /** 调用对应上传接口，成功后把文件指纹与微信返回的内容写入（或覆盖）缓存。 */
    private Mono<String> uploadAndCache(MediaCacheKind kind, String apiBase, String appId, String token,
        byte[] data, String filename, String sourceUrl, String fingerprint) {
        String normalizeVersion = WechatMpClient.NORMALIZE_VERSION;
        Mono<CachedMedia> uploaded = kind == MediaCacheKind.PERMANENT_IMAGE
            // 永久素材：media_id 供草稿引用，url 仅留档（素材是否还在由 media_id 本身校验）
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
