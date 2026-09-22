package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.cache.CachedMedia;
import com.hcjike.wechatofficialsync.cache.MediaCacheKind;
import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.support.CronExpression;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * {@link WechatCacheCleanupService} 的行为验证：清理固定挂在每天 0 点、按保留天数删除（保留期按
 * 「最近一次使用时间」算）、「全部保留」时不动数据、保留天数解析的容错，以及计划任务确实按 cron 触发与启停安全。
 *
 * <p>存储用的是真实 SQLite（每个用例一个临时库），设置读取用 mock。</p>
 */
class WechatCacheCleanupServiceTest {

    private static final String APP_ID = "wx-app-1";

    private static final String FINGERPRINT = "a".repeat(64);

    private static final String ACTIVE_FINGERPRINT = "b".repeat(64);

    /** 当前生效的图片归一化规则版本（与缓存里写入的一致，才能命中）。 */
    private static final String NORMALIZE_VERSION = "1";

    @TempDir
    Path tempDirectory;

    private WechatMediaCacheStore store;

    private ReactiveSettingFetcher settingFetcher;

    private WechatCacheCleanupService service;

    @BeforeEach
    void setUp() {
        store = new WechatMediaCacheStore(() -> tempDirectory.resolve("plugins"));
        settingFetcher = mock(ReactiveSettingFetcher.class);
        service = new WechatCacheCleanupService(store, settingFetcher);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void cronFiresEveryDayAtMidnight() {
        CronExpression cron = CronExpression.parse(WechatCacheCleanupService.CLEANUP_CRON);

        // 每天 0 点：上午 10:30 之后的下一次执行是次日 0 点整
        assertThat(cron.next(LocalDateTime.of(2026, 9, 22, 10, 30)))
            .isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0));
        // 刚过 0 点（0 点 0 分 1 秒）时，下一次执行仍是次日 0 点整——不会在当天重复执行
        assertThat(cron.next(LocalDateTime.of(2026, 9, 22, 0, 0, 1)))
            .isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0));
    }

    @Test
    void cleansUpEntriesUnusedForLongerThanRetention() {
        plan("15");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(20)));
        saveUsedAt(ACTIVE_FINGERPRINT, Instant.now().minus(Duration.ofDays(3)));

        service.runCleanup();

        // 20 天没用过的被清掉；3 天前还在用的保留
        assertThat(find(FINGERPRINT)).isNull();
        assertThat(find(ACTIVE_FINGERPRINT)).isNotNull();
    }

    @Test
    void usesDefaultRetentionWhenSettingMissing() {
        // 设置组缺失（如尚未保存过配置）：按默认 30 天处理
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)).thenReturn(Mono.empty());
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(40)));

        service.runCleanup();

        assertThat(find(FINGERPRINT)).isNull();
    }

    @Test
    void cleanupFailureDoesNotThrow() {
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class))
            .thenReturn(Mono.error(new IllegalStateException("读取设置失败")));

        assertThatCode(service::runCleanup).doesNotThrowAnyException();
    }

    @Test
    void retentionDaysResolvesPositiveDaysAndKeepAllMarkers() {
        // 正数＝保留天数（忽略首尾空白）；表单是数字输入，兼容 30.0 这类小数写法
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting(" 90 "))).isEqualTo(90);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("365"))).isEqualTo(365);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("30.0"))).isEqualTo(30);

        // 留空 / 0 / 负数＝全部保留（null）
        assertThat(WechatCacheCleanupService.resolveRetentionDays(new WechatSetting())).isNull();
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("   "))).isNull();
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("0"))).isNull();
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("-7"))).isNull();
    }

    @Test
    void unresolvableRetentionDaysFallBackToDefault() {
        // 设置组缺失（如尚未保存过配置）与取值无法识别时，都按默认 30 天处理
        // （保留策略只有「天数」与「留空/0/负数的全部保留」两类，其余取值一律按默认天数）
        assertThat(WechatCacheCleanupService.resolveRetentionDays(null))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("mystery")))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        // 保留策略里没有「never」这个取值（它只是清理结果中「全部保留」的输出标识），按无法识别处理
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("never")))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
    }

    @Test
    void keepsEverythingWhenRetentionIsBlankOrNegative() {
        // 留空＝全部保留：再久没用过的记录也不删
        plan(" ");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(999)));
        service.runCleanup();
        assertThat(find(FINGERPRINT)).isNotNull();

        // 负数＝全部保留
        plan("-1");
        service.runCleanup();
        assertThat(find(FINGERPRINT)).isNotNull();
    }

    @Test
    void startsScheduledTaskThatRunsCleanupOnCron() {
        plan("15");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(20)));

        service.start();
        // 每秒执行一次：便于在测试中观察计划任务确实被注册并按 cron 触发了清理
        service.schedule("* * * * * *");
        try {
            awaitTrue(() -> find(FINGERPRINT) == null, Duration.ofSeconds(10));
        } finally {
            service.stop();
        }
    }

    @Test
    void startIsIdempotentAndToleratesSettingReadFailure() {
        // 读设置失败：计划任务照常挂上（保留天数在执行时才读），且不抛异常
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class))
            .thenReturn(Mono.error(new IllegalStateException("读取设置失败")));

        service.start();
        // 插件重载等场景可能重复 start()：不能泄漏前一个调度线程池
        service.start();

        assertThatCode(service::stop).doesNotThrowAnyException();
        // 已停止后再注册（如调度器正在关闭时插件又收到一次调用）：忽略，不抛异常
        assertThatCode(() -> service.schedule("* * * * * *")).doesNotThrowAnyException();
    }

    @Test
    void cleanupNowDeletesExpiredRecordsAndReportsRetentionAndCounts() {
        plan("15");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(20)));
        saveUsedAt(ACTIVE_FINGERPRINT, Instant.now().minus(Duration.ofDays(3)));

        WechatCacheCleanupService.CleanupResult result = service.cleanupNow().block();

        assertThat(result).isNotNull();
        assertThat(result.deletedRecords()).isEqualTo(1L);
        assertThat(result.remainingRecords()).isEqualTo(1L);
        assertThat(result.retentionDays()).isEqualTo("15");
        // 判定时间即「保留期之前」的那个时间点：早于它未使用的记录被删除
        assertThat(Instant.parse(result.cutoff()))
            .isBefore(Instant.now().minus(Duration.ofDays(14)))
            .isAfter(Instant.now().minus(Duration.ofDays(16)));
    }

    @Test
    void cleanupNowKeepsEverythingAndReportsKeepAllMarkerWhenRetentionNotSet() {
        plan("-1");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(999)));

        WechatCacheCleanupService.CleanupResult result = service.cleanupNow().block();

        assertThat(result).isNotNull();
        assertThat(result.deletedRecords()).isZero();
        assertThat(result.remainingRecords()).isEqualTo(1L);
        // 「全部保留」在清理结果（MCP 工具输出）中以固定的策略标识呈现
        assertThat(result.retentionDays()).isEqualTo(WechatCacheCleanupService.RETENTION_KEEP_ALL);
        // 没有判定时间（不做删除），但字段仍在，便于调用方统一解析
        assertThat(result.cutoff()).isEmpty();
        assertThat(find(FINGERPRINT)).isNotNull();
    }

    @Test
    void cleanupNowUsesDefaultRetentionWhenSettingMissing() {
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class)).thenReturn(Mono.empty());
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(40)));

        WechatCacheCleanupService.CleanupResult result = service.cleanupNow().block();

        assertThat(result).isNotNull();
        assertThat(result.retentionDays())
            .isEqualTo(String.valueOf(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS));
        assertThat(result.deletedRecords()).isEqualTo(1L);
        assertThat(result.remainingRecords()).isZero();
    }

    /** 预置设置：只保留「缓存保留天数」（{@code null} 表示留空）。 */
    private void plan(String retentionDays) {
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class))
            .thenReturn(Mono.just(setting(retentionDays)));
    }

    private static WechatSetting setting(String retentionDays) {
        WechatSetting setting = new WechatSetting();
        setting.setCacheRetentionDays(retentionDays);
        return setting;
    }

    /** 写入一条「最近一次使用时间为 {@code usedAt}」的缓存记录。 */
    private void saveUsedAt(String fingerprint, Instant usedAt) {
        long timestamp = usedAt.toEpochMilli();
        store.save(new CachedMedia(APP_ID, MediaCacheKind.CONTENT_IMAGE, fingerprint, NORMALIZE_VERSION,
            "https://blog.example.com/upload/a.png", "a.png", 12, null,
            "https://mmbiz.qpic.cn/" + fingerprint.charAt(0) + ".png", timestamp, timestamp)).block();
    }

    private CachedMedia find(String fingerprint) {
        return store.find(APP_ID, MediaCacheKind.CONTENT_IMAGE, fingerprint, NORMALIZE_VERSION).block();
    }

    /** 轮询等待条件成立：计划任务的注册是异步的，避免测试因时序抖动而失败。 */
    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as("等待超时：条件始终未成立").isTrue();
    }
}
