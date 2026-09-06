package com.hcjike.wechatofficialsync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 正文美化器：把 Halo 渲染出的正文 HTML 转换为「微信友好」的内联样式 HTML。
 *
 * <p>微信图文与邮件客户端类似，会剥离 {@code <head>}/{@code <style>}/外部 {@code <link>} CSS，并过滤
 * {@code class}/{@code id} 属性，<b>只保留元素上的内联 {@code style="..."} 属性</b>。而 Halo 正文靠主题
 * class + 外部 CSS 排版，直接塞进草稿会丢样式变成「裸 HTML」。本类按<b>标签名</b>为常见元素注入内联样式
 * （标题/段落/引用/代码块/图片自适应/列表/表格/链接等），对标 doocs/md 的默认排版效果。</p>

 * <p>注入策略：我们的默认样式写在<b>前</b>、元素原有内联样式写在<b>后</b>。同一 {@code style} 属性内后出现的
 * 同名属性覆盖先出现的，故用户在编辑器里已设置的行内样式优先级更高，本类只补齐缺省样式，不覆盖用户意图。
 * 仅使用 jsoup（项目已有依赖），无阻塞 I/O，可安全在 boundedElastic 线程执行。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
final class WechatContentBeautifier {

    private static final Logger log = LoggerFactory.getLogger(WechatContentBeautifier.class);

    /** 未配置或非法时使用的内置默认正文文字色（根节点/段落/列表/表格正文共用）。 */
    private static final String DEFAULT_TEXT_COLOR = "#3f3f3f";

    /** 未配置或非法时使用的内置默认链接色。 */
    private static final String DEFAULT_LINK_COLOR = "#576b95";

    /**
     * 未配置或非法时使用的内置默认行内代码文字色。
     *
     * <p>对齐 doocs/md「经典」主题（即微信图文事实上的行内代码标准）的 {@code #d14}。</p>
     */
    private static final String DEFAULT_INLINE_CODE_COLOR = "#d14";

    /**
     * 未配置或非法时使用的内置默认行内代码底色。
     *
     * <p>doocs/md「经典」用 {@code rgba(27,31,35,.05)}，白底上视觉等价于 {@code #f2f3f5}；
     * 因颜色选择器只支持十六进制，故用该 hex 作为可配默认值。</p>
     */
    private static final String DEFAULT_INLINE_CODE_BG_COLOR = "#f2f3f5";

    /** 未配置或非法主题色时使用的内置强调色（微信绿）。 */
    private static final String DEFAULT_ACCENT = "#07c160";

    /** H1–H6 未配置或非法时的内置默认文字颜色（下标 0 对应 H1）。 */
    private static final String[] DEFAULT_HEADING_COLORS =
        {"#222222", "#222222", "#222222", "#222222", "#333333", "#888888"};

    /** 仅接受 3/6/8 位十六进制颜色，避免把非法值写进 style 破坏样式。 */
    private static final Pattern HEX_COLOR =
        Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    /** 代码块统一等宽字体栈。 */
    private static final String CODE_FONT =
        "font-family:Consolas,'Liberation Mono',Menlo,Courier,monospace;";

    /** 引用块内的段落：收紧上下间距，避免与引用块自身的内边距叠加。 */
    private static final String QUOTE_P_STYLE =
        "margin:0.3em 0;line-height:1.7;font-size:15px;color:#666666;";

    /**
     * 代码块（浅色）：对齐微信后台「插入代码」的原生观感——浅灰底 + 极细内阴影边框（而非实线
     * border）+ 小圆角；{@code overflow-x:auto} + {@code white-space:pre} 让长行不折行、发布后可横向
     * 拖拽，缩进原样保留。
     */
    private static final String CODE_BLOCK_LIGHT =
        "margin:1em 0;overflow-x:auto;padding:10px 12px;background-color:#f8f8f8;"
            + "box-shadow:rgba(216,216,216,0.5) 0 0 0 1px inset;border-radius:3px;"
            + "font-size:14px;line-height:1.6;text-align:left;color:#333333;white-space:pre;"
            + "word-wrap:normal;" + CODE_FONT;

