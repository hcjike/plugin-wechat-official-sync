package com.hcjike.wechatofficialsync.content;

import com.hcjike.wechatofficialsync.config.BeautifySetting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 正文美化器：把 Halo 渲染出的正文 HTML 转换为「微信友好」的内联样式 HTML。
 *
 * <p>微信图文与邮件客户端类似，会剥离 {@code <head>}/{@code <style>}/外部 {@code <link>} CSS，并过滤
 * {@code class}/{@code id} 属性，<b>只保留元素上的内联 {@code style="..."} 属性</b>。而 Halo 正文靠主题
 * class + 外部 CSS 排版，直接塞进草稿会丢样式变成「裸 HTML」。本类按<b>标签名</b>为常见元素注入内联样式
 * （标题/段落/引用/代码块/图片自适应/列表/任务列表/表格/链接等），对标 doocs/md 的默认排版效果。</p>

 * <p>注入策略：我们的默认样式写在<b>前</b>、元素原有内联样式写在<b>后</b>。同一 {@code style} 属性内后出现的
 * 同名属性覆盖先出现的，故用户在编辑器里已设置的行内样式优先级更高，本类只补齐缺省样式，不覆盖用户意图。
 * 仅使用 jsoup（项目已有依赖），无阻塞 I/O，可安全在 boundedElastic 线程执行。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public final class WechatContentBeautifier {

    private static final Logger log = LoggerFactory.getLogger(WechatContentBeautifier.class);

    /** 解析 Halo 插件自定义元素（如 {@code <DOWNLOAD-LINKS>}）中 JSON 数据属性用。 */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * 承载下载链接等块级自定义元素转换结果的「行内容器」标签：当自定义元素已位于这些标签内时，
     * 直接把链接插入原位置即可，避免产出 {@code <p><p>...</p></p>} 之类的非法嵌套。
     */
    private static final java.util.Set<String> INLINE_CONTAINER_TAGS = java.util.Set.of(
        "p", "li", "td", "th", "blockquote", "figcaption");

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

    /** 未配置或非法时使用的内置默认引用块背景色（浅灰）。 */
    private static final String DEFAULT_BLOCKQUOTE_BG_COLOR = "#f7f7f7";

    /** 未配置或非法时使用的内置默认折叠块标题栏背景色（浅灰）。 */
    private static final String DEFAULT_DETAILS_TITLE_BG_COLOR = "#f7f7f7";

    /** 未配置或非法时使用的内置默认折叠块内容区背景色（白色，与正文背景一致）。 */
    private static final String DEFAULT_DETAILS_CONTENT_BG_COLOR = "#ffffff";

    /** 未配置或非法时使用的内置默认折叠块边框颜色（浅灰，与表格边框同色）。 */
    private static final String DEFAULT_DETAILS_BORDER_COLOR = "#e6e6e6";

    /** H1–H6 未配置或非法时的内置默认文字颜色（下标 0 对应 H1）。 */
    private static final String[] DEFAULT_HEADING_COLORS =
        {"#222222", "#222222", "#222222", "#222222", "#333333", "#888888"};

    /** 仅接受 3/6/8 位十六进制颜色，避免把非法值写进 style 破坏样式。 */
    private static final Pattern HEX_COLOR =
        Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    /** 引用块内的段落：收紧上下间距，避免与引用块自身的内边距叠加。 */
    private static final String QUOTE_P_STYLE =
        "margin:0.3em 0;line-height:1.7;font-size:15px;color:#666666;";

    /**
     * 微信原生代码块外层容器类名：{@code __fix} 提供「行号列 + 代码区」布局，{@code __js} 标记
     * 交由微信脚本按 {@code data-lang} 重新高亮，两者均命中微信公众平台文章加载的全局样式。
     */
    private static final String CODE_SNIPPET_FIX_CLASS = "code-snippet__fix";

    /** 微信原生代码块类名：外层容器、行号列与代码区 {@code <pre>} 共用。 */
    private static final String CODE_SNIPPET_CLASS = "code-snippet__js";

    /** 微信原生代码块行号列类名：每个代码行对应一个空 {@code <li>}，序号由微信 CSS 计数器自增渲染。 */
    private static final String CODE_SNIPPET_LINE_INDEX_CLASS = "code-snippet__line-index";

    /** 空代码行的占位符（零宽空格）：保证空行高度不塌陷，使行号与代码行严格一一对应。 */
    private static final String EMPTY_CODE_LINE = "\u200b";

    private static final String UL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:disc;";

    private static final String OL_STYLE = "margin:0.9em 0;padding-left:1.6em;list-style:decimal;";

    private static final String IMG_STYLE =
        "max-width:100%;height:auto;display:block;margin:0.9em auto;border-radius:4px;";

    /**
     * 表格外层横向滚动容器（兜底）：表格默认铺满微信屏宽（见 {@link #TABLE_STYLE}），仅在「保持比例」
     * 宽度模式（{@link BeautifySetting#TABLE_WIDTH_MODE_PROPORTIONAL}）下文章列宽总和超出屏宽时
     * （表格带 {@code min-width} 托底总宽），{@code overflow-x:auto} 让表格可横向滚动查看全貌，与代码块
     * 的横向拖拽观感一致；「宽度铺满」模式下列宽已收敛在屏宽内，该容器不触发滚动。
     */
    private static final String TABLE_SCROLL_WRAPPER =
        "margin:1em 0;overflow-x:auto;-webkit-overflow-scrolling:touch;";

    /**
     * 表格本体：默认 {@code width:100%} <b>铺满屏宽</b>（页面宽度自适应），与微信编辑器插入表格的观感一致；
     * 若文章已为表格设置具体宽度，用户内联 {@code width} 因「默认在前、原有在后」而优先生效
     * （见 {@link #applyStyle}；「宽度铺满」模式下由 {@link #applyTableFillWidth} 覆盖为 100%）。
     * 同时补回 {@code table-layout:fixed}：Halo/TipTap 编辑器表格本依赖它（来自样式表）但被微信剥离；
     * 固定布局下列宽按<b>首行单元格</b>的 {@code width} 渲染（文章 {@code <colgroup>} 的列宽已在
     * {@link #normalizeTables} 中转写到首行单元格、colgroup 随之删除），单元格内容在列宽内自动换行。
     * 列宽载体与兜底按 {@link BeautifySetting#getTableWidthMode()} 处理：两模式都把列宽归一化为
     * 百分比转写到首行单元格——实测微信渲染会丢弃单元格上的像素列宽、把表格压回屏宽，百分比才是
     * 可靠载体（见 {@link #writeFirstRowWidths}）；「保持比例」（默认）另在表格上补 {@code min-width}
     * （列宽总和）——与生效中的 {@code width:100%} 同属表格内联样式，屏宽够大时仍铺满，总宽超出屏宽时
     * 表格整体溢出、由外层容器横向滚动兜底；「宽度铺满」不加 min-width，始终收敛在屏宽内。
     */
    private static final String TABLE_STYLE =
        "border-collapse:collapse;width:100%;font-size:15px;table-layout:fixed;";

    /**
     * 单元格换行策略：固定布局下列宽已由首行单元格宽度确定，内容自然在列内换行；仅需
     * {@code overflow-wrap:break-word} 折断超长 token（如 URL）防止其溢出列宽、遮挡相邻列。
     * <b>不用 {@code overflow-wrap:anywhere}</b>：它会塌缩最小内容宽度，在自动布局下把表格压成满宽、丢失横向滚动。
     */
    private static final String CELL_WRAP = "word-break:break-word;overflow-wrap:break-word;";

    /**
     * 表头单元格：对齐微信编辑器原生表格的观感——1px 浅灰细边框、紧凑内边距、左对齐且加粗、<b>无底色</b>
     * （微信编辑器插入表格的表头默认不带背景色）。
     */
    private static final String TH_STYLE =
        "border:1px solid #e6e6e6;padding:8px;text-align:left;font-weight:bold;color:#333333;" + CELL_WRAP;

    private static final String HR_STYLE = "border:none;border-top:1px solid #eaeaea;margin:1.6em 0;";

    /**
     * 图片描述（{@code <figcaption>}，对应 Halo 编辑器的「图片描述 / Image description」）：
     * 对齐 Halo 编辑器 {@code figure figcaption} 的默认观感——居中、灰色小字、<b>斜体</b>
     * （Halo 编辑器样式即 {@code font-style: italic}），斜体在微信侧同样保真渲染。
     */
    private static final String FIGCAPTION_STYLE =
        "text-align:center;font-size:13px;color:#999999;font-style:italic;margin-top:6px;";

    /** 折叠块标题栏前缀标记（呼应 Halo 编辑器折叠块的展开箭头）。 */
    private static final String DETAILS_MARKER = "▸";

    /** 任务列表已完成项的行首图标：微信会剥离 {@code <input>} 复选框，勾选状态用 Emoji 表达。 */
    private static final String TASK_CHECKED_ICON = "✅";

    /** 任务列表未完成项的行首图标。 */
    private static final String TASK_UNCHECKED_ICON = "⬜";

    /** 任务列表容器样式：去掉列表默认圆点与缩进（行首标记即 Emoji 图标），上下外边距对齐正文列表。 */
    private static final String TASK_LIST_STYLE = "margin:0.9em 0;padding-left:0;list-style:none;";

    /**
     * 正文中以<b>纯文本</b>残留的 markdown 任务清单行首标记：行首的 {@code - [ ] 事项} /
     * {@code - [x] 事项}（兼容 {@code *}/{@code +} 项目符号与 {@code [X]} 大写勾选）。缩进仅匹配
     * 空格/制表符（不吃换行，避免多行文本跨行误匹配）；{@code ]} 后允许行尾（无内容的空任务项）。
     */
    private static final Pattern TASK_LIST_TEXT_LINE = Pattern.compile(
        "^([ \\t]*)[-+*][ \\t]+\\[([ xX])\\](?:[ \\t]+|$)", Pattern.MULTILINE);

    /**
     * 布局表格样式（分栏卡片/画廊重建用）：铺满屏宽、固定布局按单元格百分比宽度分配列宽、
     * 不带正文表格的边框与内边距。布局表格在通用样式注入<b>之后</b>重建，不受 {@link #TABLE_STYLE} 影响。
     */
    private static final String LAYOUT_TABLE_STYLE = "width:100%;border-collapse:collapse;table-layout:fixed;";

    /**
     * 布局表格标记类名：供 Console 预览弹窗为重建后的分栏/画廊表格补充辨识样式（预览里显示浅灰细边框）。
     * 提交到微信的产物不依赖该类名——微信端无匹配样式、class 属性被剥离也不影响渲染。
     */
    private static final String LAYOUT_TABLE_CLASS = "wechat-layout-table";

    /**
     * 画廊图片覆写样式：Halo 画廊的图片靠 flex 项撑高（{@code height:100%}），表格布局下改由宽度
     * 决定高度，须覆盖为自动高度，并去掉正文图片的段落外边距（原有样式在前、本样式在后）。
     */
    private static final String GALLERY_IMG_STYLE = "width:100%;height:auto;display:block;margin:0;";

    /** 分栏容器的缺省间距：编辑器默认 {@code gap: 1em}，按 16px 正文字号折算。 */
    private static final int DEFAULT_COLUMN_GAP_PX = 16;

    /** 间距 {@code em} 单位的像素换算基准：正文字号为 16px（见 {@link #baseStyle}）。 */
    private static final int EM_TO_PX = 16;

    /**
     * 画廊统一网格的列数上限：各行图片数（分组大小与末行余数）的最小公倍数超过它时退化为
     * 最大行图片数，避免极端分组数下表格列数过大（此时仅末行可能不再精确等分）。
     */
    private static final int MAX_GALLERY_GRID_COLUMNS = 60;

    /** 内联样式中的 flex grow 值（{@code flex: 2 1} → 2）：分栏列宽按该值比例分配。 */
    private static final Pattern STYLE_FLEX_GROW = Pattern.compile("\\bflex\\s*:\\s*([\\d.]+)");

    /** 内联样式中的 {@code gap} 间距（如 {@code gap: 1em}），仅识别数值与 em/px 单位。 */
    private static final Pattern STYLE_GAP = Pattern.compile("\\bgap\\s*:\\s*([\\d.]+)\\s*(em|px)?");

    /** 从纯数值文本（如画廊 {@code data-gap="8"}）中提取首个数值。 */
    private static final Pattern SIZE_NUMBER = Pattern.compile("([\\d.]+)");

    /**
     * 内联样式中的 {@code width} 声明（像素/百分比）；负向后行断言跳过 {@code min-width}/{@code max-width}
     * 中的 width 子串。用于解析 {@code <colgroup>} 列宽与检查单元格既有宽度。
     */
    private static final Pattern STYLE_WIDTH =
        Pattern.compile("(?<![-a-zA-Z])width\\s*:\\s*([\\d.]+)\\s*(px|%)?");

    /** 单条 {@code <col span>} 允许展开的最大列数（防御异常 HTML 造成超长列表）。 */
    private static final int MAX_COL_SPAN = 1000;

    /**
     * 以<b>转义文本</b>形式残留在正文里的 {@code <style>}/{@code <script>} 块：从其他平台粘贴或
     * 导入 HTML 文章时，原始标签常被编辑器转义为纯文本（形如 {@code &lt;style&gt;.a{}&lt;/style&gt;}），
     * 解析后是普通文本而非元素，{@link #sanitize} 的标签选择器删不到，会以可见源码的形式出现在
     * 预览与草稿中。仅匹配「开标签 + 其中内容 + 闭标签」的完整成对块，无闭合标签的零星提及不动。
     */
    private static final Pattern ESCAPED_STYLE_SCRIPT_BLOCK = Pattern.compile(
        "<style\\b[^>]*>[\\s\\S]*?</style\\s*>|<script\\b[^>]*>[\\s\\S]*?</script\\s*>",
        Pattern.CASE_INSENSITIVE);

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
     * @param config 美化配置（引用块边框开关/边框色/背景色、标题边框开关与 H2–H6 逐级边框色、H1–H6 与正文/链接/行内代码颜色、折叠块标题/内容背景色与边框颜色、表格宽度模式、分栏卡片版式与画廊版式）；为 {@code null} 时用内置默认值
     * @return 适配微信编辑模式的内联样式 HTML
     */
    public static String beautify(String html, BeautifySetting config) {
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
        // 粘贴/导入的 HTML 常把 <style>/<script> 转义成纯文本残留在正文里（sanitize 按标签删不到），
        // 预览与草稿都不应展示这些源码文本；在空段落清理前整体剔除，代码块（pre/code）内的示例保留
        removeEscapedStyleScriptBlocks(body);
        // 转换 Halo 插件注入的自定义 Web Component（链接卡片/下载链接等），微信无法渲染，
        // 需在样式注入前转为标准 <a>/<p>；转换后遗留的空段落交由 removeEmptyParagraphs 清理
        convertPluginCustomElements(body);
        // 把 <figure>、游离的 <summary> 等微信不识别的块级包装降级为标准 <p>，
        // 否则微信编辑器会在这些块前后插入空 <p> 占位（发布后表现为图片上下多出空行）；
        // 折叠块（<details>）整体保留，由流程末尾的 buildDetailsBlocks 统一重建
        normalizeBlockWrappers(body);
        // 删除 TipTap/ProseMirror 在表格等块前后遗留的空段落（否则微信里渲染成多余空行）
        removeEmptyParagraphs(body);
        // 代码块重建须在通用样式注入前：重建为微信原生 code-snippet 结构（行号列 + 逐行 code 的 pre），
        // 通用样式注入会跳过该结构，结构外剩余 <code> 即行内代码
        buildCodeBlocks(body);
        // 任务列表重建为「Emoji 图标 + 内容」：微信会剥离 <input> 复选框、勾选状态无法呈现。
        // 覆盖两种来源——Halo 默认编辑器（TipTap）的待办清单与 markdown 渲染输出（如「Markdown
        // 编辑块」）的 GFM 任务列表；须在通用样式注入前完成：重建后的 ul/li 样式由本步精确写入、
        // 注入阶段跳过，任务项内段落由段落循环按任务项间距注入
        buildTaskLists(body, cfg);
        // 正文中以纯文本残留的 markdown 任务清单（- [ ] 事项 / - [x] 事项）替换为图标呈现
        convertTaskListText(body);
        // 表格结构归一化须在包裹与样式注入前：colgroup 列宽归一化为百分比转写到首行单元格（「保持
        // 比例」另给表格补 min-width 托底原始总宽），随后删除 colgroup（微信编辑器不识别它，草稿在
        // 编辑器里二次编辑重建时容易被误解析出多余的空行/空框）、压紧结构空白
        normalizeTables(body, cfg);
        // 表格包裹须在通用样式注入前：外层滚动容器就位后，table/th/td 样式仍按标签名注入
        buildTables(body);
        injectStyles(body, cfg);
        // 「宽度铺满」模式：列宽已归一化为百分比，再把表格宽度强制为 100%（覆盖文章自定义宽度），
        // 保证表格收敛在屏宽内、不触发横向滚动；布局表格在其后重建、不受影响
        if (isTableWidthFill(cfg)) {
            applyTableFillWidth(body);
        }
        wrapWithBase(body, cfg);
        // 分栏卡片与画廊按各自配置重建版式（表格/独占一行）：微信会过滤 display:grid、flex 支持不稳定，
        // 直接同步会导致各列/各图纵向堆叠；置于最后也让布局表格避开通用 table/td 样式注入与滚动容器包裹
        rebuildBlockLayouts(body, cfg);
        // 折叠块（Halo <details>）重建为静态展开的卡片，须在全部样式注入与版式重建之后：
        // 新生成的标题栏 <p> 不参与通用段落样式注入，内容区沿用内容块自身已注入的样式；
        // 标题栏/内容区背景色与边框颜色取自配置（非法值回退内置默认）
        buildDetailsBlocks(body, cfg);
        return body.html();
    }

    /**
     * 安全清理：移除 {@code <script>}/{@code <style>}/{@code <link>} 等标签与所有 {@code on*} 事件属性。
     * 微信自身也会剥离，这里主动清理让产物更干净、也避免残留可执行内容；
     * 其中 {@code <link>} 会加载外部样式资源，预览与草稿都不应加载（只按正文自身的行内样式渲染）。
     */
    private static void sanitize(Element body) {
        body.select("script, style, link, iframe, object, embed").remove();
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
     * 移除正文中以<b>转义文本</b>形式残留的 {@code <style>}/{@code <script>} 块（见
     * {@link #ESCAPED_STYLE_SCRIPT_BLOCK}）：它们因被编辑器转义而成为普通文本，会以可见源码的
     * 形式出现在预览与微信草稿中。仅处理正文流中的文本节点，{@code <pre>}/{@code <code>} 内的
     * 示例代码原样保留；剔除后变为空的段落交由 {@link #removeEmptyParagraphs} 清理。
     */
    private static void removeEscapedStyleScriptBlocks(Element body) {
        for (Element element : body.getAllElements()) {
            for (Node node : new ArrayList<>(element.childNodes())) {
                if (!(node instanceof TextNode textNode) || isInsideCode(textNode)) {
                    continue;
                }
                String text = textNode.getWholeText();
                if (!ESCAPED_STYLE_SCRIPT_BLOCK.matcher(text).find()) {
                    continue;
                }
                String cleaned = ESCAPED_STYLE_SCRIPT_BLOCK.matcher(text).replaceAll("");
                if (cleaned.isBlank()) {
                    textNode.remove();
                } else {
                    textNode.text(cleaned);
                }
            }
        }
    }

    /**
     * 把正文里以纯文本残留的 markdown 任务清单标记替换为任务图标（{@link #TASK_CHECKED_ICON}/
     * {@link #TASK_UNCHECKED_ICON}）：从其他平台粘贴的 markdown 文本不会被渲染成任务列表，直接同步
     * 会以「- [x] 事项」的源码形式展示。仅处理<b>行首</b>标记（正文中间的 {@code - [ ]} 属正常文字），
     * 代码块（{@code <pre>}/{@code <code>}）中的示例原样保留。
     */
    private static void convertTaskListText(Element body) {
        for (Element element : body.getAllElements()) {
            for (Node node : new ArrayList<>(element.childNodes())) {
                if (!(node instanceof TextNode textNode) || isInsideCode(textNode)) {
                    continue;
                }
                replaceTaskListTextPrefixes(textNode);
            }
        }
    }

    /**
     * 把单个文本节点内各行行首的任务清单标记替换为图标，兼容同一节点内以换行分隔的多行；
     * 节点内首行须确认真处于行首（见 {@link #startsLine}）——正文中间的 {@code - [ ]} 不做替换。
     */
    private static void replaceTaskListTextPrefixes(TextNode textNode) {
        String text = textNode.getWholeText();
        Matcher matcher = TASK_LIST_TEXT_LINE.matcher(text);
        if (!matcher.find()) {
            return;
        }
        StringBuilder replaced = new StringBuilder();
        int copied = 0;
        boolean changed = false;
        do {
            // 匹配从文本开头起算时（多行文本的其余行一定在换行之后），须确认前面没有同行内容
            if (matcher.start() == 0 && !startsLine(textNode)) {
                continue;
            }
            replaced.append(text, copied, matcher.start());
            replaced.append(matcher.group(1));
            replaced.append("x".equalsIgnoreCase(matcher.group(2)) ? TASK_CHECKED_ICON : TASK_UNCHECKED_ICON);
            replaced.append(' ');
            copied = matcher.end();
            changed = true;
        } while (matcher.find());
        if (!changed) {
            return;
        }
        replaced.append(text, copied, text.length());
        textNode.text(replaced.toString());
    }

    /**
     * 判断文本节点是否处于「视觉行首」（其行首的任务清单标记可被识别）：无前兄弟、前兄弟为换行
     * {@code <br>}，或前面只有空白与无内容的包装层（如空 {@code <span>}）；注释等节点不构成行内容。
     */
    private static boolean startsLine(Node node) {
        Node previous = node.previousSibling();
        while (previous != null) {
            if (previous instanceof TextNode text) {
                String content = text.getWholeText();
                if (content.contains("\n")) {
                    return true;
                }
                if (!content.isBlank()) {
                    return false;
                }
            } else if (previous instanceof Element element) {
                if ("br".equalsIgnoreCase(element.tagName())) {
                    return true;
                }
                if (!isVisuallyEmpty(element)) {
                    return false;
                }
            }
            previous = previous.previousSibling();
        }
        return true;
    }

    /** 判断文本节点是否位于 {@code <pre>}/{@code <code>} 内（其中的转义标签属文章示例，不能剔除）。 */
    private static boolean isInsideCode(Node node) {
        Node parent = node.parent();
        while (parent != null) {
            if (parent instanceof Element element
                && ("pre".equalsIgnoreCase(element.tagName()) || "code".equalsIgnoreCase(element.tagName()))) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }

    /**
     * 转换 Halo 插件注入的自定义 Web Component（标签名非标准 HTML，如链接卡片
     * {@code <HYPERLINK-INLINE-CARD>}、下载链接 {@code <DOWNLOAD-LINKS>}）。
     *
     * <p>微信图文只渲染标准 HTML + 内联样式，这些自定义元素在草稿里既不会显示为卡片，其空的
     * 占位又会被微信编辑器包成 {@code <p>} 而渲染成多余空行。故在样式注入前统一处理：</p>
     * <ul>
     *   <li>{@code <DOWNLOAD-LINKS>}：解析 {@code data-links} JSON，逐条转为可点击的 {@code <a>} 下载链接；</li>
     *   <li>携带 {@code href} 的自定义卡片（如 {@code <HYPERLINK-INLINE-CARD>}）：转为标准 {@code <a>}，保留其子内容；</li>
     *   <li>其余自定义元素：解包（保留子内容），子内容为空时等同于移除。</li>
     * </ul>
     */
    private static void convertPluginCustomElements(Element body) {
        // 先快照所有非标准标签，避免边遍历边替换导致并发修改
        List<Element> customElements = new ArrayList<>();
        for (Element element : body.getAllElements()) {
            if (!Tag.isKnownTag(element.tagName())) {
                customElements.add(element);
            }
        }
        for (Element element : customElements) {
            // 可能已随某个祖先自定义元素被替换/移除而脱离文档，跳过
            if (element.parent() == null) {
                continue;
            }
            convertCustomElement(element);
        }
    }

    /** 按自定义元素类型转换为标准 HTML。 */
    private static void convertCustomElement(Element element) {
        if ("download-links".equalsIgnoreCase(element.tagName())) {
            convertDownloadLinks(element);
            return;
        }
        String href = element.attr("href");
        if (href != null && !href.isBlank()) {
            // 带链接的卡片（如 HYPERLINK-INLINE-CARD）→ 标准 <a>，保留原子内容
            Element anchor = new Element(Tag.valueOf("a"), "");
            anchor.attr("href", href);
            String target = element.attr("target");
            anchor.attr("target", target.isBlank() ? "_blank" : target);
            anchor.attr("rel", "noopener noreferrer");
            for (Node child : new ArrayList<>(element.childNodes())) {
                anchor.appendChild(child);
            }
            // 卡片内无可见文字时，回退用 custom-title 或链接地址作为文字，避免产出空链接
            if (hasNoVisibleText(anchor.text())) {
                String fallback = element.attr("custom-title");
                anchor.text(fallback.isBlank() ? href : fallback);
            }
            element.replaceWith(anchor);
            return;
        }
        // 其余自定义元素：解包保留子内容（子内容为空则等同移除，遗留空段落交由 removeEmptyParagraphs 清理）
        element.unwrap();
    }

    /**
     * 转换 {@code <DOWNLOAD-LINKS data-links="[...]">}：解析出每个下载地址转为 {@code <a>}。
     * 解析不出任何链接时直接移除该元素，避免残留空占位。
     */
    private static void convertDownloadLinks(Element element) {
        List<Element> anchors = buildDownloadAnchors(element.attr("data-links"));
        if (anchors.isEmpty()) {
            element.remove();
            return;
        }
        Element parent = element.parent();
        boolean inlineContext = parent != null
            && INLINE_CONTAINER_TAGS.contains(parent.tagName().toLowerCase(java.util.Locale.ROOT));
        if (inlineContext) {
            // 已在 <p>/<li>/<td> 等行内容器内：把链接插入原位置，避免非法的 <p><p> 嵌套
            for (int i = 0; i < anchors.size(); i++) {
                if (i > 0) {
                    element.before(new Element(Tag.valueOf("br"), ""));
                }
                element.before(anchors.get(i));
            }
            element.remove();
        } else {
            // 块级位置：新建 <p> 承载下载链接，多条以 <br> 分隔
            Element paragraph = new Element(Tag.valueOf("p"), "");
            for (int i = 0; i < anchors.size(); i++) {
                if (i > 0) {
                    paragraph.appendChild(new Element(Tag.valueOf("br"), ""));
                }
                paragraph.appendChild(anchors.get(i));
            }
            element.replaceWith(paragraph);
        }
    }

    /**
     * 解析 {@code data-links} JSON 数组为下载链接 {@code <a>} 列表。数组每项形如
     * {@code {"url":...,"filename":...,"source":...,"code":...}}，链接文字<b>直接使用下载地址</b>
     * （保留 URL、不加文件名/来源/提取码等描述）。解析失败或无有效地址时返回空列表
     * （调用方据此移除元素），绝不阻断同步。
     */
    private static List<Element> buildDownloadAnchors(String dataLinks) {
        List<Element> anchors = new ArrayList<>();
        if (dataLinks == null || dataLinks.isBlank()) {
            return anchors;
        }
        try {
            List<?> items = JSON.readValue(dataLinks, List.class);
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String url = stringValue(map.get("url"));
                if (url == null || url.isBlank()) {
                    continue;
                }
                Element anchor = new Element(Tag.valueOf("a"), "");
                anchor.attr("href", url);
                anchor.attr("target", "_blank");
                anchor.attr("rel", "noopener noreferrer");
                anchor.text(url);
                anchors.add(anchor);
            }
        } catch (RuntimeException e) {
            log.warn("正文美化：解析下载链接 data-links 失败，将移除该下载组件：{}", e.getMessage());
        }
        return anchors;
    }

    /** 取 JSON 值的字符串形式，{@code null} 原样返回。 */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把微信编辑器不友好的块级包装标签降级为标准 {@code <p>}：Halo 用 {@code <figure>} 包图片，
     * 微信编辑器不认识这些块，会在其前后插入空的 {@code <p>} 占位（发布后表现为图片上下多出空行）。
     * 故在样式注入前降级为微信原生支持的 {@code <p>} 块，从根源上避免编辑器插入占位空行。
     *
     * <p>折叠块（{@code <details>}）不在此处处理：整块由 {@link #buildDetailsBlocks} 在流程末尾
     * 重建为「卡片 + 标题栏」，其中 {@code <summary>} 保留至该步；仅游离的 {@code <summary>}
     * （无 {@code <details>} 父，属来源异常的遗留标记）在此降级为 {@code <p>}。</p>
     */
    private static void normalizeBlockWrappers(Element body) {
        // <figure>（图片块）→ 标准 <p>，丢弃其 Halo 布局内联样式（如 display:flex）
        for (Element figure : body.select("figure")) {
            wrapChildrenIntoParagraph(figure);
        }
        // 游离的 <summary>（无 <details> 父）→ 标准 <p>
        for (Element summary : body.select("summary")) {
            if (!isInside(summary, "details")) {
                wrapChildrenIntoParagraph(summary);
            }
        }
    }

    /** 用标准 {@code <p>} 替换元素，子节点按原顺序原样保留（figure/summary 降级共用）。 */
    private static void wrapChildrenIntoParagraph(Element element) {
        Element paragraph = new Element(Tag.valueOf("p"), "");
        for (Node child : new ArrayList<>(element.childNodes())) {
            paragraph.appendChild(child);
        }
        element.replaceWith(paragraph);
    }

    /**
     * 重建 Halo 编辑器的「折叠内容」（{@code <details>}；编辑器输出形如
     * {@code <details class="details" open><summary>标题</summary><div data-type="detailsContent">…</div></details>}）
     * 为<b>静态展开的卡片</b>（样式见 {@link #detailsCardStyle}）：标题栏加粗、带
     * {@link #DETAILS_MARKER} 标记，以细分割线与内容区隔开，内容照常排版（等价于「展开」状态）；
     * 标题栏背景色、内容区背景色与边框颜色分别取配置 {@code detailsTitleBgColor}/
     * {@code detailsContentBgColor}/{@code detailsBorderColor}（非法值回退内置默认）。
     *
     * <p>微信图文无法保留折叠交互：微信编辑器不识别 {@code <details>}/{@code <summary>}，会丢弃这些
     * 标签并在块前后插入空 {@code <p>} 占位。故整块重建为微信能稳定渲染的 {@code <section>} 结构，
     * 不引入任何 class/脚本依赖。</p>
     *
     * <p>须在全部样式注入与版式重建（分栏/画廊）之后执行：新生成的标题栏 {@code <p>} 不参与通用
     * 段落样式注入，内容区搬运的内容块沿用自身已注入的样式。折叠块可嵌套，倒序遍历快照——先重建
     * 内层，外层搬运的子树中已是重建后的卡片。无可见标题（{@code <summary>} 缺失或为空白）的折叠块
     * 失去折叠语义，解包保留内容。</p>
     */
    private static void buildDetailsBlocks(Element body, BeautifySetting cfg) {
        // 三项颜色各自解析一次（合法十六进制，非法回退内置默认），本页所有折叠块共用同一组样式
        String borderColor = color(cfg.getDetailsBorderColor(), DEFAULT_DETAILS_BORDER_COLOR);
        String cardStyle = detailsCardStyle(borderColor);
        String titleStyle = detailsSummaryStyle(
            color(cfg.getDetailsTitleBgColor(), DEFAULT_DETAILS_TITLE_BG_COLOR), borderColor);
        String contentStyle = detailsContentStyle(
            color(cfg.getDetailsContentBgColor(), DEFAULT_DETAILS_CONTENT_BG_COLOR));
        List<Element> detailsBlocks = body.select("details");
        for (int i = detailsBlocks.size() - 1; i >= 0; i--) {
            Element details = detailsBlocks.get(i);
            // 可能已随外层折叠块重建被替换/搬运后脱离文档，跳过失效节点
            if (details.parent() == null) {
                continue;
            }
            convertDetails(details, cardStyle, titleStyle, contentStyle);
        }
    }

    /** 把单个 {@code <details>} 重建为「卡片 + 标题栏 + 内容区」；无可见标题时解包保留内容。 */
    private static void convertDetails(Element details, String cardStyle, String titleStyle, String contentStyle) {
        Element summary = directSummary(details);
        if (summary == null || hasNoVisibleText(summary.text())) {
            // 无可见标题的折叠块失去折叠语义：清掉空 summary 与内容包装层，解包保留内容
            if (summary != null) {
                summary.remove();
            }
            for (Element child : new ArrayList<>(details.children())) {
                if (isDetailsContentBox(child)) {
                    child.unwrap();
                }
            }
            details.unwrap();
            return;
        }
        Element card = new Element(Tag.valueOf("section"), "");
        card.attr("style", cardStyle);
        card.appendChild(buildDetailsTitle(summary, titleStyle));
        appendDetailsContent(card, details, contentStyle);
        details.replaceWith(card);
    }

    /** 折叠块标题栏：{@code ▸} 标记 + 原 summary 的子节点（保留其行内样式），整体用标题栏样式呈现。 */
    private static Element buildDetailsTitle(Element summary, String titleStyle) {
        Element title = new Element(Tag.valueOf("p"), "");
        title.attr("style", titleStyle);
        title.appendChild(new TextNode(DETAILS_MARKER + " "));
        for (Node child : new ArrayList<>(summary.childNodes())) {
            title.appendChild(child);
        }
        return title;
    }

    /**
     * 把折叠块内容搬入内容区 {@code <section>}：优先取 {@code <div data-type="detailsContent">}
     * （编辑器输出的标准内容包装层），缺失时兜底取 {@code <summary>} 之外的全部直接子节点；
     * 内容为空时不生成内容区（仅保留标题栏卡片，避免卡片底部多出一段空白）。
     */
    private static void appendDetailsContent(Element card, Element details, String contentStyle) {
        Element contentBox = null;
        for (Element child : details.children()) {
            if (isDetailsContentBox(child)) {
                contentBox = child;
                break;
            }
        }
        if (contentBox == null) {
            contentBox = new Element(Tag.valueOf("div"), "");
            for (Element child : new ArrayList<>(details.children())) {
                if ("summary".equalsIgnoreCase(child.tagName())) {
                    continue;
                }
                contentBox.appendChild(child);
            }
        }
        if (contentBox.children().isEmpty() && hasNoVisibleText(contentBox.text())) {
            return;
        }
        Element contentSection = new Element(Tag.valueOf("section"), "");
        contentSection.attr("style", contentStyle);
        for (Node node : new ArrayList<>(contentBox.childNodes())) {
            contentSection.appendChild(node);
        }
        card.appendChild(contentSection);
    }

    /** 取 {@code <details>} 的直接子 {@code <summary>}（编辑器输出的标准位置）；无则返回 {@code null}。 */
    private static Element directSummary(Element details) {
        for (Element child : details.children()) {
            if ("summary".equalsIgnoreCase(child.tagName())) {
                return child;
            }
        }
        return null;
    }

    /** 是否为折叠块内容包装层（编辑器输出 {@code <div data-type="detailsContent">}）。 */
    private static boolean isDetailsContentBox(Element element) {
        return "div".equalsIgnoreCase(element.tagName())
            && "detailsContent".equalsIgnoreCase(element.attr("data-type"));
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

    /**
     * 按标签名注入内联样式。微信原生代码块（{@link #buildCodeBlocks} 重建）的行号列 {@code <ul>/<li>} 与
     * 代码行 {@code <code>} 须跳过，否则通用列表/行内代码样式会破坏微信自身的行号计数与代码行排版。
     */
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
        applyAll(body, "blockquote", blockquoteStyle(cfg.isBlockquoteBorderEnabled(), accent,
            color(cfg.getBlockquoteBgColor(), DEFAULT_BLOCKQUOTE_BG_COLOR)));
        applyAllSkippingEmbedded(body, "ul", UL_STYLE);
        applyAll(body, "ol", OL_STYLE);
        applyAllSkippingEmbedded(body, "li", liStyle(textColor));
        applyAll(body, "img", IMG_STYLE);
        applyAll(body, "table", TABLE_STYLE);
        applyAll(body, "th", TH_STYLE);
        applyAll(body, "td", tdStyle(textColor));
        applyAll(body, "hr", HR_STYLE);
        applyAll(body, "a", aStyle(linkColor));
        applyAll(body, "figcaption", FIGCAPTION_STYLE);
        // 代码块内的代码行由微信自身样式接管，跳过；其余 <code> 均为行内代码
        applyAllSkippingCodeBlocks(body, "code", inlineCodeStyle(inlineCodeColor, inlineCodeBgColor));

        // 段落：表格单元格内、引用块内、普通正文的间距不同，分别处理，避免二次注入导致样式顺序错乱
        for (Element p : body.select("p")) {
            String paragraphStyle;
            if (isInside(p, "td") || isInside(p, "th")) {
                // 单元格内段落去掉上下外边距，否则首/末行单元格会在表格上下多出空行
                paragraphStyle = tableCellPStyle(textColor);
            } else if (isInside(p, "blockquote")) {
                paragraphStyle = QUOTE_P_STYLE;
            } else if (isInsideTaskList(p)) {
                // 任务项内段落：行高字号同正文、间距收紧（项间距由段落外边距提供，见 taskItemPStyle）
                paragraphStyle = taskItemPStyle(textColor);
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
     * 把每个 {@code <pre>} 重建为微信编辑器原生代码块结构（{@code code-snippet}）：外层
     * {@code code-snippet__fix} 容器内，行号列 {@code <ul class="code-snippet__line-index">}（每个代码行一个
     * 空 {@code <li>}）在前、代码区 {@code <pre class="code-snippet__js" data-lang="…">}（每个代码行一个
     * {@code <code>}）在后，与微信编辑器「插入代码」产出的标记一致。
     *
     * <p>行号由微信自身样式渲染：{@code <li>} 序号走 CSS 计数器，删行时行号自动减少，无需维护数字；
     * 代码行由微信样式 {@code white-space:pre} 不折行，长行不换行、行号与代码行严格对应。代码取纯文本
     * 重建并写入 {@code data-lang}，微信编辑器会按语言重新高亮。空行以零宽空格占位防行高塌陷。</p>
     */
    private static void buildCodeBlocks(Element body) {
        for (Element pre : body.select("pre")) {
            List<String> lines = extractCodeLines(pre);
            String language = extractCodeLanguage(pre);

            Element wrapper = new Element(Tag.valueOf("section"), "");
            wrapper.addClass(CODE_SNIPPET_FIX_CLASS).addClass(CODE_SNIPPET_CLASS);

            Element lineIndex = new Element(Tag.valueOf("ul"), "");
            lineIndex.addClass(CODE_SNIPPET_LINE_INDEX_CLASS).addClass(CODE_SNIPPET_CLASS);
            wrapper.appendChild(lineIndex);

            Element codePre = new Element(Tag.valueOf("pre"), "");
            codePre.addClass(CODE_SNIPPET_CLASS);
            if (language != null) {
                codePre.attr("data-lang", language);
            }
            wrapper.appendChild(codePre);

            for (String line : lines) {
                lineIndex.appendChild(new Element(Tag.valueOf("li"), ""));
                Element codeLine = new Element(Tag.valueOf("code"), "");
                Element span = new Element(Tag.valueOf("span"), "");
                span.text(line.isEmpty() ? EMPTY_CODE_LINE : line);
                codeLine.appendChild(span);
                codePre.appendChild(codeLine);
            }
            pre.replaceWith(wrapper);
        }
    }

    /**
     * 把每个 {@code <table>} 包进一个横向滚动的 {@code <section>} 容器，并移除 Halo/TipTap 编辑器的
     * {@code <div class="tableWrapper">} 多余包裹层。
     *
     * <p>表格默认 {@code width:100%} 铺满屏宽、{@code table-layout:fixed}（见 {@link #TABLE_STYLE}）按首行
     * 单元格的宽度渲染列宽（{@code <colgroup>} 的列宽已由 {@link #normalizeTables} 转写到首行单元格），
     * 单元格内容在列内自动换行；「保持比例」模式下表格带 {@code min-width}（原始列宽总和），总宽超出
     * 屏宽时表格整体溢出容器，由 {@code overflow-x:auto} 横向滚动兜底查看全貌。</p>
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
     * 归一化表格结构，使产物更贴近微信编辑器「插入表格」的原生结构：
     *
     * <ul>
     *   <li><b>压平 {@code <colgroup>} 列宽</b>：微信编辑器（及多数富文本编辑器）的表格模型不认识
     *       {@code <colgroup>}/{@code <col>}——草稿在编辑器里二次编辑、结构被重建时，这些节点容易被
     *       误解析成多余的空行/空框（表现为表格上多出莫名的外边框与无内容的表格部分）。故把各列宽度按
     *       {@code colspan} 合并、以百分比转写到<b>首行单元格</b>的 {@code width} 上（固定布局下首行宽度
     *       即列宽，与微信编辑器按单元格记录列宽的行为一致），随后删除 {@code <colgroup>}；「保持比例」
     *       模式另给表格补 {@code min-width} 托底原始总宽（见 {@link #moveColgroupWidths}）；</li>
     *   <li><b>压紧结构空白</b>：删除表格结构标签（{@code table}/{@code thead}/{@code tbody}/{@code tfoot}/
     *       {@code tr}）之间的纯空白文本节点——微信编辑器重建结构时对标签间空白敏感，可能把这些排版
     *       缩进误判成结构节点。</li>
     * </ul>
     *
     * <p>须在 {@link #buildTables} 包裹与 {@link #injectStyles} 注入之前执行：转写的宽度处于各单元格
     * 既有样式之后，后续通用样式注入时仍优先生效。</p>
     */
    private static void normalizeTables(Element body, BeautifySetting cfg) {
        boolean fillMode = isTableWidthFill(cfg);
        for (Element table : body.select("table")) {
            moveColgroupWidths(table, fillMode);
            removeTableStructuralWhitespace(table);
        }
    }

    /**
     * 把 {@code <colgroup>} 的列宽转写到首行单元格并删除该 {@code <colgroup>}（见 {@link #normalizeTables}）：
     * 解析各 {@code <col>} 宽度（百分比/像素均可），按首行各单元格的 {@code colspan} 合并后以百分比写入
     * （见 {@link #writeFirstRowWidths}）；「保持比例」模式另给表格补 {@code min-width} 托底原始总宽
     * （总宽超出屏宽时表格溢出、横向滚动），「宽度铺满」模式则收敛在屏宽内。任一列宽度缺失、单位混用或
     * 首行结构与列数对不上时放弃转写（仅删除 colgroup，列宽回退为固定布局均分）。
     */
    private static void moveColgroupWidths(Element table, boolean fillMode) {
        Element colgroup = null;
        for (Element child : table.children()) {
            if ("colgroup".equalsIgnoreCase(child.tagName())) {
                colgroup = child;
                break;
            }
        }
        if (colgroup == null) {
            return;
        }
        ColWidths widths = colWidths(colgroup);
        if (widths != null) {
            writeFirstRowWidths(table, widths, fillMode);
        }
        colgroup.remove();
    }

    /**
     * {@code <colgroup>} 的解析结果：{@code values} 为各列宽度值（{@code <col span>} 已按跨列数展开），
     * {@code percent} 标记整组是否百分比单位（解析时保证单位一致）。
     */
    private record ColWidths(List<Double> values, boolean percent) {
    }

    /**
     * 解析 {@code <colgroup>} 中各列的宽度：每列宽度须为可解析的正数且单位一致（全部带 {@code %}
     * 或全部为像素/无单位）；任一列缺失或不符合时返回 {@code null}（放弃转写）。{@code <col span>}
     * 代表跨多列，宽度按跨列数展开。
     */
    private static ColWidths colWidths(Element colgroup) {
        List<Double> values = new ArrayList<>();
        Boolean percent = null;
        for (Element col : colgroup.children()) {
            if (!"col".equalsIgnoreCase(col.tagName())) {
                continue;
            }
            Matcher matcher = STYLE_WIDTH.matcher(col.attr("style"));
            if (!matcher.find()) {
                return null;
            }
            double value;
            try {
                value = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (value <= 0) {
                return null;
            }
            boolean isPercent = "%".equals(matcher.group(2));
            if (percent == null) {
                percent = isPercent;
            } else if (percent != isPercent) {
                return null;
            }
            int span = 1;
            String spanAttr = col.attr("span");
            if (!spanAttr.isBlank()) {
                try {
                    span = Integer.parseInt(spanAttr.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (span < 1 || span > MAX_COL_SPAN) {
                    return null;
                }
            }
            for (int i = 0; i < span; i++) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : new ColWidths(values, percent);
    }

    /**
     * 把列宽按首行单元格的 {@code colspan} 合并、以归一化百分比（覆盖列宽 ÷ 总宽）写入其内联样式末尾
     * （原有样式在前、转写宽度在后）：百分比是微信渲染可靠保留的列宽载体——实测像素列宽会被微信丢弃、
     * 表格被压回屏宽。另于「保持比例」模式且列宽为像素时，给 {@code <table>} 追加
     * {@code min-width:列宽总和px} 托底原始总宽：总宽超出屏宽时表格整体溢出、由外层容器横向滚动查看
     * 全貌，屏宽够大时仍由默认 {@code width:100%} 铺满。首行缺失、覆盖列数与 {@code <colgroup>} 对不上、
     * {@code colspan} 非法或单元格已有 {@code width} 声明时整体跳过（尊重原有结构，不猜测）。
     */
    private static void writeFirstRowWidths(Element table, ColWidths widths, boolean fillMode) {
        Element row = table.selectFirst("tr");
        if (row == null) {
            return;
        }
        List<Element> cells = new ArrayList<>();
        for (Element cell : row.children()) {
            if ("td".equalsIgnoreCase(cell.tagName()) || "th".equalsIgnoreCase(cell.tagName())) {
                cells.add(cell);
            }
        }
        if (cells.isEmpty()) {
            return;
        }
        List<Integer> spans = new ArrayList<>(cells.size());
        int coveredColumns = 0;
        for (Element cell : cells) {
            // 首行单元格已有宽度声明（文章自定义）时尊重原有值，不转写、不覆盖
            if (STYLE_WIDTH.matcher(cell.attr("style")).find()) {
                return;
            }
            Integer span = cellColspan(cell);
            if (span == null) {
                return;
            }
            spans.add(span);
            coveredColumns += span;
        }
        if (coveredColumns != widths.values().size()) {
            return;
        }
        double total = 0;
        for (Double value : widths.values()) {
            total += value;
        }
        int index = 0;
        for (int i = 0; i < cells.size(); i++) {
            double covered = 0;
            for (int j = index; j < index + spans.get(i); j++) {
                covered += widths.values().get(j);
            }
            index += spans.get(i);
            overrideStyle(cells.get(i), "width:" + formatPercent(covered / total) + ";");
        }
        // 「保持比例」+ 像素列宽：表格补 min-width=列宽总和托底原始总宽。微信渲染会丢弃单元格上的
        // 像素列宽、把表格压回屏宽；min-width 与生效中的 width:100% 同属表格内联样式，只要样式被应用
        // 表格宽度就不低于原始总宽——超出屏宽时整体溢出、由外层容器横向滚动，屏宽够大时仍铺满
        if (!fillMode && !widths.percent()) {
            overrideStyle(table, "min-width:" + formatNumber(total) + "px;");
        }
    }

    /** 解析单元格 {@code colspan}（无属性按 1）；值非法（非整数或小于 1）返回 {@code null}。 */
    private static Integer cellColspan(Element cell) {
        String colspan = cell.attr("colspan");
        if (colspan.isBlank()) {
            return 1;
        }
        try {
            int value = Integer.parseInt(colspan.trim());
            return value >= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 删除表格结构标签（{@code table}/{@code thead}/{@code tbody}/{@code tfoot}/{@code tr}）之间的纯空白
     * 文本节点（含 {@code &nbsp;}/零宽字符）：这些排版缩进在微信编辑器二次编辑、重建结构时可能被误判成
     * 结构节点（如多出一行/一列）；单元格 {@code td}/{@code th} 内部的内容空白保持原样。
     */
    private static void removeTableStructuralWhitespace(Element table) {
        for (Element element : table.getAllElements()) {
            String tag = element.tagName().toLowerCase(java.util.Locale.ROOT);
            if (!"table".equals(tag) && !"thead".equals(tag) && !"tbody".equals(tag)
                && !"tfoot".equals(tag) && !"tr".equals(tag)) {
                continue;
            }
            for (Node child : new ArrayList<>(element.childNodes())) {
                if (child instanceof TextNode text && hasNoVisibleText(text.getWholeText())) {
                    text.remove();
                }
            }
        }
    }

    /** 表格宽度模式是否为「宽度铺满」（{@link BeautifySetting#TABLE_WIDTH_MODE_FILL}）；缺省/非法值按「保持比例」处理。 */
    private static boolean isTableWidthFill(BeautifySetting cfg) {
        return BeautifySetting.TABLE_WIDTH_MODE_FILL.equalsIgnoreCase(cfg.getTableWidthMode());
    }

    /**
     * 「宽度铺满」模式：把正文表格的宽度强制为 {@code 100%}（追加在各表格现有内联样式之后，覆盖文章
     * 自定义的固定宽度）——与 {@link #normalizeTables} 的列宽归一化（百分比）配套，表格与列宽始终收敛
     * 在屏宽内、不产生横向滚动。须在 {@link #injectStyles} 之后、{@link #rebuildBlockLayouts} 之前执行：
     * 布局表格在其后创建，不受影响。
     */
    private static void applyTableFillWidth(Element body) {
        for (Element table : body.select("table")) {
            overrideStyle(table, "width:100%;");
        }
    }

    /**
     * 重建两种来源的任务列表为「Emoji 图标 + 内容」：微信图文不渲染 {@code <input>} 复选框（表单元素
     * 会被剥离），勾选状态无法呈现——已完成用 {@link #TASK_CHECKED_ICON}、未完成用
     * {@link #TASK_UNCHECKED_ICON}，列表去掉默认圆点与缩进（见 {@link #TASK_LIST_STYLE}）。
     *
     * <ul>
     *   <li><b>Halo 默认编辑器（TipTap）输出</b>：{@code <ul data-type="taskList"><li
     *       data-checked="true" data-type="taskItem">…}，图标置于首个段落行首
     *       （见 {@link #rebuildTaskItem}）；</li>
     *   <li><b>markdown 渲染输出</b>（「Markdown 编辑块」等 GFM 任务列表）：{@code <li>} 内含
     *       {@code input[type=checkbox]}，图标就地替换复选框
     *       （见 {@link #buildMarkdownTaskLists}）。</li>
     * </ul>
     *
     * <p>须在通用样式注入前执行：重建后的 {@code <ul>}/{@code <li>} 由本方法直接写入样式，注入阶段
     * 跳过它们（见 {@link #applyAllSkippingEmbedded}）；任务项内段落由段落循环按
     * {@link #taskItemPStyle} 处理。任务列表可嵌套，倒序遍历快照——先重建内层。</p>
     */
    private static void buildTaskLists(Element body, BeautifySetting cfg) {
        String itemStyle = taskItemStyle(color(cfg.getTextColor(), DEFAULT_TEXT_COLOR));
        List<Element> lists = body.select("ul[data-type=taskList]");
        for (int i = lists.size() - 1; i >= 0; i--) {
            Element list = lists.get(i);
            // 可能已随外层任务列表重建搬运后脱离文档，跳过失效节点
            if (list.parent() == null) {
                continue;
            }
            list.attr("style", TASK_LIST_STYLE);
            for (Element item : new ArrayList<>(list.children())) {
                if ("li".equalsIgnoreCase(item.tagName())) {
                    rebuildTaskItem(item, itemStyle);
                }
            }
        }
        // markdown 渲染输出的 GFM 任务列表（识别方式不同）另行重建，产物与上面完全一致
        buildMarkdownTaskLists(body, itemStyle);
    }

    /**
     * 重建单个任务项：移除 checkbox 标记（{@code <label>} 及其 {@code <input>}）、解包内容包装层
     * {@code <div>}，行首拼入状态图标。{@code data-checked} 判定后即清除——状态已由图标表达，
     * 产物不保留编辑器私有数据属性。
     */
    private static void rebuildTaskItem(Element item, String itemStyle) {
        String icon = isTaskItemChecked(item) ? TASK_CHECKED_ICON : TASK_UNCHECKED_ICON;
        item.removeAttr("data-checked");
        item.removeAttr("data-type");
        item.attr("style", itemStyle);
        for (Element child : new ArrayList<>(item.children())) {
            if ("label".equalsIgnoreCase(child.tagName())) {
                child.remove();
            } else if ("div".equalsIgnoreCase(child.tagName())) {
                child.unwrap();
            }
        }
        // 防御异常结构：裸在任务项下的 <input>（未经 <label> 包装）一并清除
        item.select("input").remove();
        // 图标置于行首：优先拼进首个段落开头（与内容同行），无段落时直接放任务项开头
        Element target = item;
        for (Element child : item.children()) {
            if ("p".equalsIgnoreCase(child.tagName())) {
                target = child;
                break;
            }
        }
        target.prependChild(new TextNode(icon + " "));
    }

    /**
     * 判断任务项是否已完成：{@code data-checked} 存在且值不为 {@code false}（TipTap 将空串与
     * {@code true} 均视为勾选，见编辑器 parseHTML 的兼容逻辑）。
     */
    private static boolean isTaskItemChecked(Element item) {
        return isTruthyAttr(item, "data-checked");
    }

    /**
     * 属性存在且值不为 {@code false} 即视为「真」：勾选状态的常见写法有 {@code checked=""}、
     * {@code checked="checked"}、{@code data-checked="true"} 等，空串与同名值均表示已勾选。
     */
    private static boolean isTruthyAttr(Element element, String attributeName) {
        return element.hasAttr(attributeName)
            && !"false".equalsIgnoreCase(element.attr(attributeName).trim());
    }

    /**
     * 重建 markdown 渲染输出的「GFM 任务列表」（如「Markdown 编辑块」的 marked 输出：
     * {@code <ul><li><input type="checkbox" [checked]> 内容</li>…</ul>}，松散列表的复选框位于
     * 首段 {@code <p>} 内），产物与 {@link #buildTaskLists} 的 TipTap 处理一致。
     *
     * <p>判定任务项只看「{@code <li>} 直接内容区（不跨越嵌套列表）内是否有
     * {@code input[type=checkbox]}」，兼容 marked/flexmark/GitHub 复制等常见输出。含任务项的列表
     * 整体补 {@code data-type="taskList"} 标记——让通用样式注入跳过（见
     * {@link #applyAllSkippingEmbedded}）、任务项内段落按 {@link #taskItemPStyle} 注入；列表中混排的
     * 普通项会随列表一并去掉圆点（罕见场景，内容不受影响）。倒序遍历快照：嵌套任务列表先重建内层。</p>
     */
    private static void buildMarkdownTaskLists(Element body, String itemStyle) {
        List<Element> lists = body.select("ul, ol");
        for (int i = lists.size() - 1; i >= 0; i--) {
            Element list = lists.get(i);
            // 可能已随外层任务列表重建搬运后脱离文档，跳过失效节点
            if (list.parent() == null) {
                continue;
            }
            // TipTap 任务列表（上面已重建，ul 保留 data-type="taskList" 标记）不重复处理
            if ("taskList".equalsIgnoreCase(list.attr("data-type"))) {
                continue;
            }
            List<Element> items = new ArrayList<>();
            for (Element item : list.children()) {
                if ("li".equalsIgnoreCase(item.tagName()) && taskItemCheckbox(item) != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            list.attr("data-type", "taskList");
            list.attr("style", TASK_LIST_STYLE);
            for (Element item : items) {
                rebuildMarkdownTaskItem(item, itemStyle);
            }
        }
    }

    /**
     * 重建单个 markdown 任务项：把复选框<b>就地</b>替换为状态图标——紧凑列表在 {@code <li>} 行首、
     * 松散列表在段首 {@code <p>} 内，替换后图标位置天然正确；随后写入任务项样式。
     */
    private static void rebuildMarkdownTaskItem(Element item, String itemStyle) {
        Element checkbox = taskItemCheckbox(item);
        if (checkbox == null) {
            return;
        }
        String icon = isTruthyAttr(checkbox, "checked") ? TASK_CHECKED_ICON : TASK_UNCHECKED_ICON;
        // 渲染器一般在复选框后输出一个分隔空格，此时图标直接替换即可；没有时补一个避免图标与文字黏连
        boolean spaceFollows = startsWithInlineSpace(checkbox.nextSibling());
        checkbox.replaceWith(new TextNode(spaceFollows ? icon : icon + " "));
        item.attr("style", itemStyle);
        // 防御异常结构：任务项内残留的复选框一并清除（嵌套任务列表已先行重建，不会误删其图标）
        item.select("input[type=checkbox]").remove();
    }

    /**
     * 取任务项「直接内容区」内的复选框：沿 input 的祖先链向上直到任务项，中途经过列表容器
     * （{@code <ul>}/{@code <ol>}）说明该复选框属于嵌套的子列表、不是本项的状态标记；找不到
     * 返回 {@code null}（即当前项不是任务项）。
     */
    private static Element taskItemCheckbox(Element item) {
        for (Element input : item.select("input[type=checkbox]")) {
            Element current = input.parent();
            boolean nested = false;
            while (current != null && current != item) {
                if ("ul".equalsIgnoreCase(current.tagName()) || "ol".equalsIgnoreCase(current.tagName())) {
                    nested = true;
                    break;
                }
                current = current.parent();
            }
            if (!nested) {
                return input;
            }
        }
        return null;
    }

    /** 节点是否以行内空白（空格/制表符）开头：判断图标替换复选框后是否需要补一个分隔空格。 */
    private static boolean startsWithInlineSpace(Node node) {
        if (!(node instanceof TextNode textNode)) {
            return false;
        }
        String text = textNode.getWholeText();
        return !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t');
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
     * 提取代码块语言标识，写入 {@code pre} 的 {@code data-lang} 供微信重新高亮：
     * 先看 {@code <pre>} 自身、再看其内部首个 {@code <code>} 的 class（{@code language-*} / {@code lang-*}）；
     * 取不到时返回 {@code null}，代码块结构照常渲染、仅无高亮。
     */
    private static String extractCodeLanguage(Element pre) {
        String language = languageFromClass(pre.attr("class"));
        if (language != null) {
            return language;
        }
        Element code = pre.selectFirst("code");
        return code == null ? null : languageFromClass(code.attr("class"));
    }

    /** 从 class 属性解析 {@code language-*} / {@code lang-*} 语言标识（统一小写）；无则返回 {@code null}。 */
    private static String languageFromClass(String classAttribute) {
        if (classAttribute == null || classAttribute.isBlank()) {
            return null;
        }
        for (String token : classAttribute.trim().split("\\s+")) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            String language = null;
            if (lower.startsWith("language-")) {
                language = lower.substring("language-".length());
            } else if (lower.startsWith("lang-")) {
                language = lower.substring("lang-".length());
            }
            if (language != null && !language.isEmpty()) {
                return language;
            }
        }
        return null;
    }

    /**
     * 按配置重建 Halo 编辑器的「分栏卡片」与「画廊」区块版式——{@link BeautifySetting#getColumnsLayoutStyle()}
     * 与 {@link BeautifySetting#getGalleryLayoutStyle()} 两项配置相互独立：<b>表格</b>（默认）——重建为微信
     * 渲染最可靠的 {@code <table>} 布局（见 {@link #rebuildColumns} 与 {@link #rebuildGallery}）；
     * <b>独占一行</b>——不重建表格而是取消并排、按块级流堆叠（见 {@link #flattenColumns} 与
     * {@link #flattenGallery}）。取值非法时按默认「表格」处理。
     *
     * <p>Halo 编辑器输出的分栏依赖 {@code display:flex}、画廊依赖 {@code display:grid}+flex 排布，
     * 但微信图文会过滤 {@code display:grid}、对 {@code display:flex} 的支持也不稳定，直接同步会让
     * 各列/各图纵向堆叠、各占一行。</p>
     *
     * <p>须在整个美化流程最后执行（见 {@link #beautify}）：布局表格不再参与通用 {@code table/td}
     * 样式注入，也不会被 {@link #buildTables} 包上横向滚动容器（其宽度恒为 100%，无需滚动兜底）。</p>
     */
    private static void rebuildBlockLayouts(Element body, BeautifySetting cfg) {
        if (BeautifySetting.LAYOUT_STYLE_STACKED.equalsIgnoreCase(cfg.getColumnsLayoutStyle())) {
            flattenColumns(body);
        } else {
            rebuildColumns(body);
        }
        if (BeautifySetting.LAYOUT_STYLE_STACKED.equalsIgnoreCase(cfg.getGalleryLayoutStyle())) {
            flattenGallery(body);
        } else {
            rebuildGallery(body);
        }
    }

    /**
     * 重建分栏卡片：{@code <div class="columns">} 的每列一个 {@code <td>}，列宽按各列 {@code flex} 的
     * grow 值比例分配，列间距折算为非首列单元格的 {@code padding-left}。
     *
     * <p>倒序遍历快照：嵌套分栏时先重建内层，外层重建搬运的子树中已是重建后的表格。</p>
     */
    private static void rebuildColumns(Element body) {
        List<Element> containers = body.select("div.columns, div[data-type=columns]");
        for (int i = containers.size() - 1; i >= 0; i--) {
            Element container = containers.get(i);
            // 可能已随外层分栏重建被替换/搬运后脱离文档，跳过失效节点
            if (container.parent() == null) {
                continue;
            }
            List<Element> columns = columnChildren(container);
            if (columns.isEmpty()) {
                continue;
            }
            List<Double> ratios = growRatios(columns);
            int gap = columnGapPx(container.attr("style"));
            Element table = createLayoutTable();
            Element tbody = table.child(0);
            Element row = new Element(Tag.valueOf("tr"), "");
            tbody.appendChild(row);
            for (int c = 0; c < columns.size(); c++) {
                Element cell = new Element(Tag.valueOf("td"), "");
                cell.attr("style", layoutCellStyle(0, c, gap, ratios.get(c)));
                moveChildrenInto(columns.get(c), cell);
                row.appendChild(cell);
            }
            container.replaceWith(table);
        }
    }

    /**
     * 重建画廊：整张画廊重建为<b>一个整体表格</b>、所有列等宽（用户确认的期望版式——不按各图宽高比
     * 分宽，也不按行拆成多个表格）。
     *
     * <p>统一网格：列数取各行图片数的最小公倍数（见 {@link #galleryGridColumns}），行内图片用
     * {@code colspan} 均分整行——3 图行各占三分之一、2 图行各占二分之一，末行不满时同样铺满整行，
     * 表格的列宽始终一致。{@code data-gap} 折算为非首列单元格的 {@code padding-left}（行内间距）
     * 与非首行单元格的 {@code padding-top}（行间距）。</p>
     *
     * <p>图片原样式靠 flex 项撑高（{@code height:100%}），表格布局下改由宽度决定高度，故追加覆写样式
     * （见 {@link #GALLERY_IMG_STYLE}）。无有效图片行的画廊保持原结构不动。</p>
     */
    private static void rebuildGallery(Element body) {
        for (Element gallery : body.select("div[data-type=gallery]")) {
            if (gallery.parent() == null) {
                continue;
            }
            List<List<Element>> rows = new ArrayList<>();
            for (Element group : gallery.select("div[data-type=gallery-group]")) {
                if (!group.children().isEmpty()) {
                    rows.add(group.children());
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            int gap = galleryGapPx(gallery.attr("data-gap"));
            int columns = galleryGridColumns(rows);
            Element table = createLayoutTable();
            Element tbody = table.child(0);
            for (int r = 0; r < rows.size(); r++) {
                List<Element> items = rows.get(r);
                // 行内均分：跨列数 = 统一网格列数 / 该行图片数（最小公倍数保证整除，末行不满时也铺满整行）
                int span = columns % items.size() == 0 ? columns / items.size() : 1;
                Element row = new Element(Tag.valueOf("tr"), "");
                tbody.appendChild(row);
                for (int c = 0; c < items.size(); c++) {
                    Element cell = new Element(Tag.valueOf("td"), "");
                    if (span > 1) {
                        cell.attr("colspan", String.valueOf(span));
                    }
                    cell.attr("style", layoutCellStyle(r, c, gap, 1.0 / items.size()));
                    moveChildrenInto(items.get(c), cell);
                    row.appendChild(cell);
                }
            }
            for (Element img : table.select("img")) {
                overrideStyle(img, GALLERY_IMG_STYLE);
            }
            gallery.replaceWith(table);
        }
    }

    /**
     * 分栏卡片「独占一行」版式：容器与各列恢复为普通块级——每栏各占一行，栏内图片、描述等内容完整保留。
     *
     * <p>微信对 {@code display:flex} 的支持不稳定，仅删除 flex 声明无法保证一定不并排：这里把容器的内联
     * 样式整体重写为块级 {@code display:block;}（丢弃 flex/gap/min-width 等纯布局声明）。</p>
     */
    private static void flattenColumns(Element body) {
        for (Element container : body.select("div.columns, div[data-type=columns]")) {
            container.attr("style", "display:block;");
            for (Element column : columnChildren(container)) {
                column.removeAttr("style");
            }
        }
    }

    /**
     * 画廊「独占一行」版式：网格容器与每个分组恢复为块级——每张图片各占一行；图片与描述等内容节点
     * 原样保留，照常参与通用美化。
     *
     * <p>微信过滤 {@code display:grid}、对 {@code display:flex} 支持不稳定：网格层与分组的内联样式整体
     * 重写为块级 {@code display:block;}。画廊图片覆写为自动高度（见 {@link #GALLERY_IMG_STYLE}），相邻
     * 图片之间保留原 {@code data-gap} 作为纵向间距。</p>
     */
    private static void flattenGallery(Element body) {
        for (Element gallery : body.select("div[data-type=gallery]")) {
            for (Element gridLayer : gallery.children()) {
                if ("div".equalsIgnoreCase(gridLayer.tagName())) {
                    gridLayer.attr("style", "display:block;");
                }
            }
            int gap = galleryGapPx(gallery.attr("data-gap"));
            List<Element> items = new ArrayList<>();
            for (Element group : gallery.select("div[data-type=gallery-group]")) {
                group.attr("style", "display:block;");
                items.addAll(group.children());
            }
            for (int i = 0; i < items.size(); i++) {
                // 原 flex 项样式对块级无意义，重写为与下一张图片的纵向间距（最后一张无需间距）
                if (i < items.size() - 1 && gap > 0) {
                    items.get(i).attr("style", "margin-bottom:" + gap + "px;");
                } else {
                    items.get(i).removeAttr("style");
                }
            }
            for (Element img : gallery.select("img")) {
                overrideStyle(img, GALLERY_IMG_STYLE);
            }
        }
    }

    /** 新建布局表格骨架 {@code <table class=… style=…><tbody></tbody></table>}，行由调用方追加到 {@code <tbody>}。 */
    private static Element createLayoutTable() {
        Element table = new Element(Tag.valueOf("table"), "");
        table.addClass(LAYOUT_TABLE_CLASS);
        table.attr("style", LAYOUT_TABLE_STYLE);
        table.appendChild(new Element(Tag.valueOf("tbody"), ""));
        return table;
    }

    /**
     * 取出分栏容器的列元素：优先 class 含 {@code column} 或 {@code data-type="column"} 的直接子元素；
     * 都匹配不到时兜底取全部直接子元素（兼容其他渲染器/手写 HTML 的变体结构）。
     */
    private static List<Element> columnChildren(Element container) {
        List<Element> columns = new ArrayList<>();
        for (Element child : container.children()) {
            if (child.hasClass("column") || "column".equalsIgnoreCase(child.attr("data-type"))) {
                columns.add(child);
            }
        }
        if (columns.isEmpty()) {
            columns.addAll(container.children());
        }
        return columns;
    }

    /** 分栏列宽占比：取各列的 flex grow 值归一化；全部解析失败时按均分处理。 */
    private static List<Double> growRatios(List<Element> items) {
        List<Double> values = new ArrayList<>(items.size());
        for (Element item : items) {
            values.add(flexGrow(item));
        }
        return normalizeRatios(values);
    }

    /**
     * 画廊统一网格的列数：取各行图片数的最小公倍数——所有列等宽，且每行都能用整数列的
     * {@code colspan} 均分整行（末行不满时同样铺满）。极端分组数下最小公倍数可能过大，超过
     * {@link #MAX_GALLERY_GRID_COLUMNS} 时退化为最大行图片数（此时仅末行可能不再精确等分）。
     */
    private static int galleryGridColumns(List<List<Element>> rows) {
        int max = 0;
        int columns = 1;
        for (List<Element> row : rows) {
            int count = row.size();
            max = Math.max(max, count);
            columns = lcm(columns, count);
        }
        return columns > MAX_GALLERY_GRID_COLUMNS ? max : columns;
    }

    /** 最小公倍数（先除后乘，避免极端值下乘法溢出）。 */
    private static int lcm(int a, int b) {
        return a / gcd(a, b) * b;
    }

    /** 最大公约数：欧几里得算法。 */
    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** 把一组正数归一化为合计 1 的宽度占比；总和恰为 0 时按均分处理。 */
    private static List<Double> normalizeRatios(List<Double> values) {
        double total = 0;
        for (Double value : values) {
            total += value;
        }
        for (int i = 0; i < values.size(); i++) {
            values.set(i, total > 0 ? values.get(i) / total : 1.0 / values.size());
        }
        return values;
    }

    /** 解析内联样式中的 flex grow 值（{@code flex: 2 1} → 2），取不到时兜底 1（均分）。 */
    private static double flexGrow(Element element) {
        Double fromStyle = positiveNumber(STYLE_FLEX_GROW, element.attr("style"));
        return fromStyle == null ? 1 : fromStyle;
    }

    /**
     * 用给定正则从文本中提取首个正数（预期第 1 个捕获组为数值）；文本为空、无匹配或解析失败时
     * 返回 {@code null}，绝不抛出异常。
     */
    private static Double positiveNumber(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            double value = Double.parseDouble(matcher.group(1));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析分栏容器的 {@code gap} 间距（像素）：{@code em} 按 16px 字号折算，缺省单位按 px；取不到时用默认 16px。 */
    private static int columnGapPx(String style) {
        if (style != null) {
            Matcher matcher = STYLE_GAP.matcher(style);
            if (matcher.find()) {
                return toPx(matcher.group(1), matcher.group(2));
            }
        }
        return DEFAULT_COLUMN_GAP_PX;
    }

    /**
     * 解析画廊 {@code data-gap}（渲染器输出为像素数值，如 {@code data-gap="8"}）；
     * 解析失败时按 0（无间距）处理。
     */
    private static int galleryGapPx(String dataGap) {
        Double value = positiveNumber(SIZE_NUMBER, dataGap);
        return value == null ? 0 : (int) Math.round(value);
    }

    /** 把尺寸值折算为像素：{@code em} × 16，其余（px/缺省单位）按原值；解析失败回退默认分栏间距。 */
    private static int toPx(String value, String unit) {
        try {
            double size = Double.parseDouble(value);
            if (unit != null && unit.equalsIgnoreCase("em")) {
                size *= EM_TO_PX;
            }
            return (int) Math.round(size);
        } catch (NumberFormatException e) {
            return DEFAULT_COLUMN_GAP_PX;
        }
    }

    /**
     * 生成布局单元格样式：顶部对齐、盒模型含内边距，非首行加 {@code padding-top}、非首列加
     * {@code padding-left}（分别折算行/列间距），最后指定宽度占比。
     */
    private static String layoutCellStyle(int rowIndex, int columnIndex, int gap, double ratio) {
        StringBuilder style = new StringBuilder("vertical-align:top;box-sizing:border-box;");
        if (rowIndex > 0 && gap > 0) {
            style.append("padding-top:").append(gap).append("px;");
        }
        if (columnIndex > 0 && gap > 0) {
            style.append("padding-left:").append(gap).append("px;");
        }
        return style.append("width:").append(formatPercent(ratio)).append(";").toString();
    }

    /** 把 0–1 的宽度占比格式化为百分比：0.5 → {@code 50%}、1/3 → {@code 33.33%}。 */
    private static String formatPercent(double ratio) {
        return formatNumber(ratio * 100) + "%";
    }

    /** 格式化宽度数值（最多两位小数、整数不带小数点）：250.0 → {@code 250}、33.3333 → {@code 33.33}。 */
    private static String formatNumber(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** 把源元素的全部子节点按原顺序搬入目标元素（改挂载、不复制）；搬空后的源元素由调用方处置。 */
    private static void moveChildrenInto(Element source, Element target) {
        for (Node child : new ArrayList<>(source.childNodes())) {
            target.appendChild(child);
        }
    }

    /**
     * 组合样式：元素原有内联样式在前、追加样式在后（同名属性以追加样式为准），
     * 用于必须覆盖既有内联样式的场合（如画廊图片的 {@code height:100%}）。
     */
    private static void overrideStyle(Element element, String style) {
        String existing = element.attr("style");
        if (existing == null || existing.isBlank()) {
            element.attr("style", style);
            return;
        }
        element.attr("style", existing.endsWith(";") ? existing + style : existing + ";" + style);
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

    private static String blockquoteStyle(boolean borderEnabled, String accent, String bgColor) {
        // 关闭边框时左侧无描边，四角统一圆角更协调；开启时左侧直角贴合边框
        return "margin:1em 0;padding:10px 15px;"
            + (borderEnabled ? "border-left:4px solid " + accent + ";" : "")
            + "background:" + bgColor + ";color:#666666;border-radius:"
            + (borderEnabled ? "0 4px 4px 0" : "4px") + ";";
    }

    /**
     * 折叠块卡片样式：细边框（颜色可配）、圆角；{@code overflow:hidden} 让标题栏底色不溢出圆角。
     */
    private static String detailsCardStyle(String borderColor) {
        return "margin:1em 0;border:1px solid " + borderColor + ";border-radius:6px;overflow:hidden;";
    }

    /**
     * 折叠块标题栏样式：底色可配 + 底部细分割线（与卡片边框同色）、加粗；标题文字前带
     * {@link #DETAILS_MARKER} 标记。文字保持深色，配浅色底观感最协调。
     */
    private static String detailsSummaryStyle(String titleBgColor, String borderColor) {
        return "margin:0;padding:10px 14px;background:" + titleBgColor
            + ";border-bottom:1px solid " + borderColor
            + ";font-weight:bold;font-size:15px;line-height:1.6;color:#333333;";
    }

    /**
     * 折叠块内容区样式：底色可配，左右内边距与标题栏对齐；内容块沿用各自已注入的样式
     * （段落自带上下外边距）。
     */
    private static String detailsContentStyle(String contentBgColor) {
        return "padding:2px 14px;background:" + contentBgColor + ";";
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

    /**
     * 任务项样式：不带外边距（项间距由任务项内段落的上下外边距提供，见 {@link #taskItemPStyle}）；
     * {@code list-style:none} 兜底去掉圆点，行首标记即状态图标。
     */
    private static String taskItemStyle(String textColor) {
        return "line-height:1.75;font-size:16px;color:" + textColor + ";list-style:none;";
    }

    /** 任务项内段落：行高字号同正文，间距收紧（各项之间与多段任务项的段间间距一致）。 */
    private static String taskItemPStyle(String textColor) {
        return "margin:0.25em 0;line-height:1.75;font-size:16px;color:" + textColor + ";";
    }

    /** 单元格：与 {@link #TH_STYLE} 同款微信原生观感——浅灰细边框、紧凑内边距、左对齐。 */
    private static String tdStyle(String textColor) {
        return "border:1px solid #e6e6e6;padding:8px;text-align:left;color:" + textColor + ";" + CELL_WRAP;
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

    private static void applyAll(Element body, String cssQuery, String style) {
        for (Element element : body.select(cssQuery)) {
            applyStyle(element, style);
        }
    }

    /** 同 {@link #applyAll}，但跳过微信原生代码块结构内的元素（行号列与代码行由微信样式接管）。 */
    private static void applyAllSkippingCodeBlocks(Element body, String cssQuery, String style) {
        for (Element element : body.select(cssQuery)) {
            if (!isInsideWechatCodeBlock(element)) {
                applyStyle(element, style);
            }
        }
    }

    /**
     * 同 {@link #applyAll}，但跳过「由本类重建、样式已精确写入」的结构：微信原生代码块（行号列与
     * 代码行由微信样式接管）与任务列表（{@link #buildTaskLists} 已写入列表/任务项样式）。
     */
    private static void applyAllSkippingEmbedded(Element body, String cssQuery, String style) {
        for (Element element : body.select(cssQuery)) {
            if (!isInsideWechatCodeBlock(element) && !isInsideTaskList(element)) {
                applyStyle(element, style);
            }
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

    /** 判断元素是否位于微信原生代码块（code-snippet）结构内。 */
    private static boolean isInsideWechatCodeBlock(Element element) {
        Element current = element;
        while (current != null) {
            if (current.hasClass(CODE_SNIPPET_FIX_CLASS) || current.hasClass(CODE_SNIPPET_CLASS)) {
                return true;
            }
            current = current.parent();
        }
        return false;
    }

    /**
     * 判断元素是否位于任务列表结构内：向上（含自身）找到的<b>第一个</b>列表容器是任务列表
     * （{@code ul} 带 {@code data-type="taskList"}）即视为在任务列表内——任务项内容里更深层嵌套的
     * 普通列表（无该标记）不在此列，恢复常规样式注入。
     */
    private static boolean isInsideTaskList(Element element) {
        Element current = element;
        while (current != null) {
            if ("ul".equalsIgnoreCase(current.tagName()) || "ol".equalsIgnoreCase(current.tagName())) {
                return "taskList".equalsIgnoreCase(current.attr("data-type"));
            }
            current = current.parent();
        }
        return false;
    }
}
