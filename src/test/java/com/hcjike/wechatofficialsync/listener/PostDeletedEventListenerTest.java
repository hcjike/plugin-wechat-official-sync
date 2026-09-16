package com.hcjike.wechatofficialsync.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.extension.Metadata;

/**
 * {@link PostDeletedEventListener} 的行为验证：文章被永久删除时按文章 name 清理同步记录；
 * 文章 name 缺失时不做删除。
 */
class PostDeletedEventListenerTest {

    @Test
    void deletesRecordOnPostDeleted() {
        WechatSyncTaskStore taskStore = mock(WechatSyncTaskStore.class);
        when(taskStore.delete("post-a")).thenReturn(Mono.empty());

        new PostDeletedEventListener(taskStore).onPostDeleted(event("post-a")).block();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(taskStore).delete(captor.capture());
        assertThat(captor.getValue()).isEqualTo("post-a");
    }

    @Test
    void skipsWhenPostNameBlank() {
        WechatSyncTaskStore taskStore = mock(WechatSyncTaskStore.class);

        new PostDeletedEventListener(taskStore).onPostDeleted(event("")).block();

        verify(taskStore, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    private PostDeletedEvent event(String postName) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(postName);
        post.setMetadata(metadata);
        return new PostDeletedEvent(this, post);
    }
}
