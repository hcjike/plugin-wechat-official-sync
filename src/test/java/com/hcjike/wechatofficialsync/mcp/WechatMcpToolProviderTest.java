package com.hcjike.wechatofficialsync.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolResult;

/**
 * {@link WechatMcpToolProvider} 的行为验证：贡献的工具名与入参 schema 合法（MCP Server 对工具定义
 * 有格式与长度约束）、只读 / 写操作标注、未认证时权限回调拒绝，以及 Handler 把业务结果与预期内失败
 * 正确封装成 {@code McpToolResult}。
 */
class WechatMcpToolProviderTest {

    private final WechatMcpSyncService mcpSyncService = mock(WechatMcpSyncService.class);

    private final WechatMcpToolProvider provider = new WechatMcpToolProvider(mcpSyncService);

    @Test
    void contributesFourToolsWithValidNamesAndObjectSchema() {
        List<McpToolDefinition> tools = provider.tools().collectList().block();

        assertThat(tools).isNotNull();
        assertThat(tools).extracting(McpToolDefinition::name)
            .containsExactly(WechatMcpToolProvider.TOOL_PREVIEW, WechatMcpToolProvider.TOOL_SUBMIT,
                WechatMcpToolProvider.TOOL_STATUS, WechatMcpToolProvider.TOOL_CLEANUP);
        for (McpToolDefinition tool : tools) {
            // 工具名必须是 MCP Server 要求的 snake_case 本地名（≤63 字符，字母开头）
            assertThat(tool.name()).matches("[a-z][a-z0-9_]*");
            assertThat(tool.title()).isNotBlank();
            assertThat(tool.description()).isNotBlank();
            // 管理界面文案（中文）与面向 Agent 的协议描述分开维护
            assertThat(tool.displayTitle()).isNotBlank();
            assertThat(tool.displayDescription()).isNotBlank();
            // inputSchema / outputSchema 都必须是非空 properties 的 JSON object
            assertThat(tool.inputSchema()).containsEntry("type", "object");
            assertThat(tool.inputSchema().get("properties")).isInstanceOf(Map.class);
            assertThat(tool.outputSchema()).containsEntry("type", "object");
            assertThat(tool.outputSchema().get("properties")).isInstanceOf(Map.class);
        }
    }

    @Test
    void requiresPostNameForArticleToolsAndNoArgumentForCacheCleanup() {
        // 三个按文章操作的工具都要求 postName
        for (String toolName : List.of(WechatMcpToolProvider.TOOL_PREVIEW,
            WechatMcpToolProvider.TOOL_SUBMIT, WechatMcpToolProvider.TOOL_STATUS)) {
            assertThat(toolByName(toolName).inputSchema().get("required")).isEqualTo(List.of("postName"));
        }
        // 缓存清理不需要任何参数，但仍须声明为 JSON object 且不接受额外参数
        McpToolDefinition cleanup = toolByName(WechatMcpToolProvider.TOOL_CLEANUP);
        assertThat(cleanup.inputSchema().get("required")).isNull();
        assertThat(cleanup.inputSchema().get("properties")).isEqualTo(Map.of());
        assertThat(cleanup.inputSchema()).containsEntry("additionalProperties", false);
    }

    @Test
    void declaresOutputSchemaMatchingPreviewResultFields() {
        assertThat(requiredKeys(WechatMcpToolProvider.TOOL_PREVIEW))
            .containsExactlyInAnyOrder("content", "title", "digest", "author", "sourceUrl",
                "commentMode", "truncatedFields");
    }

    @Test
    void declaresOutputSchemaMatchingSubmitResultFields() {
        assertThat(requiredKeys(WechatMcpToolProvider.TOOL_SUBMIT))
            .containsExactlyInAnyOrder("postName", "title", "status", "message");
    }

    @Test
    void declaresOutputSchemaMatchingStatusResultFields() {
        assertThat(requiredKeys(WechatMcpToolProvider.TOOL_STATUS))
            .containsExactlyInAnyOrder("postName", "status", "message", "time");
    }

    @Test
    void declaresOutputSchemaMatchingCacheCleanupResultFields() {
        assertThat(requiredKeys(WechatMcpToolProvider.TOOL_CLEANUP))
            .containsExactlyInAnyOrder("deletedRecords", "remainingRecords", "retentionDays", "cutoff");
    }

