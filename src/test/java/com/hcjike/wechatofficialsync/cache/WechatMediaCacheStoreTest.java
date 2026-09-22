package com.hcjike.wechatofficialsync.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WechatMediaCacheStore} 与 {@link MediaCacheDatabase} 的行为验证：库文件位置、schema 版本记录、
 * 以「公众号 + 上传类型 + 文件指纹 + 归一化版本」为键的读写与覆盖，以及缓存不可用时的降级行为。
 */
class WechatMediaCacheStoreTest {

    private static final String APP_ID = "wx-app-1";

    private static final String OTHER_APP_ID = "wx-app-2";

    private static final String FINGERPRINT = "a".repeat(64);

    /** 当前生效的图片归一化规则版本（测试中固定为一个值，不依赖 WechatMpClient 的常量）。 */
    private static final String NORMALIZE_VERSION = "1";

    /** 假设的下一个归一化规则版本（转码规则变更后应作废旧缓存）。 */
    private static final String NEXT_NORMALIZE_VERSION = "2";

    @TempDir
    Path tempDirectory;

    private WechatMediaCacheStore store;

    @BeforeEach
    void setUp() {
        store = storeUnder(tempDirectory);
    }

    /** 以给定目录作为 Halo 工作目录构造缓存存储（库文件落在其 {@code plugins/<插件名>/} 下）。 */
    private static WechatMediaCacheStore storeUnder(Path workDirectory) {
        return new WechatMediaCacheStore(() -> workDirectory.resolve("plugins"));
    }

    /** 缓存库的预期路径：插件根目录下以插件名命名的目录。 */
    private Path databaseFile() {
        return tempDirectory.resolve("plugins").resolve(WechatMediaCacheStore.DATA_DIRECTORY)
            .resolve(WechatMediaCacheStore.SQLITE_FILE_NAME);
    }

    /** 按「当前归一化版本」查询缓存。 */
    private CachedMedia find(String appId, MediaCacheKind kind, String fingerprint) {
        return store.find(appId, kind, fingerprint, NORMALIZE_VERSION).block();
    }

    @Test
    void storesDatabaseInPluginDirectoryUnderPluginsRoot() throws Exception {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();

        // 与其他插件一致：在自己的插件名目录下存放数据（插件包本身是同级的 <插件名>-<版本>.jar）
        assertThat(databaseFile()).exists();
    }

