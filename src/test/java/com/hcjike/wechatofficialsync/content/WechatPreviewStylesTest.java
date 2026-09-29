package com.hcjike.wechatofficialsync.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatPreviewStyles} 的行为验证：样式块置于正文之前、正文原样保留，
 * 且样式块覆盖预览渲染依赖的两处（微信原生代码块结构与布局表格标记）。
 */
class WechatPreviewStylesTest {

    @Test
    void prependsStyleBlockAndKeepsContentUnchanged() {
        String content = "<section style=\"font-size:16px;\"><p>正文</p></section>";

        String decorated = WechatPreviewStyles.withPreviewStyles(content);

        // 样式块在前，正文原样跟在后面（逐字一致，便于客户端渲染与人工核对）
        assertThat(decorated).startsWith("<style>").endsWith(content);
        assertThat(decorated).contains("</style>");
    }

    @Test
    void carriesStylesRequiredByPreviewRendering() {
        String decorated = WechatPreviewStyles.withPreviewStyles("<p>正文</p>");

        // 微信原生代码块（行号列 + 代码区）与布局表格标记：缺任一项预览都会「散架」或看不出并排结构
        assertThat(decorated).contains(".code-snippet__fix")
            .contains("ul.code-snippet__line-index")
            .contains("pre.code-snippet__js")
            .contains(".wechat-layout-table td");
    }

    @Test
    void keepsBlankContentWithoutStyle() {
        // 空正文加样式没有意义：原样返回，不产出只有样式的空内容
        assertThat(WechatPreviewStyles.withPreviewStyles("")).isEmpty();
        assertThat(WechatPreviewStyles.withPreviewStyles("   ")).isEqualTo("   ");
        assertThat(WechatPreviewStyles.withPreviewStyles(null)).isEmpty();
    }
}
