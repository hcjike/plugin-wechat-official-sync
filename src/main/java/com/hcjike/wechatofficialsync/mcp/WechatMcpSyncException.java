package com.hcjike.wechatofficialsync.mcp;

/**
 * MCP 工具调用中的「预期内失败」：参数不合法、文章不存在、文章正在同步中、提交前预检未通过等。
 *
 * <p>携带稳定的错误码（{@code INVALID_ARGUMENT} / {@code NOT_FOUND} / {@code CONFLICT} /
 * {@code PRECONDITION_FAILED}），由 {@link WechatMcpToolProvider} 统一转成
 * {@code McpToolResult.error(code, message)} 返回给调用方。</p>
 *
 * <p>之所以在本插件内定义而不是直接抛 MCP API 的 {@code McpToolException}：业务层
 * （{@link WechatMcpSyncService}）必须能在<b>未安装 MCP Server</b> 时照常被加载，
 * 不能引用只在 MCP Server 存在时才有的类型。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class WechatMcpSyncException extends RuntimeException {

    /** 参数不合法（如 postName 为空）。 */
    public static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";

    /** 目标文章不存在。 */
    public static final String CODE_NOT_FOUND = "NOT_FOUND";

    /** 与进行中的同步任务冲突（同一篇文章正在同步中）。 */
    public static final String CODE_CONFLICT = "CONFLICT";

    /** 提交前预检未通过（微信配置缺失、封面图缺失或无法解析、正文为空等）。 */
    public static final String CODE_PRECONDITION_FAILED = "PRECONDITION_FAILED";

    private final String code;

    public WechatMcpSyncException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 稳定的错误码，供 MCP 客户端程序化判断。 */
    public String code() {
        return code;
    }
}
