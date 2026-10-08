package com.hcjike.wechatofficialsync.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 预览内容的补充样式：把「渲染预览所需的样式」<b>内联到元素上</b>，使预览 HTML 自带全部样式、
 * 不依赖任何外部样式表（也不带 {@code <style>} 块）。
 *
 * <p>美化后的正文只带行内 {@code style}（微信会剥离 {@code class} 与外部 CSS），但其中有两处观感
 * 依赖样式表才能成立，缺了就会「散架」：</p>
 *
 * <ul>
 *   <li><b>微信原生代码块</b>（{@code code-snippet} 结构，见 {@link WechatContentBeautifier}）：
 *       行号列靠 CSS 计数器生成行号、右侧代码区每行一个块级 {@code <code>}，没有样式时行号列与代码行
 *       会挤成一行；</li>
 *   <li><b>分栏卡片 / 画廊</b>重建出的布局表格（{@code .wechat-layout-table}）：预览里补浅灰细边框，
 *       便于确认并排结构已生效、相邻卡片不会连成一片。</li>
 * </ul>
 *
 * <p>Console 预览弹窗把这些样式渲染进正文的 Shadow DOM（见 {@code ui/src/components/SyncPreviewDialog.vue}
 * 的 {@code PREVIEW_CONTENT_CSS}），而 MCP 客户端（AI 对话界面等）拿到的是脱离站点的 HTML 片段，
 * 并且往往经过 AI 转述或 Markdown / HTML 清洗——{@code <style>} 标签很容易被丢掉，一丢预览就散架。
 * 因此 MCP 侧不再下发样式块，改由 {@link #withInlineStyles(String)} 把这些声明写成元素上的
 * {@code style} 属性；代码块的<b>行号也改写为字面数字</b>（不再依赖 CSS 计数器），
 * 于是「元素 + 自身的行内样式」就足以正确渲染。</p>
 *
 * <p>这里的样式<b>只服务于预览渲染</b>：提交到微信公众号的草稿仍是纯行内样式的正文（微信端由它自己的
 * 全局样式渲染代码块），不会带上这些预览补丁。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public final class WechatPreviewStyles {

    /** 代码块外层容器类名（与 {@link WechatContentBeautifier} 的 {@code code-snippet__fix} 一致）。 */
    private static final String CODE_FIX_CLASS = "code-snippet__fix";

    /** 代码块行号列类名（与 {@link WechatContentBeautifier} 的 {@code code-snippet__line-index} 一致）。 */
    private static final String LINE_INDEX_CLASS = "code-snippet__line-index";

    /** 布局表格标记类名（与 {@link WechatContentBeautifier} 的 {@code wechat-layout-table} 一致）。 */
    private static final String LAYOUT_TABLE_CLASS = "wechat-layout-table";

    /** 代码块外层容器（行号列 + 代码区并排）：原样式块的 {@code .code-snippet__fix} 规则。 */
    private static final String CODE_FIX_STYLE = "display:flex;margin:0.9em 0;overflow:hidden;"
        + "font-family:Menlo,Consolas,'Liberation Mono','Courier New',monospace;font-size:13px;"
        + "line-height:1.7;color:#333333;background:#f7f7f7;border:1px solid #f0f0f0;border-radius:4px;";

    /** 代码块行号列：原样式块的 {@code ul.code-snippet__line-index} 规则。 */
    private static final String LINE_INDEX_STYLE = "flex:none;padding:12px 8px;margin:0;color:#b2b2b2;"
        + "text-align:right;list-style:none;user-select:none;";

    /** 行号列的每个序号项：原样式块的 {@code ul.code-snippet__line-index li} 规则。 */
    private static final String LINE_INDEX_ITEM_STYLE = "height:1.7em;list-style:none;";

    /** 代码区：原样式块的 {@code pre.code-snippet__js} 规则。 */
    private static final String CODE_PRE_STYLE = "flex:1;min-width:0;padding:12px;margin:0;"
        + "overflow-x:auto;font-family:inherit;background:transparent;border:none;";

    /** 代码区的每个代码行：原样式块的 {@code pre.code-snippet__js code} 规则。 */
    private static final String CODE_LINE_STYLE =
        "display:block;height:1.7em;font-family:inherit;white-space:pre;";

    /** 布局表格单元格的预览补线：原样式块的 {@code .wechat-layout-table td} 规则。 */
    private static final String LAYOUT_CELL_STYLE = "border:1px solid #e6e6e6;";

    /** 相邻布局表格之间的间距：原样式块的 {@code .wechat-layout-table + .wechat-layout-table} 规则。 */
    private static final String LAYOUT_TABLE_GAP_STYLE = "margin-top:10px;";

    private WechatPreviewStyles() {
    }

    /**
     * 给预览正文补上渲染所需的样式：把样式声明内联到相应元素上（代码块行号同时写成字面数字）。
     *
     * <p>不改动其它元素，也不包裹额外容器；正文里原本的行内样式原样保留（补充声明追加在其后，
     * 优先级更高）。空内容原样返回——没有正文时补样式没有意义。</p>
     *
     * @param content 美化后的正文 HTML，可为 {@code null}
     * @return 自带样式（可脱离页面样式表渲染）的正文 HTML
     */
    public static String withInlineStyles(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        Document document = Jsoup.parseBodyFragment(content);
        document.outputSettings().prettyPrint(false);
        decorateCodeBlocks(document);
        decorateLayoutTables(document);
        return document.body().html();
    }

    /**
     * 微信原生代码块：把行号列 / 代码行的排版样式内联到元素上，并把行号写成<b>字面数字</b>——
     * 脱离样式表时（客户端丢掉 {@code <style>}、AI 只转发片段等）依然能看出「行号 + 代码行」结构。
     */
    private static void decorateCodeBlocks(Document document) {
        for (Element wrapper : document.select("section." + CODE_FIX_CLASS)) {
            appendStyle(wrapper, CODE_FIX_STYLE);
            for (Element lineIndex : wrapper.select("ul." + LINE_INDEX_CLASS)) {
                appendStyle(lineIndex, LINE_INDEX_STYLE);
                int line = 0;
                for (Element item : lineIndex.select("li")) {
                    line++;
                    appendStyle(item, LINE_INDEX_ITEM_STYLE);
                    // 行号列原本是刻意留空的 <li>（行号由 CSS 计数器渲染），这里直接写入数字
                    item.text(String.valueOf(line));
                }
            }
            for (Element pre : wrapper.select("pre")) {
                appendStyle(pre, CODE_PRE_STYLE);
                for (Element code : pre.select("code")) {
                    appendStyle(code, CODE_LINE_STYLE);
                }
            }
        }
    }

    /**
     * 布局表格（分栏卡片 / 画廊重建）：给单元格补浅灰细边框（仅预览标记，不进草稿），
     * 并给紧邻上一个布局表格的表格补一点上间距，避免两个卡片的边框连成一片。
     */
    private static void decorateLayoutTables(Document document) {
        for (Element table : document.select("table." + LAYOUT_TABLE_CLASS)) {
            for (Element cell : table.select("td")) {
                appendStyle(cell, LAYOUT_CELL_STYLE);
            }
            if (table.previousElementSibling() instanceof Element previous
                && previous.hasClass(LAYOUT_TABLE_CLASS)) {
                appendStyle(table, LAYOUT_TABLE_GAP_STYLE);
            }
        }
    }

    /**
     * 追加内联样式：保留元素原有的 {@code style} 声明，补充的声明写在后面（同名属性覆盖原值）；
     * 原样式不以 {@code ;} 结尾时补一个，避免两条声明粘连成非法值。
     */
    private static void appendStyle(Element element, String style) {
        String existing = element.attr("style").trim();
        if (existing.isEmpty()) {
            element.attr("style", style);
            return;
        }
        element.attr("style", existing.endsWith(";") ? existing + style : existing + ";" + style);
    }
}