    /** 代码块（深色）：One Dark 观感，深底浅字，适合技术类公众号。 */
    private static final String CODE_BLOCK_DARK =
        "margin:1em 0;overflow-x:auto;padding:12px 15px;background-color:#282c34;border-radius:5px;"
            + "font-size:14px;line-height:1.6;text-align:left;color:#abb2bf;white-space:pre;"
            + "word-wrap:normal;" + CODE_FONT;

    private static final String UL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:disc;";

    private static final String OL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:decimal;";

    private static final String IMG_STYLE =
        "max-width:100%;height:auto;display:block;margin:0.9em auto;border-radius:4px;";

    /**
     * 表格外层横向滚动容器：表格宽度以文章中设置的为准，若该宽度超出微信移动端屏宽，
     * 容器 {@code overflow-x:auto} 让表格可横向滚动查看全貌，与代码块的横向拖拽观感一致。
     */
    private static final String TABLE_SCROLL_WRAPPER =
        "margin:1em 0;overflow-x:auto;-webkit-overflow-scrolling:touch;";

    /**
     * 表格本体：<b>不强制总宽度</b>，以文章中设置的为准（用户内联 {@code width} 因「默认在前、原有在后」
     * 而优先生效，见 {@link #applyStyle}）。关键是补回 {@code table-layout:fixed}：Halo/TipTap 编辑器表格
     * 本依赖它（来自样式表）但被微信剥离；固定布局下列宽严格按文章 {@code <colgroup>} 设定的每列宽度渲染，
     * 单元格内容在列宽内自动换行；当各列宽之和超出屏幕时表格整体溢出，由外层容器横向滚动。
     */
    private static final String TABLE_STYLE =
        "border-collapse:collapse;font-size:15px;table-layout:fixed;";

    /**
     * 单元格换行策略：固定布局下列宽已由 {@code <colgroup>} 确定，内容自然在列内换行；仅需
     * {@code overflow-wrap:break-word} 折断超长 token（如 URL）防止其溢出列宽、遮挡相邻列。
     * <b>不用 {@code overflow-wrap:anywhere}</b>：它会塌缩最小内容宽度，在自动布局下把表格压成满宽、丢失横向滚动。
     */
    private static final String CELL_WRAP = "word-break:break-word;overflow-wrap:break-word;";

    private static final String TH_STYLE =
        "border:1px solid #dfe2e5;padding:8px 12px;text-align:left;background:#f6f8fa;font-weight:bold;"
            + "color:#333333;" + CELL_WRAP;

    private static final String HR_STYLE = "border:none;border-top:1px solid #eaeaea;margin:1.6em 0;";

    private static final String FIGCAPTION_STYLE = "text-align:center;font-size:13px;color:#999999;margin-top:6px;";

    /**
     * 内容型标签：段落若含这些后代则视为非空，不能被当作空段落删除。
     * 用于区分「仅含 {@code <br>}/空 {@code <span>} 的占位空段落」与「包着图片/表格等内容的段落」。
     */
    private static final java.util.Set<String> CONTENT_BEARING_TAGS = java.util.Set.of(
        "img", "table", "iframe", "video", "audio", "embed", "object", "svg", "code", "pre", "math");

    private WechatContentBeautifier() {
    }

