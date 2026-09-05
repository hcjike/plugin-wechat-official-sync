package com.hcjike.wechatofficialsync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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

    public WechatOfficialSyncPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("微信公众号同步插件启动成功");
    }

    @Override
    public void stop() {
        log.info("微信公众号同步插件已停止");
    }
}
