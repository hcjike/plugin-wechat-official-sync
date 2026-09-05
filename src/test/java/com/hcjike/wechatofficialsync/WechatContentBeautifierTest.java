package com.hcjike.wechatofficialsync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link WechatContentBeautifier} 的行为验证：内联样式注入、用户样式优先、根节点包裹与安全清理。
 */
class WechatContentBeautifierTest {

    @Test
    void blankInputIsReturnedAsIs() {
        assertThat(WechatContentBeautifier.beautify(null)).isEmpty();
        assertThat(WechatContentBeautifier.beautify("")).isEmpty();
        assertThat(WechatContentBeautifier.beautify("   ")).isEqualTo("   ");
    }

    @Test
    void injectsInlineStylesByTag() {
        String html = "<h2>标题</h2><p>正文段落</p><img src=\"https://x/a.png\">";
        String result = WechatContentBeautifier.beautify(html);

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
        String result = WechatContentBeautifier.beautify(html);

        assertThat(result).contains("color:#3f3f3f");
        assertThat(result).contains("color:#ff0000;");
        // 原有样式出现在默认样式之后
        assertThat(result.indexOf("color:#ff0000")).isGreaterThan(result.indexOf("color:#3f3f3f"));
    }

    @Test
    void distinguishesInlineCodeFromPreCode() {
        String html = "<pre><code>block()</code></pre><p>行内 <code>x</code> 代码</p>";
        String result = WechatContentBeautifier.beautify(html);

        // 代码块内 code 不加底色
        assertThat(result).contains("background:transparent;padding:0");
        // 行内 code 加浅底色与醒目色
        assertThat(result).contains("background:#f2f3f5");
        assertThat(result).contains("color:#d63200");
    }

    @Test
    void quoteParagraphUsesTighterSpacing() {
        String html = "<blockquote><p>引用内段落</p></blockquote><p>普通段落</p>";
        String result = WechatContentBeautifier.beautify(html);

        assertThat(result).contains("margin:0.3em 0");
        assertThat(result).contains("border-left:4px solid #dcdcdc");
    }

    @Test
    void removesScriptAndEventHandlers() {
        String html = "<p onclick=\"evil()\">文本</p><script>alert(1)</script>";
        String result = WechatContentBeautifier.beautify(html);

        assertThat(result).doesNotContain("<script");
        assertThat(result).doesNotContain("alert(1)");
        assertThat(result).doesNotContain("onclick");
        assertThat(result).doesNotContain("evil()");
        assertThat(result).contains("文本");
    }

    @Test
    void preMatchesWechatNativeStyle() {
        String html = "<pre><code>int a = 1;</code></pre>";
        String result = WechatContentBeautifier.beautify(html);

        // 微信原生代码块：极细内阴影边框 + 浅灰底 + 小圆角 + Consolas 优先等宽字体
        assertThat(result).contains("box-shadow:rgba(216,216,216,0.5) 0 0 0 1px inset");
        assertThat(result).contains("background-color:#f8f8f8");
        assertThat(result).contains("border-radius:3px");
        assertThat(result).contains("font-family:Consolas");
    }

    @Test
    void convertsPreNewlinesToBrAndKeepsIndentation() {
        String html = "<pre><code>void f() {\n    return;\n}</code></pre>";
        String result = WechatContentBeautifier.beautify(html);

        // 裸换行转为 <br>，缩进空格保留（靠 white-space:pre 渲染）
        assertThat(result).contains("void f() {<br>    return;<br>}");
        assertThat(result).contains("white-space:pre");
    }

    @Test
    void inlineCodeOutsidePreIsNotConvertedToBr() {
        String html = "<p>行内 <code>a\nb</code></p>";
        String result = WechatContentBeautifier.beautify(html);

        // 非代码块不做 <br> 转换
        assertThat(result).doesNotContain("<br>");
    }
}
