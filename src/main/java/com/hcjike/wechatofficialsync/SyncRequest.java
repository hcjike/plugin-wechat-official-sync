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
}
