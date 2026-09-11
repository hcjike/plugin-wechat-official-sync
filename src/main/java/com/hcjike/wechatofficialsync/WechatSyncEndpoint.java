package com.hcjike.wechatofficialsync;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 同步相关接口。最终访问路径为
 * {@code POST /apis/api.wechat-sync.halo.run/v1alpha1/sync}（提交同步）、
 * {@code POST /apis/api.wechat-sync.halo.run/v1alpha1/validate}（同步前预检）与
 * {@code POST /apis/api.wechat-sync.halo.run/v1alpha1/preview}（预览美化效果与草稿元信息）。
 *
 * <p>提交同步的请求立即返回 {@code 202 Accepted}，实际同步在后台异步执行；预检与预览均同步返回，
 * 不提交任何任务、不写同步记录。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatSyncEndpoint implements CustomEndpoint {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncEndpoint.class);

    private final ReactiveSettingFetcher settingFetcher;

    private final WechatSyncService syncService;

    private final WechatSyncRecordStore recordStore;

    public WechatSyncEndpoint(ReactiveSettingFetcher settingFetcher, WechatSyncService syncService,
        WechatSyncRecordStore recordStore) {
        this.settingFetcher = settingFetcher;
        this.syncService = syncService;
        this.recordStore = recordStore;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/sync", this::sync)
            .POST("/validate", this::validate)
            .POST("/preview", this::preview)
            .GET("/status", this::status)
            .build();
    }

    private Mono<ServerResponse> sync(ServerRequest request) {
        return request.bodyToMono(SyncRequest.class)
            .flatMap(body -> {
                String postName = body.getPostName() == null ? "" : body.getPostName();
                log.info("收到同步请求：文章《{}》，postName={}", body.getTitle(), postName);
                // 同一文章已有进行中的同步任务时拒绝重复提交（409）：
                // 避免重复上传素材、重复建草稿，以及两个任务的状态互相覆盖
                return recordStore.findAll()
                    .flatMap(records -> {
                        if (isSyncing(records, postName)) {
                            log.info("文章《{}》正在同步中，忽略本次重复提交", body.getTitle());
                            return ServerResponse.status(HttpStatus.CONFLICT)
                                .bodyValue(Map.of("message", "该文章正在同步中，请等待完成后再试"));
                        }
                        return doSync(body, postName);
                    });
            });
    }

    /**
     * 通过重复提交检查后的提交流程：先落库「同步中」，再异步执行，接口立即返回。
     */
    private Mono<ServerResponse> doSync(SyncRequest body, String postName) {
        return settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
            .flatMap(setting -> settingFetcher
                .fetch(BeautifySetting.GROUP, BeautifySetting.class)
                // 未配置「正文美化」分组时用内置默认值，保证美化不中断
                .defaultIfEmpty(new BeautifySetting())
                .flatMap(beautify -> recordStore.save(postName, SyncRecord.pending())
                    // 先落库「同步中」，再异步执行，接口立即返回，不阻塞 Console 请求
                    .then(Mono.fromRunnable(() -> startAsync(body, setting, beautify)))
                    .then(ServerResponse.accepted()
                        .bodyValue(Map.of(
                            "message", "同步任务已提交",
                            "postName", postName)))))
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("文章《{}》同步被拒绝：插件尚未配置微信公众号信息", body.getTitle());
                return recordStore
                    .save(postName, SyncRecord.failed("插件尚未配置微信公众号信息"))
                    .then(ServerResponse.badRequest()
                        .bodyValue(Map.of(
                            "message", "请先在插件设置中配置 AppID / AppSecret")));
            }));
    }

    /**
     * 同步前预检接口：返回 {@code {"errors": ["...", ...]}}——「提交前即可发现」的已知错误
     * （微信配置缺失 AppID / AppSecret、封面图缺失或无法解析、该文章正在同步中）的汇总，
     * 空数组表示校验通过、可继续进入预览 / 同步流程。
     *
     * <p>不调用微信接口、不写同步记录（校验规则见 {@link WechatSyncService#validate}）；
     * Console 在点击「同步到微信公众号」时先调用本接口，有问题直接提示并中止后续流程。</p>
     */
    private Mono<ServerResponse> validate(ServerRequest request) {
        return request.bodyToMono(SyncRequest.class)
            .flatMap(body -> recordStore.findAll()
                .flatMap(records -> {
                    if (isSyncing(records, body.getPostName())) {
                        return okErrors(List.of("该文章正在同步中，请等待完成后再试"));
                    }
                    return settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
                        // 未配置公众号信息时用空配置：由校验逻辑给出「尚未配置」的明确提示
                        .defaultIfEmpty(new WechatSetting())
                        .flatMap(setting -> syncService.validate(body, setting).flatMap(this::okErrors));
                }));
    }

    /** 统一返回校验问题列表（空数组表示校验通过）。 */
    private Mono<ServerResponse> okErrors(List<String> errors) {
        return ServerResponse.ok().bodyValue(Map.of("errors", errors));
    }

    /** 该文章当前是否有进行中的同步任务（重复提交前与预检时用于提前提示）。 */
    private static boolean isSyncing(Map<String, SyncRecord> records, String postName) {
        SyncRecord existing = records.get(postName == null ? "" : postName);
        return existing != null && SyncRecord.STATUS_PENDING.equals(existing.getStatus());
    }

    /**
     * 预览接口：返回「提交后」的正文效果（美化后的内联样式 HTML）与草稿元信息
     * （作者、原文链接、留言设置），供 Console 在确认同步前展示。
     *
     * <p>按与同步流程一致的规则解析（见 {@link WechatSyncService#preview}）：不校验公众号凭据、
     * 不调用微信接口、不写同步记录；未配置公众号信息时也允许预览（作者回退文章作者、
     * 留言按关闭展示），保证预览路径不被配置缺失阻断。</p>
     */
    private Mono<ServerResponse> preview(ServerRequest request) {
        return request.bodyToMono(SyncRequest.class)
            .flatMap(body -> settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
                // 未配置公众号信息时用空配置，保证预览可用
                .defaultIfEmpty(new WechatSetting())
                .flatMap(setting -> settingFetcher.fetch(BeautifySetting.GROUP, BeautifySetting.class)
                    // 未配置「正文美化」分组时用内置默认值，保证预览与真实同步的美化结果一致
                    .defaultIfEmpty(new BeautifySetting())
                    .flatMap(beautify -> syncService.preview(body, setting, beautify)
                        .flatMap(result -> ServerResponse.ok().bodyValue(result)))));
    }

    /**
     * 返回全部文章的同步状态记录，键为文章 name，供 Console 列表渲染状态图标。
     */
    private Mono<ServerResponse> status(ServerRequest request) {
        return recordStore.findAll()
            .flatMap(records -> ServerResponse.ok().bodyValue(records));
    }

    /**
     * 在后台线程执行同步，并将最终结果（成功/失败）写回状态存储。
     */
    private void startAsync(SyncRequest body, WechatSetting setting, BeautifySetting beautify) {
        String postName = body.getPostName() == null ? "" : body.getPostName();
        syncService.submit(body, setting, beautify)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                mediaId -> {
                    log.info("文章《{}》已同步到公众号草稿箱，media_id={}", body.getTitle(), mediaId);
                    recordStore.save(postName, SyncRecord.success(mediaId)).subscribe();
                },
                error -> {
                    log.error("文章《{}》同步到公众号失败：{}", body.getTitle(), error.getMessage(), error);
                    recordStore.save(postName, SyncRecord.failed(error.getMessage())).subscribe();
                });
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.wechat-sync.halo.run/v1alpha1");
    }
}
