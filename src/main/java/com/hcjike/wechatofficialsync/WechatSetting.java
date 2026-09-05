package com.hcjike.wechatofficialsync;

import lombok.Data;

/**
 * 微信公众号同步相关的插件配置，对应 settings.yaml 中 group 为 {@value #GROUP} 的表单。
 *
 * @author hcjike
 * @since 1.0.0
 */
@Data
public class WechatSetting {

    public static final String GROUP = "wechat";

    private String appId;

    private String appSecret;

    /**
     * 微信接口基址。留空则直连官方 {@code https://api.weixin.qq.com}；无固定公网 IP 时可填自建
     * 反向代理地址（把代理服务器的固定 IP 加入微信白名单），插件会将用到的微信接口请求发往该地址。
     */
    private String baseUrl;

    private String author;

    private boolean openComment;
}
