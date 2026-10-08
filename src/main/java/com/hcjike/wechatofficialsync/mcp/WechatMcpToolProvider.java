package com.hcjike.wechatofficialsync.mcp;

import com.hcjike.wechatofficialsync.util.SensitiveText;
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

    /**
     * 计字口径（简短片段）：写进各字段说明，让「字段说明」自身就能解释「为什么值比文章里短」，
     * 不依赖任何跨字段的指引。完整说法见 {@link #WECHAT_LENGTH_RULE}。
     *
     * <p>与 {@code WechatSyncService#truncateToWechatLength} 的实现严格一致（公众号编辑器口径，按码点
     * 整体取舍），修改截断逻辑时须同步这里的文案。</p>
     */
    private static final String WECHAT_LENGTH_RULE_SHORT =
        "汉字 / 全角字符计 1 字、半角字符计 0.5 字、emoji 计 2 字";

    /**
     * 微信草稿字段的「计字口径 + 截断规则」完整说明，写进预览 / 提交两个工具的描述里。
     *
     * <p>截断后的字段值（{@code title} / {@code digest} / {@code author}）与文章里的原文长度不一致，
     * 若只写「微信上限 64 字」这类结果，MCP 客户端（AI 助手）无法向用户解释「为什么变短了、会不会掉字」；
     * 因此把规则一并写入描述，让客户端能自行判断与说明。</p>
     */
    private static final String WECHAT_LENGTH_RULE =
        "长度按微信口径折算：" + WECHAT_LENGTH_RULE_SHORT + "（按整字符取舍），超出上限自动截断";

    /**
     * 预览正文 HTML 的转发要求，写进工具描述、{@code content} 字段说明与预览工具的结果说明
     * （模型在调用时读到的就是结果说明那段文本，故三处都写）。
     *
     * <p>预览 HTML 的样式<b>已内联在元素上</b>（见 {@code WechatPreviewStyles}），不再依赖
     * {@code <style>} 标签；因此「原样输出」是唯一要求——一旦改写、精简、重新排版或另加样式，
     * 渲染结果就会与公众号后台预览、最终草稿不一致。</p>
     */
    private static final String CONTENT_FORWARD_RULE =
        "样式已内联在元素上，请原样输出给用户，不要改写、精简、重新排版或另加样式，否则会和后台预览不一致";

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
            .description("预览文章同步到微信公众号后的效果：正文 HTML（" + CONTENT_FORWARD_RULE + "）、"
                + "写入草稿的标题（上限 64 字）、摘要（120 字）、作者（8 字）、原文链接与留言设置，"
                + "以及被截断的字段名。截断规则：" + WECHAT_LENGTH_RULE + "。"
                + "不调用微信接口、不写数据，可反复调用。")
            .displayTitle("获取微信同步预览")
            .displayDescription("按同步规则生成文章的微信图文预览与草稿元信息，不调用微信接口、不提交任务。")
            .inputSchema(postNameSchema("要预览的文章 metadata.name"))
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
            .description("把文章提交同步到微信公众号草稿箱：先预检（微信配置、封面图、是否正在同步中），"
                + "通过后创建后台任务并立即返回；封面与正文图片会自动转存到微信素材库、正文按公众号排版美化。"
                + "标题 / 作者 / 摘要超长不会提交失败，会先截断再提交（" + WECHAT_LENGTH_RULE
                + "；可用 wechat_sync_preview 查看截断后的值）。"
                + "返回的 draftAction 说明本次是更新已有草稿（update）还是新建草稿（create），"
                + "直接据此回复用户即可。同一篇文章同步中时重复提交会被拒绝。")
            .displayTitle("提交同步到微信")
            .displayDescription("预检通过后创建后台同步任务，把文章同步到微信公众号草稿箱。")
            .inputSchema(postNameSchema("要同步的文章 metadata.name"))
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
            .description("查询文章最近一次同步到微信公众号的状态（提交同步后用它轮询）："
                + "PENDING 执行中 / SUCCESS 已写入草稿箱 / FAILED 失败（message 为原因）/ NONE 尚未同步过。"
                + "只读，不调用微信接口、不写数据。")
            .displayTitle("查询微信同步状态")
            .displayDescription("查询文章最近一次同步到公众号的状态与失败原因，不调用微信接口。")
            .inputSchema(postNameSchema("要查询状态的文章 metadata.name"))
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
            .description("立即清理一次公众号素材缓存（缓存用于避免同一张图重复上传到微信素材库）："
                + "只删除超过保留期且最近未被使用的记录，仍在复用的不会被删；插件设置里「缓存保留天数」"
                + "留空、0 或负数表示全部保留，此时不删除。不影响每天 0 点的自动清理。")
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
     *
     * <p>三个会被微信截断的字段（{@code title} / {@code digest} / {@code author}）的描述里各自写明
     * 长度上限与简短的计字口径（{@link #WECHAT_LENGTH_RULE_SHORT}），因此单看字段说明也能解释
     * 「为什么值比文章里短」；{@code truncatedFields} 列出被截断的字段名。</p>
     */
    private static Map<String, Object> previewOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "content", Map.of("type", "string",
                    "description", "正文 HTML（就是写入草稿的内容，不是 Markdown）：" + CONTENT_FORWARD_RULE
                        + "；图片与链接已是完整地址，可直接加载"),
                "title", Map.of("type", "string",
                    "description", "写入草稿的标题；微信上限 64 字，超出自动截断（"
                        + WECHAT_LENGTH_RULE_SHORT + "）"),
                "digest", Map.of("type", "string",
                    "description", "写入草稿的摘要；微信上限 120 字，超出自动截断（"
                        + WECHAT_LENGTH_RULE_SHORT + "）；为空表示未填写，微信会抓取正文前 54 个字"),
                "author", Map.of("type", "string",
                    "description", "写入草稿的作者；微信上限 8 字，超出自动截断（"
                        + WECHAT_LENGTH_RULE_SHORT + "）；为空表示未设置"),
                "sourceUrl", Map.of("type", "string",
                    "description", "草稿底部的「阅读原文」链接；为空表示不生成"),
                "commentMode", Map.of("type", "string",
                    "description", "留言设置：close 关闭 / all 所有人可留言 / fans 仅关注的人可留言"),
                "truncatedFields", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "被自动截断的字段名：title（上限 64 字）/ author（8 字）/ digest（120 字）")),
            "required", List.of("content", "title", "digest", "author", "sourceUrl",
                "commentMode", "truncatedFields"));
    }

    /**
     * 提交工具的输出结构：与 {@link WechatMcpSyncService#submit} 返回的字段一一对应。
     *
     * <p>{@code draftAction} 是<b>本次提交将要执行的草稿动作</b>（更新既有草稿 / 新建草稿），
     * 提交时已实查该文章上次写入的草稿是否还在微信侧，因此如实反映本次会做什么——
     * 客户端无需理解配置项，直接据此说明即可。</p>
     */
    private static Map<String, Object> submitOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "postName", Map.of("type", "string",
                    "description", "文章 metadata.name"),
                "title", Map.of("type", "string",
                    "description", "本次同步的文章标题"),
                "status", Map.of("type", "string",
                    "description", "PENDING 表示已提交、正在后台执行"),
                "draftAction", Map.of("type", "string",
                    "enum", List.of(WechatMcpSyncService.DRAFT_ACTION_UPDATE,
                        WechatMcpSyncService.DRAFT_ACTION_CREATE),
                    "description", "本次提交将要执行的动作（提交时已查过上次那份草稿是否还在微信侧）："
                        + "update 更新那份草稿；create 新建一份草稿（还没有草稿、草稿已被删除，"
                        + "或未开启草稿更新）。据此告诉用户本次是更新还是新建即可"),
                "message", Map.of("type", "string",
                    "description", "提交结果说明")),
            "required", List.of("postName", "title", "status", "draftAction", "message"));
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
                    "description", "PENDING 同步中 / SUCCESS 已写入草稿箱 / FAILED 失败 / NONE 尚未同步过"),
                "message", Map.of("type", "string",
                    "description", "说明；FAILED 时为微信返回的失败原因"),
                "time", Map.of("type", "string",
                    "description", "更新时间（ISO-8601）；尚未同步过时为空串")),
            "required", List.of("postName", "status", "message", "time"));
    }

    /**
     * 缓存清理工具的输出结构：与 {@link WechatMcpSyncService#cleanupCache()} 返回的字段一一对应。
     * 四个字段在任何情况下都存在——「全部保留」时 {@code deletedRecords} 为 0、{@code cutoff} 为空串、
     * {@code retentionDays} 取「全部保留」的策略标识
     * （{@link com.hcjike.wechatofficialsync.service.WechatCacheCleanupService#RETENTION_KEEP_ALL}），
     * 因此 {@code required} 不会因保留策略不同而校验失败。
     */
    private static Map<String, Object> cacheCleanupOutputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "deletedRecords", Map.of("type", "integer",
                    "description", "本次删除的记录数"),
                "remainingRecords", Map.of("type", "integer",
                    "description", "清理后剩下的记录数"),
                "retentionDays", Map.of("type", "string",
                    "description", "保留期天数（如 \"30\"）；\"never\" 表示全部保留、不删除任何记录"),
                "cutoff", Map.of("type", "string",
                    "description", "判定时间（早于它未被使用的记录会被删除，ISO-8601）；全部保留时为空串")),
            "required", List.of("deletedRecords", "remainingRecords", "retentionDays", "cutoff"));
    }

    /** 获取预览信息：返回美化后的正文与草稿元信息。 */
    private Mono<McpToolResult> preview(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.preview(postName)
            .map(result -> McpToolResult.success(result, previewSummary(result))));
    }

    /**
     * 预览结果的一句话说明。模型在调用时读到的就是这段文本，因此在「已生成」之外一并写明
     * {@link #CONTENT_FORWARD_RULE}：预览正文自带全部样式，原样输出才不会与后台预览不一致。
     */
    private static String previewSummary(Map<String, Object> result) {
        return "已生成《" + result.get("title") + "》的同步预览：请把 content 字段里的整段 HTML "
            + "原样输出给用户（" + CONTENT_FORWARD_RULE + "）";
    }

    /** 提交同步：预检通过后创建后台同步任务。 */
    private Mono<McpToolResult> submit(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.submit(postName)
            .map(result -> McpToolResult.success(result, submitSummary(result))));
    }

    /**
     * 提交结果的一句话说明。<b>把草稿动作直接写进这段文本</b>：部分客户端只把工具结果的文本喂给模型
     * （不传 {@code structuredContent}，也不会把 {@code outputSchema} 的字段说明放进上下文），
     * 只把动作放在字段里时模型看不到、只能自行猜测；写进结果文本后，模型照抄即可如实回答。
     */
    private static String submitSummary(Map<String, Object> result) {
        boolean update = WechatMcpSyncService.DRAFT_ACTION_UPDATE
            .equals(String.valueOf(result.get("draftAction")));
        return "已提交《" + result.get("title") + "》的同步任务，正在后台执行；"
            + (update
                ? "本次会更新该文章已有的那份草稿（draftAction=update），可如实告诉用户「本次会更新草稿」"
                : "本次会新建一份草稿（draftAction=create），可如实告诉用户「本次会新建草稿」");
    }

    /** 查询同步状态：返回该文章最近一次同步的状态与说明。 */
    private Mono<McpToolResult> status(McpToolInvocation invocation) {
        return withPostName(invocation, postName -> mcpSyncService.status(postName)
            .map(result -> McpToolResult.success(result, statusSummary(result))));
    }

    /** 状态查询结果的一句话说明：状态码与说明一并给出，模型无需再去结构化字段里翻。 */
    private static String statusSummary(Map<String, Object> result) {
        Object message = result.get("message");
        String detail = message == null ? "" : String.valueOf(message).trim();
        return "《" + result.get("postName") + "》最近一次同步状态：" + result.get("status")
            + (detail.isEmpty() ? "" : "（" + detail + "）");
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
            .onErrorResume(WechatMcpSyncException.class, error -> Mono.just(
                McpToolResult.error(error.code(), SensitiveText.mask(error.getMessage()))))
            .onErrorResume(error -> {
                // 返回给 MCP 调用方的错误消息同样脱敏：外部系统边界上再兜一层
                String reason = SensitiveText.mask(error.getMessage());
                log.error("MCP 工具执行异常：{}，原因：{}", toolName, reason, error);
                return Mono.just(McpToolResult.error("INTERNAL_ERROR",
                    reason.isBlank() ? "执行失败，请查看服务端日志" : reason));
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
