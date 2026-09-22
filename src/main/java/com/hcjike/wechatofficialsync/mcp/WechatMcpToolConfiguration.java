package com.hcjike.wechatofficialsync.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具的可选注册：只有当运行环境里<b>确实存在</b> MCP Server 插件的 API 类时，
 * 才把 {@link WechatMcpToolProvider} 注册成 Bean，向 MCP Server 贡献工具。
 *
 * <p>条件必须写成<b>类名字符串</b>（{@code @ConditionalOnClass(name = "...")}）：
 * 未安装 MCP Server 时，本插件的运行时类路径里没有 {@code McpToolProvider} 这个类，
 * 直接以 {@code .class} 字面量引用会让插件在启动阶段加载失败。</p>
 *
 * <p>未安装 MCP Server 时本类被整体跳过：插件照常安装、启动与使用，Console 的一键同步不受影响；
 * 安装并启用 MCP Server 后，两个工具会出现在「工具 → MCP 服务」的工具目录中，
 * 需要管理员在访问密钥里勾选后才会对该密钥生效。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class WechatMcpToolConfiguration {

    @Bean
    public WechatMcpToolProvider wechatMcpToolProvider(WechatMcpSyncService mcpSyncService) {
        return new WechatMcpToolProvider(mcpSyncService);
    }
}
