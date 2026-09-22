package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.app.plugin.ReactiveSettingFetcher;
import tools.jackson.databind.JsonNode;

/**
 * 素材缓存的清理计划任务：按设置的 cron 定时删除「保留期之外、最近没再被使用」的缓存记录，
 * 避免缓存库随同步无限增长。
 *
 * <p><b>保留期按「最近一次使用时间」计算</b>（见 {@link WechatMediaCacheStore#purgeUnusedSince}）：
 * 某张图只要还会被同步命中，计时就会刷新，因此被清掉的都是确实已不再使用的缓存——不会出现
 * 「图片还在用、缓存却被删掉，下次同步又上传一遍」的情况。</p>
 *
 * <p><b>配置即时生效</b>：cron 变化时由 {@link PluginConfigUpdatedEvent}（Halo 在插件配置变更时发布到
 * 插件自己的 ApplicationContext）触发重排，无需重启插件；保留天数在<b>每次执行时</b>重新读取，
 * 改完立刻按新保留期清理。cron 留空或填写非法时回退到
 * {@link WechatSetting#DEFAULT_CACHE_CLEANUP_CRON}（每天凌晨 2 点），并记日志告警。</p>
 *
 * <p><b>线程与生命周期</b>：自带一个单线程调度器，由插件在 {@code start()} / {@code stop()} 中显式启停。
 * 停止时必须关掉它：调度线程若跨插件生命周期存活，会牵住插件的类加载器，导致热重载/卸载后旧类无法回收。
 * 清理执行体在调度线程（普通线程，非 Reactor 事件循环线程）上运行，因此可以直接阻塞等待响应式读写。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Service
public class WechatCacheCleanupService {

    private static final Logger log = LoggerFactory.getLogger(WechatCacheCleanupService.class);

    /** 未配置（或取值非法）保留天数时使用的默认值（天）。 */
    static final int DEFAULT_RETENTION_DAYS = 30;

    /** 在调度线程上等待「读取设置 / 清理数据库」完成的上限。 */
    private static final Duration REACTIVE_TIMEOUT = Duration.ofSeconds(30);

    private final WechatMediaCacheStore store;

    private final ReactiveSettingFetcher settingFetcher;

    /** 调度线程池；{@code null} 表示未启动（或已停止）。 */
    private volatile ThreadPoolTaskScheduler scheduler;

    /** 当前已注册的计划任务。 */
    private volatile ScheduledFuture<?> scheduledTask;

    /** 当前已生效的 cron 表达式，用于判断设置变更后是否需要重排。 */
    private volatile String scheduledCron;

    public WechatCacheCleanupService(WechatMediaCacheStore store,
        ReactiveSettingFetcher settingFetcher) {
        this.store = store;
        this.settingFetcher = settingFetcher;
    }

    /**
     * 启动计划任务（插件启动时调用）：建调度线程池并按当前配置注册 cron 任务。
     * 注册需先读取设置，故为异步且失败只告警——计划任务起不来不该影响插件启动与文章同步。
     */
    public void start() {
        // 插件重载等场景可能重复调用 start()：先释放上一次的调度线程池，避免线程泄漏
        stop();
        ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("wechat-cache-cleanup-");
        // 守护线程且不等待任务收尾：插件卸载/热重载时不能留下调度线程（会牵住插件类加载器）
        created.setDaemon(true);
        created.setRemoveOnCancelPolicy(true);
        created.initialize();
        scheduler = created;
        reschedule("插件启动");
    }

    /** 停止计划任务并关闭调度线程池（插件停止/卸载时调用）。 */
    public void stop() {
        ScheduledFuture<?> task = scheduledTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduledTask = null;
        scheduledCron = null;
        ThreadPoolTaskScheduler current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
            log.info("缓存清理计划任务已停止");
        }
    }

    /**
     * 插件设置变更后按新配置重排计划任务：cron 改了要按新表达式重新注册
     * （保留天数每次执行时读取，无需重排）。插件已停止时忽略该事件。
     *
     * <p><b>方法必须声明事件参数</b>：Spring 要求 {@code @EventListener} 的监听方法带事件参数，
     * 无参形式会在注册监听器时直接抛 {@code BeanInitializationException}（编译期与直接调用的单测都
     * 发现不了，见 {@code WechatCacheCleanupServiceTest#springAcceptsTheEventListenerSignature}）。</p>
     *
     * <p>不区分设置分组：任何变更都重排一次，cron 未变时 {@link #applyCron} 会直接返回，不会重复注册。</p>
     */
    @EventListener
    public void onConfigUpdated(PluginConfigUpdatedEvent event) {
        if (scheduler == null) {
            return;
        }
        Map<String, JsonNode> values = event == null ? null : event.getNewSettingValues();
        if (log.isDebugEnabled()) {
            log.debug("插件设置已更新（{} 个分组），检查缓存清理计划任务是否需要重排",
                values == null ? 0 : values.size());
        }
        reschedule("插件设置已更新");
    }

    /** 读取当前设置并（在 cron 表达式变化时）重新注册计划任务。 */
    private void reschedule(String reason) {
        settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
            .map(WechatSetting::getCacheCleanupCron)
            .defaultIfEmpty("")
            // 读设置失败也按默认计划任务注册：一次瞬时失败不该让清理任务永久缺失
            .onErrorResume(e -> {
                log.warn("读取插件设置失败，缓存清理计划任务按默认 cron 处理：{}", e.getMessage());
                return Mono.just("");
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(cron -> applyCron(cron, reason),
                error -> log.warn("注册缓存清理计划任务失败：{}", error.getMessage()));
    }

    /**
     * 按给定表达式注册（或重排）计划任务；表达式与当前已生效的一致时保持原注册不动
     * （避免每次保存设置都重排一次）。
     */
    void applyCron(String configured, String reason) {
        ThreadPoolTaskScheduler current = scheduler;
        if (current == null) {
            return;
        }
        String cron = resolveCron(configured);
        if (cron.equals(scheduledCron)) {
            return;
        }
        ScheduledFuture<?> previous = scheduledTask;
        if (previous != null) {
            previous.cancel(false);
        }
        try {
            scheduledTask = current.schedule(this::runCleanup, new CronTrigger(cron));
        } catch (RuntimeException e) {
            // 调度器已在关闭途中（如插件停止与设置变更同时发生）：此时注册失败可忽略，下次启动会重新注册
            log.warn("缓存清理计划任务注册失败（cron「{}」，调度器可能已关闭）：{}", cron, e.getMessage());
            return;
        }
        scheduledCron = cron;
        log.info("缓存清理计划任务已注册（{}）：cron「{}」，保留天数在每次执行时读取", reason, cron);
    }

    /**
     * 执行一次缓存清理：读取当前保留天数，删除早于该保留期、且最近未被使用的缓存记录。
     *
     * <p>在调度线程上运行（普通线程而非 Reactor 事件循环线程），故可直接阻塞等待响应式读取；
     * 任何异常都只记日志，避免一次失败让后续计划任务停摆。</p>
     */
    void runCleanup() {
        try {
            WechatSetting setting = settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
                .block(REACTIVE_TIMEOUT);
            Integer retentionDays = resolveRetentionDays(setting);
            if (retentionDays == null) {
                log.info("缓存保留策略为「全部保留」，跳过本次缓存清理");
                return;
            }
            long cutoff = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
            Long deleted = store.purgeUnusedSince(cutoff).block(REACTIVE_TIMEOUT);
            log.info("缓存清理完成：删除 {} 条超过 {} 天未使用的缓存记录", deleted == null ? 0L : deleted,
                retentionDays);
        } catch (Exception e) {
            log.warn("缓存清理执行失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 解析缓存保留天数。
     *
     * @param setting 当前插件设置，可为 {@code null}
     * @return 保留天数；{@code null} 表示「全部保留」（永久保留）
     */
    static Integer resolveRetentionDays(WechatSetting setting) {
        String configured = setting == null ? null : setting.getCacheRetentionDays();
        if (configured == null || configured.isBlank()) {
            return DEFAULT_RETENTION_DAYS;
        }
        String value = configured.trim();
        if (WechatSetting.CACHE_RETENTION_NEVER.equalsIgnoreCase(value)) {
            return null;
        }
        try {
            int days = Integer.parseInt(value);
            if (days > 0) {
                return days;
            }
            log.warn("缓存保留天数「{}」不是正数，按默认 {} 天处理", configured, DEFAULT_RETENTION_DAYS);
        } catch (NumberFormatException e) {
            log.warn("缓存保留天数「{}」无法识别，按默认 {} 天处理", configured, DEFAULT_RETENTION_DAYS);
        }
        return DEFAULT_RETENTION_DAYS;
    }

    /**
     * 解析真正生效的 cron 表达式：留空用默认值；兼容 crontab 的 5 位写法（分 时 日 月 周，自动补上
     * 「秒」位），避免用户照抄 crontab 后被判为非法；其余无法解析的值告警并回退到默认值。
     */
    static String resolveCron(String configured) {
        String cron = configured == null ? "" : configured.trim();
        if (cron.isEmpty()) {
            return WechatSetting.DEFAULT_CACHE_CLEANUP_CRON;
        }
        if (cron.split("\\s+").length == 5) {
            cron = "0 " + cron;
        }
        try {
            CronExpression.parse(cron);
            return cron;
        } catch (IllegalArgumentException e) {
            log.warn("缓存清理计划任务的 cron 表达式「{}」非法，改用默认值「{}」：{}", configured,
                WechatSetting.DEFAULT_CACHE_CLEANUP_CRON, e.getMessage());
            return WechatSetting.DEFAULT_CACHE_CLEANUP_CRON;
        }
    }
}
