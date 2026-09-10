package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatSyncService#resolveSourceUrl} 与 {@link WechatSyncService#resolveCommentMode}
 * 的行为验证：原文链接始终按「外部访问地址 + 文章路由」拼接；留言设置按选项映射并兼容旧开关。
 */
class WechatSyncServiceTest {

    private final WechatSyncService service = new WechatSyncService(null, null, null);

    @Test
    void joinsExternalBaseUrlAndPermalink() {
        String url = service.resolveSourceUrl("/archives/hello-world", "https://blog.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void toleratesPermalinkWithoutLeadingSlash() {
        String url = service.resolveSourceUrl("archives/hello-world", "https://blog.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void keepsAbsolutePermalinkAsIs() {
        // Halo 配置了绝对地址策略时，status.permalink 本身即完整地址，不再叠加外部访问地址
        String url = service.resolveSourceUrl("https://blog.example.com/archives/hello-world",
            "https://internal.example.com");
        assertThat(url).isEqualTo("https://blog.example.com/archives/hello-world");
    }

    @Test
    void emptyWhenPermalinkOrBaseUrlMissing() {
        assertThat(service.resolveSourceUrl(null, "https://blog.example.com")).isEmpty();
        assertThat(service.resolveSourceUrl("   ", "https://blog.example.com")).isEmpty();
        assertThat(service.resolveSourceUrl("/archives/hello-world", "")).isEmpty();
    }

    @Test
    void commentModeKeepsExplicitSelection() {
        WechatSetting setting = new WechatSetting();
        setting.setCommentMode(WechatSetting.COMMENT_MODE_ALL);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_ALL);
        setting.setCommentMode(WechatSetting.COMMENT_MODE_FANS);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_FANS);
        setting.setCommentMode(WechatSetting.COMMENT_MODE_CLOSE);
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }

    @Test
    void commentModeFallsBackToLegacySwitch() {
        // 升级前保存的「开启评论」开关：true 等价于所有人可留言，false / 未保存按关闭处理
        WechatSetting enabled = new WechatSetting();
        enabled.setOpenComment(true);
        assertThat(WechatSyncService.resolveCommentMode(enabled)).isEqualTo(WechatSetting.COMMENT_MODE_ALL);

        WechatSetting disabled = new WechatSetting();
        disabled.setOpenComment(false);
        assertThat(WechatSyncService.resolveCommentMode(disabled)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
        assertThat(WechatSyncService.resolveCommentMode(new WechatSetting()))
            .isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }

    @Test
    void commentModeIgnoresUnknownValue() {
        WechatSetting setting = new WechatSetting();
        setting.setCommentMode("mystery");
        assertThat(WechatSyncService.resolveCommentMode(setting)).isEqualTo(WechatSetting.COMMENT_MODE_CLOSE);
    }
}
