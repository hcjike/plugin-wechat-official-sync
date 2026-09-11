package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatContentBeautifier} 的行为验证：内联样式注入、用户样式优先、标题颜色可配、
 * 代码块微信原生结构重建、根节点包裹与安全清理。
 */
class WechatContentBeautifierTest {

    @Test
    void blankInputIsReturnedAsIs() {
        assertThat(WechatContentBeautifier.beautify(null, null)).isEmpty();
        assertThat(WechatContentBeautifier.beautify("", null)).isEmpty();
        assertThat(WechatContentBeautifier.beautify("   ", null)).isEqualTo("   ");
    }

    @Test
    void injectsInlineStylesByTag() {
        String html = "<h2>标题</h2><p>正文段落</p><img src=\"https://x/a.png\">";
        String result = WechatContentBeautifier.beautify(html, null);

        // 根节点包裹为带基础排版的 section
        assertThat(result).startsWith("<section style=\"font-family:");
        // 各标签注入了内联样式
        assertThat(result).contains("<h2 style=\"font-size:19px");
        assertThat(result).contains("<p style=\"margin:0.9em 0");
        // 图片自适应，防止溢出
        assertThat(result).contains("max-width:100%");
    }

    @Test
    void preservesExistingInlineStyleWithHigherPriority() {
        // 用户已设置的 color 应覆盖默认色（默认在前、原有在后，后者胜出）
        String html = "<p style=\"color:#ff0000;\">红字</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).contains("color:#3f3f3f");
        assertThat(result).contains("color:#ff0000;");
        // 原有样式出现在默认样式之后
        assertThat(result.indexOf("color:#ff0000")).isGreaterThan(result.indexOf("color:#3f3f3f"));
    }

    @Test
    void inlineCodeOutsidePreIsStyled() {
        String html = "<p>行内 <code>x</code> 代码</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 行内 code 加浅底色与醒目色（默认对齐 doocs/md「经典」的 #d14）
        assertThat(result).contains("background:#f2f3f5");
        assertThat(result).contains("color:#d14");
    }

    @Test
    void quoteParagraphUsesTighterSpacing() {
        String html = "<blockquote><p>引用内段落</p></blockquote><p>普通段落</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).contains("margin:0.3em 0");
        // 默认主题色为微信绿，作用于引用块左侧强调边框
        assertThat(result).contains("border-left:4px solid #07c160");
    }

    @Test
    void removesScriptAndEventHandlers() {
        String html = "<p onclick=\"evil()\">文本</p><script>alert(1)</script>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("<script");
        assertThat(result).doesNotContain("alert(1)");
        assertThat(result).doesNotContain("onclick");
        assertThat(result).doesNotContain("evil()");
        assertThat(result).contains("文本");
    }

    @Test
    void codeBlockMatchesWechatNativeStyle() {
        String html = "<pre><code class=\"language-java\">int a = 1;</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 与微信编辑器「插入代码」一致的标记：外层 code-snippet 容器内，行号列在前、代码区在后
        assertThat(result).contains("<section class=\"code-snippet__fix code-snippet__js\">");
        assertThat(result).contains("<ul class=\"code-snippet__line-index code-snippet__js\">");
        assertThat(result).contains("<pre class=\"code-snippet__js\" data-lang=\"java\">");
        // 每个代码行：行号列一个空 <li>，代码区一个 <code>（内容包在 <span> 中）
        assertThat(result).contains("<li></li>");
        assertThat(result).contains("<code><span>int a = 1;</span></code>");
        // 代码块内不注入行内代码样式，排版交给微信自身
        assertThat(result).doesNotContain("background:#f2f3f5");
    }

    @Test
    void codeBlockLinesMatchLineIndexCount() {
        String html = "<pre><code>line1\nline2\nline3</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 行号列与代码行一一对应：每个代码行一个空 <li>、一个 <code>，数量严格一致
        assertThat(result.split("<li></li>", -1).length - 1).isEqualTo(3);
        assertThat(result.split("<code><span>", -1).length - 1).isEqualTo(3);
        assertThat(result).contains("<code><span>line1</span></code>"
            + "<code><span>line2</span></code>"
            + "<code><span>line3</span></code>");
        // 不再把行内容拍平成 <br> 分隔的纯文本
        assertThat(result).doesNotContain("line1<br>line2");
        // 行号列与代码行不被注入通用列表/行内代码样式，排版交给微信自身
        assertThat(result).doesNotContain("list-style:disc");
        assertThat(result).doesNotContain("margin:0.35em 0");
        assertThat(result).doesNotContain("background:#f2f3f5");
    }

