package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * {@link SsrfGuard} 的行为验证：URL 结构校验、受限 IP 网段判定与统一下载入口的预检。
 *
 * <p>测试仅使用 IP 字面量与 {@code localhost}，避免依赖真实 DNS / 外网。</p>
 */
class SsrfGuardTest {

    private static InetAddress addr(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new AssertionError("测试用例 IP 非法：" + ip, e);
        }
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> SsrfGuard.validateUrl("file:///etc/passwd"))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateUrl("ftp://example.com/a.png"))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateUrl("gopher://example.com"))
            .isInstanceOf(WechatApiException.class);
    }

    @Test
    void rejectsUserInfoAndMissingHost() {
        // userinfo 常被用于混淆真实主机名（http://expected@evil/）
        assertThatThrownBy(() -> SsrfGuard.validateUrl("http://user:pass@example.com/a.png"))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateUrl("http://example.com@127.0.0.1/a.png"))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateUrl("http:///a.png"))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateUrl("  "))
            .isInstanceOf(WechatApiException.class);
    }

    @Test
    void acceptsPlainHttpAndHttps() {
        assertThat(SsrfGuard.validateUrl("https://example.com/a.png").getHost()).isEqualTo("example.com");
        assertThat(SsrfGuard.validateUrl("http://example.com:8090/a.png").getPort()).isEqualTo(8090);
    }

    @Test
    void blocksLoopbackPrivateLinkLocalAndMetadataAddresses() {
        // 环回
        assertThat(SsrfGuard.isBlockedAddress(addr("127.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("127.5.6.7"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("::1"))).isTrue();
        // 私网（站点本地）
        assertThat(SsrfGuard.isBlockedAddress(addr("10.0.0.5"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("192.168.1.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("172.16.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("172.31.255.255"))).isTrue();
        // 链路本地 / 云平台元数据（AWS、GCP、Azure 169.254.169.254）
        assertThat(SsrfGuard.isBlockedAddress(addr("169.254.169.254"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("fe80::1"))).isTrue();
        // 运营商级 NAT / 阿里云元数据 100.100.100.200
        assertThat(SsrfGuard.isBlockedAddress(addr("100.64.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("100.100.100.200"))).isTrue();
        // 未指定 / 组播
        assertThat(SsrfGuard.isBlockedAddress(addr("0.0.0.0"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("224.0.0.1"))).isTrue();
        // IPv6 唯一本地地址 fc00::/7
        assertThat(SsrfGuard.isBlockedAddress(addr("fc00::1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("fd12:3456::1"))).isTrue();
        // 测试网段
        assertThat(SsrfGuard.isBlockedAddress(addr("192.0.2.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("198.51.100.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("203.0.113.1"))).isTrue();
        assertThat(SsrfGuard.isBlockedAddress(addr("198.18.0.1"))).isTrue();
        // null 视为受限
        assertThat(SsrfGuard.isBlockedAddress(null)).isTrue();
    }

    @Test
    void allowsPublicAddresses() {
        assertThat(SsrfGuard.isBlockedAddress(addr("8.8.8.8"))).isFalse();
        assertThat(SsrfGuard.isBlockedAddress(addr("1.1.1.1"))).isFalse();
        assertThat(SsrfGuard.isBlockedAddress(addr("93.184.216.34"))).isFalse();
        // 172.32 不在 172.16/12 私网范围内
        assertThat(SsrfGuard.isBlockedAddress(addr("172.32.0.1"))).isFalse();
        // 100.128 不在 100.64/10 CGNAT 范围内
        assertThat(SsrfGuard.isBlockedAddress(addr("100.128.0.1"))).isFalse();
        // 公网 IPv6
        assertThat(SsrfGuard.isBlockedAddress(addr("2606:2800:220:1:248:1893:25c8:1946"))).isFalse();
    }

    @Test
    void validateAndResolveRejectsLoopbackLiteral() {
        // IP 字面量不触发真实 DNS，可直接断言预检拒绝内网目标
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://127.0.0.1/upload/a.png", SsrfPolicy.EMPTY))
            .isInstanceOf(WechatApiException.class)
            .hasMessageContaining("受限网络");
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://[::1]/a.png", SsrfPolicy.EMPTY))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(
            () -> SsrfGuard.validateAndResolve("http://169.254.169.254/latest/meta-data", SsrfPolicy.EMPTY))
            .isInstanceOf(WechatApiException.class);
    }

    @Test
    void validateAndResolveAcceptsPublicLiteral() {
        assertThat(SsrfGuard.validateAndResolve("http://93.184.216.34/a.png", SsrfPolicy.EMPTY).getHost())
            .isEqualTo("93.184.216.34");
    }

    @Test
    void allowlistBySingleIpPermitsThatInternalTarget() {
        // 内网 Halo 场景：管理员将自站内网 IP 加入白名单后，该地址得以放行
        SsrfPolicy policy = SsrfPolicy.parse("192.168.1.20");
        assertThat(SsrfGuard.validateAndResolve("http://192.168.1.20:8090/upload/a.png", policy).getHost())
            .isEqualTo("192.168.1.20");
        // 白名单仅放行指定目标，其余内网/元数据地址仍被拒绝
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://192.168.1.21/a.png", policy))
            .isInstanceOf(WechatApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://169.254.169.254/x", policy))
            .isInstanceOf(WechatApiException.class);
    }

    @Test
    void allowlistByCidrPermitsWholeSegment() {
        SsrfPolicy policy = SsrfPolicy.parse("10.0.0.0/8");
        assertThat(SsrfGuard.validateAndResolve("http://10.1.2.3/a.png", policy).getHost()).isEqualTo("10.1.2.3");
        // /8 之外的内网地址（192.168/16）不在本白名单，仍拒绝
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://192.168.0.1/a.png", policy))
            .isInstanceOf(WechatApiException.class);
    }

    @Test
    void allowlistByHostPermitsWithoutResolving() {
        // 域名命中白名单时直接放行（不依赖真实 DNS），适用于内网域名自解析场景
        SsrfPolicy policy = SsrfPolicy.parse("halo.internal\n*.example.com");
        assertThat(policy.isHostAllowed("halo.internal")).isTrue();
        assertThat(policy.isHostAllowed("HALO.INTERNAL")).isTrue();
        assertThat(policy.isHostAllowed("img.example.com")).isTrue();
        assertThat(policy.isHostAllowed("example.com")).isTrue();
        assertThat(policy.isHostAllowed("evil.com")).isFalse();
        assertThatThrownBy(() -> SsrfGuard.validateAndResolve("http://127.0.0.1/a.png", policy))
            .isInstanceOf(WechatApiException.class);
    }
}
