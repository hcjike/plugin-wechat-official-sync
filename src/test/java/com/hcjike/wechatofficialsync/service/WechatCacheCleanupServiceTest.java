package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hcjike.wechatofficialsync.cache.CachedMedia;
import com.hcjike.wechatofficialsync.cache.MediaCacheKind;
import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import com.hcjike.wechatofficialsync.config.WechatSetting;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * {@link WechatCacheCleanupService} 的行为验证：按保留天数清理（保留期按「最近一次使用时间」算）、
 * 「全部保留」时不动数据、保留天数与 cron 的解析容错，以及计划任务确实按配置的 cron 注册并触发。
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
    void cleansUpEntriesUnusedForLongerThanRetention() {
        plan("15", "0 0 2 * * *");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(20)));
        saveUsedAt(ACTIVE_FINGERPRINT, Instant.now().minus(Duration.ofDays(3)));

        service.runCleanup();

        // 20 天没用过的被清掉；3 天前还在用的保留
        assertThat(find(FINGERPRINT)).isNull();
        assertThat(find(ACTIVE_FINGERPRINT)).isNotNull();
    }

    @Test
    void keepsEverythingWhenRetentionIsNever() {
        plan(WechatSetting.CACHE_RETENTION_NEVER, "0 0 2 * * *");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(999)));

        service.runCleanup();

        assertThat(find(FINGERPRINT)).isNotNull();
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
    void retentionDaysFallBackToDefaultWhenMissingOrInvalid() {
        assertThat(WechatCacheCleanupService.resolveRetentionDays(null))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(new WechatSetting()))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("mystery", null)))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        // 0 / 负数不是合法保留期，同样按默认处理
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("0", null)))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("-7", null)))
            .isEqualTo(WechatCacheCleanupService.DEFAULT_RETENTION_DAYS);

        // 正常取值：忽略首尾空白；never（大小写不敏感）表示全部保留
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting(" 90 ", null))).isEqualTo(90);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("365", null))).isEqualTo(365);
        assertThat(WechatCacheCleanupService.resolveRetentionDays(setting("NEVER", null))).isNull();
    }

    @Test
    void cronFallsBackToDefaultAndToleratesCrontabStyle() {
        assertThat(WechatCacheCleanupService.resolveCron(null))
            .isEqualTo(WechatSetting.DEFAULT_CACHE_CLEANUP_CRON);
        assertThat(WechatCacheCleanupService.resolveCron("   "))
            .isEqualTo(WechatSetting.DEFAULT_CACHE_CLEANUP_CRON);
        assertThat(WechatCacheCleanupService.resolveCron(" 0 30 3 * * * "))
            .isEqualTo("0 30 3 * * *");
        // 兼容照抄 crontab 的 5 位写法（分 时 日 月 周）：自动补上「秒」位
        assertThat(WechatCacheCleanupService.resolveCron("30 3 * * *")).isEqualTo("0 30 3 * * *");
        // 无法解析的表达式回退默认值
        assertThat(WechatCacheCleanupService.resolveCron("每天凌晨三点"))
            .isEqualTo(WechatSetting.DEFAULT_CACHE_CLEANUP_CRON);
    }

    @Test
    void startsScheduledTaskThatRunsCleanupOnConfiguredCron() {
        // 每秒执行一次：便于在测试中观察计划任务确实被注册并按 cron 触发了清理
        plan("15", "* * * * * *");
        saveUsedAt(FINGERPRINT, Instant.now().minus(Duration.ofDays(20)));

        service.start();
        try {
            awaitTrue(() -> find(FINGERPRINT) == null, Duration.ofSeconds(10));
        } finally {
            service.stop();
        }
    }

    @Test
    void startIsIdempotentAndToleratesSettingReadFailure() {
        // 读设置失败：仍按默认 cron 挂上计划任务，且不抛异常
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class))
            .thenReturn(Mono.error(new IllegalStateException("读取设置失败")));

        service.start();
        // 插件重载等场景可能重复 start()：不能泄漏前一个调度线程池
        service.start();

        assertThatCode(service::stop).doesNotThrowAnyException();
    }

    @Test
    void reschedulesAfterSettingUpdateAndStopsCleanly() {
        plan("15", "0 0 3 * * *");
        service.start();

        // 设置变更（含 cron）后重排：这里只验证重排不会抛错，且停止后仍可安全调用
        plan("15", "0 4 * * *");
        service.onConfigUpdated(configUpdatedEvent());

        assertThatCode(() -> service.onConfigUpdated(configUpdatedEvent())).doesNotThrowAnyException();
        assertThatCode(service::stop).doesNotThrowAnyException();
        // 已停止后再收到设置变更事件（如禁用插件后保存设置）：忽略，不重新注册任务
        assertThatCode(() -> service.onConfigUpdated(configUpdatedEvent())).doesNotThrowAnyException();
    }

    @Test
    void springAcceptsTheEventListenerSignature() {
        // @EventListener 的校验发生在 Spring 刷新上下文、注册监听器时：方法签名不合规（例如漏掉事件参数）
        // 只会在运行期抛 BeanInitializationException，编译与直接调用的单测都发现不了，故这里真刷新一次
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean("cacheCleanup", WechatCacheCleanupService.class, () -> service);

            assertThatCode(context::refresh).doesNotThrowAnyException();
        }
    }

    @Test
    void springRejectsEventListenerWithoutEventParameter() {
        // 反向验证上一个用例不是空转：无参的 @EventListener 方法在这里必须被 Spring 拒绝。
        // 这条同时也把「监听方法必须带事件参数」钉住，避免后人又把它「简化」成无参形式
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean("brokenListener", BrokenEventListener.class);

            assertThatThrownBy(context::refresh)
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("Failed to process @EventListener")
                .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    /** 故意写错签名的监听器（无事件参数）：仅用于验证上面的守卫用例确实能拦住这类问题。 */
    public static class BrokenEventListener {

        @EventListener
        public void onConfigUpdated() {
            // 故意无参：Spring 注册监听器时会直接抛错
        }
    }

    /** 构造一次「插件设置已更新」事件（事件负载与计划任务无关，只用于驱动监听方法）。 */
    private static PluginConfigUpdatedEvent configUpdatedEvent() {
        return PluginConfigUpdatedEvent.builder()
            .source("test")
            .newSettingValues(Map.of())
            .build();
    }

    /** 预置设置：保留天数与 cron 表达式（{@code null} 表示留空）。 */
    private void plan(String retentionDays, String cron) {
        when(settingFetcher.fetch(WechatSetting.GROUP, WechatSetting.class))
            .thenReturn(Mono.just(setting(retentionDays, cron)));
    }

    private static WechatSetting setting(String retentionDays, String cron) {
        WechatSetting setting = new WechatSetting();
        setting.setCacheRetentionDays(retentionDays);
        setting.setCacheCleanupCron(cron);
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
