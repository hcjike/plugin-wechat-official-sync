package com.hcjike.wechatofficialsync;

/**
 * 调用微信公众号接口失败时抛出。
 *
 * @author hcjike
 * @since 1.0.0
 */
public class WechatApiException extends RuntimeException {

    public WechatApiException(String message) {
        super(message);
    }
}
