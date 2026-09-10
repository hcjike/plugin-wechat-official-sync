package com.hcjike.wechatofficialsync;

import lombok.Data;

/**
 * Console 前端提交的同步请求体。
 *
 * @author hcjike
 * @since 1.0.0
 */
@Data
public class SyncRequest {

    private String postName;

    private String title;

    private String digest;

    /**
     * 渲染后的正文 HTML。
     */
    private String content;

    /**
     * 文章封面图地址，可能为相对路径。
     */
    private String cover;

    private String author;

    /**
     * 文章在站点上的路由地址（Halo {@code status.permalink}，如 {@code /archives/xxx}），
     * 用于与站点「外部访问地址」拼成草稿的「原文链接」（阅读原文）。
     */
    private String permalink;
}
