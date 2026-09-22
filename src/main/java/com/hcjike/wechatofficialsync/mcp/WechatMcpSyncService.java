package com.hcjike.wechatofficialsync.mcp;

import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.service.WechatCacheCleanupService;
import com.hcjike.wechatofficialsync.service.WechatSyncService;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskRunner;
import com.hcjike.wechatofficialsync.service.WechatSyncTaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * MCP 工具的同步能力入口：把「文章 name」翻译成与 Console 完全一致的同步请求，
 * 再复用 Console 的预览 / 提交 / 状态查询流程。
 *
 * <ul>
 *   <li>{@link #preview(String)}：与 {@code POST .../preview} 同一套规则，生成美化后的正文与
 *   草稿元信息（标题 / 摘要 / 作者 / 原文链接 / 留言设置），不调用微信接口、不落库；</li>
 *   <li>{@link #submit(String)}：与 {@code POST .../sync} 同一条路径——先做提交前预检，
 *   通过后落库任务（含输入快照）并交给 {@link WechatSyncTaskRunner} 在后台异步执行；</li>
 *   <li>{@link #status(String)}：与 {@code GET .../status} 同一份任务记录投影，返回该文章最近一次
 *   同步的状态与说明，供提交后轮询结果；</li>
 *   <li>{@link #cleanupCache()}：主动执行一次素材缓存清理，返回清理条数与生效的保留策略。</li>
 * </ul>
 *
 * <p>文章字段的取值口径与 Console 前端一致（见 {@code ui/src/utils/syncToWechat.ts}）：
 * 标题取 {@code spec.title}、摘要取 {@code spec.excerpt.raw}（去除首尾空白）、封面取
 * {@code spec.cover}、作者取文章所属用户的显示名、原文链接取 {@code status.permalink}；
 * 正文取文章头部快照渲染后的 HTML（缺失时回退编辑器原始内容）。</p>
 *
 * <p>本类不引用任何 MCP API 类型，未安装 MCP Server 时同样是一枚普通的插件 Bean。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatMcpSyncService {

    private static final Logger log = LoggerFactory.getLogger(WechatMcpSyncService.class);

    /** 状态查询：该文章尚未同步过（正常结果，不是错误）。 */
    static final String STATUS_NONE = "NONE";

    private final ReactiveExtensionClient client;

    private final PostContentService postContentService;

    private final ReactiveSettingFetcher settingFetcher;

    private final WechatSyncService syncService;

    private final WechatSyncTaskStore taskStore;

    private final WechatSyncTaskRunner taskRunner;

    private final WechatCacheCleanupService cacheCleanupService;

    public WechatMcpSyncService(ReactiveExtensionClient client, PostContentService postContentService,
        ReactiveSettingFetcher settingFetcher, WechatSyncService syncService,
        WechatSyncTaskStore taskStore, WechatSyncTaskRunner taskRunner,
        WechatCacheCleanupService cacheCleanupService) {
        this.client = client;
        this.postContentService = postContentService;
        this.settingFetcher = settingFetcher;
        this.syncService = syncService;
        this.taskStore = taskStore;
        this.taskRunner = taskRunner;
        this.cacheCleanupService = cacheCleanupService;
    }

    /**
     * 主动执行一次素材缓存清理，并返回清理条数与生效的保留策略。
     *
     * <p>复用每天 0 点计划任务的同一套规则（见 {@link WechatCacheCleanupService#cleanupNow()}）：
     * 只删除「超过保留期、且最近未被使用」的缓存记录——某张图只要还会被同步命中，它的使用时间就会
     * 刷新，不会被误删；「全部保留」时不做删除。缓存记录的是「图片指纹 → 已上传到微信的素材」，
     * 用于避免同一张图重复上传、挤占微信素材库。</p>
     */
    public Mono<Map<String, Object>> cleanupCache() {
        return cacheCleanupService.cleanupNow().map(WechatMcpSyncService::cleanupResult);
    }

    /** 缓存清理结果：清理条数 + 生效的保留策略与判定时间。 */
    private static Map<String, Object> cleanupResult(WechatCacheCleanupService.CleanupResult outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedRecords", outcome.deletedRecords());
        result.put("remainingRecords", outcome.remainingRecords());
        result.put("retentionDays", outcome.retentionDays());
        result.put("cutoff", outcome.cutoff());
        log.info("MCP 工具已执行缓存清理：删除 {} 条，剩余 {} 条（保留策略：{}）",
            outcome.deletedRecords(), outcome.remainingRecords(), outcome.retentionDays());
        return result;
    }

    /**
     * 生成文章的「同步预览」：美化后的正文 HTML 与上传后实际使用的草稿元信息。
     * 不调用微信接口、不写任务记录，可反复调用。
     */
    public Mono<Map<String, Object>> preview(String postName) {
        return buildRequest(postName)
            .flatMap(request -> settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
                // 未配置公众号信息时用空配置：预览仍可用（作者回退文章作者、留言按关闭展示）
                .defaultIfEmpty(new WechatSetting())
                .flatMap(setting -> settingFetcher.fetch(BeautifySetting.GROUP, BeautifySetting.class)
                    // 未配置「正文美化」分组时用内置默认值，保证与真实同步的美化结果一致
                    .defaultIfEmpty(new BeautifySetting())
                    .flatMap(beautify -> syncService.preview(request, setting, beautify))));
    }

    /**
     * 提交同步任务：重复提交检查 → 提交前预检 → 落库待执行任务 → 后台异步执行，立即返回。
     *
     * <p>与 Console 的 {@code POST .../sync} 行为一致：同一篇文章「同步中」时返回冲突错误；
     * 预检不通过（微信配置、封面图等）时不落库、直接报告问题；实际同步在后台线程执行，
     * 结果写入任务记录（文章列表状态列 / 公众号草稿箱可查）。</p>
     */
    public Mono<Map<String, Object>> submit(String postName) {
        return buildRequest(postName)
            .flatMap(request -> {
                // 统一用文章规范化的 name（去空白后与 Halo 中的一致）作为任务键
                String name = request.getPostName();
                return taskStore.findStatusMap()
                    .flatMap(records -> {
                        if (isSyncing(records, name)) {
                            return Mono.error(new WechatMcpSyncException(
                                WechatMcpSyncException.CODE_CONFLICT, "该文章正在同步中，请等待完成后再试"));
                        }
                        return settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
                            .switchIfEmpty(Mono.error(new WechatMcpSyncException(
                                WechatMcpSyncException.CODE_PRECONDITION_FAILED,
                                "插件尚未配置微信公众号信息，请先在插件设置中配置 AppID / AppSecret")))
                            .flatMap(setting -> syncService.validate(request, setting)
                                .flatMap(errors -> errors.isEmpty()
                                    ? saveAndStart(name, request)
                                    : Mono.error(new WechatMcpSyncException(
                                        WechatMcpSyncException.CODE_PRECONDITION_FAILED,
                                        String.join("；", errors)))));
                    });
            });
    }

    /**
     * 查询文章「最近一次同步到微信公众号」的状态，供提交后轮询结果。
     *
     * <p>返回 {@code status} 取值：{@code PENDING}（已提交、正在后台执行）、{@code SUCCESS}
     * （已写入公众号草稿箱）、{@code FAILED}（失败，{@code message} 为失败原因）、
     * {@code NONE}（该文章尚未同步过——这是正常结果而非错误）。只读查询：不调用微信接口、
     * 不写任何记录；文章不存在时按 {@code NOT_FOUND} 报错。</p>
     */
    public Mono<Map<String, Object>> status(String postName) {
        return requirePost(postName)
            .flatMap(post -> {
                String name = post.getMetadata().getName();
                return taskStore.findStatusMap().map(records -> statusResult(name, records.get(name)));
            });
    }

    /** 状态查询结果：未同步过返回 {@value #STATUS_NONE}，避免把「还没同步」当成失败。 */
    private static Map<String, Object> statusResult(String postName, SyncRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postName", postName);
        if (record == null) {
            result.put("status", STATUS_NONE);
            result.put("message", "该文章尚未同步过");
            result.put("time", "");
            return result;
        }
        result.put("status", nullToEmpty(record.getStatus()));
        result.put("message", nullToEmpty(record.getMessage()));
        result.put("time", nullToEmpty(record.getTime()));
        return result;
    }

    /** 先落库任务（含输入快照）再异步执行，调用方无需等待，与 Console 提交接口同一顺序。 */
    private Mono<Map<String, Object>> saveAndStart(String postName, SyncRequest request) {
        return taskStore.savePending(postName, request)
            .then(Mono.fromRunnable(() -> taskRunner.start(postName, request)))
            .thenReturn(submitted(postName, request));
    }

    /** 提交成功的返回值：文章标识、草稿标题与任务状态，供 MCP 客户端确认与后续查询。 */
    private static Map<String, Object> submitted(String postName, SyncRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postName", postName);
        result.put("title", request.getTitle() == null ? "" : request.getTitle());
        result.put("status", SyncRecord.STATUS_PENDING);
        result.put("message", "同步任务已提交，正在后台执行；完成后可在公众号草稿箱查看草稿");
        log.info("MCP 工具已提交同步任务：文章《{}》，postName={}", request.getTitle(), postName);
        return result;
    }

    /** 该文章当前是否有进行中的同步任务（与 Console 的重复提交检查同一规则）。 */
    private static boolean isSyncing(Map<String, SyncRecord> records, String postName) {
        SyncRecord existing = records.get(postName);
        return existing != null && SyncRecord.STATUS_PENDING.equals(existing.getStatus());
    }

    /**
     * 按文章 name 构造与 Console 预览 / 同步完全一致的请求体。
     *
     * <p>正文取文章头部快照渲染后的 HTML（与 Console 调用的 {@code fetchPostHeadContent} 同源），
     * 缺失时回退编辑器原始内容；两者都为空视为「没有可同步的正文」，直接报预检失败，
     * 避免把空正文交给微信换来难以理解的 {@code 44004}。</p>
     */
    private Mono<SyncRequest> buildRequest(String postName) {
        return requirePost(postName)
            .flatMap(post -> withAuthor(post)
                .flatMap(request -> postContentService.getHeadContent(request.getPostName())
                    .map(WechatMcpSyncService::contentOf)
                    .defaultIfEmpty("")
                    .flatMap(content -> {
                        if (content.isBlank()) {
                            return Mono.error(new WechatMcpSyncException(
                                WechatMcpSyncException.CODE_PRECONDITION_FAILED,
                                "文章《" + request.getTitle() + "》正文为空，没有可同步的内容"));
                        }
                        request.setContent(content);
                        return Mono.just(request);
                    })));
    }

    /**
     * 校验文章名称并确认文章存在：名称为空按 {@code INVALID_ARGUMENT}、文章不存在按 {@code NOT_FOUND}
     * 中断。预览 / 提交 / 状态查询共用同一入口，保证三种工具对「文章标识」的校验语义一致。
     */
    private Mono<Post> requirePost(String postName) {
        if (postName == null || postName.isBlank()) {
            return Mono.error(new WechatMcpSyncException(
                WechatMcpSyncException.CODE_INVALID_ARGUMENT, "postName 不能为空"));
        }
        String name = postName.trim();
        return client.fetch(Post.class, name)
            .switchIfEmpty(Mono.error(new WechatMcpSyncException(
                WechatMcpSyncException.CODE_NOT_FOUND, "未找到文章：" + name)));
    }

    /** 渲染内容 → 正文 HTML：优先用渲染结果，为空时回退编辑器原始内容（与 Console 一致）。 */
    private static String contentOf(ContentWrapper wrapper) {
        if (wrapper == null) {
            return "";
        }
        String content = wrapper.getContent();
        if (content == null || content.isBlank()) {
            content = wrapper.getRaw();
        }
        return content == null ? "" : content;
    }

    /** 补齐作者：取文章所属用户的显示名（插件设置的「默认作者」非空时以它为准）。 */
    private Mono<SyncRequest> withAuthor(Post post) {
        SyncRequest request = toRequest(post);
        String owner = post.getSpec() == null ? null : post.getSpec().getOwner();
        if (owner == null || owner.isBlank()) {
            return Mono.just(request);
        }
        return client.fetch(User.class, owner)
            // 用户不存在（数据异常）时保持空作者：不影响同步，插件设置里配置了「默认作者」仍会生效
            .doOnNext(user -> request.setAuthor(user.getSpec().getDisplayName()))
            .thenReturn(request);
    }

    /** 文章模型 → 同步请求（字段取值口径与 Console 前端完全一致）。 */
    private static SyncRequest toRequest(Post post) {
        SyncRequest request = new SyncRequest();
        request.setPostName(post.getMetadata().getName());
        Post.PostSpec spec = post.getSpec();
        request.setTitle(spec == null ? "" : nullToEmpty(spec.getTitle()));
        // 摘要：原样上送并仅去除首尾空白，截断交由服务端按微信计字规则统一处理
        Post.Excerpt excerpt = spec == null ? null : spec.getExcerpt();
        request.setDigest(excerpt == null ? "" : nullToEmpty(excerpt.getRaw()).trim());
        request.setCover(spec == null ? "" : nullToEmpty(spec.getCover()));
        // 文章路由（如 /archives/xxx），服务端与站点「外部访问地址」拼为草稿的「原文链接」
        Post.PostStatus status = post.getStatus();
        request.setPermalink(status == null ? "" : nullToEmpty(status.getPermalink()));
        return request;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