    @Test
    void codeBlockKeepsIndentation() {
        String html = "<pre><code>void f() {\n    return;\n}</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 缩进与换行原样保留在每个代码行的 <span> 中（微信样式 white-space:pre 不折行渲染）
        assertThat(result).contains("<code><span>void f() {</span></code>");
        assertThat(result).contains("<code><span>    return;</span></code>");
    }

    @Test
    void inlineCodeOutsidePreIsNotConvertedToBr() {
        String html = "<p>行内 <code>a\nb</code></p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 非代码块不做 <br> 转换
        assertThat(result).doesNotContain("<br>");
    }

    @Test
    void tableIsWrappedInHorizontalScrollContainer() {
        String html = "<table><tr><th>列</th></tr><tr><td>值</td></tr></table>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 表格外层包一个横向滚动的 section 容器（滚动为兜底：仅当文章设定宽度超屏时触发）
        assertThat(result).contains("<section style=\"margin:1em 0;overflow-x:auto;");
        // 表格默认铺满屏宽（对齐微信编辑器插入表格的页面宽度自适应），不注入 min-width，
        // 也不在 table 自身上 display:block
        assertThat(result).contains("width:100%");
        assertThat(result).doesNotContain("min-width:100%");
        assertThat(result).doesNotContain("display:block;");
        // 单元格内容允许自动换行，不再强制 white-space:nowrap
        assertThat(result).doesNotContain("white-space:nowrap");
        // 固定布局：列宽按文章 <colgroup> 设定比例渲染，列宽之和超屏时整体溢出→横向滚动
        assertThat(result).contains("table-layout:fixed");
        // 长 token 在列内折断、不遮挡相邻列（不用 anywhere 避免塌缩宽度丢失滚动）
        assertThat(result).contains("overflow-wrap:break-word");
        assertThat(result).doesNotContain("overflow-wrap:anywhere");
        // th/td 对齐微信原生表格观感：浅灰细边框；表头加粗但无底色
        assertThat(result).contains("border:1px solid #e6e6e6");
        assertThat(result).doesNotContain("background:#f6f8fa");
    }

    @Test
    void tableKeepsWidthSetInArticle() {
        // 默认提供 width:100%（微信编辑器式铺满），文章已设定的 width 因「默认在前、原有在后」胜出
        String html = "<table style=\"width:600px;\"><tr><td>值</td></tr></table>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).contains("width:600px;");
        // 用户设定的宽度出现在默认 width:100% 之后
        assertThat(result.indexOf("width:600px;")).isGreaterThan(result.indexOf("width:100%"));
        // 默认样式不注入任何 min-width，避免强制撑宽
        assertThat(result).doesNotContain("min-width");
    }

    @Test
    void tableCellParagraphHasNoVerticalMargin() {
        // Halo/TipTap 把单元格内容包在 <p> 里，若不处理会沿用正文段落 margin:0.9em 0，
        // 导致首/末行单元格在表格上下各多出一段空白
        String html = "<table><tr><td><p>单元格段落</p></td></tr></table>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 单元格内段落外边距置 0
        assertThat(result).contains("margin:0;line-height:1.6;font-size:15px");
        // 不再给单元格段落注入正文段落的 0.9em 上下外边距
        assertThat(result).doesNotContain("margin:0.9em 0");
    }

    @Test
    void removesEmptyProseMirrorParagraphsAroundTable() {
        // TipTap/ProseMirror 在表格前后遗留的空段落，在微信里会渲染成多余空行，应被删除
        String html = "<p>上文</p>"
            + "<p><span leaf=\"\"><br class=\"ProseMirror-trailingBreak\"></span></p>"
            + "<table><tr><td>值</td></tr></table>"
            + "<p><span leaf=\"\"><br class=\"ProseMirror-trailingBreak\"></span></p>"
            + "<p><br></p>"
            + "<p>下文</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 不再残留尾随换行占位与空 <br> 段落
        assertThat(result).doesNotContain("ProseMirror-trailingBreak");
        // 仅剩「上文」「下文」两个非空段落
        int paragraphs = result.split("<p ", -1).length - 1;
        assertThat(paragraphs).isEqualTo(2);
        assertThat(result).contains("上文");
        assertThat(result).contains("下文");
        // 表格保留
        assertThat(result).contains("<table");
    }

    @Test
    void removesProseMirrorTrailingBreakInsideTableCells() {
        // TipTap 把每个单元格内容包在 <p> 里，空单元格即 <td><p><span leaf=""><br class="ProseMirror-trailingBreak"></span></p></td>，
        // 在微信里会撑出一格格多余空白，应连同尾随换行占位一起清除（单元格内也不例外）
        String html = "<table><tr>"
            + "<td><p>值</p></td>"
            + "<td><p><span leaf=\"\"><br class=\"ProseMirror-trailingBreak\"></span></p></td>"
            + "</tr></table>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 不再残留尾随换行占位
        assertThat(result).doesNotContain("ProseMirror-trailingBreak");
        // 空单元格内的段落被删除，非空单元格「值」保留
        assertThat(result).contains("值");
        // 表格结构保留（仍有两个 td）
        int cells = result.split("<td ", -1).length - 1;
        assertThat(cells).isEqualTo(2);
    }

