package com.hcjike.wechatofficialsync;

import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskRunner;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
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

    private final SchemeManager schemeManager;

    private final WechatSyncTaskStore taskStore;

    private final WechatSyncTaskRunner taskRunner;

    public WechatOfficialSyncPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        WechatSyncTaskStore taskStore, WechatSyncTaskRunner taskRunner) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.taskStore = taskStore;
        this.taskRunner = taskRunner;
    }

    @Override
    public void start() {
        // 注册同步任务模型：任务（含输入快照）与状态持久化在 Halo 数据库中，插件/服务重启后仍可读取
        schemeManager.register(WechatSyncTask.class);
        log.info("微信公众号同步插件启动成功");
        // 旧版本把同步记录存放在插件 ConfigMap 中，先把存量记录迁移到任务模型（只补缺失、可重复执行）；
        // 随后把因插件（或 Halo 服务）重启而中断的同步任务用持久化输入自动重放，实现重启后恢复推送
        taskStore.migrateLegacyRecords()
            .then(taskRunner.resumeInterrupted())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(unused -> { },
                error -> log.error("恢复中断的同步任务失败：{}", error.getMessage(), error));
    }

    @Override
    public void stop() {
        // 注销模型不会删除已保存的任务数据：再次启用插件并注册模型后仍可读取
        schemeManager.unregister(Scheme.buildFromType(WechatSyncTask.class));
        log.info("微信公众号同步插件已停止");
    }
}
