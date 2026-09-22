package com.hcjike.wechatofficialsync.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志与对外消息的脱敏工具：把 URI / JSON 中敏感参数的值替换为 {@value #MASK}，其余文本原样保留。
 *
 * <p><b>为什么需要它</b>：Spring WebClient 的 {@code WebClientResponseException} 默认会把
 * 「请求方法 + 完整 URI（含查询串）」写进异常 message，而插件调用的微信接口 URI 查询串里带着
 * {@code access_token}（获取 token 的请求还带着 {@code secret}=AppSecret）。这类 message 一旦出现
 * （例如反向代理返回 403/404、网关返回 502），既会进服务端日志，也会经
 * {@code SyncRecord.failed(...)} <b>持久化为同步任务的失败原因</b>，展示在文章列表并返回给 MCP 调用方。</p>
 *
 * <p>本工具只做「按已知敏感键名脱敏」，不改动文本其余部分——因此日志仍能定位是哪个接口、哪个参数出了问题，
 * 只是看不到凭据本身。插件自身构造的异常消息（来自微信 JSON 响应的 errcode/errmsg）本就不含凭据，
 * 传入后原样返回。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public final class SensitiveText {

    /** 脱敏后的占位符。 */
    public static final String MASK = "***";

    /**
     * 需要脱敏的键名（大小写不敏感）：覆盖微信接口、常见图床/对象存储与通用鉴权字段。
     *
     * <p>{@code appid} 不在其中——AppID 是公开标识，排查时有用；AppSecret 才需要脱敏。</p>
     */
    private static final String KEYS = "access_token|accessToken|token|secret|appsecret|app_secret"
        + "|client_secret|clientSecret|sign|signature|key|apikey|api_key|apiKey"
        + "|password|passwd|pwd|credential|credentials|authorization";

    /** JSON 形式：{@code "access_token":"xxx"}。 */
    private static final Pattern JSON_PAIR = Pattern.compile(
        "\"(" + KEYS + ")\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** 查询串 / 表单形式：{@code access_token=xxx&...}；值到分隔符为止。 */
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)\\b(" + KEYS + ")=([^&\\s\"'<>,;)\\]}]+)");

    /** 请求头形式：{@code Authorization: Bearer xxx}；值取到行尾（避免只脱敏「Bearer」）。 */
    private static final Pattern HEADER = Pattern.compile(
        "(?i)\\b(authorization)\\s*:\\s*[^\\r\\n]+");

    private SensitiveText() {
    }

    /**
     * 脱敏文本中的凭据：{@code key=value}、{@code "key":"value"} 与 {@code Authorization: ...} 三类形式。
     *
     * @param text 待处理的文本，可为 {@code null}
     * @return 脱敏后的文本；{@code null} 输入返回空串（便于直接当作日志参数使用）
     */
    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return text;
        }
        String masked = JSON_PAIR.matcher(text).replaceAll("\"$1\":\"" + MASK + "\"");
        masked = HEADER.matcher(masked).replaceAll("$1: " + MASK);
        return ASSIGNMENT.matcher(masked).replaceAll("$1=" + MASK);
    }

    /**
     * 判断文本中是否含有需要脱敏的凭据（用于「内容是否被改动」的短路判断与自检）。
     */
    public static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return JSON_PAIR.matcher(text).find() || HEADER.matcher(text).find()
            || ASSIGNMENT.matcher(text).find();
    }

    /** 便于测试与排查：返回命中的敏感键数量（不关心具体值）。 */
    static int sensitiveKeyCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        count += countMatches(JSON_PAIR.matcher(text));
        count += countMatches(HEADER.matcher(text));
        count += countMatches(ASSIGNMENT.matcher(text));
        return count;
    }

    private static int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
