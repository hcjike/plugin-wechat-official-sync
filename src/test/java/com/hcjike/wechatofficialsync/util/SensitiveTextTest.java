package com.hcjike.wechatofficialsync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SensitiveText} 的行为验证：URI 查询串、JSON 字段与 Authorization 头里的凭据被替换为占位符，
 * 其余文本（含非敏感参数）保持原样。
 */
class SensitiveTextTest {

    @Test
    void masksSensitiveQueryParameters() {
        String text = "403 Forbidden from POST https://api.weixin.qq.com/cgi-bin/draft/add"
            + "?access_token=TOKEN-VALUE&lang=zh_CN";

        String masked = SensitiveText.mask(text);

        assertThat(masked).contains("access_token=" + SensitiveText.MASK);
        assertThat(masked).doesNotContain("TOKEN-VALUE");
        // 非敏感参数与路径保持原样，便于排查是哪个接口出的问题
        assertThat(masked).contains("/cgi-bin/draft/add").contains("lang=zh_CN");
    }

    @Test
    void masksAppSecretInTokenRequestUri() {
        String uri = "GET https://api.weixin.qq.com/cgi-bin/token"
            + "?grant_type=client_credential&appid=wx1234567890&secret=APP-SECRET-VALUE";

        String masked = SensitiveText.mask(uri);

        assertThat(masked).contains("secret=" + SensitiveText.MASK);
        assertThat(masked).doesNotContain("APP-SECRET-VALUE");
        // AppID 是公开标识，排查时需要保留
        assertThat(masked).contains("appid=wx1234567890");
    }

    @Test
    void masksSensitiveJsonFields() {
        String json = "{\"access_token\":\"TOKEN-VALUE\",\"expires_in\":7200,\"secret\":\"S\"}";

        String masked = SensitiveText.mask(json);

        assertThat(masked).contains("\"access_token\":\"" + SensitiveText.MASK + "\"");
        assertThat(masked).contains("\"secret\":\"" + SensitiveText.MASK + "\"");
        assertThat(masked).doesNotContain("TOKEN-VALUE").doesNotContain("\"S\"");
        assertThat(masked).contains("expires_in");
    }

    @Test
    void masksAuthorizationHeaderAndSignedImageUrl() {
        assertThat(SensitiveText.mask("Authorization: Bearer abc.def.ghi"))
            .isEqualTo("Authorization: " + SensitiveText.MASK);
        // 图床/对象存储的签名参数同样脱敏，路径保留
        assertThat(SensitiveText.mask("https://cdn.example.com/a.png?sign=ZZZ&t=1"))
            .isEqualTo("https://cdn.example.com/a.png?sign=" + SensitiveText.MASK + "&t=1");
    }

    @Test
    void isCaseInsensitive() {
        String masked = SensitiveText.mask("https://x/y?Access_Token=ABC&KEY=K");

        assertThat(masked).doesNotContain("ABC").doesNotContain("=K");
        assertThat(masked).contains("Access_Token=" + SensitiveText.MASK);
    }

    @Test
    void keepsOrdinaryTextUntouched() {
        String text = "缓存清理完成：删除 3 条超过 30 天未使用的缓存记录";

        assertThat(SensitiveText.mask(text)).isEqualTo(text);
        assertThat(SensitiveText.containsSensitive(text)).isFalse();
        assertThat(SensitiveText.sensitiveKeyCount(text)).isZero();
    }

    @Test
    void handlesNullAndEmptySafely() {
        assertThat(SensitiveText.mask(null)).isEmpty();
        assertThat(SensitiveText.mask("")).isEmpty();
        assertThat(SensitiveText.containsSensitive(null)).isFalse();
    }

    @Test
    void containsSensitiveDetectsCredentials() {
        assertThat(SensitiveText.containsSensitive("https://x/y?access_token=ABC")).isTrue();
        assertThat(SensitiveText.containsSensitive("{\"secret\":\"S\"}")).isTrue();
        assertThat(SensitiveText.sensitiveKeyCount("?access_token=A&sign=B")).isEqualTo(2);
    }
}
