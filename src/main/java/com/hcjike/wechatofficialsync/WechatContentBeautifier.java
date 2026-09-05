package com.hcjike.wechatofficialsync;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;

/**
 * 正文美化器：把 Halo 渲染出的正文 HTML 转换为「微信友好」的内联样式 HTML。
 *
 * <p>微信图文与邮件客户端类似，会剥离 {@code <head>}/{@code <style>}/外部 {@code <link>} CSS，并过滤
 * {@code class}/{@code id} 属性，<b>只保留元素上的内联 {@code style="..."} 属性</b>。而 Halo 正文靠主题
 * class + 外部 CSS 排版，直接塞进草稿会丢样式变成「裸 HTML」。本类按<b>标签名</b>为常见元素注入内联样式
 * （标题/段落/引用/代码块/图片自适应/列表/表格/链接等），对标 doocs/md 的默认排版效果。</p>
 *
 * <p>注入策略：我们的默认样式写在<b>前</b>、元素原有内联样式写在<b>后</b>。同一 {@code style} 属性内后出现的
 * 同名属性覆盖先出现的，故用户在编辑器里已设置的行内样式优先级更高，本类只补齐缺省样式，不覆盖用户意图。
 * 仅使用 jsoup（项目已有依赖），无阻塞 I/O，可安全在 boundedElastic 线程执行。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
final class WechatContentBeautifier {

    /** 正文根节点的基础排版：字体、字号、行高、颜色，供所有未显式覆盖的后代继承。 */
    private static final String BASE_STYLE =
        "font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue','PingFang SC','Hiragino Sans GB',"
            + "'Microsoft YaHei',Arial,sans-serif;"
            + "font-size:16px;color:#3f3f3f;line-height:1.75;letter-spacing:0.4px;"
            + "word-break:break-word;text-align:left;";

    private static final String H1_STYLE =
        "font-size:22px;font-weight:bold;line-height:1.4;text-align:center;margin:1.4em 0 0.9em;color:#222222;";

    private static final String H2_STYLE =
        "font-size:19px;font-weight:bold;line-height:1.4;margin:1.6em 0 0.9em;"
            + "padding-left:12px;border-left:4px solid #07c160;color:#222222;";

    private static final String H3_STYLE =
        "font-size:17px;font-weight:bold;line-height:1.4;margin:1.4em 0 0.7em;color:#222222;";

    private static final String H4_STYLE =
        "font-size:16px;font-weight:bold;line-height:1.4;margin:1.2em 0 0.6em;color:#222222;";

    private static final String H5_STYLE =
        "font-size:15px;font-weight:bold;line-height:1.4;margin:1.1em 0 0.5em;color:#333333;";

    private static final String H6_STYLE =
        "font-size:14px;font-weight:bold;line-height:1.4;margin:1.1em 0 0.5em;color:#888888;";

    private static final String P_STYLE =
        "margin:0.9em 0;line-height:1.75;font-size:16px;color:#3f3f3f;letter-spacing:0.4px;";

    /** 引用块内的段落：收紧上下间距，避免与引用块自身的内边距叠加。 */
    private static final String QUOTE_P_STYLE =
        "margin:0.3em 0;line-height:1.7;font-size:15px;color:#666666;";

    private static final String BLOCKQUOTE_STYLE =
        "margin:1em 0;padding:10px 15px;border-left:4px solid #dcdcdc;background:#f7f7f7;"
            + "color:#666666;border-radius:0 4px 4px 0;";

    /**
     * 代码块容器：对齐微信后台「插入代码」的原生观感——浅灰底 + 极细内阴影边框（而非实线 border）、
     * 小圆角、Consolas 优先的等宽字体；{@code overflow-x:auto} 支持长行横向滚动。
     */
    private static final String PRE_STYLE =
        "margin:1em 0;padding:10px;overflow-x:auto;text-align:left;background-color:#f8f8f8;"
            + "box-shadow:rgba(216,216,216,0.5) 0 0 0 1px inset;border-radius:3px;"
            + "font-size:14px;line-height:1.6;color:#333333;white-space:pre;word-wrap:normal;"
            + "font-family:Consolas,'Liberation Mono',Menlo,Courier,monospace;";

    /** 代码块内的 {@code code}：不再单独加底色/圆角，交由 {@code pre} 承载，字体与行高与容器一致。 */
    private static final String PRE_CODE_STYLE =
        "background:transparent;padding:0;border-radius:0;font-size:14px;line-height:1.6;color:inherit;"
            + "white-space:pre;font-family:Consolas,'Liberation Mono',Menlo,Courier,monospace;";

    private static final String INLINE_CODE_STYLE =
        "padding:2px 5px;margin:0 2px;background:#f2f3f5;border-radius:3px;font-size:14px;color:#d63200;"
            + "font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,Courier,monospace;";

    private static final String UL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:disc;";

    private static final String OL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:decimal;";

    private static final String LI_STYLE = "margin:0.35em 0;line-height:1.75;font-size:16px;color:#3f3f3f;";

    private static final String IMG_STYLE =
        "max-width:100%;height:auto;display:block;margin:0.9em auto;border-radius:4px;";

    private static final String TABLE_STYLE =
        "border-collapse:collapse;width:100%;margin:1em 0;font-size:15px;overflow-x:auto;display:block;";

    private static final String TH_STYLE =
        "border:1px solid #dfe2e5;padding:8px 12px;text-align:left;background:#f6f8fa;font-weight:bold;color:#333333;";

    private static final String TD_STYLE =
        "border:1px solid #dfe2e5;padding:8px 12px;text-align:left;color:#3f3f3f;";

    private static final String HR_STYLE = "border:none;border-top:1px solid #eaeaea;margin:1.6em 0;";

    private static final String A_STYLE = "color:#576b95;text-decoration:none;";

    private static final String FIGCAPTION_STYLE = "text-align:center;font-size:13px;color:#999999;margin-top:6px;";

    private WechatContentBeautifier() {
    }

    /**
     * 美化正文 HTML：注入内联样式并做基础安全清理。入参为空或异常时原样返回，绝不阻断同步流程。
     *
     * @param html Halo 渲染并经图片转存后的正文 HTML
     * @return 适配微信编辑模式的内联样式 HTML
     */
    static String beautify(String html) {
        if (html == null || html.isBlank()) {
            return html == null ? "" : html;
        }
        Document document = Jsoup.parseBodyFragment(html);
        // 关闭美化缩进，避免在行内元素之间插入换行/空格（微信会渲染成多余空白），也保证 <pre> 内容原样输出
        document.outputSettings().prettyPrint(false);
        Element body = document.body();
        if (body == null) {
            return html;
        }
        sanitize(body);
        injectStyles(body);
        convertPreNewlinesToBr(body);
        wrapWithBase(body);
        return body.html();
    }

    /**
     * 安全清理：移除 {@code <script>}/{@code <style>} 标签与所有 {@code on*} 事件属性。
     * 微信自身也会剥离，这里主动清理让产物更干净、也避免残留可执行内容。
     */
    private static void sanitize(Element body) {
        body.select("script, style, iframe, object, embed").remove();
        for (Element element : body.getAllElements()) {
            List<String> eventAttrs = new ArrayList<>();
            for (Attribute attribute : element.attributes()) {
                if (attribute.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("on")) {
                    eventAttrs.add(attribute.getKey());
                }
            }
            eventAttrs.forEach(element::removeAttr);
        }
    }

    /** 按标签名注入内联样式。 */
    private static void injectStyles(Element body) {
        applyAll(body, "h1", H1_STYLE);
        applyAll(body, "h2", H2_STYLE);
        applyAll(body, "h3", H3_STYLE);
        applyAll(body, "h4", H4_STYLE);
        applyAll(body, "h5", H5_STYLE);
        applyAll(body, "h6", H6_STYLE);
        applyAll(body, "blockquote", BLOCKQUOTE_STYLE);
        applyAll(body, "pre", PRE_STYLE);
        applyAll(body, "ul", UL_STYLE);
        applyAll(body, "ol", OL_STYLE);
        applyAll(body, "li", LI_STYLE);
        applyAll(body, "img", IMG_STYLE);
        applyAll(body, "table", TABLE_STYLE);
        applyAll(body, "th", TH_STYLE);
        applyAll(body, "td", TD_STYLE);
        applyAll(body, "hr", HR_STYLE);
        applyAll(body, "a", A_STYLE);
        applyAll(body, "figcaption", FIGCAPTION_STYLE);

        // 段落：引用块内外的间距不同，分别处理，避免二次注入导致样式顺序错乱
        for (Element p : body.select("p")) {
            applyStyle(p, isInside(p, "blockquote") ? QUOTE_P_STYLE : P_STYLE);
        }
        // 行内代码与代码块内代码样式不同
        for (Element code : body.select("code")) {
            applyStyle(code, isInside(code, "pre") ? PRE_CODE_STYLE : INLINE_CODE_STYLE);
        }
    }

    /**
     * 把代码块内的裸换行转成 {@code <br>}。
     *
     * <p>微信（尤其后台草稿编辑器）可能吞掉 {@code <pre>} 内的 {@code \n}，导致整段代码挤成一行。
     * 这里逐行拆开、以 {@code <br>} 显式断行；缩进空格仍由 {@code white-space:pre} 保留，故排版与原生一致。</p>
     */
    private static void convertPreNewlinesToBr(Element body) {
        for (Element pre : body.select("pre")) {
            // pre.textNodes() 只含直接子文本节点，而代码常在 <pre><code>…</code></pre> 的 code 里，
            // 故递归收集全部后代文本节点；用快照列表遍历，插入的新节点不会被重复处理
            List<TextNode> textNodes = new ArrayList<>();
            for (Element element : pre.getAllElements()) {
                textNodes.addAll(element.textNodes());
            }
            for (TextNode textNode : textNodes) {
                String text = textNode.getWholeText();
                if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
                    continue;
                }
                String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                String[] lines = normalized.split("\n", -1);
                Node current = textNode;
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        Element br = new Element(Tag.valueOf("br"), "");
                        current.after(br);
                        current = br;
                    }
                    if (!lines[i].isEmpty()) {
                        TextNode segment = new TextNode(lines[i]);
                        current.after(segment);
                        current = segment;
                    }
                }
                textNode.remove();
            }
        }
    }

    /**
     * 用一个带基础排版样式的 {@code <section>} 包裹全部正文，使裸文本与未显式设样式的行内元素
     * （如 {@code span}/{@code em}）也能继承统一的字体、字号与行高。
     */
    private static void wrapWithBase(Element body) {
        Element wrapper = new Element(Tag.valueOf("section"), "");
        wrapper.attr("style", BASE_STYLE);
        List<Node> children = new ArrayList<>(body.childNodes());
        for (Node child : children) {
            wrapper.appendChild(child);
        }
        body.appendChild(wrapper);
    }

    private static void applyAll(Element body, String cssQuery, String style) {
        for (Element element : body.select(cssQuery)) {
            applyStyle(element, style);
        }
    }

    /**
     * 组合样式：默认样式在前、元素原有内联样式在后，保证用户已设置的行内样式优先生效。
     */
    private static void applyStyle(Element element, String style) {
        String existing = element.attr("style");
        if (existing == null || existing.isBlank()) {
            element.attr("style", style);
            return;
        }
        String merged = style.endsWith(";") ? style + existing : style + ";" + existing;
        element.attr("style", merged);
    }

    /** 判断元素是否位于指定标签名的祖先内。 */
    private static boolean isInside(Element element, String tagName) {
        Element parent = element.parent();
        while (parent != null) {
            if (tagName.equalsIgnoreCase(parent.tagName())) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }
}
