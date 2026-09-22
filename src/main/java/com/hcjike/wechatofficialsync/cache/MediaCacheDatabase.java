package com.hcjike.wechatofficialsync.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.JDBC;

/**
 * 媒体缓存库（SQLite）：负责数据库文件的建库、连接与 <b>schema 版本管理</b>。
 *
 * <p><b>版本管理</b>：用 SQLite 内建的 {@code PRAGMA user_version} 记录库当前的 schema 版本，
 * 升级时按 {@link #MIGRATIONS} 中登记的迁移脚本逐级补齐（只执行版本号大于库中已记录版本的迁移），
 * 整个过程在一个事务里完成：任一步失败即整体回滚，不会留下「改了一半」的表结构。</p>
 *
 * <p><b>当前插件尚未发布</b>，库里只有一条 v1 建表语句，没有任何历史迁移要兼容：改动表结构直接改
 * {@link #MIGRATIONS} 里的 v1 即可。<b>发布之后</b>再动结构，才需要把 {@link #SCHEMA_VERSION} 加一、
 * 在 {@link #MIGRATIONS} 末尾追加版本号等于新版本的迁移，且不能再修改已发布的历史迁移
 * （老库上它们早已执行过，改了也不会再生效）。</p>
 *
 * <p><b>连接策略</b>：不持有长连接。插件在 Halo 中会热重载/升级，跨插件生命周期的连接会变成无法回收的
 * 文件句柄；而一次读写一个连接的开销相对一次微信上传可以忽略，故每次操作按需打开、用完即关，
 * 且<b>直接用 SQLite 驱动实例建立连接、不经 {@code DriverManager}</b>（原因见 {@link #open()}）。
 * 并发写入时的锁冲突由 {@code busy_timeout} 等待而不是直接报 {@code database is locked}。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class MediaCacheDatabase {

    private static final Logger log = LoggerFactory.getLogger(MediaCacheDatabase.class);

    /** 当前 schema 版本：新增迁移时递增，并同步在 {@link #MIGRATIONS} 里追加对应迁移。 */
    static final int SCHEMA_VERSION = 1;

    /** SQLite 写锁等待时间（毫秒）：并发同步写入时等待锁释放，避免瞬时 database is locked。 */
    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    /** 读取 schema 版本号的 SQLite pragma（写入时在其后追加 {@code = 版本号}）。 */
    private static final String USER_VERSION_PRAGMA = "PRAGMA user_version";

    /**
     * 依次应用的迁移脚本；每条迁移只执行一次（按 {@code user_version} 判断是否已应用）。
     *
     * <p>v1：建立媒体缓存表。唯一索引落在「公众号 + 上传类型 + 文件指纹」上，既做查询索引，
     * 也保证同一资源在同一公众号下只有一条缓存记录（并发写入时由 upsert 收敛到同一条）；
     * {@code normalize_version} 是图片归一化规则版本，参与查询匹配（见 {@link CachedMedia}）。
     * {@code created_at} / {@code updated_at} 都是毫秒时间戳：前者为首次上传成功的时间，后者为
     * 「最近一次使用时间」（上传成功或命中缓存复用都会刷新），缓存清理的保留期按它计算。</p>
     *
     * <p><b>插件尚未发布，当前只有 v1 一条建表语句：改动表结构时直接改它即可，不必追加迁移。</b>
     * 等发布后再动结构，才需要递增 {@link #SCHEMA_VERSION} 并在下方追加一条同版本号的迁移
     * （已发布版本的迁移一旦生效就不能再改）。</p>
     */
    private static final List<Migration> MIGRATIONS = List.of(
        new Migration(1, List.of(
            """
            CREATE TABLE IF NOT EXISTS wechat_media_cache (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                app_id            TEXT    NOT NULL,
                kind              TEXT    NOT NULL,
                fingerprint       TEXT    NOT NULL,
                normalize_version TEXT    NOT NULL,
                source_url        TEXT,
                filename          TEXT,
                file_size         INTEGER NOT NULL DEFAULT 0,
                media_id          TEXT,
                content_url       TEXT,
                created_at        INTEGER NOT NULL,
                updated_at        INTEGER NOT NULL
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uk_wechat_media_cache_key
                ON wechat_media_cache (app_id, kind, fingerprint)
            """)));

    private final Path databaseFile;

    public MediaCacheDatabase(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** 数据库文件路径（用于日志与排查）。 */
    public Path file() {
        return databaseFile;
    }

    /**
     * 打开一个连接，调用方负责关闭（try-with-resources）。
     *
     * <p>直接用 SQLite 驱动实例建立连接，不经 {@link java.sql.DriverManager}：插件运行在独立的类加载器里，
     * 而 DriverManager 的驱动发现依赖 JDBC 的 ServiceLoader 与线程上下文类加载器，在响应式链路的工作线程
     * 上未必能匹配到插件内的驱动；直接实例化既更稳，也少一层全局注册表。</p>
     */
    public Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MILLIS));
        try {
            Connection connection = new JDBC().connect(jdbcUrl(), properties);
            if (connection == null) {
                // 驱动只接受 jdbc:sqlite: 前缀的地址；走到这里说明地址被驱动拒绝（正常不会发生）
                throw new SQLException("SQLite 驱动未接受连接地址：" + jdbcUrl());
            }
            return connection;
        } catch (LinkageError e) {
            // 驱动类缺失或原生库解压/加载失败（如临时目录不可写）：统一转成明确的 SQLException，
            // 由上层按「缓存不可用」降级，而不是抛出 Error 打断同步流程
            throw new SQLException("SQLite JDBC 驱动不可用（缺失或原生库加载失败）：" + e, e);
        }
    }

    /**
     * 建库（含父目录）并把 schema 升级到 {@link #SCHEMA_VERSION}；已是当前版本时只读一次
     * {@code user_version} 即返回，可重复调用。
     *
     * @throws SQLException  驱动不可用、目录不可写、迁移失败等
     * @throws IOException   数据库所在目录无法创建
     */
    public void initialize() throws SQLException, IOException {
        Path parent = databaseFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection connection = open()) {
            int current = readUserVersion(connection);
            if (current > SCHEMA_VERSION) {
                // 库来自更新版本的插件：当前代码不认识新增的表结构，但既有列仍可用，故只告警不阻断
                log.warn("媒体缓存库 schema 版本（v{}）高于当前插件支持的版本（v{}），将按现有结构继续使用；"
                    + "如出现异常请升级插件", current, SCHEMA_VERSION);
                return;
            }
            if (current == SCHEMA_VERSION) {
                return;
            }
            applyMigrations(connection, current);
        }
    }

    /** 在单个事务内逐级应用尚未执行的迁移，全部成功后再提交。 */
    private void applyMigrations(Connection connection, int currentVersion) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (Migration migration : MIGRATIONS) {
                if (migration.version() <= currentVersion) {
                    continue;
                }
                log.info("媒体缓存库升级 schema：v{} → v{}", migration.version() - 1, migration.version());
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.statements()) {
                        statement.execute(sql);
                    }
                }
                writeUserVersion(connection, migration.version());
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static int readUserVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(USER_VERSION_PRAGMA)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    /** 写入 {@code user_version}；版本号取自代码中的常量，不存在注入风险。 */
    private static void writeUserVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(USER_VERSION_PRAGMA + " = " + version);
        }
    }

    private String jdbcUrl() {
        // Windows 路径统一成 '/'（避免反斜杠被 JDBC URL 解析），绝对路径避免受工作目录影响
        return "jdbc:sqlite:" + databaseFile.toAbsolutePath().toString().replace('\\', '/');
    }

    /** 一次 schema 迁移：{@code version} 为完成该迁移后的 schema 版本，{@code statements} 为该版本的全部 DDL。 */
    private record Migration(int version, List<String> statements) {
    }
}
