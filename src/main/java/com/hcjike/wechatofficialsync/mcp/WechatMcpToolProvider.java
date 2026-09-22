package com.hcjike.wechatofficialsync.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/**
 * 向 Halo MCP Server 贡献「微信公众号同步」相关工具，让 AI 助手可以：
 *
 * <ul>
 *   <li>{@value #TOOL_PREVIEW}：获取文章同步到公众号后的预览信息（美化后的正文 + 草稿元信息）；</li>
 *   <li>{@value #TOOL_SUBMIT}：预检通过后把文章提交同步到公众号草稿箱；</li>
 *   <li>{@value #TOOL_STATUS}：查询文章最近一次同步的状态（供提交后轮询结果）；</li>
 *   <li>{@value #TOOL_CLEANUP}：主动执行一次素材缓存清理，返回清理条数与生效的保留策略。</li>
 * </ul>
 *
 * <p>本类只做协议适配（参数解析、权限回调、结果与错误封装），具体流程全部复用
 * {@link WechatMcpSyncService}，因此 MCP 调用与在 Console 上点「同步到微信公众号」的行为一致。</p>
 *
 * <p>三个工具都声明了 {@code inputSchema} 与 {@code outputSchema}：MCP Server 会在调用前按
 * {@code inputSchema} 校验参数、在成功返回后按 {@code outputSchema} 校验 {@code structuredContent}
 * （失败结果用 {@code McpToolResult.error} 返回，不参与该校验），因此这里的输出字段是可依赖的契约。</p>
 *
 * <p><b>调用日志</b>：每次调用都会记录「工具名 + 脱敏后的入参摘要」，每次返回都会记录「成功摘要 / 失败
 * 错误码与原因」——长文本（如预览的正文 HTML）只记长度、不写内容，未登记的入参只记键名（{@code <已脱敏>}），
 * 因此日志里不会出现文章正文或任何凭据。</p>
 *
 * <p><b>刻意不加 {@code @Component}</b>：本类引用 MCP API 类型，只应由
 * {@link WechatMcpToolConfiguration} 在「类路径上确实存在 MCP Server API」时注册为 Bean；
 * 若直接扫描注册，未安装 MCP Server 的环境会在加载 Bean 时因缺少 API 类而报错。</p>
 *
 * <p>本地工具名 {@code snake_case} 且插件内唯一；插件前缀由 MCP Server 统一拼接
 * （协议名 = 编码后的插件 ID + {@code __} + 本地工具名，例如
 * {@code plugin-wechat-official-sync__wechat_sync_preview}），此处不要自行拼接。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class WechatMcpToolProvider implements McpToolProvider {

    /** 工具：获取同步预览信息。 */
    static final String TOOL_PREVIEW = "wechat_sync_preview";

    /** 工具：提交同步到微信公众号。 */
    static final String TOOL_SUBMIT = "wechat_sync_submit";

    /** 工具：查询同步状态。 */
    static final String TOOL_STATUS = "wechat_sync_status";

    /** 工具：主动清理素材缓存。 */
    static final String TOOL_CLEANUP = "wechat_cache_cleanup";

    private static final Logger log = LoggerFactory.getLogger(WechatMcpToolProvider.class);

    private static final String POST_NAME = "postName";

    /**
     * 日志中允许输出取值的入参名（本插件的工具参数都不含凭据）。未登记的入参只记键名、不记取值，
     * 这样将来新增敏感参数时不会被日志带出。
     */
    private static final Set<String> LOGGABLE_ARGUMENTS = Set.of(POST_NAME);

    /** 单个日志值（入参取值 / 结果字段）的最大长度，超长只记长度。 */
    private static final int MAX_LOG_VALUE_LENGTH = 120;

    /** 整条结果摘要的最大长度。 */
    private static final int MAX_LOG_SUMMARY_LENGTH = 500;

    /** 日志中只记长度、绝不输出取值的结构化结果字段（正文类内容体）。 */
    private static final Set<String> REDACTED_RESULT_FIELDS = Set.of("content");

    private final WechatMcpSyncService mcpSyncService;

    private final AuthenticationTrustResolver authTrustResolver = new AuthenticationTrustResolverImpl();

    public WechatMcpToolProvider(WechatMcpSyncService mcpSyncService) {
        this.mcpSyncService = mcpSyncService;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(previewTool(), submitTool(), statusTool(), cacheCleanupTool());
    }

    /** 获取预览信息：按与同步一致的规则美化正文，并返回上传后将使用的草稿元信息。 */
    private McpToolDefinition previewTool() {
        return McpToolDefinition.builder()
            .name(TOOL_PREVIEW)
            .title("获取微信同步预览")
            .description("生成 Halo 文章同步到微信公众号后的预览信息，包括：美化后的正文 HTML、"
                + "上传后实际使用的标题（微信上限 64 字）、摘要（120 字）、作者（8 字）、"
                + "原文链接（阅读原文）与留言设置，以及因超过微信长度上限被截断的字段名。"
                + "不调用微信接口、不写入任何数据，可反复调用。")
            .displayTitle("获取微信同步预览")
            .displayDescription("按同步规则生成文章的微信图文预览与草稿元信息，不调用微信接口、不提交任务。")
            .inputSchema(postNameSchema("要预览的 Halo 文章 metadata.name（文章列表中的文章标识）"))
            .outputSchema(previewOutputSchema())
            // 只读：不产生任何副作用
            .annotations(McpToolAnnotations.readOnly("获取微信同步预览"))
            .permission(this::authenticated)
            .handler(invocation -> handle(TOOL_PREVIEW, invocation, preview(invocation)))
            .build();
    }

    /** 提交同步：先做提交前预检，通过后创建后台同步任务并立即返回。 */
    private McpToolDefinition submitTool() {
        return McpToolDefinition.builder()
            .name(TOOL_SUBMIT)
            .title("提交同步到微信")
            .description("把 Halo 文章提交同步到微信公众号草稿箱：先做提交前预检（微信配置是否可用、"
                + "文章是否设置了封面、文章是否正在同步中），通过后创建后台同步任务并立即返回。"
                + "同步过程中封面与正文图片会自动转存到微信素材库，正文按公众号排版规则美化，"
                + "完成后可在公众号草稿箱看到草稿。同一篇文章在「同步中」时重复提交会被拒绝。")
            .displayTitle("提交同步到微信")
            .displayDescription("预检通过后创建后台同步任务，把文章同步到微信公众号草稿箱。")
            .inputSchema(postNameSchema("要同步的 Halo 文章 metadata.name（文章列表中的文章标识）"))
            .outputSchema(submitOutputSchema())
            // 写操作：向外部系统（微信公众号）创建草稿，按保守策略标注
            .annotations(McpToolAnnotations.defaults("提交同步到微信"))
            .permission(this::authenticated)
            .handler(invocation -> handle(TOOL_SUBMIT, invocation, submit(invocation)))
            .build();
    }

    /** 查询同步状态：提交同步后据此轮询结果（同步是后台异步执行的）。 */
    private McpToolDefinition statusTool() {
        return McpToolDefinition.builder()
            .name(TOOL_STATUS)
            .title("查询微信同步状态")
            .description("查询某篇 Halo 文章最近一次同步到微信公众号的状态："
                + "PENDING（已提交、正在后台执行）、SUCCESS（已写入公众号草稿箱）、"
                + "FAILED（同步失败，message 为微信返回的失败原因）、NONE（该文章尚未同步过）。"
                + "提交同步后可用本工具轮询结果。只读，不调用微信接口、不写入任何数据。")
            .displayTitle("查询微信同步状态")
            .displayDescription("查询文章最近一次同步到公众号的状态与失败原因，不调用微信接口。")
            .inputSchema(postNameSchema(
                "要查询同步状态的 Halo 文章 metadata.name（文章列表中的文章标识）"))
            .outputSchema(statusOutputSchema())
            // 只读：只读取任务记录，不产生任何副作用
            .annotations(McpToolAnnotations.readOnly("查询微信同步状态"))
            .permission(this::authenticated)
            .handler(invocation -> handle(TOOL_STATUS, invocation, status(invocation)))
            .build();
    }

    /** 清理素材缓存：立即执行一次缓存清理，返回清理条数与当前缓存配置。 */
    private McpToolDefinition cacheCleanupTool() {
        return McpToolDefinition.builder()
            .name(TOOL_CLEANUP)
            .title("清理素材缓存")
            .description("立即执行一次微信公众号素材缓存清理，返回本次清理条数与生效的保留策略"
                + "（删除条数、清理后记录数、保留天数 / never、判定时间）。"
                + "缓存记录的是「图片指纹 → 已上传到微信的素材」，用于避免同一张图重复上传、"
                + "挤占微信素材库；清理只删除「超过保留期、且最近未被使用」的记录，"
                + "仍在被复用的记录不会被误删（保留策略设为「全部保留」时不做任何删除）。"
                + "本工具不影响每天 0 点自动执行的清理计划。")
            .displayTitle("清理素材缓存")
            .displayDescription("立即清理超过保留期且最近未被使用的素材缓存记录，并返回清理条数与保留策略。")
            .inputSchema(noArgumentSchema())
            .outputSchema(cacheCleanupOutputSchema())
            // 会删除缓存记录，按保守策略标注为写操作
            .annotations(McpToolAnnotations.defaults("清理素材缓存"))
            .permission(this::authenticated)
            .handler(invocation -> handle(TOOL_CLEANUP, invocation, cleanupCache()))
            .build();
    }

    /** 参数：文章名称（三个按文章操作的工具共用同一份 schema）。 */
    private static Map<String, Object> postNameSchema(String description) {
        return Map.of(
            "type", "object",
            "properties", Map.of(POST_NAME, Map.of(
                "type", "string",
                "minLength", 1,
                "description", description)),
            "required", List.of(POST_NAME),
            "additionalProperties", false);
    }

    /** 无参数工具的输入结构：不接受任何参数（MCP 仍要求 inputSchema 是 JSON object）。 */
    private static Map<String, Object> noArgumentSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);
    }

    /**
     * 预览工具的输出结构：与 {@link WechatMcpSyncService#preview} 返回的字段一一对应
     * （MCP Server 会按该 Schema 校验成功结果的 {@code structuredContent}，声明后即成为稳定契约；
     * 这里不限制 {@code additionalProperties}，便于后续在不破坏契约的前提下补充字段）。
     */
    private static Map<String, Object> previewOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "content", Map.of("type", "string",
                    "description", "美化后的正文 HTML（提交到微信草稿后的大致效果）"),
                "title", Map.of("type", "string",
                    "description", "上传后实际使用的标题（已按微信 64 字上限截断）"),
                "digest", Map.of("type", "string",
                    "description", "上传后实际使用的摘要（120 字上限），空表示未填写摘要"),
                "author", Map.of("type", "string",
                    "description", "上传后实际使用的作者（8 字上限），空表示未设置"),
                "sourceUrl", Map.of("type", "string",
                    "description", "草稿「阅读原文」链接，空表示不会生成该入口"),
                "commentMode", Map.of("type", "string",
                    "description", "留言设置：close（关闭）/ all（所有人可留言）/ fans（仅关注的人可留言）"),
                "truncatedFields", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "因超过微信长度上限被自动截断的字段名：title / author / digest")),
            "required", List.of("content", "title", "digest", "author", "sourceUrl",
                "commentMode", "truncatedFields"));
    }

    /** 提交工具的输出结构：与 {@link WechatMcpSyncService#submit} 返回的字段一一对应。 */
    private static Map<String, Object> submitOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "postName", Map.of("type", "string",
                    "description", "文章 metadata.name"),
                "title", Map.of("type", "string",
                    "description", "本次提交同步的文章标题"),
                "status", Map.of("type", "string",
                    "description", "任务状态：PENDING 表示任务已落库、正在后台执行"),
                "message", Map.of("type", "string",
                    "description", "提交结果说明")),
            "required", List.of("postName", "title", "status", "message"));
    }

    /**
     * 状态查询工具的输出结构：与 {@link WechatMcpSyncService#status} 返回的字段一一对应。
     * 四个字段在任何情况下都存在——尚未同步过时 {@code status} 为 {@code NONE}、{@code time} 为空串，
     * 因此 {@code required} 不会因「还没同步」而校验失败。
     */
    private static Map<String, Object> statusOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "postName", Map.of("type", "string",
                    "description", "文章 metadata.name"),
                "status", Map.of("type", "string",
                    "description", "同步状态：PENDING（同步中）/ SUCCESS（已写入公众号草稿箱）"
                        + " / FAILED（同步失败）/ NONE（尚未同步过）"),
                "message", Map.of("type", "string",
                    "description", "状态说明；FAILED 时为微信返回的失败原因"),
                "time", Map.of("type", "string",
                    "description", "状态更新时间（ISO-8601），尚未同步过时为空串")),
            "required", List.of("postName", "status", "message", "time"));
    }

    /**
     * 缓存清理工具的输出结构：与 {@link WechatMcpSyncService#cleanupCache()} 返回的字段一一对应。
     * 四个字段在任何情况下都存在——「全部保留」时 {@code deletedRecords} 为 0、{@code cutoff} 为空串，
     * 因此 {@code required} 不会因保留策略不同而校验失败。
     */
    private static Map<String, Object> cacheCleanupOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "deletedRecords", Map.of("type", "integer",
                    "description", "本次清理删除的缓存记录数"),
                "remainingRecords", Map.of("type", "integer",
                    "description", "清理后缓存库中的记录总数"),
                "retentionDays", Map.of("type", "string",
                    "description", "生效的缓存保留策略：天数（如 \"30\"）或 \"never\"（全部保留、不删除）"),
                "cutoff", Map.of("type", "string",
                    "description", "本次判定时间（早于该时间未使用的记录被删除，ISO-8601）；全部保留时为空串")),
            "required", List.of("deletedRecords", "remainingRecords", "retentionDays", "cutoff"));
    }

    /** 获取预览信息：返回美化后的正文与草稿元信息。 */
    private Mono<McpToolResult> preview(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.preview(postName)
            .map(result -> McpToolResult.success(result,
                "已生成《" + result.get("title") + "》的同步预览")));
    }

    /** 提交同步：预检通过后创建后台同步任务。 */
    private Mono<McpToolResult> submit(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.submit(postName)
            .map(result -> McpToolResult.success(result,
                "已提交《" + result.get("title") + "》的同步任务，正在后台执行")));
    }

    /** 查询同步状态：返回该文章最近一次同步的状态与说明。 */
    private Mono<McpToolResult> status(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.status(postName)
            .map(result -> McpToolResult.success(result,
                "《" + result.get("postName") + "》最近一次同步状态：" + result.get("status"))));
    }

    /** 清理素材缓存：删除超过保留期且最近未被使用的缓存记录，返回清理条数与当前缓存配置。 */
    private Mono<McpToolResult> cleanupCache() {
        return mcpSyncService.cleanupCache()
            .map(result -> McpToolResult.success(result,
                "缓存清理完成：删除 " + result.get("deletedRecords") + " 条，剩余 "
                    + result.get("remainingRecords") + " 条"));
    }

    /** 取出并校验 {@value #POST_NAME} 后执行工具逻辑，三个工具共用同一段参数校验。 */
    private Mono<McpToolResult> withPostName(McpToolInvocation invocation,
        Function<String, Mono<McpToolResult>> action) {
        String postName = postNameArgument(invocation);
        if (postName.isBlank()) {
            return Mono.error(new WechatMcpSyncException(
                WechatMcpSyncException.CODE_INVALID_ARGUMENT, POST_NAME + " 不能为空"));
        }
        return action.apply(postName);
    }

    /**
     * 把执行结果统一封装成 MCP 工具结果：预期内失败（{@link WechatMcpSyncException}）按其稳定错误码
     * 返回，其余异常兜底为 {@code INTERNAL_ERROR}；两种情况下结果都带 {@code error=true}，
     * 不会让异常穿透成「Provider 故障」。
     *
     * <p>同时记录调用与返回日志（见 {@link #describeArguments(McpToolInvocation)} 与
     * {@link #describeResult(McpToolResult)} 的脱敏规则）：入口记「工具名 + 脱敏后的入参」，
     * 出口记「成功摘要 / 失败错误码与原因」，正文等内容体只记长度、不写内容。</p>
     */
    private Mono<McpToolResult> handle(String toolName, McpToolInvocation invocation,
        Mono<McpToolResult> result) {
        log.info("MCP 工具调用：{}，入参 {}", toolName, describeArguments(invocation));
        return result
            .onErrorResume(WechatMcpSyncException.class,
                error -> Mono.just(McpToolResult.error(error.code(), error.getMessage())))
            .onErrorResume(error -> {
                log.error("MCP 工具执行异常：{}，原因：{}", toolName, error.getMessage(), error);
                return Mono.just(McpToolResult.error("INTERNAL_ERROR",
                    error.getMessage() == null ? "执行失败，请查看服务端日志" : error.getMessage()));
            })
            .doOnNext(outcome -> log.info("MCP 工具返回：{}，{}", toolName, describeResult(outcome)));
    }

    /**
     * 入参摘要（脱敏）：仅 {@link #LOGGABLE_ARGUMENTS} 中登记过的参数输出取值（并限制单值长度），
     * 其余参数只输出键名与 {@code <已脱敏>}——即便将来新增了凭据类参数，也不会被写进日志。
     */
    static String describeArguments(McpToolInvocation invocation) {
        Map<?, ?> arguments = invocation.arguments();
        if (arguments.isEmpty()) {
            return "无参数";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> entry : arguments.entrySet()) {
            String key = String.valueOf(entry.getKey());
            parts.add(LOGGABLE_ARGUMENTS.contains(key)
                ? key + "=" + abbreviate(String.valueOf(entry.getValue()), MAX_LOG_VALUE_LENGTH)
                : key + "=<已脱敏>");
        }
        return String.join(", ", parts);
    }

    /**
     * 结果摘要（脱敏）：失败只记错误码与原因（来自插件自身的说明或微信原始 errmsg，不含任何凭据）；
     * 成功逐字段输出，长文本（如美化后的正文 HTML）只记长度、不写内容。
     */
    static String describeResult(McpToolResult outcome) {
        if (outcome.error()) {
            return "失败，" + abbreviate(outcome.textContent(), MAX_LOG_SUMMARY_LENGTH);
        }
        Map<?, ?> structured = outcome.structuredContent();
        if (structured == null || structured.isEmpty()) {
            return "成功（无结构化结果）";
        }
        List<String> parts = new ArrayList<>();
        structured.forEach((key, value) -> parts.add(String.valueOf(key) + "="
            + (REDACTED_RESULT_FIELDS.contains(String.valueOf(key))
                ? lengthOnly(value)
                : describeValue(value))));
        return "成功，" + abbreviate(String.join(", ", parts), MAX_LOG_SUMMARY_LENGTH);
    }

    /** 单个结果字段的日志取值：短文本原样输出，长文本只输出长度。 */
    private static String describeValue(Object value) {
        if (value instanceof String text && text.length() > MAX_LOG_VALUE_LENGTH) {
            return lengthOnly(value);
        }
        return String.valueOf(value);
    }

    /** 正文类字段的日志取值：只记长度，内容体一律不写日志。 */
    private static String lengthOnly(Object value) {
        return value instanceof String text ? "<长度 " + text.length() + " 的文本>" : String.valueOf(value);
    }

    /** 截断过长的日志文本，避免单条日志被长内容刷屏。 */
    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "<空>";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…（已截断）";
    }

    /**
     * 权限回调：要求调用方已认证（MCP 访问密钥归属某个 Halo 用户）、且不是匿名身份。
     *
     * <p>访问密钥自身的工具白名单由 MCP Server 在校验本回调之前执行，因此这里只兜底拦住匿名调用；
     * 更细粒度的授权可通过「角色 - 微信公众号同步 - 发布到微信公众号」权限在密钥侧控制。</p>
     */
    private Mono<Boolean> authenticated(McpToolInvocation invocation) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(auth -> authTrustResolver.isAuthenticated(auth) && !authTrustResolver.isAnonymous(auth))
            .defaultIfEmpty(false);
    }

    /** 读取 {@value #POST_NAME} 参数：缺失或类型不符一律按空串处理，由各工具自行报告「参数不合法」。 */
    private static String postNameArgument(McpToolInvocation invocation) {
        Object value = invocation.arguments().get(POST_NAME);
        return value instanceof String text ? text.trim() : "";
    }
}
