package com.hcjike.wechatofficialsync.cache;

/**
 * 一条媒体缓存记录：以「公众号 + 上传类型 + 文件指纹 + 归一化版本」为唯一键，
 * 记录该文件上传到微信后返回的结果，供后续同步直接复用。
 *
 * <p>{@code fingerprint} 是文件的唯一属性——<b>格式转换前</b>原始文件内容的 SHA-256，
 * 与文件名、来源地址无关：同一张图无论出现在哪篇文章、以什么文件名、从哪个地址下载，指纹都一致，
 * 因而只会被上传一次。之所以取转换前的字节：图片归一化（webp 转码等）依赖 JDK 图像编解码器实现，
 * 转换后的字节在同 JDK 内确定、跨 JDK 版本却不保证一致，拿它做键会让 Halo/JDK 升级后缓存全部失效。</p>
 *
 * <p>因此指纹<b>不随归一化规则变化</b>，单靠它无法感知「转码规则改了」；故把
 * {@code normalizeVersion}（见 {@code WechatMpClient#NORMALIZE_VERSION}）作为键的另一个维度：
 * 匹配时要求版本一致，规则一改旧缓存即不再命中（并会被重新上传的记录覆盖），
 * 避免出现「转码 bug 已修、草稿里却还是旧产物」。</p>
 *
 * <p>上传后的属性按接口区分存放：永久素材存 {@code mediaId}，正文图片存 {@code contentUrl}
 * （见 {@link MediaCacheKind} 与 {@link #remoteValue()}）。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public record CachedMedia(
    String appId,
    MediaCacheKind kind,
    /** 格式转换前原始文件内容的 SHA-256。 */
    String fingerprint,
    /** 上传时使用的图片归一化规则版本。 */
    String normalizeVersion,
    /** 图片来源地址（仅用于排查，不参与匹配）。 */
    String sourceUrl,
    String filename,
    long fileSize,
    /** 永久图片素材的 media_id，仅 {@link MediaCacheKind#PERMANENT_IMAGE} 有值。 */
    String mediaId,
    /**
     * 微信侧图片地址：正文图片（{@link MediaCacheKind#CONTENT_IMAGE}）即是它的复用值；
     * 永久素材（{@link MediaCacheKind#PERMANENT_IMAGE}）则存该素材的图片地址（{@code add_material}
     * 返回的 {@code url}），只作留档排查用，<b>不参与复用前的校验</b>——素材被删除后该地址往往仍可访问，
     * 据此判定「素材还在」会放过已失效的 {@code media_id}（见 {@link #remoteValue()}）。
     */
    String contentUrl,
    /** 记录建立时间（首次上传成功）。 */
    long createdAt,
    /** 最近一次使用时间：上传成功或命中缓存复用都会刷新，缓存清理的保留期按它计算。 */
    long updatedAt
) {

    /**
     * 新建一条「刚刚上传成功」的缓存记录（创建/更新时间取当前时刻）。
     *
     * @param normalizeVersion 上传时使用的图片归一化规则版本
     * @param mediaId          永久图片素材的 {@code media_id}，正文图片传 {@code null}
     * @param contentUrl       正文图片的微信地址；永久图片素材传 {@code add_material} 返回的素材图片地址
     *                         （仅作留档，不参与校验，可为 {@code null}）
     */
    public static CachedMedia uploaded(String appId, MediaCacheKind kind, String fingerprint,
        String normalizeVersion, String sourceUrl, String filename, long fileSize, String mediaId,
        String contentUrl) {
        long now = System.currentTimeMillis();
        return new CachedMedia(appId, kind, fingerprint, normalizeVersion, sourceUrl, filename, fileSize,
            mediaId, contentUrl, now, now);
    }

    /**
     * 该记录里的微信侧资源标识：永久素材为 {@code media_id}，正文图片为微信图片地址。
     * 为空表示记录不完整（数据异常），调用方应重新上传。
     */
    public String remoteValue() {
        return kind == MediaCacheKind.PERMANENT_IMAGE ? mediaId : contentUrl;
    }
}
