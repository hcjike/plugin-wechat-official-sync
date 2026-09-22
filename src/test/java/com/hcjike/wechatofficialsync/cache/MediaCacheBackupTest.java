package com.hcjike.wechatofficialsync.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MediaCacheBackup} 的行为验证：备份落在库文件同级的 {@code backups} 目录、产物是一份含
 * <b>数据与表结构</b>的完整库文件（可直接打开，schema 版本也在）、只保留最新 3 份，以及库不存在时
 * 不产生备份。
 *
 * <p>全程使用真实 SQLite（每个用例一个临时目录），并直接连上备份文件读取内容——「备份能不能用」
 * 只有真打开才算验证过。</p>
 */
class MediaCacheBackupTest {

    private static final String APP_ID = "wx-app-1";

    private static final String FINGERPRINT = "a".repeat(64);

    /** 当前生效的图片归一化规则版本（与缓存里写入的一致，才能命中）。 */
    private static final String NORMALIZE_VERSION = "1";

    private static final String CONTENT_URL = "https://mmbiz.qpic.cn/a.png";

    @TempDir
    Path tempDirectory;

    private WechatMediaCacheStore store;

    @BeforeEach
    void setUp() {
        store = new WechatMediaCacheStore(() -> tempDirectory.resolve("plugins"));
    }

    @Test
    void snapshotContainsDataAndSchemaAndLandsNextToDatabase() throws Exception {
        store.save(contentImageRecord()).block();

        Optional<Path> created = new MediaCacheBackup(databaseFile()).backup();

        assertThat(created).isPresent();
        Path backup = created.get();
        // 与库文件同级：<插件数据目录>/backups/wechat-media-cache-<时间戳>.sqlite
        assertThat(backup.getParent())
            .isEqualTo(databaseFile().getParent().resolve(MediaCacheBackup.BACKUP_DIRECTORY));
        assertThat(backup.getFileName().toString())
            .startsWith(MediaCacheBackup.BACKUP_FILE_PREFIX)
            .endsWith(MediaCacheBackup.BACKUP_FILE_SUFFIX)
            .isNotEqualTo(databaseFile().getFileName().toString());
        // 不影响正在使用的库文件本身
        assertThat(databaseFile()).exists();

        // 备份是一份完整、可直接打开的库：数据、表结构（含唯一索引）与 schema 版本都在其中
        assertThat(userVersionOf(backup)).isEqualTo(MediaCacheDatabase.SCHEMA_VERSION);
        assertThat(schemaSqlOf(backup, "table")).contains("CREATE TABLE").contains("wechat_media_cache");
        assertThat(schemaSqlOf(backup, "index")).contains("uk_wechat_media_cache_key");
        assertThat(contentUrlOf(backup)).isEqualTo(CONTENT_URL);
    }

    @Test
    void publishesBackupOnlyWhenCompleteAndLeavesNoTemporaryFile() throws Exception {
        store.save(contentImageRecord()).block();

        Path created = new MediaCacheBackup(databaseFile()).backup().orElseThrow();

        // 快照先写成 *.tmp、写完才改名为备份名：目录里除这一个备份文件外不该留下任何东西。
        // 残留的临时文件同样以 BACKUP_FILE_PREFIX 开头，会被下面这条断言抓出来
        try (Stream<Path> files = Files.list(backupDirectory())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                .containsExactly(created.getFileName().toString());
        }
        // 出现在备份目录里的这份就是完整可用的快照（能被 SQLite 打开并读出内容）
        assertThat(contentUrlOf(created)).isEqualTo(CONTENT_URL);
    }

    @Test
    void keepsOnlyLatestThreeBackupsAndLeavesOtherFilesAlone() throws Exception {
        store.save(contentImageRecord()).block();
        Path backups = backupDirectory();
        Files.createDirectories(backups);
        // 4 份既有备份（时间戳越早越旧）+ 一个不属于本插件的文件
        List<Path> old = List.of(
            backupFile("20200101000000"),
            backupFile("20210101000000"),
            backupFile("20220101000000"),
            backupFile("20230101000000"));
        Path unrelated = Files.writeString(backups.resolve("backups-readme.txt"), "不要删我");

        Path created = new MediaCacheBackup(databaseFile()).backup().orElseThrow();

        // 只保留最新 3 份：本次生成的 + 两份最新的旧备份；更早的两份被清理
        assertThat(listBackups()).containsExactlyInAnyOrder(created, old.get(2), old.get(3));
        // 备份目录里的其他文件不受影响
        assertThat(unrelated).exists();
    }