    @Test
    void removesEmptyLeafParagraphsAroundRealWorldTable() {
        // 还原用户真实文章结构（Halo 原始未注入样式的输入）：
        // h4 + 空段落 + div.tableWrapper>table + 空段落 + blockquote，空段落带 Halo 的 <span leaf="">
        String html = "<h4>2.2.1、系统环境变量</h4>"
            + "<p><span leaf=\"\"></span></p>"
            + "<div class=\"tableWrapper\"><table><tbody><tr><td>值</td></tr></tbody></table></div>"
            + "<p><span leaf=\"\"><br class=\"ProseMirror-trailingBreak\"></span></p>"
            + "<blockquote><p><span leaf=\"\">如需了解更多</span></p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 我们发出的内容里：表格上下两个空段落都被删除，不残留任何尾随换行占位
        assertThat(result).doesNotContain("ProseMirror-trailingBreak");
        // 仅剩引用块内那一个非空段落
        int paragraphs = result.split("<p ", -1).length - 1;
        assertThat(paragraphs).isEqualTo(1);
        assertThat(result).contains("如需了解更多");
        // 表格与引用块保留
        assertThat(result).contains("<table");
        assertThat(result).contains("<blockquote");
    }

    @Test
    void keepsParagraphContainingImage() {
        // 包着图片的段落文本为空，但含内容型后代，不能被当空段落删除
        String html = "<p><img src=\"https://x/a.png\"></p>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).contains("<img");
        assertThat(result).contains("max-width:100%");
    }

    @Test
    void removesNbspAndZeroWidthEmptyParagraphs() {
        // TipTap 空段落变体：仅含 &nbsp; 或零宽空格，Java isBlank() 不视其为空白，需专门剔除
        String html = "<p>正文</p><p>&nbsp;</p><p>\u200b</p><p>   </p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 仅剩「正文」一个非空段落
        int paragraphs = result.split("<p ", -1).length - 1;
        assertThat(paragraphs).isEqualTo(1);
        assertThat(result).contains("正文");
    }

