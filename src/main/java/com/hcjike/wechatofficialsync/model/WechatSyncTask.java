package com.hcjike.wechatofficialsync.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 同步任务：每篇文章对应一条，持久化在 Halo 数据库中（自定义模型，随插件/服务重启保留）。
 *
 * <p>任务同时承担两个职责：一是作为「任务队列」保存任务输入快照（{@link WechatSyncTaskSpec#getRequest()}），
 * 使插件（或 Halo 服务）重启后可以按输入自动重放、恢复推送；二是作为同步状态的唯一数据源，
 * 供 Console 文章列表渲染状态列（{@code /status} 接口由本模型投影得到）。</p>
 *
 * <p>任务名由文章 name 确定性推导（见 {@code WechatSyncTaskStore#taskName}），同一篇文章只有一个任务，
 * 重复提交即重置同一条记录。任务落到终态（SUCCESS / FAILED）后输入快照会被清空，
 * 避免正文 HTML 长期占用数据库空间。快照只包含文章输入，不含任何微信凭据。</p>
 *
 * <p>另在 spec 上单独留一份提交时的文章标题（{@link WechatSyncTaskSpec#getPostTitle()}）：输入快照落终态
 * 后会被清空，标题独立保存才能在任务记录里长期认出「这条任务是哪篇文章」。</p>
 *
 * <p>类名与 kind 均以 {@code WechatSync} 前缀命名，避免与其他插件注册的同名模型冲突。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "api.wechat-sync.halo.run", version = "v1alpha1",
    kind = "WechatSyncTask", plural = "wechatsynctasks", singular = "wechatsynctask")
public class WechatSyncTask extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private WechatSyncTaskSpec spec;

    @Data
    @Schema(name = "WechatSyncTaskSpec")
    public static class WechatSyncTaskSpec {

        /** 归属的文章 name（Halo {@code Post} 的 {@code metadata.name}），也是状态下发的键。 */
        private String postName;

        /**
         * 提交时文章标题的留存副本（即当次同步实际使用的草稿标题，超长时已按微信上限截断），
         * 便于在任务记录里直接看出「这条任务属于哪篇文章」——文章 name 是 Halo 生成的随机串，
         * 单看它认不出文章。
         *
         * <p>与 {@link #request} 分开保存：输入快照在任务落到终态（SUCCESS / FAILED）后会被清空
         * （避免正文 HTML 长期占用数据库空间），标题留在 spec 上才不会随之丢失。</p>
         */
        private String postTitle;

        /**
         * 任务状态：{@link SyncRecord#STATUS_PENDING} / {@link SyncRecord#STATUS_SUCCESS}
         * / {@link SyncRecord#STATUS_FAILED}。
         */
        private String status;

        /** 展示给用户的说明或失败原因。 */
        private String message;

        /** 最近一次状态更新时间（ISO-8601）。 */
        private String time;

        /** 成功时的公众号草稿 media_id。 */
        private String mediaId;

        /**
         * 已开始的执行次数：提交时清零，每次开始执行（含插件重启后的自动重放）+1；
         * 用于限制中断后的自动恢复次数，避免服务反复重启时无限重放。
         */
        private Integer attempts;

        /**
         * 任务输入快照（提交时上送的标题/正文/封面等），仅用于中断后的自动重放；
         * 落到终态后清空。不包含任何微信凭据。
         */
        private SyncRequest request;
    }
}
