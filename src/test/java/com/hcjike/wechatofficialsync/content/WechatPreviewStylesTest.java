package com.hcjike.wechatofficialsync.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatPreviewStyles} 的行为验证：把预览所需的样式内联到元素上（不产出 {@code <style>} 块），
 * 代码块行号改写为字面数字，布局表格补预览边框，且无需装饰的内容逐字保留。
 *
 * <p>关键点是<b>脱离样式表也能渲染</b>：MCP 客户端（AI 对话界面等）可能丢掉 {@code <style>} 标签、
 * AI 也可能转述 HTML，所以样式必须长在元素自己身上。</p>
 */
class WechatPreviewStylesTest {

    /** 与 {@link WechatContentBeautifier} 产出的微信原生代码块结构一致：行号列 + 逐行 code。 */
    private static final String CODE_BLOCK = "<section class=\"code-snippet__fix code-snippet__js\">"
        + "<ul class=\"code-snippet__line-index code-snippet__js\"><li></li><li></li></ul>"
        + "<pre class=\"code-snippet__js\" data-lang=\"java\"><code><span>a</span></code>"
        + "<code><span>b</span></code></pre></section>";

    @Test
    void inlinesCodeBlockStylesAndLiteralLineNumbers() {
        String decorated = WechatPreviewStyles.withInlineStyles(CODE_BLOCK);

        // 不再下发样式块：样式长在元素上，客户端丢掉 <style> / AI 转述片段都不影响渲染
        assertThat(decorated).doesNotContain("<style");
        assertThat(decorated)
            .contains("display:flex")       // 外层容器：行号列与代码区并排
            .contains("flex:none")          // 行号列：固定宽度
            .contains("flex:1")             // 代码区：占满剩余宽度
            .contains("white-space:pre");   // 代码行不折行
        // 行号写成字面数字（不再依赖 CSS 计数器），与代码行一一对应
        assertThat(decorated)
            .contains("<li style=\"height:1.7em;list-style:none;\">1</li>")
            .contains("<li style=\"height:1.7em;list-style:none;\">2</li>");
    }

    @Test
    void appendsLayoutTablePreviewBordersAndGap() {
        String content = "<table class=\"wechat-layout-table\"><tbody><tr>"
            + "<td>左</td><td style=\"padding:8px\">右</td></tr></tbody></table>"
            + "<table class=\"wechat-layout-table\"><tbody><tr><td>下一个卡片</td></tr></tbody></table>";

        String decorated = WechatPreviewStyles.withInlineStyles(content);

        // 单元格补浅灰细边框（仅预览标记）；原有行内样式保留，补充声明追加在后
        assertThat(decorated).contains("<td style=\"border:1px solid #e6e6e6;\">左</td>")
            .contains("<td style=\"padding:8px;border:1px solid #e6e6e6;\">右</td>");
        // 紧邻的第二个布局表格留出上间距，避免两个卡片的边框连成一片
        assertThat(decorated).contains("<table class=\"wechat-layout-table\" style=\"margin-top:10px;\">");
    }

    @Test
    void keepsContentUnchangedWhenNothingNeedsDecoration() {
        String content = "<section style=\"font-size:16px;\"><p>正文</p></section>";

        // 没有代码块与布局表格时逐字保留：既不前置样式块，也不改写任何元素
        assertThat(WechatPreviewStyles.withInlineStyles(content)).isEqualTo(content);
    }

    @Test
    void keepsBlankContentAsIs() {
        // 空正文补样式没有意义：原样返回，不产出只有样式的空内容
        assertThat(WechatPreviewStyles.withInlineStyles("")).isEmpty();
        assertThat(WechatPreviewStyles.withInlineStyles("   ")).isEqualTo("   ");
        assertThat(WechatPreviewStyles.withInlineStyles(null)).isEmpty();
    }
}