    @Test
    void cacheCleanupHandlerReturnsCountsAndRetention() {
        when(mcpSyncService.cleanupCache()).thenReturn(Mono.just(Map.of(
            "deletedRecords", 3L,
            "remainingRecords", 12L,
            "retentionDays", "30",
            "cutoff", "2026-08-23T10:00:00Z")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_CLEANUP, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("deletedRecords", 3L);
        assertThat(result.structuredContent()).containsEntry("remainingRecords", 12L);
        assertThat(result.structuredContent()).containsEntry("retentionDays", "30");
        assertThat(result.textContent()).contains("删除 3 条").contains("剩余 12 条");
        verify(mcpSyncService).cleanupCache();
    }

    @Test
    void statusResultCoversDeclaredOutputSchema() {
        when(mcpSyncService.status("post-a")).thenReturn(Mono.just(Map.of(
            "postName", "post-a",
            "status", "NONE",
            "message", "该文章尚未同步过",
            "time", "")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_STATUS, "post-a");

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("status", "NONE");
        for (String key : requiredKeys(WechatMcpToolProvider.TOOL_STATUS)) {
            assertThat(result.structuredContent().containsKey(key)).isTrue();
        }
        verify(mcpSyncService).status("post-a");
    }

    @Test
    void previewResultCoversDeclaredOutputSchema() {
        when(mcpSyncService.preview("post-a")).thenReturn(Mono.just(Map.of(
            "content", "<p>正文</p>",
            "title", "文章标题",
            "digest", "",
            "author", "宏尘极客",
            "sourceUrl", "https://example.com/archives/post-a",
            "commentMode", "close",
            "truncatedFields", List.of())));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_PREVIEW, "post-a");

        // 返回值的字段必须覆盖 outputSchema 声明的必填字段，否则 MCP Server 会判为 INVALID_TOOL_RESULT
        for (String key : requiredKeys(WechatMcpToolProvider.TOOL_PREVIEW)) {
            assertThat(result.structuredContent().containsKey(key)).isTrue();
        }
    }

    @Test
    void marksPreviewAndStatusAsReadOnlyAndWriteToolsAsNot() {
        assertThat(toolByName(WechatMcpToolProvider.TOOL_PREVIEW).annotations().readOnlyHint()).isTrue();
        assertThat(toolByName(WechatMcpToolProvider.TOOL_STATUS).annotations().readOnlyHint()).isTrue();
        // 提交同步与清理缓存都会改动数据，按保守策略标注为非只读
        assertThat(toolByName(WechatMcpToolProvider.TOOL_SUBMIT).annotations().readOnlyHint()).isFalse();
        assertThat(toolByName(WechatMcpToolProvider.TOOL_CLEANUP).annotations().readOnlyHint()).isFalse();
    }

    @Test
    void permissionCallbackRejectsAnonymousCallers() {
        // 无安全上下文（未认证）时权限回调返回 false，工具不可调用
        Object allowed = toolByName(WechatMcpToolProvider.TOOL_PREVIEW).permission()
            .check(invocation(Map.of()))
            .block();

        assertThat(allowed).isEqualTo(false);
    }

    @Test
    void previewHandlerReturnsStructuredContentAndSummary() {
        when(mcpSyncService.preview("post-a"))
            .thenReturn(Mono.just(Map.of("title", "文章标题", "content", "<p>正文</p>")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_PREVIEW, "post-a");

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("title", "文章标题");
        assertThat(result.textContent()).contains("文章标题");
    }

    @Test
    void submitHandlerDelegatesToSubmitFlow() {
        when(mcpSyncService.submit("post-a")).thenReturn(Mono.just(Map.of(
            "postName", "post-a",
            "title", "文章标题",
            "status", "PENDING")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_SUBMIT, "post-a");

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent()).containsEntry("status", "PENDING");
        verify(mcpSyncService).submit("post-a");
    }

    @Test
    void mapsBlankArgumentToInvalidArgumentError() {
        McpToolResult result = execute(WechatMcpToolProvider.TOOL_SUBMIT, "   ");

        assertThat(result.error()).isTrue();
        assertThat(result.textContent()).isEqualTo("INVALID_ARGUMENT: postName 不能为空");
        verify(mcpSyncService, never()).submit(anyString());
    }

    @Test
    void mapsPrecheckFailureToPreconditionFailedError() {
        when(mcpSyncService.submit("post-a")).thenReturn(Mono.error(new WechatMcpSyncException(
            WechatMcpSyncException.CODE_PRECONDITION_FAILED, "当前文章未设置封面图")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_SUBMIT, "post-a");

        assertThat(result.error()).isTrue();
        assertThat(result.structuredContent())
            .containsEntry("error", Map.of(
                "code", WechatMcpSyncException.CODE_PRECONDITION_FAILED,
                "message", "当前文章未设置封面图"));
    }

    @Test
    void mapsUnexpectedFailureToInternalError() {
        when(mcpSyncService.preview("post-a")).thenReturn(Mono.error(new IllegalStateException("boom")));

        McpToolResult result = execute(WechatMcpToolProvider.TOOL_PREVIEW, "post-a");

        assertThat(result.error()).isTrue();
        assertThat(result.textContent()).startsWith("INTERNAL_ERROR");
    }

    @Test
    void logsPostNameButMasksUnregisteredArguments() {
        String summary = WechatMcpToolProvider.describeArguments(new McpToolInvocation("t",
            Map.of("postName", "post-a", "accessToken", "SECRET-TOKEN")));

        assertThat(summary).contains("postName=post-a");
        // 未登记的入参只记键名：将来新增凭据类参数也不会被日志带出
        assertThat(summary).contains("accessToken=<已脱敏>");
        assertThat(summary).doesNotContain("SECRET-TOKEN");
    }

    @Test
    void logsNoArgumentsForParameterlessTool() {
        assertThat(WechatMcpToolProvider.describeArguments(new McpToolInvocation("t", Map.of())))
            .isEqualTo("无参数");
    }

    @Test
    void truncatesOverlongArgumentValues() {
        String overlongName = "a".repeat(300);

        String summary = WechatMcpToolProvider.describeArguments(
            new McpToolInvocation("t", Map.of("postName", overlongName)));

        assertThat(summary).doesNotContain(overlongName).contains("已截断");
    }

    @Test
    void logsResultFieldsButNeverLogsContentBody() {
        String longHtml = "<p>" + "正文".repeat(200) + "</p>";
        String shortHtml = "<p>正文</p>";

        String longSummary = WechatMcpToolProvider.describeResult(McpToolResult.success(
            Map.of("content", longHtml, "title", "文章标题", "truncatedFields", List.of())));
        String shortSummary = WechatMcpToolProvider.describeResult(
            McpToolResult.success(Map.of("content", shortHtml, "title", "文章标题")));

        // 正文类字段（content）无论长短都只记长度，内容体不进日志；短标量字段照常记录便于排查
        assertThat(longSummary).doesNotContain(longHtml)
            .contains("content=<长度 " + longHtml.length() + " 的文本>")
            .contains("title=文章标题");
        assertThat(shortSummary).doesNotContain(shortHtml)
            .contains("content=<长度 " + shortHtml.length() + " 的文本>");
    }

    @Test
    void truncatesOverlongResultSummary() {
        // 每个字段都不超过单值上限，但字段多到整条摘要超长：整体截断，避免单条日志过大
        String summary = WechatMcpToolProvider.describeResult(McpToolResult.success(Map.of(
            "a", "y".repeat(100),
            "b", "y".repeat(100),
            "c", "y".repeat(100),
            "d", "y".repeat(100),
            "e", "y".repeat(100),
            "f", "y".repeat(100))));

        assertThat(summary).contains("已截断");
        assertThat(summary.length()).isLessThan(600);
    }

    @Test
    void logsErrorResultWithCodeAndReason() {
        String summary = WechatMcpToolProvider.describeResult(
            McpToolResult.error("NOT_FOUND", "未找到文章：post-x"));

        assertThat(summary).contains("失败").contains("NOT_FOUND").contains("未找到文章：post-x");
    }

    /** 执行指定工具并取回结果（工具 Handler 与 MCP 调用方一样只按参数调用，权限由回调单独校验）。 */
    private McpToolResult execute(String toolName, String postName) {
        return execute(toolName, Map.of("postName", postName));
    }

    /** 按显式参数执行指定工具（无参数的工具传空 Map）。 */
    private McpToolResult execute(String toolName, Map<String, Object> arguments) {
        Object result = toolByName(toolName).handler()
            .execute(invocation(arguments))
            .block();
        assertThat(result).isInstanceOf(McpToolResult.class);
        return (McpToolResult) result;
    }

    /** 读取工具 outputSchema 声明的必填字段名。 */
    @SuppressWarnings("unchecked")
    private List<String> requiredKeys(String toolName) {
        Object required = toolByName(toolName).outputSchema().get("required");
        assertThat(required).isInstanceOf(List.class);
        return (List<String>) required;
    }

    private McpToolDefinition toolByName(String name) {
        List<McpToolDefinition> tools = provider.tools().collectList().block();
        assertThat(tools).isNotNull();
        return tools.stream()
            .filter(tool -> name.equals(tool.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到工具：" + name));
    }

    private static McpToolInvocation invocation(Map<String, Object> arguments) {
        return new McpToolInvocation(WechatMcpToolProvider.TOOL_PREVIEW, arguments);
    }
}