    /**
     * 美化正文 HTML：注入内联样式并做基础安全清理。入参为空或异常时原样返回，绝不阻断同步流程。
     *
     * @param html   Halo 渲染并经图片转存后的正文 HTML
     * @param config 美化配置（引用块边框色、标题边框开关与 H2–H6 逐级边框色、代码块主题、H1–H6 与正文/链接/行内代码颜色）；为 {@code null} 时用内置默认值
     * @return 适配微信编辑模式的内联样式 HTML
     */
    static String beautify(String html, BeautifySetting config) {
        if (html == null || html.isBlank()) {
            return html == null ? "" : html;
        }
        BeautifySetting cfg = config == null ? new BeautifySetting() : config;
        Document document = Jsoup.parseBodyFragment(html);
        // 关闭美化缩进，避免在行内元素之间插入换行/空格（微信会渲染成多余空白），也保证代码列内容原样输出
        document.outputSettings().prettyPrint(false);
        Element body = document.body();
        if (body == null) {
            return html;
        }
        sanitize(body);
        // 删除 TipTap/ProseMirror 在表格等块前后遗留的空段落（否则微信里渲染成多余空行）
        removeEmptyParagraphs(body);
        // 代码块重建须在通用样式注入前：重建后 <pre> 已不存在，剩余 <code> 即行内代码
        buildCodeBlocks(body, cfg);
        // 表格包裹须在通用样式注入前：外层滚动容器就位后，table/th/td 样式仍按标签名注入
        buildTables(body);
        injectStyles(body, cfg);
        wrapWithBase(body, cfg);
        return body.html();
    }

