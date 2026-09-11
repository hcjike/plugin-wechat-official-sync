package com.hcjike.wechatofficialsync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatOfficialSyncPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(WechatOfficialSyncPlugin.class);

    private final WechatSyncRecordStore recordStore;

    public WechatOfficialSyncPlugin(PluginContext pluginContext, WechatSyncRecordStore recordStore) {
        super(pluginContext);
        this.recordStore = recordStore;
    }

    @Override
    public void start() {
        log.info("微信公众号同步插件启动成功");
        // 插件（或 Halo 服务）重启后，此前进行中的同步任务已随进程终止、不会再写回最终状态；
        // 这里把遗留的「同步中」记录标记为失败，避免文章状态永远停留在「同步中」
        recordStore.failAllPending("同步任务因插件重启中断，请重新同步")
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    @Override
    public void stop() {
        log.info("微信公众号同步插件已停止");
    }
}
