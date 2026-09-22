package com.hcjike.wechatofficialsync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hcjike.wechatofficialsync.cache.CachedMedia;
import com.hcjike.wechatofficialsync.cache.MediaCacheBackup;
import com.hcjike.wechatofficialsync.cache.MediaCacheKind;
import com.hcjike.wechatofficialsync.cache.WechatMediaCacheStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@link WechatMediaCacheBackupService} 的行为验证：备份固定挂在每天 1 点、执行时把库快照到备份目录
 * （库尚未建立时先建库，好让备份里带表结构）、失败只记日志不外抛，以及计划任务确实按 cron 触发与启停安全。
 *
 * <p>存储用的是真实 SQLite（每个用例一个临时库），备份文件也真打开检查。</p>
 */
class WechatMediaCacheBackupServiceTest {

    private static final String APP_ID = "wx-app-1";

    private static final String FINGERPRINT = "a".repeat(64);

    /** 当前生效的图片归一化规则版本（与缓存里写入的一致，才能命中）。 */
    private static final String NORMALIZE_VERSION = "1";

    @TempDir
    Path tempDirectory;

    private WechatMediaCacheStore store;

    private WechatMediaCacheBackupService service;

    @BeforeEach
    void setUp() {
        store = new WechatMediaCacheStore(() -> tempDirectory.resolve("plugins"));
        service = new WechatMediaCacheBackupService(store);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void cronFiresEveryDayAtOneAm() {
        CronExpression cron = CronExpression.parse(WechatMediaCacheBackupService.BACKUP_CRON);

        // 每天 1 点：上午 10:30 之后的下一次执行是次日 1 点整
        assertThat(cron.next(LocalDateTime.of(2026, 9, 22, 10, 30)))
            .isEqualTo(LocalDateTime.of(2026, 9, 23, 1, 0));
        // 刚过 0 点（0 点 0 分 1 秒）时，下一次执行是当天 1 点整——不会重复执行，也不会拖到次日
        assertThat(cron.next(LocalDateTime.of(2026, 9, 22, 0, 0, 1)))
            .isEqualTo(LocalDateTime.of(2026, 9, 22, 1, 0));
    }

    @Test
    void backupCreatesDatabaseWhenMissingSoSnapshotCarriesSchema() throws Exception {
        // 库尚未建立（插件刚启动、还没同步过文章）：备份任务先建库，备份里因此也有表结构
        assertThat(store.databaseFile()).doesNotExist();

        service.runBackup();

        List<Path> backups = listBackups();
        assertThat(backups).hasSize(1);
        // 备份与源库的 schema 版本一致（都已完成建库迁移）且已写版本号 > 0，即备份里确实带着表结构
        assertThat(userVersionOf(backups.get(0)))
            .isGreaterThan(0)
            .isEqualTo(userVersionOf(store.databaseFile()));
    }

    @Test
    void backupKeepsCachedData() throws Exception {
        store.save(CachedMedia.uploaded(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT,
            NORMALIZE_VERSION, "https://blog.example.com/upload/a.png", "a.png", 12, null,
            "https://mmbiz.qpic.cn/a.png")).block();

        service.runBackup();

        assertThat(listBackups()).hasSize(1);
        assertThat(contentUrlOf(listBackups().get(0))).isEqualTo("https://mmbiz.qpic.cn/a.png");
    }

    @Test
    void backupFailureIsLoggedNotThrown() throws Exception {
        // 数据目录被一个同名普通文件占住：建库必然失败、缓存整体不可用，此时备份只记日志、不外抛
        Files.createDirectories(tempDirectory.resolve("plugins"));
        Files.createFile(store.databaseFile().getParent());

        assertThatCode(service::runBackup).doesNotThrowAnyException();
        // 没有库就没有备份：备份目录不会被创建。
        // 这里刻意用 Files.exists（「不存在」与「无法判定」都返回 false）而不是 PathAssert#doesNotExist：
        // 该路径的上级是个普通文件（非目录），Linux 上 stat 这类路径返回 ENOTDIR 而非「不存在」，
        // 而 doesNotExist 基于 Files.notExists（只在「确定不存在」时为 true），会把这个「无法判定」判成
        // 失败——Windows 上映射为「不存在」因而通过，属于平台差异
        assertThat(Files.exists(backupDirectory())).isFalse();
    }

    @Test
    void startsScheduledTaskThatRunsBackupOnCron() throws Exception {
        store.save(CachedMedia.uploaded(APP_ID, MediaCacheKind.PERMANENT_IMAGE, FINGERPRINT,
            NORMALIZE_VERSION, "https://blog.example.com/upload/a.png", "a.png", 12, "MEDIA-ID-1",
            null)).block();

        service.start();
        // 每秒执行一次：便于在测试中观察计划任务确实被注册并按 cron 触发了备份
        service.schedule("* * * * * *");
        try {
            // 这里只断言「计划任务确实触发了备份、且快照写出了内容」。
            // 备份产物本身的正确性（可打开、schema 版本、数据）由上面几个同步用例覆盖：
            // 计划任务每秒触发一次并轮转旧备份，在此处读取文件内容会与轮转 / 下一次写入竞态，
            // 在较慢的 CI 机器上会偶发失败
            awaitTrue(() -> listBackups().stream().anyMatch(WechatMediaCacheBackupServiceTest::hasContent),
                Duration.ofSeconds(30));
        } finally {
            service.stop();
        }
    }

    @Test
    void startIsIdempotentAndStopIsSafe() {
        service.start();
        // 插件重载等场景可能重复 start()：不能泄漏前一个调度线程池
        service.start();

        assertThatCode(service::stop).doesNotThrowAnyException();
        assertThatCode(service::stop).doesNotThrowAnyException();
        // 已停止后再注册（如调度器正在关闭时插件又收到一次调用）：忽略，不抛异常
        assertThatCode(() -> service.schedule("* * * * * *")).doesNotThrowAnyException();
    }

    /** 备份目录：与库文件同级（库文件位置由存储自己给出，测试不重复拼一遍路径）。 */
    private Path backupDirectory() {
        return store.databaseFile().getParent().resolve(MediaCacheBackup.BACKUP_DIRECTORY);
    }

    /**
     * 备份目录下的备份文件（按文件名升序，即按时间从旧到新）。
     *
     * <p>排除 {@code *.tmp}：那是正在写入中的快照，不是备份文件——计划任务每秒触发时，
     * 下一次备份可能刚好在写临时文件，把它当备份读会读到写了一半的库。</p>
     */
    private List<Path> listBackups() {
        Path directory = backupDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(MediaCacheBackup.BACKUP_FILE_PREFIX)
                        && !name.endsWith(MediaCacheBackup.TEMPORARY_SUFFIX);
                })
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取备份目录失败", e);
        }
    }

    /**
     * 备份文件存在且已写出内容。
     *
     * <p>计划任务每秒触发并轮转旧备份，读取的瞬间文件可能刚好被删除：读不到就返回 {@code false}，
     * 由调用方的轮询继续观察，而不是把这种正常竞态判成失败。</p>
     */
    private static boolean hasContent(Path backup) {
        try {
            return Files.size(backup) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 打开备份文件读取 schema 版本（SQLite 内建的 {@code PRAGMA user_version}）。 */
    private static int userVersionOf(Path databaseFile) throws SQLException {
        try (Connection connection = open(databaseFile);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /** 打开备份文件读取缓存记录里的图片地址。 */
    private static String contentUrlOf(Path databaseFile) throws SQLException {
        try (Connection connection = open(databaseFile);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT content_url FROM wechat_media_cache")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /** 按文件路径直连 SQLite：备份文件在插件上下文之外，不能走缓存库自己的连接建立方式。 */
    private static Connection open(Path databaseFile) throws SQLException {
        return DriverManager.getConnection(
            "jdbc:sqlite:" + databaseFile.toAbsolutePath().toString().replace('\\', '/'));
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
