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
}
