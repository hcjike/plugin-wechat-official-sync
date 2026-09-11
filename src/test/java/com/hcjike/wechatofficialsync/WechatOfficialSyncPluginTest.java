package com.hcjike.wechatofficialsync;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class WechatOfficialSyncPluginTest {

    @Mock
    PluginContext context;

    @Mock
    WechatSyncRecordStore recordStore;

    @InjectMocks
    WechatOfficialSyncPlugin plugin;

    @Test
    void contextLoads() {
        // 启动时会清理遗留的「同步中」记录
        when(recordStore.failAllPending(anyString())).thenReturn(Mono.just(0));
        plugin.start();
        plugin.stop();
    }
}
