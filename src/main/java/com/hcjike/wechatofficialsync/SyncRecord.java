package com.hcjike.wechatofficialsync;

import java.time.Instant;
import lombok.Data;

/**
 * 单篇文章的同步状态记录，持久化在插件专用的 ConfigMap 中，供文章列表展示。
 *
 * @author hcjike
 * @since 1.0.0
 */
@Data
public class SyncRecord {

    /** 同步中：任务已提交，尚未拿到最终结果。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 同步成功：已写入公众号草稿箱。 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 同步失败。 */
    public static final String STATUS_FAILED = "FAILED";

    private String status;

    /** 展示给用户的说明或失败原因。 */
    private String message;

    /** 最近一次状态更新时间（ISO-8601）。 */
    private String time;

    /** 成功时的公众号草稿 media_id。 */
    private String mediaId;

    public static SyncRecord pending() {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_PENDING);
        record.setMessage("同步任务已提交，正在处理…");
        record.setTime(Instant.now().toString());
        return record;
    }

    public static SyncRecord success(String mediaId) {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_SUCCESS);
        record.setMessage("已同步到公众号草稿箱");
        record.setMediaId(mediaId);
        record.setTime(Instant.now().toString());
        return record;
    }

    public static SyncRecord failed(String message) {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_FAILED);
        record.setMessage(message == null || message.isBlank() ? "同步失败，请查看服务端日志" : message);
        record.setTime(Instant.now().toString());
        return record;
    }
}