    @Test
    void keepsDatabaseWhenPluginJarIsUpgraded() throws Exception {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();

        // 插件升级：Halo 只以 REPLACE_EXISTING 覆盖同名的 <插件名>-<版本>.jar，不动同名数据目录
        Path pluginsRoot = tempDirectory.resolve("plugins");
        Files.createFile(pluginsRoot.resolve("plugin-wechat-official-sync-1.0.1.jar"));

        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT)).isNotNull();
        assertThat(databaseFile()).exists();
    }

    @Test
    void schemaVersionIsRecordedAndUpgradeIsIdempotent() throws Exception {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();

        // 首次使用即完成建库，并把 schema 版本写入 SQLite 内建的 user_version
        assertThat(userVersion()).isEqualTo(MediaCacheDatabase.SCHEMA_VERSION);

        // 再次初始化（等价于插件重启）不应重复建表或改动版本
        assertThatCode(() -> new MediaCacheDatabase(databaseFile()).initialize())
            .doesNotThrowAnyException();
        assertThat(userVersion()).isEqualTo(MediaCacheDatabase.SCHEMA_VERSION);
    }

    @Test
    void findsSavedRecordByKey() {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();

        CachedMedia found = find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT);

        assertThat(found).isNotNull();
        assertThat(found.appId()).isEqualTo(APP_ID);
        assertThat(found.kind()).isEqualTo(MediaCacheKind.CONTENT_IMAGE);
        assertThat(found.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(found.normalizeVersion()).isEqualTo(NORMALIZE_VERSION);
        assertThat(found.contentUrl()).isEqualTo("https://mmbiz.qpic.cn/a.png");
        assertThat(found.mediaId()).isNull();
        assertThat(found.remoteValue()).isEqualTo("https://mmbiz.qpic.cn/a.png");
    }

    @Test
    void returnsEmptyWhenNothingCached() {
        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT)).isNull();
    }

    @Test
    void distinguishesContentImageAndPermanentImage() {
        // 两个上传接口的返回内容不同（url / media_id），必须分别缓存、分别读取
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();
        store.save(permanentImage(FINGERPRINT, "MEDIA-ID-1")).block();

        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT).contentUrl())
            .isEqualTo("https://mmbiz.qpic.cn/a.png");
        CachedMedia permanent = find(APP_ID, MediaCacheKind.PERMANENT_IMAGE, FINGERPRINT);
        assertThat(permanent).isNotNull();
        assertThat(permanent.mediaId()).isEqualTo("MEDIA-ID-1");
        assertThat(permanent.contentUrl()).isNull();
        assertThat(permanent.remoteValue()).isEqualTo("MEDIA-ID-1");
    }

    @Test
    void isolatesRecordsByAccount() {
        // 换公众号后旧记录不得复用：素材空间是按公众号隔离的
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();

        assertThat(find(OTHER_APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT)).isNull();
    }

    @Test
    void savingSameKeyOverwritesPreviousValue() {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/old.png")).block();
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/new.png")).block();

        CachedMedia found = find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT);

        assertThat(found).isNotNull();
        assertThat(found.contentUrl()).isEqualTo("https://mmbiz.qpic.cn/new.png");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void cachesDifferentFilesSeparately() {
        String otherFingerprint = "b".repeat(64);
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/a.png")).block();
        store.save(contentImage(otherFingerprint, "https://mmbiz.qpic.cn/b.png")).block();

        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT).contentUrl())
            .isEqualTo("https://mmbiz.qpic.cn/a.png");
        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, otherFingerprint).contentUrl())
            .isEqualTo("https://mmbiz.qpic.cn/b.png");
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void invalidatesCacheWhenNormalizeVersionChanges() {
        store.save(contentImage(FINGERPRINT, "https://mmbiz.qpic.cn/old.png")).block();

        // 归一化规则升级：指纹（原始字节）没变，但旧产物不再可信，应视为未命中
        assertThat(store.find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NEXT_NORMALIZE_VERSION)
            .block()).isNull();

        // 按新版本重新上传后覆盖同一条记录（不会因为版本不同而留下两条）
        store.save(CachedMedia.uploaded(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT,
            NEXT_NORMALIZE_VERSION, "https://blog.example.com/upload/a.png", "a.png", 12, null,
            "https://mmbiz.qpic.cn/new.png")).block();

        assertThat(recordCount()).isEqualTo(1);
        assertThat(store.find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NEXT_NORMALIZE_VERSION)
            .block().contentUrl()).isEqualTo("https://mmbiz.qpic.cn/new.png");
        // 旧版本记录已被覆盖，不会在版本回退时又把旧产物捞出来
        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT)).isNull();
    }

    @Test
    void touchRefreshesLastUsedTimeSoActiveEntrySurvivesCleanup() {
        long unusedSince = Instant.now().minus(Duration.ofDays(100)).toEpochMilli();
        store.save(contentImageUsedAt(FINGERPRINT, "https://mmbiz.qpic.cn/a.png", unusedSince)).block();

        store.touch(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NORMALIZE_VERSION).block();

        // 命中缓存会刷新使用时间：保留期内的清理不再视其为过期数据
        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT).updatedAt())
            .isGreaterThan(unusedSince);
        assertThat(purgeBeforeDays(30)).isZero();
    }

    @Test
    void purgeRemovesOnlyEntriesUnusedForLongerThanRetention() {
        String activeFingerprint = "b".repeat(64);
        long longUnused = Instant.now().minus(Duration.ofDays(100)).toEpochMilli();
        store.save(contentImageUsedAt(FINGERPRINT, "https://mmbiz.qpic.cn/old.png", longUnused)).block();
        store.save(contentImage(activeFingerprint, "https://mmbiz.qpic.cn/recent.png")).block();

        assertThat(purgeBeforeDays(30)).isEqualTo(1L);

        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT)).isNull();
        assertThat(find(APP_ID, MediaCacheKind.CONTENT_IMAGE, activeFingerprint)).isNotNull();
    }

    @Test
    void touchIsNoOpForUnknownOrInvalidKey() {
        assertThatCode(() ->
            store.touch(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NORMALIZE_VERSION).block())
            .doesNotThrowAnyException();
        assertThatCode(() -> store.touch(null, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT,
            NORMALIZE_VERSION).block()).doesNotThrowAnyException();
        assertThatCode(() -> store.touch(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, "  ").block())
            .doesNotThrowAnyException();
    }

    @Test
    void createsMissingDirectoriesAndWorksOnFirstUse() {
        // 插件启动时未初始化（如启动阶段建库失败重试前的首次使用）也能自动建库：插件根目录与数据目录都不存在
        assertThat(tempDirectory.resolve("plugins")).doesNotExist();

        store.save(permanentImage(FINGERPRINT, "MEDIA-ID-1")).block();

        assertThat(find(APP_ID, MediaCacheKind.PERMANENT_IMAGE, FINGERPRINT).mediaId())
            .isEqualTo("MEDIA-ID-1");
    }

    @Test
    void degradesToNoCacheWhenDatabaseCannotBeCreated() throws Exception {
        // 数据目录被一个同名普通文件占住：建库必然失败，此时缓存整体关闭，同步流程退回「没有缓存」的旧行为
        Files.createDirectories(tempDirectory.resolve("plugins"));
        Files.createFile(tempDirectory.resolve("plugins").resolve(WechatMediaCacheStore.DATA_DIRECTORY));
        WechatMediaCacheStore broken = storeUnder(tempDirectory);

        assertThatCode(() -> broken.save(permanentImage(FINGERPRINT, "MEDIA-ID-1")).block())
            .doesNotThrowAnyException();
        assertThat(broken.find(APP_ID, MediaCacheKind.PERMANENT_IMAGE, FINGERPRINT, NORMALIZE_VERSION)
            .block()).isNull();
    }

    @Test
    void ignoresInvalidKeyArguments() {
        assertThat(store.find(null, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NORMALIZE_VERSION).block())
            .isNull();
        assertThat(store.find(APP_ID, null, FINGERPRINT, NORMALIZE_VERSION).block()).isNull();
        assertThat(store.find(APP_ID, MediaCacheKind.CONTENT_IMAGE, "  ", NORMALIZE_VERSION).block())
            .isNull();
        assertThat(store.find(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, "  ").block()).isNull();
        assertThatCode(() -> store.save(null).block()).doesNotThrowAnyException();
    }

    private static CachedMedia contentImage(String fingerprint, String contentUrl) {
        return CachedMedia.uploaded(APP_ID, MediaCacheKind.CONTENT_IMAGE, fingerprint, NORMALIZE_VERSION,
            "https://blog.example.com/upload/a.png", "a.png", 12, null, contentUrl);
    }

    private static CachedMedia permanentImage(String fingerprint, String mediaId) {
        return CachedMedia.uploaded(APP_ID, MediaCacheKind.PERMANENT_IMAGE, fingerprint, NORMALIZE_VERSION,
            "https://blog.example.com/upload/a.png", "a.png", 12, mediaId, null);
    }

    /** 构造一条「最近一次使用时间为 {@code usedAt}」的记录，用于验证按使用时间清理。 */
    private static CachedMedia contentImageUsedAt(String fingerprint, String contentUrl, long usedAt) {
        return new CachedMedia(APP_ID, MediaCacheKind.CONTENT_IMAGE, fingerprint, NORMALIZE_VERSION,
            "https://blog.example.com/upload/a.png", "a.png", 12, null, contentUrl, usedAt, usedAt);
    }

    /** 按「保留 N 天」的口径清理，返回删除条数。 */
    private long purgeBeforeDays(int days) {
        return store.purgeUnusedSince(Instant.now().minus(Duration.ofDays(days)).toEpochMilli()).block();
    }

    /** 读取库中记录的 schema 版本（SQLite 内建的 {@code PRAGMA user_version}）。 */
    private int userVersion() throws SQLException {
        try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + databaseFile().toAbsolutePath());
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /** 库中的缓存记录条数（用于验证「同一键只保留一条」）。 */
    private int recordCount() {
        try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + databaseFile().toAbsolutePath());
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM wechat_media_cache")) {
            return rows.next() ? rows.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException("读取缓存记录条数失败", e);
        }
    }
}
