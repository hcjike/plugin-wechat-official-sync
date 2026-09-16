package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.client.WechatApiException;
import com.hcjike.wechatofficialsync.config.BeautifySetting;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import com.hcjike.wechatofficialsync.model.SyncRecord;
import com.hcjike.wechatofficialsync.model.SyncRequest;
import com.hcjike.wechatofficialsync.model.WechatSyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 同步任务执行器：接口提交与插件重启后的自动重放共用同一执行入口。
 *
 * <p>执行前按当前配置实时读取微信配置与正文美化设置（任务记录里只保存文章输入快照，
 * 不落任何凭据）；执行完成（成功/失败）后把终态写回任务记录。</p>
 *
 * <p><b>恢复语义</b>：进程内的响应式任务无法断点续传，插件（或 Halo 服务）重启后的「恢复」
 * 是用持久化的输入从头重放整个同步流程，语义等价于自动帮用户重试了一次；若中断恰好发生在
 * 草稿创建成功与状态写回之间，重放会多出一份草稿。为防服务反复重启导致无限重放，
 * 单任务总执行次数上限为 {@value #MAX_ATTEMPTS} 次（首次提交 + 中断后的自动恢复），
 * 达到上限仍被中断的任务不再自动恢复，需手动重新同步。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatSyncTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatSyncTaskRunner.class);

    /** 任务允许的最大执行次数（首次提交 + 中断后的自动重放），超过后不再自动恢复。 */
    static final int MAX_ATTEMPTS = 3;

    private final WechatSyncService syncService;

    private final WechatSyncTaskStore taskStore;

    private final ReactiveSettingFetcher settingFetcher;

    public WechatSyncTaskRunner(WechatSyncService syncService, WechatSyncTaskStore taskStore,
        ReactiveSettingFetcher settingFetcher) {
        this.syncService = syncService;
        this.taskStore = taskStore;
        this.settingFetcher = settingFetcher;
    }

    /**
     * 提交执行：接口调用方无需等待，实际执行在后台线程（boundedElastic），不阻塞 Console 请求。
     */
    public void start(String postName, SyncRequest request) {
        runTask(postName, request, null)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(unused -> { },
                error -> log.error("同步任务 [{}] 执行异常：{}", postName, error.getMessage(), error));
    }

    /**
     * 插件启动时调用：把因插件（或 Halo 服务）重启而中断的未完成任务用持久化输入自动重放，
     * 实现「重启后恢复推送」。记录缺失输入快照或已达最大执行次数的任务不再重放、直接落到失败态。
     */
    public Mono<Void> resumeInterrupted() {
        return taskStore.findPending()
            .flatMapMany(Flux::fromIterable)
            .concatMap(this::resumeOne)
            .then();
    }

    /**
     * 执行一次任务链（包级可见便于测试）：记录尝试次数 → 读取最新设置 → 执行同步 → 写回终态。
     *
     * @param runningMessage 非空时替换任务的状态说明（如自动恢复时的提示）
     */
    Mono<Void> runTask(String postName, SyncRequest request, String runningMessage) {
        return taskStore.startAttempt(postName, runningMessage)
            .flatMap(attempts -> {
                if (attempts == 0) {
                    // 任务记录已不存在（例如被人工删除）：不再执行
                    log.warn("同步任务 [{}] 记录不存在，跳过执行", postName);
                    return Mono.empty();
                }
                if (attempts > MAX_ATTEMPTS) {
                    return taskStore.complete(postName,
                        SyncRecord.failed("同步任务多次因中断未能完成，已停止自动恢复，请手动重新同步"));
                }
                return execute(postName, request);
            });
    }

    /** 重放单个中断的任务；任何异常都不会中断其余任务的恢复。 */
    private Mono<Void> resumeOne(WechatSyncTask task) {
        WechatSyncTask.WechatSyncTaskSpec spec = task.getSpec();
        String postName = spec == null || spec.getPostName() == null ? "" : spec.getPostName();
        SyncRequest request = spec == null ? null : spec.getRequest();
        int attempts = spec == null || spec.getAttempts() == null ? 0 : spec.getAttempts();
        if (request == null) {
            // 无输入快照无法重放（仅出现于数据异常）：直接落到失败态，避免永远停留在「同步中」
            return taskStore.complete(postName,
                SyncRecord.failed("同步任务因插件重启中断且缺少任务数据，请重新同步"));
        }
        if (attempts >= MAX_ATTEMPTS) {
            return taskStore.complete(postName,
                SyncRecord.failed("同步任务多次因中断未能完成，已停止自动恢复，请手动重新同步"));
        }
        log.info("同步任务因插件重启中断，自动恢复：文章《{}》", request.getTitle());
        return runTask(postName, request, "同步任务因插件重启中断，正在自动恢复…")
            .onErrorResume(error -> {
                log.error("自动恢复同步任务 [{}] 失败：{}", postName, error.getMessage(), error);
                return Mono.empty();
            });
    }

    /** 按当前设置执行一次完整同步，并把结果写回任务记录。 */
    private Mono<Void> execute(String postName, SyncRequest request) {
        return settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
            .switchIfEmpty(Mono.error(new WechatApiException("插件尚未配置微信公众号信息")))
            .flatMap(setting -> settingFetcher
                .fetch(BeautifySetting.GROUP, BeautifySetting.class)
                // 未配置「正文美化」分组时用内置默认值，保证美化不中断
                .defaultIfEmpty(new BeautifySetting())
                .flatMap(beautify -> syncService.submit(request, setting, beautify)))
            .flatMap(mediaId -> {
                log.info("文章《{}》已同步到公众号草稿箱，media_id={}", request.getTitle(), mediaId);
                return taskStore.complete(postName, SyncRecord.success(mediaId));
            })
            .onErrorResume(error -> {
                log.error("文章《{}》同步到公众号失败：{}", request.getTitle(), error.getMessage(), error);
                return taskStore.complete(postName, SyncRecord.failed(error.getMessage()));
            });
    }
}
