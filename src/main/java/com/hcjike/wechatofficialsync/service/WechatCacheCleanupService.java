package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 素材缓存的清理计划任务：<b>固定每天 0 点</b>删除「保留期之外、最近没再被使用」的缓存记录，
 * 避免缓存库随同步无限增长。
 *
 * <p><b>保留期按「最近一次使用时间」计算</b>（见 {@link WechatMediaCacheStore#purgeUnusedSince}）：
 * 某张图只要还会被同步命中，计时就会刷新，因此被清掉的都是确实已不再使用的缓存——不会出现
 * 「图片还在用、缓存却被删掉，下次同步又上传一遍」的情况。</p>
 *
 * <p><b>保留天数仍是插件设置</b>，且在<b>每次执行时</b>读取：改完设置无需重启插件，下一次清理即按新保留期
 * 生效；留空或取值非法时按 {@link #DEFAULT_RETENTION_DAYS} 天处理，「全部保留」则不删任何记录。</p>
 *
 * <p><b>也可主动触发</b>：{@link #cleanupNow()} 立即按同一套规则执行一次清理，并把清理条数与生效的保留策略
 * 返回给调用方（MCP 工具「清理素材缓存」即调用它），不影响每天 0 点的计划任务。</p>
 *
 * <p><b>执行时刻不做成设置项</b>：清理判定只看「多少天没被使用」，跑在几点对结果没有影响（至多让缓存早一天
 * 或晚一天被回收），固定每天 0 点跑一次即可，少一个容易配错的 cron 输入。</p>
 *
 * <p><b>「0 点」按运行环境的系统时区解释</b>：注册时把 {@link ZoneId#systemDefault()} 显式交给
 * {@link CronTrigger}，因此每天 0 点指宿主机（JVM）本地时间的 0 点，而不是固定的 UTC 0 点；
 * 部署在容器里时，只要容器的系统时区设置正确，任务就会在本地零点触发。</p>
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

    /**
     * 清理计划任务的 cron 表达式：固定每天 0 点执行。
     *
     * <p>Spring 的 cron 为 6 位（秒 分 时 日 月 周），与常见 Linux crontab 的 5 位写法不同。</p>
     */
    static final String CLEANUP_CRON = "0 0 0 * * *";

    /** 在调度线程上等待「读取设置 / 清理数据库」完成的上限。 */
    private static final Duration REACTIVE_TIMEOUT = Duration.ofSeconds(30);

    private final WechatMediaCacheStore store;

    private final ReactiveSettingFetcher settingFetcher;

    /** 调度线程池；{@code null} 表示未启动（或已停止）。 */
    private volatile ThreadPoolTaskScheduler scheduler;

    /** 当前已注册的计划任务。 */
    private volatile ScheduledFuture<?> scheduledTask;

    public WechatCacheCleanupService(WechatMediaCacheStore store,
        ReactiveSettingFetcher settingFetcher) {
        this.store = store;
        this.settingFetcher = settingFetcher;
    }

    /** 启动计划任务（插件启动时调用）：建调度线程池并注册「每天 0 点」的清理任务。 */
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
        schedule(CLEANUP_CRON);
    }

    /** 停止计划任务并关闭调度线程池（插件停止/卸载时调用）。 */
    public void stop() {
        ScheduledFuture<?> task = scheduledTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduledTask = null;
        ThreadPoolTaskScheduler current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
            log.info("缓存清理计划任务已停止");
        }
    }

    /**
     * 按给定 cron 注册（或重排）计划任务；调度器未启动时直接忽略。
     *
     * <p>生产路径只传固定值 {@link #CLEANUP_CRON}，测试会传入高频表达式来观察任务确实被触发。</p>
     */
    void schedule(String cron) {
        ThreadPoolTaskScheduler current = scheduler;
        if (current == null) {
            return;
        }
        ScheduledFuture<?> previous = scheduledTask;
        if (previous != null) {
            previous.cancel(false);
        }
        // 显式绑定「当前系统时区」：cron 里的 0 点指本地时间 0 点，避免被当成 UTC（部署在容器里时尤其重要）
        ZoneId zone = ZoneId.systemDefault();
        try {
            scheduledTask = current.schedule(this::runCleanup, new CronTrigger(cron, zone));
        } catch (RuntimeException e) {
            // 调度器已在关闭途中（如插件停止）：此时注册失败可忽略，下次启动会重新注册
            log.warn("缓存清理计划任务注册失败（cron「{}」，调度器可能已关闭）：{}", cron, e.getMessage());
            return;
        }
        log.info("缓存清理计划任务已注册：cron「{}」（时区「{}」），保留天数在每次执行时读取", cron, zone.getId());
    }

    /**
     * 执行一次缓存清理：读取当前保留天数，删除早于该保留期、且最近未被使用的缓存记录。
     *
     * <p>在调度线程上运行（普通线程而非 Reactor 事件循环线程），故可直接阻塞等待响应式读取；
     * 任何异常都只记日志，避免一次失败让后续计划任务停摆。</p>
     */
    void runCleanup() {
        try {
            performCleanup().block(REACTIVE_TIMEOUT);
        } catch (Exception e) {
            log.warn("缓存清理执行失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 立即执行一次缓存清理（与计划任务同一套规则），并把结果作为返回值交给调用方——
     * MCP 工具「清理素材缓存」用它实现「主动清理 + 返回清理条数与当前缓存配置」。
     *
     * <p>与计划任务共用 {@link #performCleanup()} 的判定与删除逻辑，两条路径不会出现两套口径；
     * 异常（如读取设置失败）向上传递，由调用方决定如何处理（计划任务那条路径见
     * {@link #runCleanup()} 的兜底日志）。</p>
     */
    public Mono<CleanupResult> cleanupNow() {
        return performCleanup();
    }

    /**
     * 清理执行体：读取保留天数 → 删除「超过保留期且最近未被使用」的记录 → 汇总结果与当前缓存配置。
     * 计划任务与 MCP 工具共用本方法。
     */
    private Mono<CleanupResult> performCleanup() {
        return settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)
            // 用 Optional 承载「保留天数」：设置组缺失（如尚未保存过配置）→ 默认天数；
            // 配置为「全部保留」→ 空 Optional（不删除任何记录）。
            // 注意不能直接 map 出 null：Reactor 的 map 不允许映射为 null（会抛 NPE），
            // 而 resolveRetentionDays 正是用 null 表示「全部保留」。
            .map(setting -> Optional.ofNullable(resolveRetentionDays(setting)))
            .defaultIfEmpty(Optional.of(DEFAULT_RETENTION_DAYS))
            .flatMap(retentionDays -> retentionDays.isEmpty()
                ? skipCleanup()
                : purgeWith(retentionDays.get()));
    }

    /** 保留策略为「全部保留」：不做删除，但仍返回当前缓存配置（清理条数为 0、无判定时间）。 */
    private Mono<CleanupResult> skipCleanup() {
        log.info("缓存保留策略为「全部保留」，跳过本次缓存清理");
        return outcome(null, 0L, null);
    }

    /** 删除「最近一次使用时间」早于保留期的记录，并按保留天数汇总结果。 */
    private Mono<CleanupResult> purgeWith(int retentionDays) {
        long cutoff = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
        return store.purgeUnusedSince(cutoff).flatMap(deleted -> {
            long count = deleted == null ? 0L : deleted;
            log.info("缓存清理完成：删除 {} 条超过 {} 天未使用的缓存记录", count, retentionDays);
            return outcome(retentionDays, count, cutoff);
        });
    }

    /** 汇总清理结果：生效的保留策略、判定时间、删除条数与清理后的记录总数。 */
    private Mono<CleanupResult> outcome(Integer retentionDays, long deletedRecords, Long cutoffMillis) {
        return store.count().map(remaining -> new CleanupResult(
            retentionDays == null ? WechatSetting.CACHE_RETENTION_NEVER : String.valueOf(retentionDays),
            cutoffMillis == null ? "" : Instant.ofEpochMilli(cutoffMillis).toString(),
            deletedRecords,
            remaining));
    }

    /**
     * 一次缓存清理的结果。
     *
     * @param retentionDays   生效的保留天数；{@link WechatSetting#CACHE_RETENTION_NEVER} 表示「全部保留」，
     *                        该次不删除任何记录
     * @param cutoff          本次判定时间（早于该时间未使用的记录被删除），ISO-8601；全部保留时为空串
     * @param deletedRecords  本次删除的缓存记录数
     * @param remainingRecords 清理后缓存库中的记录总数
     */
    public record CleanupResult(String retentionDays, String cutoff, long deletedRecords,
        long remainingRecords) {
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
}