    /**
     * 安全清理：移除 {@code <script>}/{@code <style>} 等标签与所有 {@code on*} 事件属性。
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

    /**
     * 删除「视觉空段落」：仅含空白文本与 {@code <br>}/空 {@code <span>} 的 {@code <p>}。
     *
     * <p>Halo/TipTap 会在表格等块前后遗留形如
     * {@code <p><span leaf=""><br class="ProseMirror-trailingBreak"></span></p>} 的空段落，
     * 在微信里渲染成多余的空行（表现为表格上下多出空白）。含图片/表格/代码等内容型后代的
     * 段落不删。</p>
     *
     * <p>{@code ProseMirror-trailingBreak} 是纯编辑器渲染产物（ProseMirror 为让空块可见而插入的尾随
     * {@code <br>}），发布内容里完全多余，故先整体移除；移除后表格<b>单元格内</b>的空段落
     * （如 {@code <td><p><span leaf=""><br class="ProseMirror-trailingBreak"></span></p></td>}）也随之
     * 变成视觉空段落被一并删除，空单元格留 {@code <td></td>} 一样正常渲染（有边框与内边距），
     * 不再在微信里撑出一格格多余空白。</p>
     */
    private static void removeEmptyParagraphs(Element body) {
        // 先移除 ProseMirror 尾随换行占位 <br>，让空单元格/空段落暴露为「视觉空段落」，随后统一删除
        body.select("br.ProseMirror-trailingBreak").remove();
        int removed = 0;
        for (Element p : body.select("p")) {
            if (isVisuallyEmpty(p)) {
                p.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("正文美化：移除了 {} 个空段落（TipTap/ProseMirror 遗留的空行占位）", removed);
        }
    }

    /**
     * 段落是否「视觉为空」：不含任何内容型后代（图片/表格/代码等），且去掉常规空白与
     * 不可见字符（不间断空格 {@code \u00a0}、零宽字符、BOM）后无可见文本。兼容 TipTap 空段落
     * 可能携带 {@code &nbsp;} 或零宽空格的变体。
     */
    private static boolean isVisuallyEmpty(Element paragraph) {
        for (Element descendant : paragraph.getAllElements()) {
            if (CONTENT_BEARING_TAGS.contains(descendant.tagName().toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return hasNoVisibleText(paragraph.text());
    }

    /** 去掉常规空白与不可见字符（nbsp/零宽空格/BOM）后是否已无可见文本。 */
    private static boolean hasNoVisibleText(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00a0' && c != '\u200b' && c != '\u200c'
                && c != '\u200d' && c != '\ufeff') {
                return false;
            }
        }
        return true;
    }

    /** 按标签名注入内联样式（代码块已在 {@link #buildCodeBlocks} 单独重建，此处不再处理 pre）。 */
    private static void injectStyles(Element body, BeautifySetting cfg) {
        String accent = color(cfg.getThemeColor(), DEFAULT_ACCENT);
        String textColor = color(cfg.getTextColor(), DEFAULT_TEXT_COLOR);
        String linkColor = color(cfg.getLinkColor(), DEFAULT_LINK_COLOR);
        String inlineCodeColor = color(cfg.getInlineCodeColor(), DEFAULT_INLINE_CODE_COLOR);
        String inlineCodeBgColor = color(cfg.getInlineCodeBgColor(), DEFAULT_INLINE_CODE_BG_COLOR);
        // 「标题显示边框」开启后，H2–H6 各自加左侧强调边框（H1 居中不加），每级边框色独立配置
        boolean headingBorder = cfg.isHeadingBorderEnabled();
        applyAll(body, "h1", headingStyle("font-size:22px;text-align:center;margin:1.4em 0 0.9em;",
            color(cfg.getH1Color(), DEFAULT_HEADING_COLORS[0]), ""));
        applyAll(body, "h2", headingStyle("font-size:19px;margin:1.6em 0 0.9em;",
            color(cfg.getH2Color(), DEFAULT_HEADING_COLORS[1]),
            headingBorderCss(headingBorder, cfg.getH2BorderColor())));
        applyAll(body, "h3", headingStyle("font-size:17px;margin:1.4em 0 0.7em;",
            color(cfg.getH3Color(), DEFAULT_HEADING_COLORS[2]),
            headingBorderCss(headingBorder, cfg.getH3BorderColor())));
        applyAll(body, "h4", headingStyle("font-size:16px;margin:1.2em 0 0.6em;",
            color(cfg.getH4Color(), DEFAULT_HEADING_COLORS[3]),
            headingBorderCss(headingBorder, cfg.getH4BorderColor())));
        applyAll(body, "h5", headingStyle("font-size:15px;margin:1.1em 0 0.5em;",
            color(cfg.getH5Color(), DEFAULT_HEADING_COLORS[4]),
            headingBorderCss(headingBorder, cfg.getH5BorderColor())));
        applyAll(body, "h6", headingStyle("font-size:14px;margin:1.1em 0 0.5em;",
            color(cfg.getH6Color(), DEFAULT_HEADING_COLORS[5]),
            headingBorderCss(headingBorder, cfg.getH6BorderColor())));
        applyAll(body, "blockquote", blockquoteStyle(accent));
        applyAll(body, "ul", UL_STYLE);
        applyAll(body, "ol", OL_STYLE);
        applyAll(body, "li", liStyle(textColor));
        applyAll(body, "img", IMG_STYLE);
        applyAll(body, "table", TABLE_STYLE);
        applyAll(body, "th", TH_STYLE);
        applyAll(body, "td", tdStyle(textColor));
        applyAll(body, "hr", HR_STYLE);
        applyAll(body, "a", aStyle(linkColor));
        applyAll(body, "figcaption", FIGCAPTION_STYLE);
        // 代码块已重建为 section，剩余 <code> 均为行内代码
        applyAll(body, "code", inlineCodeStyle(inlineCodeColor, inlineCodeBgColor));

        // 段落：表格单元格内、引用块内、普通正文的间距不同，分别处理，避免二次注入导致样式顺序错乱
        for (Element p : body.select("p")) {
            String paragraphStyle;
            if (isInside(p, "td") || isInside(p, "th")) {
                // 单元格内段落去掉上下外边距，否则首/末行单元格会在表格上下多出空行
                paragraphStyle = tableCellPStyle(textColor);
            } else if (isInside(p, "blockquote")) {
                paragraphStyle = QUOTE_P_STYLE;
            } else {
                paragraphStyle = pStyle(textColor);
            }
            applyStyle(p, paragraphStyle);
        }
    }

    /** 组装标题样式：字号/对齐/间距 + 可选左侧强调边框 + 统一粗体行高 + 可配置文字颜色。 */
    private static String headingStyle(String sizeAndSpacing, String color, String borderCss) {
        return sizeAndSpacing + borderCss + "font-weight:bold;line-height:1.4;color:" + color + ";";
    }

    /** 标题左侧强调边框样式：仅在开启「标题显示边框」时生成，颜色非法/缺省回退默认强调色。 */
    private static String headingBorderCss(boolean enabled, String borderColor) {
        return enabled
            ? "padding-left:12px;border-left:4px solid " + color(borderColor, DEFAULT_ACCENT) + ";"
            : "";
    }

    /**
     * 把每个 {@code <pre>} 重建为微信原生风格的单栏代码块（不加行号）。
     *
     * <p>微信只保留内联样式，代码文本用 {@link Element#wholeText()} 原样取出（保留缩进与换行），
     * 按行拆分后逐行写入并以 {@code <br>} 断行；容器 {@code overflow-x:auto} + {@code white-space:pre}
     * 让长行不折行、发布后可横向拖拽。</p>
     */
    private static void buildCodeBlocks(Element body, BeautifySetting cfg) {
        boolean dark = isDarkCode(cfg.getCodeBlockTheme());
        String blockStyle = dark ? CODE_BLOCK_DARK : CODE_BLOCK_LIGHT;
        for (Element pre : body.select("pre")) {
            List<String> lines = extractCodeLines(pre);
            Element block = new Element(Tag.valueOf("section"), "");
            block.attr("style", blockStyle);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    block.appendChild(new Element(Tag.valueOf("br"), ""));
                }
                if (!lines.get(i).isEmpty()) {
                    block.appendChild(new TextNode(lines.get(i)));
                }
            }
            pre.replaceWith(block);
        }
    }

