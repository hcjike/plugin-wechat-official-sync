package com.hcjike.wechatofficialsync.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 媒体缓存库的备份：把 SQLite 库快照到与库文件同级的 {@value #BACKUP_DIRECTORY} 目录，
 * 并按份数上限轮转（只保留最新的 {@value #KEEP} 份）。
 *
 * <p><b>快照方式</b>：用 SQLite 的 {@code VACUUM INTO} 生成副本，而不是 {@code Files.copy} 直接拷文件。
 * 拷贝正在使用中的库文件可能拿到「写了一半」的中间状态（页已改、journal 尚未合并），拷出来的副本
 * 有损坏风险；{@code VACUUM INTO} 由 SQLite 内部按一致性读事务导出，产物是一份完整、可用、且已整理过
 * （体积更小）的库文件——<b>数据与表结构都在其中</b>：建表 DDL 与唯一索引留在 {@code sqlite_master} 里，
 * schema 版本（{@code user_version}）也一并保留，必要时可直接当库文件打开使用。</p>
 *
 * <p><b>轮转</b>：备份文件名形如 {@code wechat-media-cache-20260922000000.sqlite}，时间戳按「年月日时分秒」
 * 排列，因此按文件名倒序即为按时间倒序；每次备份后只保留最新的 {@value #KEEP} 份，更早的删除，
 * 避免备份目录随日期无限增长。只处理本插件自己生成的备份文件（前缀与后缀都匹配），
 * 备份目录里放入的其他文件不动。</p>
 *
 * <p><b>完整性</b>：快照先导出到 {@code <备份名>.tmp}、写完再改名为备份名，因此备份目录里<b>只要出现
 * 备份文件，就一定是完整可用的快照</b>；中途失败或进程被杀只会留下一个 {@code .tmp} 临时文件
 * （不参与轮转，可安全手动删除），不会被误当成有效备份、也不会白占一个保留名额。</p>
 *
 * <p>本类是纯文件 / JDBC 操作，不依赖 Halo 上下文；调度由 {@code WechatMediaCacheBackupService} 负责。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public class MediaCacheBackup {

    private static final Logger log = LoggerFactory.getLogger(MediaCacheBackup.class);

    /** 备份目录名：与库文件同级（{@code <插件数据目录>/backups}）。 */
    public static final String BACKUP_DIRECTORY = "backups";

    /** 备份文件名前缀，其后接时间戳。 */
    public static final String BACKUP_FILE_PREFIX = "wechat-media-cache-";

    /** 备份文件名后缀：仍是一个 SQLite 库文件，可直接用驱动或工具打开。 */
    public static final String BACKUP_FILE_SUFFIX = ".sqlite";

    /** 保留的备份份数：只留最新的 3 份。 */
    public static final int KEEP = 3;

    /**
     * 快照导出期间的临时文件后缀：写完改名后才以备份名出现在备份目录里（见 {@link #backup()}）。
     * 带此后缀的文件<b>不是</b>备份文件（不参与轮转统计，可能是一份正在写入的快照）。
     */
    public static final String TEMPORARY_SUFFIX = ".tmp";

    /** 备份文件名中的时间戳格式：定长且按字典序排列即按时间排列。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path databaseFile;

    public MediaCacheBackup(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /**
     * 备份一次：生成库快照并轮转旧备份。
     *
     * @return 本次生成的备份文件路径；库文件还不存在时为 {@link Optional#empty()}
     *         （缓存尚未建库或已不可用，没有可备份的内容）
     * @throws SQLException 快照失败（如库被长时间独占、目标目录不可写）
     * @throws IOException  备份目录无法创建或旧备份无法删除
     */
    public Optional<Path> backup() throws SQLException, IOException {
        Path source = databaseFile.toAbsolutePath();
        if (!Files.exists(source)) {
            log.info("媒体缓存库尚未建立，跳过本次备份：{}", source);
            return Optional.empty();
        }
        Path directory = backupDirectory();
        Files.createDirectories(directory);
        Path target = directory.resolve(backupFileName(LocalDateTime.now()));
        if (Files.exists(target)) {
            // 同一秒内重复触发（如插件热重载后紧接着又跑一次）：该文件就是这一秒的备份，不再重复生成；
            // 直接返回而不是覆盖，能保证已存在的备份文件不会写在半途被打断而损坏
            log.info("媒体缓存库备份已存在，跳过本次备份：{}", target);
            return Optional.of(target);
        }
        // 先导出到临时文件、写完再改名：VACUUM INTO 是「创建目标文件 → 逐步写满」的过程，中途会存在一份
        // 看起来像备份、实际还不完整的文件。若此刻失败（磁盘满、进程被杀），这样的残缺文件既不安全（可能被
        // 当成可用备份去还原），又会白占一个轮转名额。改成「写完才以备份名出现」后，备份目录里只要出现
        // 备份文件，就一定是完整可用的快照
        Path temporary = directory.resolve(target.getFileName() + TEMPORARY_SUFFIX);
        try {
            snapshot(temporary);
            // 同一目录内改名走系统的 rename，是原子操作：读取方只会看到「没有这个文件」或「完整文件」
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (SQLException | IOException e) {
            deleteQuietly(temporary);
            throw e;
        }
        int removed = rotate(directory);
        log.info("媒体缓存库备份完成：{}（保留最新 {} 份，清理旧备份 {} 份）", target, KEEP, removed);
        return Optional.of(target);
    }

    /** 删除临时文件；失败只记日志，不掩盖真正导致备份失败的原因。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理备份临时文件失败（该文件可安全手动删除）：{}：{}", path, e.getMessage());
        }
    }

    /** 备份文件名：{@code wechat-media-cache-<yyyyMMddHHmmss>.sqlite}。 */
    static String backupFileName(LocalDateTime timestamp) {
        return BACKUP_FILE_PREFIX + timestamp.format(TIMESTAMP_FORMAT) + BACKUP_FILE_SUFFIX;
    }

    /** 备份目录：与库文件同级，避免备份散落到缓存数据目录之外。 */
    private Path backupDirectory() {
        return databaseFile.toAbsolutePath().getParent().resolve(BACKUP_DIRECTORY);
    }

    /**
     * 用 {@code VACUUM INTO} 导出一份一致快照。
     *
     * <p>连接复用 {@link MediaCacheDatabase} 的建立方式，好让驱动选择、{@code busy_timeout} 等与缓存库
     * 完全一致（库正被同步写入时会等锁，而不是立刻报 {@code database is locked}）。</p>
     */
    private void snapshot(Path target) throws SQLException {
        // 写进 SQL 字面量的路径：Windows 的反斜杠在 SQLite 中是转义符，统一成 '/'；单引号按 SQL 规则翻倍
        String path = target.toString().replace('\\', '/').replace("'", "''");
        try (Connection connection = new MediaCacheDatabase(databaseFile).open();
            Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + path + "'");
        }
    }

    /** 轮转旧备份：按文件名保留最新的 {@value #KEEP} 份，删除其余，返回删除份数。 */
    private int rotate(Path directory) throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(directory)) {
            backups = files.filter(Files::isRegularFile)
                .filter(MediaCacheBackup::isBackupFile)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList();
        }
        int removed = 0;
        for (Path expired : backups.subList(Math.min(KEEP, backups.size()), backups.size())) {
            Files.deleteIfExists(expired);
            removed++;
        }
        return removed;
    }

    /** 是否为本插件生成的备份文件：只清理自己的文件，备份目录里的其他内容不碰。 */
    private static boolean isBackupFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(BACKUP_FILE_PREFIX) && name.endsWith(BACKUP_FILE_SUFFIX);
    }
}