    @Test
    void skipsWhenDatabaseFileDoesNotExist() throws Exception {
        Optional<Path> created = new MediaCacheBackup(databaseFile()).backup();

        // 库还没建（缓存不可用等）：没有可备份的内容，连备份目录也不必创建
        assertThat(created).isEmpty();
        assertThat(backupDirectory()).doesNotExist();
    }

    @Test
    void doesNotOverwriteExistingBackupOfTheSameSecond() throws Exception {
        store.save(contentImageRecord()).block();
        Path backups = backupDirectory();
        Files.createDirectories(backups);
        // 预先占好「这一秒」及其后两秒的文件名：无论备份落在哪一秒，都必然命中「已存在」分支
        Path now = backups.resolve(MediaCacheBackup.backupFileName(LocalDateTime.now()));
        Path next = backups.resolve(MediaCacheBackup.backupFileName(LocalDateTime.now().plusSeconds(1)));
        Path later = backups.resolve(MediaCacheBackup.backupFileName(LocalDateTime.now().plusSeconds(2)));
        Files.writeString(now, "dummy");
        Files.writeString(next, "dummy");
        Files.writeString(later, "dummy");

        Path created = new MediaCacheBackup(databaseFile()).backup().orElseThrow();

        assertThat(created).isIn(now, next, later);
        // 已存在的备份不被覆盖写入（避免把已有备份写坏）
        assertThat(Files.readString(created)).isEqualTo("dummy");
    }

    /** 缓存库的预期路径：插件根目录下以插件名命名的目录。 */
    private Path databaseFile() {
        return tempDirectory.resolve("plugins").resolve(WechatMediaCacheStore.DATA_DIRECTORY)
            .resolve(WechatMediaCacheStore.SQLITE_FILE_NAME);
    }

    /** 备份目录：与库文件同级。 */
    private Path backupDirectory() {
        return databaseFile().getParent().resolve(MediaCacheBackup.BACKUP_DIRECTORY);
    }

    /** 在备份目录下创建一个空备份文件（只验证轮转，内容无所谓）。 */
    private Path backupFile(String timestamp) throws IOException {
        return Files.createFile(backupDirectory().resolve(MediaCacheBackup.BACKUP_FILE_PREFIX
            + timestamp + MediaCacheBackup.BACKUP_FILE_SUFFIX));
    }

    /** 备份目录下本插件生成的备份文件（按文件名升序，即按时间从旧到新）。 */
    private List<Path> listBackups() throws IOException {
        try (Stream<Path> files = Files.list(backupDirectory())) {
            return files.filter(path -> path.getFileName().toString()
                    .startsWith(MediaCacheBackup.BACKUP_FILE_PREFIX))
                .sorted()
                .toList();
        }
    }

    private static CachedMedia contentImageRecord() {
        return CachedMedia.uploaded(APP_ID, MediaCacheKind.CONTENT_IMAGE, FINGERPRINT, NORMALIZE_VERSION,
            "https://blog.example.com/upload/a.png", "a.png", 12, null, CONTENT_URL);
    }

    /** 打开备份文件读取 schema 版本（SQLite 内建的 {@code PRAGMA user_version}）。 */
    private static int userVersionOf(Path databaseFile) throws SQLException {
        try (Connection connection = open(databaseFile);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /** 打开备份文件读取 {@code sqlite_master} 中某类对象的 DDL：证明表结构与索引结构都在备份里。 */
    private static String schemaSqlOf(Path databaseFile, String type) throws SQLException {
        try (Connection connection = open(databaseFile);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type = '" + type + "'")) {
            StringBuilder ddl = new StringBuilder();
            while (rows.next()) {
                ddl.append(rows.getString(1)).append('\n');
            }
            return ddl.toString();
        }
    }

    /** 打开备份文件读取缓存记录里的图片地址：证明数据本身（而不只是行数）被保留了。 */
    private static String contentUrlOf(Path databaseFile) throws SQLException {
        try (Connection connection = open(databaseFile);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT content_url FROM wechat_media_cache")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static Connection open(Path databaseFile) throws SQLException {
        return DriverManager.getConnection(
            "jdbc:sqlite:" + databaseFile.toAbsolutePath().toString().replace('\\', '/'));
    }
}
