package com.hcjike.wechatofficialsync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import com.hcjike.wechatofficialsync.service.WechatCacheCleanupService;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskRunner;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class WechatOfficialSyncPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    WechatSyncTaskStore taskStore;

    @Mock
    WechatSyncTaskRunner taskRunner;

    @Mock
    WechatMediaCacheStore mediaCacheStore;

    @Mock
    WechatCacheCleanupService cacheCleanupService;

    @InjectMocks
    WechatOfficialSyncPlugin plugin;

    @Test
    void registersSchemeAndRecoversInterruptedTasksOnStartAndUnregistersOnStop() {
        when(taskStore.migrateLegacyRecords()).thenReturn(Mono.empty());
        when(taskRunner.resumeInterrupted()).thenReturn(Mono.empty());
        when(mediaCacheStore.initialize()).thenReturn(Mono.empty());

        plugin.start();

        // 启动时注册同步任务模型
        verify(schemeManager).register(WechatSyncTask.class);
        // 启动时同时初始化媒体缓存库（建库 + schema 升级），失败不影响同步流程
        verify(mediaCacheStore).initialize();
        // 启动时注册缓存清理计划任务
        verify(cacheCleanupService).start();
        // 恢复链在后台线程执行：先迁移旧版记录，再自动重放中断的任务
        InOrder inOrder = inOrder(taskStore, taskRunner);
        inOrder.verify(taskStore, timeout(2000)).migrateLegacyRecords();
        inOrder.verify(taskRunner, timeout(2000)).resumeInterrupted();

        plugin.stop();

        // 停止时先停计划任务（否则调度线程会牵住插件类加载器），再注销模型（不删除已保存的任务数据）
        InOrder stopOrder = inOrder(cacheCleanupService, schemeManager);
        stopOrder.verify(cacheCleanupService).stop();
        stopOrder.verify(schemeManager).unregister(any(Scheme.class));
    }
}
