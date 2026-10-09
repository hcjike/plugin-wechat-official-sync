package com.hcjike.wechatofficialsync.client;

/**
 * 调用微信公众号接口失败时抛出。
 *
 * <p>若这次失败来自微信接口本身，异常会一并带上微信返回的 {@code errcode}：个别错误码需要调用方
 * 做针对性补救，而不是一律向上抛——例如 {@link #INVALID_MEDIA_ID_ERRCODE}
 * （{@code 40007 invalid media_id}）说明草稿引用的封面素材在微信侧已不存在，此时只有重新上传一张再试
 * 才有意义（见 {@code WechatSyncService}）。</p>
 *
 * <p>另有一种失败<b>没有 errcode</b>：请求被网关 / 代理层以非 2xx 状态拒绝（如腾讯云 WAF 按内容风控
 * 返回的 {@code 501}），根本没到微信业务层——这类失败由 {@link #httpStatus} 标记，调用方可据此判断
 * 「这次调用确定没有生效」并安全改写策略（{@code draft/update} 被拒时改为新建草稿，
 * 见 {@code WechatSyncService}），参见 {@link #isGatewayRejected()}。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class WechatApiException extends RuntimeException {

    /**
     * 微信「不合法的媒体文件 id」错误码。
     *
     * <p>三个场景会用到它：{@code material/get_material} 返回它说明素材确实已被删除（校验缓存时据此判失效）；
     * {@code draft/add} / {@code draft/update} 返回它说明草稿引用的 {@code thumb_media_id} 已失效，
     * 或（{@code draft/update} 时）该草稿本身已不存在——前者触发封面重传重试，后者触发改为新建草稿
     * （见 {@code WechatSyncService}）。</p>
     */
    public static final String INVALID_MEDIA_ID_ERRCODE = "40007";

    /** 微信返回的错误码；非微信接口本身的失败（下载失败、本地校验失败、网关拒绝等）为 {@code null}。 */
    private final String errcode;

    /** 网关 / 代理层拒绝时的 HTTP 状态码；非该场景为 {@code null}（见类注释）。 */
    private final Integer httpStatus;

    public WechatApiException(String message) {
        this(message, null, null);
    }

    public WechatApiException(String message, String errcode) {
        this(message, errcode, null);
    }

    public WechatApiException(String message, String errcode, Integer httpStatus) {
        super(message);
        this.errcode = errcode;
        this.httpStatus = httpStatus;
    }

    /** 微信业务错误码，可能为 {@code null}（见字段说明）。 */
    public String getErrcode() {
        return errcode;
    }

    /** 是否为「媒体 id 不合法」（{@value #INVALID_MEDIA_ID_ERRCODE}）：草稿引用的封面素材已失效。 */
    public boolean isInvalidMediaId() {
        return INVALID_MEDIA_ID_ERRCODE.equals(errcode);
    }

    /** 网关 / 代理层返回的 HTTP 状态码；非该场景为 {@code null}。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * 是否为「网关 / 代理层拒绝」：HTTP 状态非 2xx，请求没有到达微信业务层（因此没有 errcode）。
     *
     * <p>常见的如 {@code 501 Not Implemented}（腾讯云 WAF 内容风控拦截页）、{@code 502 Bad Gateway}、
     * 缺少 {@code Content-Length} 时的 {@code 412 Precondition Failed}。这类失败意味着<b>本次调用确定
     * 没有生效</b>，调用方可以安全地改走其它策略。</p>
     */
    public boolean isGatewayRejected() {
        return httpStatus != null;
    }
}
