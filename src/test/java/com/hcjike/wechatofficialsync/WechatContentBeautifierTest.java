package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatContentBeautifier} 的行为验证：内联样式注入、用户样式优先、标题颜色可配、
 * 代码块横向拖拽、根节点包裹与安全清理。
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
        String html = "<pre><code>int a = 1;</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 微信原生代码块：极细内阴影边框 + 浅灰底 + 小圆角 + Consolas 优先等宽字体
        assertThat(result).contains("box-shadow:rgba(216,216,216,0.5) 0 0 0 1px inset");
        assertThat(result).contains("background-color:#f8f8f8");
        assertThat(result).contains("border-radius:3px");
        assertThat(result).contains("font-family:Consolas");
    }

    @Test
    void codeBlockHasHorizontalScroll() {
        String html = "<pre><code>line1\nline2\nline3</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 代码换行转为 <br>，不加行号
        assertThat(result).contains("line1<br>line2<br>line3");
        // 单栏容器横向拖拽
        assertThat(result).contains("overflow-x:auto");
        // 不再使用两栏 flex 布局，也无行号文本
        assertThat(result).doesNotContain("display:flex");
        assertThat(result).doesNotContain("1<br>2<br>3");
        // 原 <pre> 已被重建，不再残留
        assertThat(result).doesNotContain("<pre");
    }

    @Test
    void codeBlockKeepsIndentation() {
        String html = "<pre><code>void f() {\n    return;\n}</code></pre>";
        String result = WechatContentBeautifier.beautify(html, null);

        // 缩进空格保留（靠 white-space:pre 渲染）
        assertThat(result).contains("void f() {<br>    return;<br>}");
        assertThat(result).contains("white-space:pre");
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

        // 表格外层包一个横向滚动的 section 容器（文章设定宽度超屏时可滚动）
        assertThat(result).contains("<section style=\"margin:1em 0;overflow-x:auto;");
        // 表格本体不强制宽度（无 min-width），也不在 table 自身上 display:block
        assertThat(result).doesNotContain("min-width:100%");
        assertThat(result).doesNotContain("display:block;");
        // 单元格内容允许自动换行，不再强制 white-space:nowrap
        assertThat(result).doesNotContain("white-space:nowrap");
        // 固定布局：列宽按文章 <colgroup> 设定渲染，列宽之和超屏时整体溢出→横向滚动
        assertThat(result).contains("table-layout:fixed");
        // 长 token 在列内折断、不遮挡相邻列（不用 anywhere 避免塌缩宽度丢失滚动）
        assertThat(result).contains("overflow-wrap:break-word");
        assertThat(result).doesNotContain("overflow-wrap:anywhere");
        // th/td 仍按标签名注入样式
        assertThat(result).contains("background:#f6f8fa");
        assertThat(result).contains("border:1px solid #dfe2e5");
    }

    @Test
    void tableKeepsWidthSetInArticle() {
        // 文章（用户）已设定的 width 应保留为准（默认样式在前、原有内联在后，后者胜出）
        String html = "<table style=\"width:600px;\"><tr><td>值</td></tr></table>";
        String result = WechatContentBeautifier.beautify(html, null);

        assertThat(result).contains("width:600px;");
        // 默认样式不注入任何 width，避免覆盖文章设定
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
    void darkCodeBlockThemeUsesDarkBackground() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setCodeBlockTheme("dark");
        String html = "<pre><code>int a = 1;</code></pre>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 深色代码块：深底浅字，不再使用浅色主题的内阴影边框
        assertThat(result).contains("background-color:#282c34");
        assertThat(result).contains("color:#abb2bf");
        assertThat(result).doesNotContain("box-shadow:rgba(216,216,216,0.5)");
    }

    @Test
    void lightIsDefaultCodeBlockTheme() {
        BeautifySetting cfg = new BeautifySetting();
        cfg.setCodeBlockTheme("light");
        String html = "<pre><code>int a = 1;</code></pre>";
        String result = WechatContentBeautifier.beautify(html, cfg);

        // 显式 light 与留空一致，采用微信原生浅色风格
        assertThat(result).contains("background-color:#f8f8f8");
        assertThat(result).contains("box-shadow:rgba(216,216,216,0.5) 0 0 0 1px inset");
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
}
