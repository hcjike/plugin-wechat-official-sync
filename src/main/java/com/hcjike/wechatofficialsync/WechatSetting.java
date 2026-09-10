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

    /** 留言设置：关闭留言。 */
    public static final String COMMENT_MODE_CLOSE = "close";

    /** 留言设置：开启留言，所有人可留言。 */
    public static final String COMMENT_MODE_ALL = "all";

    /** 留言设置：开启留言，已关注的人（粉丝）可留言。 */
    public static final String COMMENT_MODE_FANS = "fans";

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

    /**
     * 草稿的留言设置，取值 {@link #COMMENT_MODE_CLOSE} / {@link #COMMENT_MODE_ALL} /
     * {@link #COMMENT_MODE_FANS}，对应微信 draft/add 的 {@code need_open_comment} 与
     * {@code only_fans_can_comment}。
     */
    private String commentMode;

    /**
     * 已废弃：旧版「开启评论」开关。仅为兼容升级前已保存的配置（{@code true} 等价于
     * {@link #COMMENT_MODE_ALL}），新表单不再写入该字段。
     */
    private Boolean openComment;

    /**
     * 图片下载的内网白名单（多行文本，每行一个域名/IP/CIDR）。
     *
     * <p>为空时（默认）拒绝下载一切指向环回/内网/链路本地/元数据等受限地址的图片（防 SSRF）；
     * 若 Halo 部署在内网、图片也位于内网地址导致同步失败，可在此显式添加信任的目标予以放行。</p>
     */
    private String imageHostAllowlist;
}