    @Test
    void customThemeColorIsAppliedToBlockquote() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setThemeColor("#ff5500");
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 主题色作用于引用块左侧强调边框
        assertThat(result).contains("border-left:4px solid #ff5500");
        assertThat(result).doesNotContain("#07c160");
    }

    @Test
    void invalidThemeColorFallsBackToDefault() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setThemeColor("not-a-color");
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 非法色值回退到内置默认微信绿，避免污染 style
        assertThat(result).contains("border-left:4px solid #07c160");
        assertThat(result).doesNotContain("not-a-color");
    }

    @Test
    void blockquoteBgColorIsConfigurableAndDecoupledFromTheme() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setBlockquoteBgColor("#eef5ff");
        cfg.setThemeColor("#ff5500");
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 引用块背景色可配（默认 #f7f7f7 被覆盖），与边框主题色互不影响
        assertThat(result).contains("background:#eef5ff");
        assertThat(result).contains("border-left:4px solid #ff5500");
        assertThat(result).doesNotContain("#f7f7f7");
    }

    @Test
    void invalidBlockquoteBgColorFallsBackToDefault() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setBlockquoteBgColor("oops");
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 非法色值回退到内置默认浅灰，避免污染 style
        assertThat(result).contains("background:#f7f7f7");
        assertThat(result).doesNotContain("oops");
    }

    @Test
    void blockquoteBorderShownByDefault() {
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, new BeautifySetting());

        // 默认开启：引用块显示左侧强调边框，左侧两角直角贴合边框
        assertThat(result).contains("border-left:4px solid #07c160");
        assertThat(result).contains("border-radius:0 4px 4px 0");
    }

    @Test
    void blockquoteBorderCanBeDisabled() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setBlockquoteBorderEnabled(false);
        String html = "<blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 关闭后无左侧边框，背景色照常生效；无边框时四角统一圆角
        assertThat(result).doesNotContain("border-left");
        assertThat(result).contains("background:#f7f7f7");
        assertThat(result).contains("border-radius:4px");
    }

    @Test
    void h2BorderColorIsConfigurableAndDecoupledFromTheme() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setHeadingBorderEnabled(true);
        cfg.setH2BorderColor("#123456");
        cfg.setThemeColor("#ff5500");
        String html = "<h2>标题</h2><blockquote><p>引用</p></blockquote>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 开启标题边框后：H2 左边框用「二级标题边框颜色」，引用块左边框用主题色，二者互不影响
        assertThat(result).contains("border-left:4px solid #123456");
        assertThat(result).contains("border-left:4px solid #ff5500");
    }

    @Test
    void invalidHeadingBorderColorFallsBackToDefault() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setHeadingBorderEnabled(true);
        cfg.setH2BorderColor("oops");
        String html = "<h2>标题</h2>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 非法标题边框色回退到内置默认微信绿
        assertThat(result).contains("border-left:4px solid #07c160");
        assertThat(result).doesNotContain("oops");
    }

    @Test
    void headingBorderDisabledByDefaultHasNoHeadingBorder() {
        String html = "<h2>二</h2><h3>三</h3><h6>六</h6>";
        String result = WechatContentBeautifier.beautify(html, new BeautifySetting());

        // 默认关闭「标题显示边框」：H2–H6 均无左侧边框
        assertThat(result).doesNotContain("border-left");
    }

    @Test
    void headingBorderEnabledAppliesPerLevelColorsToH2ThroughH6() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setHeadingBorderEnabled(true);
        cfg.setH2BorderColor("#111111");
        cfg.setH3BorderColor("#222222");
        cfg.setH4BorderColor("#333333");
        cfg.setH5BorderColor("#444444");
        cfg.setH6BorderColor("#555555");
        String html = "<h1>一</h1><h2>二</h2><h3>三</h3><h4>四</h4><h5>五</h5><h6>六</h6>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 开启后 H2–H6 各用独立边框色；H1 居中不加边框
        assertThat(result).contains("border-left:4px solid #111111");
        assertThat(result).contains("border-left:4px solid #222222");
        assertThat(result).contains("border-left:4px solid #333333");
        assertThat(result).contains("border-left:4px solid #444444");
        assertThat(result).contains("border-left:4px solid #555555");
        // 共 5 处标题左边框（无引用块，故 border-left 总数即标题边框数）
        int borders = result.split("border-left:4px solid #", -1).length - 1;
        assertThat(borders).isEqualTo(5);
    }

    @Test
    void headingColorsAreConfigurable() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setH1Color("#111111");
        cfg.setH3Color("#00aaff");
        cfg.setH6Color("#999999");
        String html = "<h1>一</h1><h3>三</h3><h6>六</h6>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        assertThat(result).contains("color:#111111");
        assertThat(result).contains("color:#00aaff");
        assertThat(result).contains("color:#999999");
    }

    @Test
    void invalidHeadingColorFallsBackToDefault() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setH1Color("oops");
        String html = "<h1>一</h1>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 非法标题色回退到内置默认（H1 默认 #222222）
        assertThat(result).contains("color:#222222");
        assertThat(result).doesNotContain("oops");
    }

    @Test
    void codeBlockEmptyLineKeepsPlaceholder() {
        String html = "<pre><code>a\n\nb</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 空行以零宽空格占位：行高不塌陷，行号与代码行仍一一对应
        assertThat(result).contains("<code><span>\u200b</span></code>");
        assertThat(result.split("<li></li>", -1).length - 1).isEqualTo(3);
        assertThat(result.split("<code><span>", -1).length - 1).isEqualTo(3);
    }

    @Test
    void codeBlockLanguageIsWrittenToDataLang() {
        // 语言标识来自 <code class="language-*">（Halo/Prism 输出）
        String fromCode = WechatContentBeautifier.beautify(
            "<pre><code class=\"language-java\">int a = 1;</code></pre>", null);
        assertThat(fromCode).contains("data-lang=\"java\"");

        // 语言标识来自 <pre class="language-*">（部分渲染器输出）
        String fromPre = WechatContentBeautifier.beautify(
            "<pre class=\"language-python\"><code>print(1)</code></pre>", null);
        assertThat(fromPre).contains("data-lang=\"python\"");

        // lang-* 前缀同样识别
        String fromLang = WechatContentBeautifier.beautify(
            "<pre><code class=\"lang-js\">x</code></pre>", null);
        assertThat(fromLang).contains("data-lang=\"js\"");

        // 无语言标识时省略 data-lang，代码块结构照常渲染
        String noLang = WechatContentBeautifier.beautify("<pre><code>x</code></pre>", null);
        assertThat(noLang).doesNotContain("data-lang");
    }

    @Test
    void textLinkAndInlineCodeColorsAreConfigurable() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setTextColor("#123456");
        cfg.setLinkColor("#00aaff");
        cfg.setInlineCodeColor("#00aa00");
        cfg.setInlineCodeBgColor("#eeeeee");
        String html = "<p>正文</p><a href=\"https://x\">链接</a><code>x</code>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 正文色同时作用于根节点包裹与段落
        assertThat(result).contains("color:#123456");
        // 链接色
        assertThat(result).contains("color:#00aaff");
        // 行内代码文字色与底色均可配
        assertThat(result).contains("color:#00aa00");
        assertThat(result).contains("background:#eeeeee");
    }

    @Test
    void invalidTextAndLinkColorsFallBackToDefaults() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setTextColor("bad");
        cfg.setLinkColor("bad");
        cfg.setInlineCodeColor("bad");
        cfg.setInlineCodeBgColor("bad");
        String html = "<p>正文</p><a href=\"https://x\">链接</a><code>x</code>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 非法值全部回退到内置默认色
        assertThat(result).contains("color:#3f3f3f");
        assertThat(result).contains("color:#576b95");
        assertThat(result).contains("color:#d14");
        assertThat(result).contains("background:#f2f3f5");
        assertThat(result).doesNotContain("bad");
    }

    @Test
    void hyperlinkInlineCardIsConvertedToAnchor() {
        // Halo 链接卡片插件注入的自定义元素，微信无法渲染，应转为标准 <a> 并保留链接文字
        String html = "<p>开源项目 "
            + "<HYPERLINK-INLINE-CARD target=\"_blank\" href=\"https://github.com/rwv/chinese-dos-games\" "
            + "theme=\"inline\" custom-title=\"rwv/chinese-dos-games\" custom-image=\"data:image/svg+xml,x\">"
            + "<span leaf=\"\">https://github.com/rwv/chinese-dos-games</span></HYPERLINK-INLINE-CARD>"
            + "，中文 DOS 游戏合集。</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 自定义标签被清除，转为可点击链接，链接文字与地址保留
        assertThat(result).doesNotContain("HYPERLINK-INLINE-CARD");
        assertThat(result).doesNotContain("hyperlink-inline-card");
        assertThat(result).contains("<a href=\"https://github.com/rwv/chinese-dos-games\"");
        assertThat(result).contains("target=\"_blank\"");
        assertThat(result).contains("中文 DOS 游戏合集");
        // 转换后的 <a> 被注入链接色
        assertThat(result).contains("color:#576b95");
    }

    @Test
    void downloadLinksInsideParagraphIsConvertedToAnchor() {
        // Halo 下载链接插件：<p> 内包着空的 <DOWNLOAD-LINKS>，真实地址在 data-links JSON 里
        String html = "<p>上文</p>"
            + "<p><DOWNLOAD-LINKS data-links=\"[{&quot;url&quot;:&quot;https://pan.baidu.com/s/1abc?pwd=6h1c&quot;,"
            + "&quot;filename&quot;:&quot;game.zip&quot;,&quot;source&quot;:&quot;百度云网盘&quot;,"
            + "&quot;code&quot;:&quot;6h1c&quot;,&quot;icon&quot;:&quot;/x.png&quot;}]\"></DOWNLOAD-LINKS></p>"
            + "<p>下文</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 自定义标签被清除，转为下载链接
        assertThat(result).doesNotContain("DOWNLOAD-LINKS");
        assertThat(result).doesNotContain("download-links");
        assertThat(result).doesNotContain("data-links");
        assertThat(result).contains("<a href=\"https://pan.baidu.com/s/1abc?pwd=6h1c\"");
        // 链接文字只用 URL，不带文件名/来源/提取码等描述
        assertThat(result).contains(">https://pan.baidu.com/s/1abc?pwd=6h1c</a>");
        assertThat(result).doesNotContain("game.zip");
        assertThat(result).doesNotContain("百度云网盘");
        assertThat(result).doesNotContain("提取码");
    }

    @Test
    void figureIsDowngradedToParagraph() {
        // Halo 用 <figure> 包图片，微信编辑器不认识会在其前后插入空 <p>，应降级为标准 <p>
        String html = "<p>上文</p>"
            + "<figure data-content-type=\"image\" style=\"display: flex; flex-direction: column\">"
            + "<img src=\"https://x/a.png\"></figure>"
            + "<p>下文</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        // <figure> 已不存在，图片改由带样式的 <p> 承载，Halo 的 display:flex 布局样式被丢弃
        assertThat(result).doesNotContain("<figure");
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).contains("<img");
        assertThat(result).contains("max-width:100%");
        // 图片所在 <p> 被注入段落样式
        assertThat(result).contains("<p style=\"margin:0.9em 0");
    }

    @Test
    void figureCaptionIsKeptAsItalicImageDescription() {
        // Halo 图片「描述」渲染为 <figure> 内的 <figcaption>，编辑器里默认显示为居中灰色斜体小字；
        // figure 降级为 <p> 后，描述文字与斜体样式须保留
        String html = "<figure data-content-type=\"image\" style=\"display: flex; flex-direction: column\">"
            + "<img src=\"https://x/a.png\">"
            + "<figcaption data-placeholder=\"Add description\">图片描述文字</figcaption>"
            + "</figure>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("<figure");
        assertThat(result).contains("<figcaption");
        assertThat(result).contains("图片描述文字");
        assertThat(result).contains("font-style:italic");
        assertThat(result).contains("text-align:center");
    }

    @Test
    void summaryIsDowngradedToParagraphAndKeepsText() {
        // 单独的 <summary>（无 <details> 父）微信不识别，应降级为 <p> 并保留其文字
        String html = "<summary><span leaf=\"\">百度云盘下载，约34.14GB</span></summary>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("<summary");
        assertThat(result).contains("百度云盘下载，约34.14GB");
        assertThat(result).contains("<p style=\"margin:0.9em 0");
    }

    @Test
    void downloadLinksWithoutValidDataIsRemovedWithItsEmptyParagraph() {
        // data-links 解析不出有效地址时移除该组件，其外层空 <p> 也一并被清理，不残留空行
        String html = "<p>上文</p>"
            + "<p><DOWNLOAD-LINKS data-links=\"[]\"></DOWNLOAD-LINKS></p>"
            + "<p>下文</p>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("download-links");
        // 仅剩「上文」「下文」两个非空段落
        int paragraphs = result.split("<p ", -1).length - 1;
        assertThat(paragraphs).isEqualTo(2);
        assertThat(result).contains("上文");
        assertThat(result).contains("下文");
    }

    @Test
    void standaloneDownloadLinksIsWrappedInParagraph() {
        // 块级位置的 <DOWNLOAD-LINKS>（未被 <p> 包裹）转换后应新建 <p> 承载，避免裸链接
        String html = "<p>上文</p>"
            + "<DOWNLOAD-LINKS data-links=\"[{&quot;url&quot;:&quot;https://x/f.zip&quot;,"
            + "&quot;filename&quot;:&quot;f.zip&quot;}]\"></DOWNLOAD-LINKS>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("download-links");
        assertThat(result).contains("<a href=\"https://x/f.zip\"");
        assertThat(result).contains("f.zip");
    }

    @Test
    void columnsAreRebuiltAsTableLayout() {
        // Halo 分栏卡片靠 display:flex 并排，微信不支持会导致每列各占一行，应重建为表格布局
        String html = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" index=\"0\" style=\"min-width: 0;flex: 2 1;box-sizing: border-box;\">"
            + "<p>左栏</p></div>"
            + "<div class=\"column\" index=\"1\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\">"
            + "<p>右栏</p></div>"
            + "</div>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 不再依赖 flex 与 class：重建为 2:1 宽度的表格布局
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("columns");
        // 重建的布局表格带标记类（供 Console 预览补样式）与固定布局样式
        assertThat(result).contains("class=\"wechat-layout-table\"");
        assertThat(result).contains("style=\"width:100%;border-collapse:collapse;table-layout:fixed;\"");
        assertThat(result).contains("width:66.67%");
        assertThat(result).contains("width:33.33%");
        // 列间距（编辑器默认 gap: 1em）折算为第二列的左内边距
        assertThat(result).contains("padding-left:16px");
        assertThat(result).contains("左栏");
        assertThat(result).contains("右栏");
    }

    @Test
    void columnsWithEqualFlexAreSplitEvenly() {
        // 三列默认 flex: 1 1 均分，每列约 33.33%
        String html = "<div class=\"columns\" cols=\"3\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>一</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>二</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>三</p></div>"
            + "</div>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 列宽 33.33% 同时出现在 colgroup 的 <col> 与单元格样式里，这里只数 <col> 的声明
        int cols = result.split("<col style=\"width:33.33%;\">", -1).length - 1;
        assertThat(cols).isEqualTo(3);
        assertThat(result).doesNotContain("display: flex");
    }

    @Test
    void multipleColumnsBlocksBecomeSeparateTables() {
        // 一篇文章里可以有多个分栏卡片；每个卡片各生成一个独立表格（1 个卡片 = 1 个表格，
        // 2 个卡片 = 2 个表格），不能把相邻卡片合并成「一个表格多行」
        String card = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>左</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>右</p></div>"
            + "</div>";
        String result = WechatContentBeautifier.beautify(card + card, null);

        // 两张卡片各对应一个表格，且每个表格只有一行
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(2);
        int rows = result.split("<tr>", -1).length - 1;
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void galleryIsRebuiltAsTableLayout() {
        // Halo 画廊用 grid 分行、flex 排图（微信会过滤 grid），应重建为「一个整体表格、所有列等宽」——
        // 用户确认的期望版式：整张画廊（含多行图片）只对应一个表格，不按行拆分、不按各图宽高比分宽
        String html = "<div data-type=\"gallery\" data-group-size=\"2\" data-layout=\"auto\" data-gap=\"8\">"
            + "<div style=\"display: grid; gap: 8px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 8px;\">"
            + "<div style=\"flex: 1.5 1 0%;\" data-aspect-ratio=\"1.5\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/a.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "<div style=\"flex: 0.5 1 0%;\" data-aspect-ratio=\"0.5\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/b.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 8px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/c.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "</div></div>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 不再依赖 grid/flex 与 group 包装层
        assertThat(result).doesNotContain("display: grid");
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("gallery-group");
        // 两行图片重建为同一个整体表格，带标记类（供 Console 预览补样式）
        assertThat(result).contains("class=\"wechat-layout-table\"");
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(1);
        // 所有列等宽（不按宽高比 1.5:0.5 分宽）：两图行各占 50%，单图末行用 colspan 铺满整行
        assertThat(result).doesNotContain("width:75%");
        assertThat(result).contains("width:50%");
        int cols = result.split("<col style=\"width:50%;\">", -1).length - 1;
        assertThat(cols).isEqualTo(2);
        assertThat(result).contains("colspan=\"2\"");
        assertThat(result).contains("width:100%");
        // 行间距与列间距（data-gap=8）折算为单元格内边距：非首行 padding-top、行内非首列 padding-left
        assertThat(result).contains("padding-top:8px");
        assertThat(result).contains("padding-left:8px");
        // 图片高度改由宽度决定，原 height:100% 被覆写；三张图片全部保留
        assertThat(result).contains("height:auto");
        int images = result.split("<img ", -1).length - 1;
        assertThat(images).isEqualTo(3);
    }

    @Test
    void galleryRowsSplitEvenlyIntoUniformColumns() {
        // 末行不满（3 图 + 2 图）时仍是「一个整体表格、所有列等宽」：统一网格取 6 列
        // （各行图片数 3 与 2 的最小公倍数），3 图行每图跨 2 列（各 33.33%）、2 图行每图跨 3 列（各 50%），
        // 都恰好铺满整行；data-gap=0 时不注入任何行/列内边距
        String html = "<div data-type=\"gallery\" data-group-size=\"3\" data-layout=\"square\" data-gap=\"0\">"
            + "<div style=\"display: grid; gap: 0px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 0px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\"><img data-type=\"gallery-image\" src=\"https://x/1.png\" style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\"><img data-type=\"gallery-image\" src=\"https://x/2.png\" style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\"><img data-type=\"gallery-image\" src=\"https://x/3.png\" style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 0px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\"><img data-type=\"gallery-image\" src=\"https://x/4.png\" style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\"><img data-type=\"gallery-image\" src=\"https://x/5.png\" style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "</div></div>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 整张画廊仍是一个表格，6 条 colgroup 声明 16.67% 等宽列
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(1);
        int cols = result.split("<col style=\"width:16.67%;\">", -1).length - 1;
        assertThat(cols).isEqualTo(6);
        // 3 图行每图跨 2 列、2 图行每图跨 3 列（都铺满整行）
        int span2 = result.split("colspan=\"2\"", -1).length - 1;
        assertThat(span2).isEqualTo(3);
        int span3 = result.split("colspan=\"3\"", -1).length - 1;
        assertThat(span3).isEqualTo(2);
        assertThat(result).contains("width:33.33%");
        assertThat(result).contains("width:50%");
        // gap=0：不注入行/列内边距
        assertThat(result).doesNotContain("padding-left");
        assertThat(result).doesNotContain("padding-top");
    }

    @Test
    void galleryInsideColumnsIsRebuiltToo() {
        // 交叉场景：一列文字 + 一列画廊，分栏与画廊都应重建为表格，无 flex/grid 残留
        String html = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>文字</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\">"
            + "<div data-type=\"gallery\" data-group-size=\"1\" data-layout=\"auto\" data-gap=\"0\">"
            + "<div style=\"display: grid; gap: 0px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 0px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/a.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div></div></div>"
            + "</div></div>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("display: grid");
        // 外层分栏与内层画廊各重建为一个布局表格
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(2);
        assertThat(result).contains("文字");
        assertThat(result).contains("<img");
    }

    @Test
    void stackedLayoutFlattensColumnsToOnePerLine() {
        // 分栏版式「独占一行」：分栏不重建表格，取消 flex 并排、恢复块级——两栏各占一行
        BeautifySetting setting = new BeautifySetting();
        setting.setColumnsLayoutStyle(BeautifySetting.LAYOUT_STYLE_STACKED);
        String html = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" index=\"0\" style=\"min-width: 0;flex: 2 1;box-sizing: border-box;\">"
            + "<p>左栏</p></div>"
            + "<div class=\"column\" index=\"1\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\">"
            + "<p>右栏</p></div>"
            + "</div>";
        String result = WechatContentBeautifier.beautify(html, setting);

        // 不生成布局表格，也不残留任何 flex 布局样式
        assertThat(result).doesNotContain("<table");
        assertThat(result).doesNotContain("wechat-layout-table");
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("flex: 2 1");
        // 容器与各列恢复为普通块级，栏内内容完整保留
        assertThat(result).contains("display:block;");
        assertThat(result).contains("左栏");
        assertThat(result).contains("右栏");
    }

    @Test
    void stackedLayoutFlattensGalleryToOneImagePerLine() {
        // 画廊版式「独占一行」：画廊不重建表格，网格/分组/图片项全部恢复为块级——每张图片各占一行；
        // 图片与描述（figcaption）内容完整保留，相邻图片间距按 data-gap 折算为 margin-bottom
        BeautifySetting setting = new BeautifySetting();
        setting.setGalleryLayoutStyle("stacked");
        String html = "<div data-type=\"gallery\" data-group-size=\"2\" data-layout=\"auto\" data-gap=\"8\">"
            + "<div style=\"display: grid; gap: 8px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 8px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/a.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\">"
            + "<figcaption>第一张的描述</figcaption></div>"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/b.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 8px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/c.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div>"
            + "</div></div>";
        String result = WechatContentBeautifier.beautify(html, setting);

        // 不生成布局表格，grid/flex 布局样式全部清除
        assertThat(result).doesNotContain("<table");
        assertThat(result).doesNotContain("display: grid");
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("flex-direction");
        // 三张图片与描述内容保留；描述沿用图片描述样式（居中灰色斜体）
        int images = result.split("<img ", -1).length - 1;
        assertThat(images).isEqualTo(3);
        assertThat(result).contains("第一张的描述");
        assertThat(result).contains("font-style:italic");
        // 图片改回自动高度（原 height:100% 仅适合 flex 撑高），相邻图片保留原间距
        assertThat(result).contains("height:auto");
        assertThat(result).contains("margin-bottom:8px");
    }

    @Test
    void invalidLayoutStyleFallsBackToTableLayout() {
        // 两项版式取值非法（历史残留、手工改错等）时均按默认「表格」处理，保证同步流程不中断
        BeautifySetting setting = new BeautifySetting();
        setting.setColumnsLayoutStyle("masonry");
        setting.setGalleryLayoutStyle("masonry");
        String html = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>左</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>右</p></div>"
            + "</div>"
            + "<div data-type=\"gallery\" data-group-size=\"1\" data-layout=\"auto\" data-gap=\"0\">"
            + "<div style=\"display: grid; gap: 0px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 0px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/a.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div></div></div>";
        String result = WechatContentBeautifier.beautify(html, setting);

        // 分栏与画廊都按「表格」重建（各一个布局表格），无 grid/flex 残留
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(2);
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).doesNotContain("display: grid");
    }

    @Test
    void columnsAndGalleryLayoutStylesAreIndependent() {
        // 两项配置相互独立：分栏「独占一行」时画廊仍保持默认「表格」，只影响各自区块
        BeautifySetting setting = new BeautifySetting();
        setting.setColumnsLayoutStyle(BeautifySetting.LAYOUT_STYLE_STACKED);
        String html = "<div class=\"columns\" cols=\"2\" style=\"display: flex;width: 100%;gap: 1em;\">"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>左栏</p></div>"
            + "<div class=\"column\" style=\"min-width: 0;flex: 1 1;box-sizing: border-box;\"><p>右栏</p></div>"
            + "</div>"
            + "<div data-type=\"gallery\" data-group-size=\"2\" data-layout=\"auto\" data-gap=\"4\">"
            + "<div style=\"display: grid; gap: 4px;\">"
            + "<div data-type=\"gallery-group\" style=\"display: flex; flex-direction: row; gap: 4px;\">"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/a.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "<div style=\"flex: 1 1 0%;\" data-aspect-ratio=\"1\">"
            + "<img data-type=\"gallery-image\" src=\"https://x/b.png\" "
            + "style=\"width: 100%; height: 100%; margin: 0; object-fit: cover;\"></div>"
            + "</div></div></div>";
        String result = WechatContentBeautifier.beautify(html, setting);

        // 分栏保持块级堆叠（未重建表格），画廊仍重建为一张布局表格；内容与图片全部保留
        assertThat(result).contains("display:block;");
        int tables = result.split("<table ", -1).length - 1;
        assertThat(tables).isEqualTo(1);
        assertThat(result).doesNotContain("display: flex");
        assertThat(result).contains("左栏");
        assertThat(result).contains("右栏");
        int images = result.split("<img ", -1).length - 1;
        assertThat(images).isEqualTo(2);
    }
}
