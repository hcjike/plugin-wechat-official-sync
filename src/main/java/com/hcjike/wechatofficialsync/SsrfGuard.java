package com.hcjike.wechatofficialsync;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 出站下载（封面图 / 正文图片）的 SSRF 防护工具。
 *
 * <p>用户可在同步请求中提交任意的封面地址与正文 HTML，其中 {@code <img src>} 也可为任意外部地址。
 * 插件会从 Halo 服务端主动拉取这些地址，若不加限制即可被用于访问环回、内网、链路本地或云平台
 * 元数据等敏感资源（SSRF）。本类提供统一的目标校验：</p>
 * <ol>
 *   <li>{@link #validateUrl(String)}：用 URI 解析器校验结构——仅允许 http/https，必须有主机名，
 *       禁止携带用户名/密码（userinfo）等易被用于绕过的结构；</li>
 *   <li>{@link #validateAndResolve(String, SsrfPolicy)}：在结构校验之上解析目标主机的全部 IP，逐一拒绝受限网段，命中内网白名单的目标则放行；</li>
 *   <li>{@link #isBlockedAddress(InetAddress)}：判断单个 IP 是否为环回、私网、链路本地、组播、
 *       未指定地址或云平台元数据地址，供连接期的自定义解析器复用，防止 DNS rebinding 与重定向绕过。</li>
 * </ol>
 *
 * <p>连接期的实际目标由 {@code SsrfSafeAddressResolverGroup} 再次用 {@link #isBlockedAddress} 校验，
 * 与本类的预检形成纵深防御。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
final class SsrfGuard {

    private SsrfGuard() {
    }

    /**
     * 校验 URL 结构：仅允许 http/https、必须有主机名、禁止 userinfo。
     *
     * @param url 待校验地址
     * @return 规范化后的 {@link URI}
     * @throws WechatApiException 地址非法或协议不受支持时抛出
     */
    static URI validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new WechatApiException("下载地址为空");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new WechatApiException("下载地址不是合法的 URI：" + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
            || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new WechatApiException("仅允许 http/https 地址，已拒绝：" + scheme);
        }
        // 禁止 http://user:pass@host 形式的 userinfo，避免借助 @ 混淆主机名实现绕过
        if (uri.getUserInfo() != null || url.contains("@")) {
            throw new WechatApiException("下载地址不得包含用户名/密码等 userinfo");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new WechatApiException("下载地址缺少合法主机名");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new WechatApiException("下载地址端口非法：" + port);
        }
        return uri;
    }

    /**
     * 结构校验 + 解析主机全部 IP 并拒绝受限网段。用于发起下载前的预检，给出清晰错误信息。
     *
     * <p>命中 {@code policy} 白名单的目标（域名精确/通配，或 IP 落在允许的 CIDR 内）会被放行，
     * 即便其指向内网/环回；未命中白名单的受限地址仍一律拒绝。白名单为空时等价于不放行任何内网地址。</p>
     *
     * <p>{@link InetAddress#getAllByName(String)} 是阻塞式 DNS 解析，调用方须置于
     * {@code Schedulers.boundedElastic()} 上执行。</p>
     *
     * @param url    待校验地址
     * @param policy 内网白名单策略，{@code null} 按空白名单处理
     * @return 校验通过的 {@link URI}
     * @throws WechatApiException 结构非法、主机无法解析或命中受限网段且不在白名单时抛出
     */
    static URI validateAndResolve(String url, SsrfPolicy policy) {
        SsrfPolicy effective = policy == null ? SsrfPolicy.EMPTY : policy;
        URI uri = validateUrl(url);
        String host = uri.getHost();
        // 管理员显式信任的主机（域名精确/通配）直接放行，无需再解析 IP
        if (effective.isHostAllowed(host)) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new WechatApiException("无法解析下载地址的主机名：" + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new WechatApiException("下载地址主机名未解析到任何 IP：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address, effective)) {
                throw new WechatApiException(
                    "已拒绝下载：地址「" + host + "」指向环回、内网、链路本地或元数据等受限网络，"
                        + "且不在插件设置的图片下载内网白名单中");
            }
        }
        return uri;
    }

    /**
     * 结合白名单判定单个 IP 是否应被拒绝：命中受限网段<b>且</b>不在白名单 CIDR 内才拒绝。
     *
     * @param address 待判定 IP
     * @param policy  内网白名单策略，{@code null} 按空白名单处理
     */
    static boolean isBlocked(InetAddress address, SsrfPolicy policy) {
        if (!isBlockedAddress(address)) {
            return false;
        }
        SsrfPolicy effective = policy == null ? SsrfPolicy.EMPTY : policy;
        return !effective.isAddressAllowed(address);
    }

    /**
     * 判断 IP 是否属于禁止访问的受限网段：环回、私网（站点本地）、链路本地、组播、未指定地址，
     * 以及 IPv4 CGNAT（含阿里云元数据 100.100.100.200）、IPv6 唯一本地地址（fc00::/7）与若干测试网段。
     *
     * @param address 待判断 IP，{@code null} 视为受限
     * @return 命中任一受限网段返回 {@code true}
     */
    static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        // JDK 已覆盖：环回（127/8、::1）、链路本地（169.254/16、fe80::）、组播、未指定（0.0.0.0、::）
        // 以及 IPv4 站点本地私网（10/8、172.16/12、192.168/16）
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            int b2 = bytes[2] & 0xff;
            // 0.0.0.0/8「本网络」
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10 运营商级 NAT / 共享地址空间（阿里云元数据 100.100.100.200 亦在此段）
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 192.0.0.0/24、192.0.2.0/24（TEST-NET-1）
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
                return true;
            }
            // 198.18.0.0/15 基准测试网段
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 198.51.100.0/24（TEST-NET-2）、203.0.113.0/24（TEST-NET-3）
            if (b0 == 198 && b1 == 51 && b2 == 100) {
                return true;
            }
            return b0 == 203 && b1 == 0 && b2 == 113;
        }
        if (bytes.length == 16) {
            // fc00::/7 IPv6 唯一本地地址（JDK 的 isSiteLocalAddress 仅判定已废弃的 fec0::/10）
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // ::ffff:0:0/96 IPv4 映射地址：正常情况下 JDK 会返回 Inet4Address，此处兜底解包再判
            if (isIPv4Mapped(bytes)) {
                byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isBlockedAddress(InetAddress.getByAddress(v4));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
            return false;
        }
        // 未知地址族一律拒绝
        return true;
    }

    /** 判断是否为 ::ffff:a.b.c.d 形式的 IPv4 映射 IPv6 地址。 */
    private static boolean isIPv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    /**
     * 由 URI 推导展示用的 host[:port]，仅用于日志与错误信息，不含任何凭据。
     */
    static String describe(URI uri) {
        if (uri == null) {
            return "";
        }
        int port = uri.getPort();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return port == -1 ? host : host + ":" + port;
    }
}
