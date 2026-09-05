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

    private String author;

    private boolean openComment;
}
