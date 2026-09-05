package com.hcjike.wechatofficialsync;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 同步接口。最终访问路径为
 * {@code POST /apis/api.wechat-sync.halo.run/v1alpha1/sync}。
 *
 * <p>接口收到请求后立即返回 {@code 202 Accepted}，实际同步在后台异步执行。</p>
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
            .GET("/status", this::status)
            .build();
    }

    private Mono<ServerResponse> sync(ServerRequest request) {
        return request.bodyToMono(SyncRequest.class)
            .flatMap(body -> {
                String postName = body.getPostName() == null ? "" : body.getPostName();
                log.info("收到同步请求：文章《{}》，postName={}", body.getTitle(), postName);
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
            });
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
