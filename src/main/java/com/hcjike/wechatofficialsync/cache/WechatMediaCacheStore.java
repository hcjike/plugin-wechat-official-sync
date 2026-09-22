package com.hcjike.wechatofficialsync.cache;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.PluginsRootGetter;

/**
 * 微信媒体缓存存储：以 {@link CachedMedia} 为模型读写 SQLite 缓存库（{@link MediaCacheDatabase}），
 * 记录「文件指纹（格式转换前的原始字节）+ 图片归一化版本 → 上传到微信后的返回内容」，
 * 避免同一资源被反复上传、挤占微信素材库。
 *
 * <p><b>库文件位置</b>：{@code <插件根目录>/plugin-wechat-official-sync/wechat-media-cache.sqlite}
 * （即 Halo 工作目录下的 {@code plugins/plugin-wechat-official-sync/}，与其他插件一样在插件根目录下
 * 以自己的插件名建目录存放数据）。</p>
 *
 * <p>该目录与插件包本身不冲突：生产模式下插件包是平铺在插件根目录的 {@code <插件名>-<版本>.jar}，
 * Halo 安装/升级只是 {@code REPLACE_EXISTING} 覆盖同名 jar，不会动同级的同名目录，
 * 因此缓存可以跨插件升级保留。</p>
 *
 * <p><b>降级策略</b>：缓存是「少上传一次」的优化，不是同步的必要条件。建库或读写失败时只记日志并把
 * 缓存整体关闭（查询一律未命中、写入丢弃），同步流程照常上传资源——即退回到「没有缓存」的旧行为，
 * 不会因为磁盘/权限问题导致文章发不出去。初始化失败只告警一次，避免每次同步都刷日志。</p>
 *
 * <p>所有 JDBC 操作都是阻塞调用，统一切到 {@link Schedulers#boundedElastic()} 执行，不占用 Netty 事件循环。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Component
public class WechatMediaCacheStore {

    private static final Logger log = LoggerFactory.getLogger(WechatMediaCacheStore.class);

    /** 插件数据目录名（位于 Halo 插件根目录下，与插件名同名）。 */
    static final String DATA_DIRECTORY = "plugin-wechat-official-sync";

    /** 缓存库的 SQLite 文件名。 */
    static final String SQLITE_FILE_NAME = "wechat-media-cache.sqlite";

    private static final String SELECT_SQL = """
        SELECT app_id, kind, fingerprint, normalize_version, source_url, filename, file_size,
               media_id, content_url, created_at, updated_at
          FROM wechat_media_cache
         WHERE app_id = ? AND kind = ? AND fingerprint = ? AND normalize_version = ?
        """;

    /** 写入缓存：同一「公众号 + 上传类型 + 指纹」只有一条记录，重复上传后覆盖为最新结果。 */
    private static final String UPSERT_SQL = """
        INSERT INTO wechat_media_cache
            (app_id, kind, fingerprint, normalize_version, source_url, filename, file_size, media_id,
             content_url, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(app_id, kind, fingerprint) DO UPDATE SET
            normalize_version = excluded.normalize_version,
            source_url        = excluded.source_url,
            filename          = excluded.filename,
            file_size         = excluded.file_size,
            media_id          = excluded.media_id,
            content_url       = excluded.content_url,
            updated_at        = excluded.updated_at
        """;

    /** 刷新「最近一次使用时间」：命中缓存并校验通过后调用（保留期按它计算，见 {@link #purgeUnusedSince}）。 */
    private static final String TOUCH_SQL = """
        UPDATE wechat_media_cache
           SET updated_at = ?
         WHERE app_id = ? AND kind = ? AND fingerprint = ? AND normalize_version = ?
        """;

    /** 清理：删除「最近一次使用时间」早于给定时间戳的记录。 */
    private static final String PURGE_SQL = "DELETE FROM wechat_media_cache WHERE updated_at < ?";

    private final PluginsRootGetter pluginsRootGetter;

    /** 已就绪的缓存库；为 {@code null} 表示尚未初始化或已不可用（见 {@link #unavailable}）。 */
    private volatile MediaCacheDatabase database;

    /** 缓存库初始化失败：整体关闭缓存，不再重试（避免每次同步都重复报错）。 */
    private volatile boolean unavailable;

    public WechatMediaCacheStore(PluginsRootGetter pluginsRootGetter) {
        this.pluginsRootGetter = pluginsRootGetter;
    }

    /**
     * 触发建库与 schema 升级（插件启动时调用，让目录权限等问题尽早出现在日志里）。
     * 失败不抛出：缓存不可用时同步流程照常上传，仅多一次重复上传。
     */
    public Mono<Void> initialize() {
        return Mono.fromRunnable(this::ensureReady)
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    /**
     * 按「公众号 + 上传类型 + 文件指纹 + 归一化版本」查询缓存记录。
     *
     * @param normalizeVersion 当前图片归一化规则版本；与记录中的版本不一致视为未命中，
     *                         使转码规则变更后旧缓存自动失效（见 {@link CachedMedia}）
     * @return 命中的记录；未命中、缓存不可用或读取失败时为空信号
     */
    public Mono<CachedMedia> find(String appId, MediaCacheKind kind, String fingerprint,
        String normalizeVersion) {
        if (isBlank(appId) || kind == null || isBlank(fingerprint) || isBlank(normalizeVersion)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> query(appId, kind, fingerprint, normalizeVersion))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.warn("读取媒体缓存失败（按未命中处理，该资源将重新上传）：{}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 写入（或覆盖）缓存记录：上传成功后调用，把文件指纹与微信返回的 url / media_id 关联起来。
     * 写入失败只记日志——本次同步已经成功，下次重新上传即可。
     */
    public Mono<Void> save(CachedMedia media) {
        if (media == null || isBlank(media.appId()) || media.kind() == null
            || isBlank(media.fingerprint())) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
                upsert(media);
                return Boolean.TRUE;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.warn("写入媒体缓存失败（不影响本次同步，但下次会重新上传）：{}", e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 刷新记录的「最近一次使用时间」（命中缓存并校验通过后调用）。
     *
     * <p>保留期是按使用时间算的（见 {@link #purgeUnusedSince}），不刷新的话，一张仍在被反复复用的图片
     * 会因为「上传时间久远」被清理任务删掉，下次同步又得重新上传一遍——正好违背缓存的初衷。
     * 刷新失败只记日志，不影响本次同步。</p>
     */
    public Mono<Void> touch(String appId, MediaCacheKind kind, String fingerprint,
        String normalizeVersion) {
        if (isBlank(appId) || kind == null || isBlank(fingerprint) || isBlank(normalizeVersion)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
                touchRecord(appId, kind, fingerprint, normalizeVersion);
                return Boolean.TRUE;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.warn("刷新媒体缓存的使用时间失败（不影响本次同步）：{}", e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 删除「最近一次使用时间」早于 {@code cutoffMillis} 的记录，返回删除条数；供缓存清理计划任务调用。
     *
     * <p>按<b>使用时间</b>而不是建立时间判定：只要某张图还会被同步命中，它的使用时间就不断刷新，
     * 不会被误删；被清掉的都是确实已不再使用的缓存。缓存不可用或删除失败时返回 {@code 0}。</p>
     */
    public Mono<Long> purgeUnusedSince(long cutoffMillis) {
        return Mono.fromCallable(() -> purge(cutoffMillis))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.warn("清理媒体缓存失败：{}", e.getMessage());
                return Mono.just(0L);
            });
    }

    /** 查询单条记录；未命中返回 {@code null}（{@code Mono.fromCallable} 收到 null 即发出空信号）。 */
    private CachedMedia query(String appId, MediaCacheKind kind, String fingerprint,
        String normalizeVersion) throws SQLException {
        if (!ensureReady()) {
            return null;
        }
        try (Connection connection = database.open();
            PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, appId);
            statement.setString(2, kind.name());
            statement.setString(3, fingerprint);
            statement.setString(4, normalizeVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    /** 写入或覆盖记录（SQLite upsert）。 */
    private void upsert(CachedMedia media) throws SQLException {
        if (!ensureReady()) {
            return;
        }
        try (Connection connection = database.open();
            PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, media.appId());
            statement.setString(2, media.kind().name());
            statement.setString(3, media.fingerprint());
            statement.setString(4, media.normalizeVersion());
            statement.setString(5, media.sourceUrl());
            statement.setString(6, media.filename());
            statement.setLong(7, media.fileSize());
            statement.setString(8, media.mediaId());
            statement.setString(9, media.contentUrl());
            statement.setLong(10, media.createdAt());
            statement.setLong(11, media.updatedAt());
            statement.executeUpdate();
        }
    }

    /** 刷新单条记录的使用时间。 */
    private void touchRecord(String appId, MediaCacheKind kind, String fingerprint,
        String normalizeVersion) throws SQLException {
        if (!ensureReady()) {
            return;
        }
        try (Connection connection = database.open();
            PreparedStatement statement = connection.prepareStatement(TOUCH_SQL)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, appId);
            statement.setString(3, kind.name());
            statement.setString(4, fingerprint);
            statement.setString(5, normalizeVersion);
            statement.executeUpdate();
        }
    }

    /** 删除使用时间早于给定时间戳的记录，返回删除条数。 */
    private long purge(long cutoffMillis) throws SQLException {
        if (!ensureReady()) {
            return 0L;
        }
        try (Connection connection = database.open();
            PreparedStatement statement = connection.prepareStatement(PURGE_SQL)) {
            statement.setLong(1, cutoffMillis);
            return statement.executeUpdate();
        }
    }

    /** 结果集映射为缓存记录；类型字段无法识别（如来自更新版本插件的库）时返回 {@code null}，按未命中处理。 */
    private static CachedMedia map(ResultSet rows) throws SQLException {
        MediaCacheKind kind = MediaCacheKind.from(rows.getString("kind"));
        if (kind == null) {
            log.debug("媒体缓存中存在无法识别的上传类型 [{}]，跳过该记录", rows.getString("kind"));
            return null;
        }
        return new CachedMedia(
            rows.getString("app_id"),
            kind,
            rows.getString("fingerprint"),
            rows.getString("normalize_version"),
            rows.getString("source_url"),
            rows.getString("filename"),
            rows.getLong("file_size"),
            rows.getString("media_id"),
            rows.getString("content_url"),
            rows.getLong("created_at"),
            rows.getLong("updated_at"));
    }

    /**
     * 确保缓存库已就绪（首次调用时建库并升级 schema），返回缓存是否可用。
     * 初始化失败后不再重试：缓存整体关闭，直到插件下次启动。
     */
    private boolean ensureReady() {
        if (unavailable) {
            return false;
        }
        if (database != null) {
            return true;
        }
        synchronized (this) {
            if (unavailable) {
                return false;
            }
            if (database != null) {
                return true;
            }
            try {
                MediaCacheDatabase created = new MediaCacheDatabase(databaseFile());
                created.initialize();
                database = created;
                log.info("媒体缓存库已就绪：{}（schema v{}）", created.file(),
                    MediaCacheDatabase.SCHEMA_VERSION);
                return true;
            } catch (SQLException | IOException | RuntimeException e) {
                unavailable = true;
                log.error("媒体缓存库初始化失败，本次运行不使用缓存（相同资源会被重复上传）：{}",
                    e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * 缓存库文件路径：{@code <Halo 插件根目录>/plugin-wechat-official-sync/wechat-media-cache.sqlite}，
     * 即与其他插件一致，在插件根目录下以自己的插件名建目录存放数据（插件包本身是同级的
     * {@code <插件名>-<版本>.jar}，两者互不影响，升级覆盖 jar 时不会动数据目录）。
     */
    private Path databaseFile() {
        return pluginsRootGetter.get()
            .resolve(DATA_DIRECTORY)
            .resolve(SQLITE_FILE_NAME);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