    /**
     * 把每个 {@code <table>} 包进一个横向滚动的 {@code <section>} 容器，并移除 Halo/TipTap 编辑器的
     * {@code <div class="tableWrapper">} 多余包裹层。
     *
     * <p>表格 {@code table-layout:fixed}（见 {@link #TABLE_STYLE}）使列宽严格按文章设定的每列宽度渲染、
     * 单元格内容在列内自动换行；当各列宽之和超出屏幕时，表格整体溢出容器，由 {@code overflow-x:auto} 横向滚动
     * 查看全貌。</p>
     *
     * <p>Halo/TipTap 编辑器输出的表格通常被 {@code <div class="tableWrapper">} 包裹，该 div 在微信中无实际
     * 作用（class 会被剥离），且多层嵌套会触发微信编辑器重构 DOM、在表格前后插入空段落（表现为发布后
     * 表格上下多出空行）。故此方法在包裹 section 后会将多余的 div 层解开，产出更简洁的
     * {@code <section><table></table></section>} 结构。</p>
     */
    private static void buildTables(Element body) {
        for (Element table : body.select("table")) {
            Element wrapper = new Element(Tag.valueOf("section"), "");
            wrapper.attr("style", TABLE_SCROLL_WRAPPER);
            // 若表格直接被 <div>（如 TipTap 的 tableWrapper）包裹，用我们的 section 替换整个 div，
            // 避免产出 <div><section><table></section></div> 多层嵌套结构
            Element parent = table.parent();
            if (parent != null && "div".equalsIgnoreCase(parent.tagName()) && parent.children().size() == 1) {
                parent.replaceWith(wrapper);
            } else {
                table.before(wrapper);
            }
            wrapper.appendChild(table);
        }
    }

