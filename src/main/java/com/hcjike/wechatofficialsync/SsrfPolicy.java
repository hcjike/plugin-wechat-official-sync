package com.hcjike.wechatofficialsync;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 出站图片下载的「内网白名单」策略。
 *
 * <p>{@link SsrfGuard} 默认拒绝一切指向环回、内网、链路本地、元数据等受限网段的目标，以防止 SSRF。
 * 但当 Halo 自身部署在内网、封面/正文图片也位于内网地址时，这一默认策略会连带拦住合法的自站图片。
 * 本类承载管理员在插件设置里显式配置的<b>白名单</b>：只有命中白名单的域名/IP/网段才被放行访问受限地址，
 * 其余仍按 SSRF 规则拒绝。</p>
 *
 * <p>白名单是「明确的允许列表」而非「放开全部内网」的开关——留空即完全关闭（最安全，默认值），
 * 每一行一个条目，支持：</p>
 * <ul>
 *   <li>域名精确匹配：{@code halo.internal}；</li>
 *   <li>域名通配（子域）：{@code *.example.com} 匹配 {@code example.com} 及其所有子域；</li>
 *   <li>单个 IP：{@code 192.168.1.10}、{@code fd00::1}（按 /32、/128 处理）；</li>
 *   <li>CIDR 网段：{@code 192.168.0.0/16}、{@code 10.0.0.0/8}、{@code fd00::/8}；</li>
 *   <li>以 {@code #} 开头的行或行内 {@code #} 之后的内容视为注释。</li>
 * </ul>
 *
 * <p>本类不可变、线程安全；解析结果由 {@code WechatMpClient} 以 volatile 引用持有，供预检与连接期解析器共享。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
final class SsrfPolicy {

    /** 空白名单：不放行任何受限地址（默认、最安全）。 */
    static final SsrfPolicy EMPTY = new SsrfPolicy(Set.of(), List.of());

    /** 允许的域名（已小写规范化；以 {@code *.} 开头表示子域通配）。 */
    private final Set<String> allowedHosts;

    /** 允许的 IP / CIDR 网段。 */
    private final List<Cidr> allowedCidrs;

    private SsrfPolicy(Set<String> allowedHosts, List<Cidr> allowedCidrs) {
        this.allowedHosts = allowedHosts;
        this.allowedCidrs = allowedCidrs;
    }

    /**
     * 解析管理员配置的白名单文本（每行一个条目）。非法条目静默忽略，保证不会因个别笔误而整体失效或误放行。
     *
     * @param raw 白名单原文，可为空
     * @return 解析后的策略；输入为空时返回 {@link #EMPTY}
     */
    static SsrfPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }
        Set<String> hosts = new LinkedHashSet<>();
        List<Cidr> cidrs = new ArrayList<>();
        for (String rawLine : raw.split("\\R")) {
            String entry = stripComment(rawLine).trim();
            if (entry.isEmpty()) {
                continue;
            }
            String host = stripBrackets(entry);
            String ipPart = host;
            String prefixPart = null;
            int slash = host.indexOf('/');
            if (slash >= 0) {
                ipPart = host.substring(0, slash).trim();
                prefixPart = host.substring(slash + 1).trim();
            }
            if (isIpv4Literal(ipPart) || isIpv6Literal(ipPart)) {
                Cidr cidr = Cidr.parse(ipPart, prefixPart);
                if (cidr != null) {
                    cidrs.add(cidr);
                }
            } else if (prefixPart == null) {
                // 普通域名（含 *. 通配）；带 '/' 的非法域名条目忽略
                hosts.add(normalizeHost(host));
            }
        }
        if (hosts.isEmpty() && cidrs.isEmpty()) {
            return EMPTY;
        }
        return new SsrfPolicy(Set.copyOf(hosts), List.copyOf(cidrs));
    }

    boolean isEmpty() {
        return allowedHosts.isEmpty() && allowedCidrs.isEmpty();
    }

    /**
     * 主机名是否命中白名单（精确或 {@code *.} 子域通配）。
     *
     * @param host 目标主机名（域名或 IP 字面量）
     */
    boolean isHostAllowed(String host) {
        if (host == null || allowedHosts.isEmpty()) {
            return false;
        }
        String normalized = normalizeHost(stripBrackets(host));
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.")) {
                String root = allowed.substring(2);
                String suffix = allowed.substring(1);
                if (normalized.equals(root) || normalized.endsWith(suffix)) {
                    return true;
                }
            } else if (normalized.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IP 是否落在白名单的任一 CIDR/单机网段内。
     */
    boolean isAddressAllowed(InetAddress address) {
        if (address == null) {
            return false;
        }
        for (Cidr cidr : allowedCidrs) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "SsrfPolicy{hosts=" + allowedHosts + ", cidrs=" + allowedCidrs + '}';
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String stripBrackets(String host) {
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /** 规范化域名：小写、去除末尾的根点。 */
    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isIpv4Literal(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 主机条目中若含 ':' 只可能是 IPv6 字面量（域名不含冒号）。 */
    private static boolean isIpv6Literal(String s) {
        return s.indexOf(':') >= 0;
    }

    /**
     * 一条 CIDR 或单机网段。{@code network} 为网络地址字节，{@code prefix} 为前缀位数。
     */
    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String ip, String prefixStr) {
            byte[] address;
            try {
                // 仅在 ip 为 IPv4/IPv6 字面量时调用，getByName 直接解析字面量、不触发 DNS
                address = InetAddress.getByName(ip).getAddress();
            } catch (UnknownHostException | RuntimeException e) {
                return null;
            }
            int max = address.length * 8;
            int prefix = max;
            if (prefixStr != null && !prefixStr.isBlank()) {
                try {
                    prefix = Integer.parseInt(prefixStr.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (prefix < 0 || prefix > max) {
                return null;
            }
            return new Cidr(address, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] target = address.getAddress();
            if (target.length != network.length) {
                // 地址族不同（IPv4 vs IPv6）不匹配
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits != 0 && fullBytes < target.length) {
                int mask = (0xff << (8 - remainingBits)) & 0xff;
                return (target[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }

        @Override
        public String toString() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
            } catch (UnknownHostException e) {
                return "?/" + prefix;
            }
        }
    }
}
