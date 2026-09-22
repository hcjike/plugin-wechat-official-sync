package com.hcjike.wechatofficialsync.client;

/**
 * 调用微信公众号接口失败时抛出。
 *
 * <p>若这次失败来自微信接口本身，异常会一并带上微信返回的 {@code errcode}：个别错误码需要调用方
 * 做针对性补救，而不是一律向上抛——例如 {@link #INVALID_MEDIA_ID_ERRCODE}
 * （{@code 40007 invalid media_id}）说明草稿引用的封面素材在微信侧已不存在，此时只有重新上传一张再试
 * 才有意义（见 {@code WechatSyncService}）。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class WechatApiException extends RuntimeException {

    /**
     * 微信「不合法的媒体文件 id」错误码。
     *
     * <p>两个场景会用到它：{@code material/get_material} 返回它说明素材确实已被删除（校验缓存时据此判失效）；
     * {@code draft/add} 返回它说明草稿引用的 {@code thumb_media_id} 已失效（据此触发封面的重传重试）。</p>
     */
    public static final String INVALID_MEDIA_ID_ERRCODE = "40007";

    /** 微信返回的错误码；非微信接口本身的失败（下载失败、本地校验失败等）为 {@code null}。 */
    private final String errcode;

    public WechatApiException(String message) {
        this(message, null);
    }

    public WechatApiException(String message, String errcode) {
        super(message);
        this.errcode = errcode;
    }

    /** 微信错误码，可能为 {@code null}（见字段说明）。 */
    public String getErrcode() {
        return errcode;
    }

    /** 是否为「媒体 id 不合法」（{@value #INVALID_MEDIA_ID_ERRCODE}）：草稿引用的封面素材已失效。 */
    public boolean isInvalidMediaId() {
        return INVALID_MEDIA_ID_ERRCODE.equals(errcode);
    }
}
