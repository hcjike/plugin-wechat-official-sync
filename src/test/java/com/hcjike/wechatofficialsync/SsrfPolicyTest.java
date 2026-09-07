package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * {@link SsrfPolicy} 白名单解析与匹配验证：域名精确/通配、单 IP、CIDR、注释、非法条目与地址族隔离。
 *
 * <p>全部使用字面量，避免依赖真实 DNS / 外网。</p>
 */
class SsrfPolicyTest {

    private static InetAddress addr(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new AssertionError("测试用例 IP 非法：" + ip, e);
        }
    }

    @Test
    void emptyOrNullYieldsClosedPolicy() {
        assertThat(SsrfPolicy.parse(null).isEmpty()).isTrue();
        assertThat(SsrfPolicy.parse("   ").isEmpty()).isTrue();
        assertThat(SsrfPolicy.parse("# only comment\n\n").isEmpty()).isTrue();
        assertThat(SsrfPolicy.EMPTY.isHostAllowed("127.0.0.1")).isFalse();
        assertThat(SsrfPolicy.EMPTY.isAddressAllowed(addr("192.168.1.1"))).isFalse();
    }

    @Test
    void matchesExactHostCaseInsensitiveAndTrailingDot() {
        SsrfPolicy policy = SsrfPolicy.parse("halo.internal");
        assertThat(policy.isHostAllowed("halo.internal")).isTrue();
        assertThat(policy.isHostAllowed("HALO.Internal")).isTrue();
        assertThat(policy.isHostAllowed("halo.internal.")).isTrue();
        assertThat(policy.isHostAllowed("sub.halo.internal")).isFalse();
        assertThat(policy.isHostAllowed("other.com")).isFalse();
    }

    @Test
    void wildcardMatchesRootAndSubdomains() {
        SsrfPolicy policy = SsrfPolicy.parse("*.example.com");
        assertThat(policy.isHostAllowed("example.com")).isTrue();
        assertThat(policy.isHostAllowed("img.example.com")).isTrue();
        assertThat(policy.isHostAllowed("a.b.example.com")).isTrue();
        // 不应匹配到后缀相似但不同域的地址
        assertThat(policy.isHostAllowed("notexample.com")).isFalse();
        assertThat(policy.isHostAllowed("example.com.evil.com")).isFalse();
    }

    @Test
    void singleIpTreatedAsFullPrefixCidr() {
        SsrfPolicy policy = SsrfPolicy.parse("192.168.1.20");
        assertThat(policy.isAddressAllowed(addr("192.168.1.20"))).isTrue();
        assertThat(policy.isAddressAllowed(addr("192.168.1.21"))).isFalse();
        assertThat(policy.isAddressAllowed(addr("10.0.0.1"))).isFalse();
    }

    @Test
    void cidrMatchesSegmentBoundaries() {
        SsrfPolicy policy = SsrfPolicy.parse("10.0.0.0/8");
        assertThat(policy.isAddressAllowed(addr("10.0.0.0"))).isTrue();
        assertThat(policy.isAddressAllowed(addr("10.255.255.255"))).isTrue();
        assertThat(policy.isAddressAllowed(addr("11.0.0.0"))).isFalse();

        SsrfPolicy slash24 = SsrfPolicy.parse("192.168.1.0/24");
        assertThat(slash24.isAddressAllowed(addr("192.168.1.123"))).isTrue();
        assertThat(slash24.isAddressAllowed(addr("192.168.2.1"))).isFalse();
    }

    @Test
    void ipv6LiteralAndCidr() {
        SsrfPolicy single = SsrfPolicy.parse("fd00::1");
        assertThat(single.isAddressAllowed(addr("fd00::1"))).isTrue();
        assertThat(single.isAddressAllowed(addr("fd00::2"))).isFalse();

        SsrfPolicy cidr = SsrfPolicy.parse("fd00::/8");
        assertThat(cidr.isAddressAllowed(addr("fd12:3456::1"))).isTrue();
        assertThat(cidr.isAddressAllowed(addr("fe80::1"))).isFalse();
    }

    @Test
    void addressFamilyMismatchDoesNotMatch() {
        // IPv4 网段不应匹配同数值的 IPv6 地址，反之亦然
        SsrfPolicy v4 = SsrfPolicy.parse("192.168.0.0/16");
        assertThat(v4.isAddressAllowed(addr("::1"))).isFalse();
        SsrfPolicy v6 = SsrfPolicy.parse("fd00::/8");
        assertThat(v6.isAddressAllowed(addr("192.168.1.1"))).isFalse();
    }

    @Test
    void bracketsInlineCommentsAndMultipleEntries() {
        SsrfPolicy policy = SsrfPolicy.parse("""
            # 内网自站
            192.168.1.20          # 行内注释
            [fd00::1]
            halo.internal
            10.0.0.0/8
            """);
        assertThat(policy.isAddressAllowed(addr("192.168.1.20"))).isTrue();
        assertThat(policy.isAddressAllowed(addr("fd00::1"))).isTrue();
        assertThat(policy.isAddressAllowed(addr("10.9.9.9"))).isTrue();
        assertThat(policy.isHostAllowed("halo.internal")).isTrue();
    }

    @Test
    void invalidEntriesAreIgnoredSafely() {
        // 非法前缀、非法 IP、带 '/' 的伪域名均被忽略，不误放行
        SsrfPolicy policy = SsrfPolicy.parse("""
            10.0.0.0/999
            999.999.999.999
            not a host/with slash
            1.2.3.4/abc
            """);
        assertThat(policy.isEmpty()).isTrue();
        assertThat(policy.isAddressAllowed(addr("10.0.0.1"))).isFalse();
    }
}
