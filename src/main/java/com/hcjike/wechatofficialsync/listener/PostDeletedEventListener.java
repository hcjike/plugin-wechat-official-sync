package com.hcjike.wechatofficialsync.listener;

import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.event.post.PostDeletedEvent;

/**
 * 文章生命周期监听：文章在 Halo 中被<b>永久删除</b>时（{@link PostDeletedEvent} 由 Halo 在文章
 * 真正从数据库移除时发布；移入回收站的软删除不会触发），同步清理该文章的同步记录。
 *
 * <p>{@code PostDeletedEvent} 继承自 {@code PostEvent}，带有 {@code @SharedEvent} 注解，故插件可在
 * 自身 Spring 上下文中通过响应式 {@link EventListener} 收到 Halo 主上下文发布的事件。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class PostDeletedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PostDeletedEventListener.class);

    private final WechatSyncTaskStore taskStore;

    public PostDeletedEventListener(WechatSyncTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /**
     * 文章被永久删除时删除其同步记录：任务名由文章 name 确定性推导，故按 {@code event.getName()}
     * 即可定位对应记录。Halo 调谐循环会在一次永久删除中多次发布该事件，故本日志为 debug 级；
     * 实际清理只在首次生效（见 {@link WechatSyncTaskStore#delete} 已标记删除时跳过），后续重复事件为无操作。
     */
    @EventListener
    public Mono<Void> onPostDeleted(PostDeletedEvent event) {
        String postName = event.getName();
        if (postName == null || postName.isBlank()) {
            return Mono.empty();
        }
        log.debug("检测到文章 [{}] 被永久删除，清理其微信同步记录", postName);
        return taskStore.delete(postName);
    }
}
