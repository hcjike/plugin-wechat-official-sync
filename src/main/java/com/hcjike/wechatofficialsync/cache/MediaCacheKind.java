package com.hcjike.wechatofficialsync.cache;

/**
 * 媒体缓存条目的类型：对应微信两个上传接口，二者的上传语义与返回内容完全不同，故分开缓存、分开校验。
 *
 * <ul>
 *   <li>{@link #CONTENT_IMAGE} 正文图片（{@code cgi-bin/media/uploadimg}）：不占素材库、返回微信域名下的
 *       图片地址（响应字段 {@code url}），该地址长期有效，可直接复用；</li>
 *   <li>{@link #PERMANENT_IMAGE} 永久图片素材（{@code cgi-bin/material/add_material}）：<b>占用素材库</b>、
 *       返回素材 id（响应字段 {@code media_id}），草稿封面靠它引用，素材被删除后 media_id 即失效。</li>
 * </ul>
 *
 * @author hcjike
 * @since 1.0.0
 */
public enum MediaCacheKind {

    /** 正文图片：uploadimg 接口，返回图片 url。 */
    CONTENT_IMAGE,

    /** 永久图片素材：add_material 接口，返回 media_id。 */
    PERMANENT_IMAGE;

    /**
     * 按名称解析（大小写不敏感、忽略首尾空白）。
     *
     * @param value 数据库中的类型字段，可为 {@code null}
     * @return 对应的类型；取值缺失或非法（如库中残留了更新版本插件写入的未知类型）时返回 {@code null}
     */
    public static MediaCacheKind from(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (MediaCacheKind kind : values()) {
            if (kind.name().equalsIgnoreCase(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