    /**
     * 取出 {@code <pre>} 的纯文本并按行拆分：先归一换行符，再剥掉渲染器常引入的首/尾空行；
     * 空代码块至少返回一个空行，保证结构完整。
     */
    private static List<String> extractCodeLines(Element pre) {
        String text = pre.wholeText();
        if (text == null) {
            text = "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        if (lines.size() > 1 && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    /**
     * 用一个带基础排版样式的 {@code <section>} 包裹全部正文，使裸文本与未显式设样式的行内元素
     * （如 {@code span}/{@code em}）也能继承统一的字体、字号与行高。
     */
    private static void wrapWithBase(Element body, BeautifySetting cfg) {
        Element wrapper = new Element(Tag.valueOf("section"), "");
        wrapper.attr("style", baseStyle(color(cfg.getTextColor(), DEFAULT_TEXT_COLOR)));
        List<Node> children = new ArrayList<>(body.childNodes());
        for (Node child : children) {
            wrapper.appendChild(child);
        }
        body.appendChild(wrapper);
    }

    private static String blockquoteStyle(String accent) {
        return "margin:1em 0;padding:10px 15px;border-left:4px solid " + accent + ";background:#f7f7f7;"
            + "color:#666666;border-radius:0 4px 4px 0;";
    }

    /** 正文根节点基础排版：字体、字号、行高与可配置正文色，供未显式覆盖的后代继承。 */
    private static String baseStyle(String textColor) {
        return "font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue','PingFang SC','Hiragino Sans GB',"
            + "'Microsoft YaHei',Arial,sans-serif;"
            + "font-size:16px;color:" + textColor + ";line-height:1.75;letter-spacing:0.4px;"
            + "word-break:break-word;text-align:left;";
    }

    private static String pStyle(String textColor) {
        return "margin:0.9em 0;line-height:1.75;font-size:16px;color:" + textColor + ";letter-spacing:0.4px;";
    }

    /**
     * 表格单元格内的段落：Halo/TipTap 把单元格内容包在 {@code <p>} 里，若沿用正文段落的
     * {@code margin:0.9em 0}，首行单元格顶部与末行单元格底部会各多出一段空白（看上去像多了
     * 一行空行）。故单元格内段落外边距置 0，字号与表格一致（{@code 15px}），只保留较小行高。
     */
    private static String tableCellPStyle(String textColor) {
        return "margin:0;line-height:1.6;font-size:15px;color:" + textColor + ";";
    }

    private static String liStyle(String textColor) {
        return "margin:0.35em 0;line-height:1.75;font-size:16px;color:" + textColor + ";";
    }

    private static String tdStyle(String textColor) {
        return "border:1px solid #dfe2e5;padding:8px 12px;text-align:left;color:" + textColor + ";" + CELL_WRAP;
    }

    private static String aStyle(String linkColor) {
        return "color:" + linkColor + ";text-decoration:none;";
    }

    /**
     * 行内代码：对齐 doocs/md「经典」主题（微信图文行内代码的事实标准）——{@code font-size:90%}、
     * {@code padding:3px 5px}、{@code border-radius:4px} 与 Fira Code/Menlo/Consolas 等宽字体栈；
     * 文字色与底色可由配置覆盖（默认 {@code #d14} 字 + 浅灰底）。
     */
    private static String inlineCodeStyle(String inlineCodeColor, String inlineCodeBgColor) {
        return "font-size:90%;padding:3px 5px;border-radius:4px;background:" + inlineCodeBgColor
            + ";color:" + inlineCodeColor
            + ";font-family:'Fira Code',Menlo,Operator Mono,Consolas,Monaco,monospace;";
    }

    /** 解析颜色：仅接受合法十六进制颜色，否则回退给定默认色。 */
    private static String color(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return HEX_COLOR.matcher(trimmed).matches() ? trimmed : fallback;
    }

    /** 代码块是否用深色主题（仅当显式配置为 {@code dark}）。 */
    private static boolean isDarkCode(String codeBlockTheme) {
        return codeBlockTheme != null && "dark".equalsIgnoreCase(codeBlockTheme.trim());
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
