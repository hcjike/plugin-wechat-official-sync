package com.hcjike.wechatofficialsync.model;

import com.hcjike.wechatofficialsync.util.SensitiveText;
import java.time.Instant;
import lombok.Data;

/**
 * 单篇文章的同步状态视图（状态码、说明、时间、草稿 media_id 与本次实际执行的草稿动作），
 * 供 Console 文章列表展示与接口下发的传输对象。持久化由 {@link WechatSyncTask} 任务记录承担，
 * 本类型仅作为对外投影。
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

    /**
     * 草稿动作：本次同步<b>新建</b>了一份草稿（首次同步、草稿已不在微信侧，或更新被拒后回退到新建）。
     */
    public static final String DRAFT_ACTION_CREATE = "create";

    /** 草稿动作：本次同步<b>更新</b>了该文章已有的草稿（{@code draft/update}，草稿 media_id 不变）。 */
    public static final String DRAFT_ACTION_UPDATE = "update";

    private String status;

    /** 展示给用户的说明或失败原因。 */
    private String message;

    /** 最近一次状态更新时间（ISO-8601）。 */
    private String time;

    /** 成功时的公众号草稿 media_id。 */
    private String mediaId;

    /**
     * 最近一次成功同步<b>实际</b>执行的草稿动作：{@value #DRAFT_ACTION_CREATE} / {@value #DRAFT_ACTION_UPDATE}。
     *
     * <p>记录的是执行结果而非提交时的预判——例如更新被微信网关 / WAF 拒绝后回退到新建草稿，这里就是
     * {@code create}。它<b>只作为结构化字段</b>对外提供（MCP 状态工具的 {@code draftAction}），
     * 不写进 {@link #message}：状态说明展示在文章列表的状态列，那里只需要「成功了 / 为什么失败」，
     * 「新建还是更新」由 MCP 工具在需要时按字段说明。</p>
     */
    private String draftAction;

    public static SyncRecord pending() {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_PENDING);
        record.setMessage("同步任务已提交，正在处理…");
        record.setTime(Instant.now().toString());
        return record;
    }

    public static SyncRecord success(String mediaId) {
        return success(mediaId, null);
    }

    /**
     * 成功记录：写入草稿 media_id 与实际动作（{@code draftAction} 可为 {@code null}）。
     *
     * <p>成功说明不提「新建 / 更新」：它展示在文章列表的状态列，那里只需一句结果；动作由
     * {@link #draftAction} 单独承载，MCP 状态工具按字段回报（见字段说明）。</p>
     */
    public static SyncRecord success(String mediaId, String draftAction) {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_SUCCESS);
        record.setMessage("已同步到公众号草稿箱");
        record.setMediaId(mediaId);
        record.setDraftAction(draftAction);
        record.setTime(Instant.now().toString());
        return record;
    }

    public static SyncRecord failed(String message) {
        SyncRecord record = new SyncRecord();
        record.setStatus(STATUS_FAILED);
        // 失败原因会持久化进任务记录、展示在文章列表并可经 MCP 工具返回给调用方：
        // 统一做一次凭据脱敏（如 WebClient 异常 message 里的 access_token / secret 查询参数）
        record.setMessage(message == null || message.isBlank()
            ? "同步失败，请查看服务端日志"
            : SensitiveText.mask(message));
        record.setTime(Instant.now().toString());
        return record;
    }
}
