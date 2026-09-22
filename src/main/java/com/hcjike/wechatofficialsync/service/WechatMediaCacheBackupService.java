package com.hcjike.wechatofficialsync.service;

import com.hcjike.wechatofficialsync.cache.MediaCacheBackup;
import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * 媒体缓存库的备份计划任务：<b>固定每天 1 点</b>把 SQLite 缓存库快照到
 * {@code <插件数据目录>/backups}（与库文件同级），只保留最新的 {@link MediaCacheBackup#KEEP} 份。
 *
 * <p>备份本身（一致快照 + 轮转）由 {@link MediaCacheBackup} 完成，这里只负责按时间调度与异常兜底：
 * 备份是「缓存之外的一份离线副本」，失败不影响缓存读写、更不影响文章同步，因此任何异常都只记日志，
 * 也不会让后续的计划任务停摆。</p>
 *
 * <p><b>线程与生命周期</b>：自带一个单线程调度器，由插件在 {@code start()} / {@code stop()} 中显式启停。
 * 停止时必须关掉它：调度线程若跨插件生命周期存活，会牵住插件的类加载器，导致热重载/卸载后旧类无法回收。
 * 备份执行体运行在调度线程（普通线程，非 Reactor 事件循环线程）上，因此可以直接阻塞等待建库与备份。</p>
 *
 * <p><b>「1 点」按运行环境的系统时区解释</b>：注册时把 {@link ZoneId#systemDefault()} 显式交给
 * {@link CronTrigger}，因此每天 1 点指宿主机（JVM）本地时间的 1 点，而不是固定的 UTC 1 点；
 * 部署在容器里时，只要容器的系统时区设置正确，任务就会在本地 1 点触发。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Service
public class WechatMediaCacheBackupService {

    private static final Logger log = LoggerFactory.getLogger(WechatMediaCacheBackupService.class);

    /**
     * 备份计划任务的 cron 表达式：固定每天 1 点执行。
     *
     * <p>Spring 的 cron 为 6 位（秒 分 时 日 月 周），与常见 Linux crontab 的 5 位写法不同。</p>
     */
    static final String BACKUP_CRON = "0 0 1 * * *";

    /** 在调度线程上等待「建库 / 备份」完成的上限。 */
    private static final Duration BACKUP_TIMEOUT = Duration.ofSeconds(60);

    private final WechatMediaCacheStore store;

    /** 调度线程池；{@code null} 表示未启动（或已停止）。 */
    private volatile ThreadPoolTaskScheduler scheduler;

    /** 当前已注册的计划任务。 */
    private volatile ScheduledFuture<?> scheduledTask;

    public WechatMediaCacheBackupService(WechatMediaCacheStore store) {
        this.store = store;
    }

    /** 启动计划任务（插件启动时调用）：建调度线程池并注册「每天 1 点」的备份任务。 */
    public void start() {
        // 插件重载等场景可能重复调用 start()：先释放上一次的调度线程池，避免线程泄漏
        stop();
        ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("wechat-cache-backup-");
        // 守护线程且不等待任务收尾：插件卸载/热重载时不能留下调度线程（会牵住插件类加载器）
        created.setDaemon(true);
        created.setRemoveOnCancelPolicy(true);
        created.initialize();
        scheduler = created;
        schedule(BACKUP_CRON);
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
            log.info("媒体缓存备份计划任务已停止");
        }
    }

    /**
     * 按给定 cron 注册（或重排）计划任务；调度器未启动时直接忽略。
     *
     * <p>生产路径只传固定值 {@link #BACKUP_CRON}，测试会传入高频表达式来观察任务确实被触发。</p>
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
        // 显式绑定「当前系统时区」：cron 里的 1 点指本地时间 1 点，避免被当成 UTC（部署在容器里时尤其重要）
        ZoneId zone = ZoneId.systemDefault();
        try {
            scheduledTask = current.schedule(this::runBackup, new CronTrigger(cron, zone));
        } catch (RuntimeException e) {
            // 调度器已在关闭途中（如插件停止）：此时注册失败可忽略，下次启动会重新注册
            log.warn("媒体缓存备份计划任务注册失败（cron「{}」，调度器可能已关闭）：{}", cron, e.getMessage());
            return;
        }
        log.info("媒体缓存备份计划任务已注册：cron「{}」（时区「{}」），每次备份保留最新 {} 份", cron,
            zone.getId(), MediaCacheBackup.KEEP);
    }

    /**
     * 执行一次备份：先确保缓存库已建好（不存在则建、schema 版本旧则升级），再把库快照到备份目录并轮转旧备份。
     *
     * <p>先建库是为了让备份里<b>既有数据也有表结构</b>——插件刚启动、还没同步过任何文章时，库文件可能
     * 尚未创建，此时若直接跳过就永远拿不到一份「带表结构」的备份。建库失败（如目录不可写）意味着缓存
     * 整体不可用，按无库可备份处理。</p>
     *
     * <p>在调度线程（普通线程）上运行，故可以直接阻塞等待；任何异常都只记日志。</p>
     */
    void runBackup() {
        try {
            store.initialize().block(BACKUP_TIMEOUT);
            // 备份结果（路径与清理的旧备份份数）由 MediaCacheBackup 记入日志
            new MediaCacheBackup(store.databaseFile()).backup();
        } catch (Exception e) {
            log.warn("媒体缓存库备份失败（不影响缓存与文章同步，下次计划任务会重试）：{}", e.getMessage(), e);
        }
    }
}
