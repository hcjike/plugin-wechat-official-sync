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

    /**
     * Halo {@code Secret} 资源中保存 AppSecret 的键名。settings.yaml 的 {@code secret} 组件通过
     * {@code requiredKeys} 声明该键，服务端据此从 Secret 中取出明文。
     */
    public static final String APP_SECRET_KEY = "appSecret";

    private String appId;

    /**
     * 保存 AppSecret 的 Halo {@code Secret} 资源<b>名称</b>，而非 AppSecret 明文。
     *
     * <p>AppSecret 属敏感凭据，按 Halo 规范交由 {@code Secret} 资源统一管理（由 settings.yaml 的
     * {@code secret} 组件写入），Setting/ConfigMap 中只保留该 Secret 的名称，不落任何明文。服务端
     * 通过 {@code ReactiveExtensionClient} 按名称读取 Secret 并在内存中解析出 AppSecret。</p>
     */
    private String appSecretName;

    /**
     * 微信接口基址。留空则直连官方 {@code https://api.weixin.qq.com}；无固定公网 IP 时可填自建
     * 反向代理地址（把代理服务器的固定 IP 加入微信白名单），插件会将用到的微信接口请求发往该地址。
     */
    private String baseUrl;

    private String author;

    private boolean openComment;
}
