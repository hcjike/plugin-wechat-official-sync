package com.hcjike.wechatofficialsync.content;

/**
 * 预览内容的公共样式：把「渲染美化后的正文所需的补充样式」随正文一起下发。
 *
 * <p>美化后的正文只带行内 {@code style}（微信会剥离 {@code class} 与外部 CSS），但其中有两处观感依赖
 * 样式表才能成立，缺了就会「散架」：</p>
 *
 * <ul>
 *   <li><b>微信原生代码块</b>（{@code code-snippet} 结构，见
 *       {@link WechatContentBeautifier}）：左侧行号列靠 CSS 计数器生成行号、右侧代码区每行一个块级
 *       {@code <code>}，没有这份样式时行号列与代码行会挤成一行；</li>
 *   <li><b>分栏卡片 / 画廊</b>重建出的布局表格（{@code .wechat-layout-table}）：预览里补浅灰细边框，
 *       便于确认并排结构已生效、相邻卡片不会连成一片。</li>
 * </ul>
 *
 * <p>Console 预览弹窗把这些样式渲染进正文的 Shadow DOM（见 {@code ui/src/components/SyncPreviewDialog.vue}
 * 的 {@code PREVIEW_CONTENT_CSS}），而 MCP 客户端（AI 对话界面等）没有微信图文加载的全局样式、也不一定
 * 有预览的宿主页面样式，故由 {@link #withPreviewStyles(String)} 把同一份样式内嵌在 {@code content}
 * 字段里一起返回，使其同样能正确渲染。</p>
 *
 * <p>这里的样式<b>只服务于预览渲染</b>：提交到微信公众号的草稿仍是纯行内样式的正文（微信端由它自己的
 * 全局样式渲染代码块），不会带上这段样式。两处样式分别维护（前端与后端无法直接共享源码），
 * 修改时须同步更新。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
public final class WechatPreviewStyles {

    /**
     * 预览所需的补充样式：与 Console 预览弹窗的 {@code PREVIEW_CONTENT_CSS} 保持一致
     * （见类注释的同步说明），只影响预览观感、不进入提交到微信的草稿。
     */
    private static final String STYLE = """
        /* 预览页没有微信图文加载的全局样式，需补齐微信对原生代码块（code-snippet 结构）的渲染：
           左侧行号列由 CSS 计数器生成行号、右侧代码区每行一个块级 code（长行横向滚动），
           否则行号列与代码行会散架、所有代码行挤成一行 */
        .code-snippet__fix {
          display: flex;
          margin: 0.9em 0;
          overflow: hidden;
          font-family: Menlo, Consolas, 'Liberation Mono', 'Courier New', monospace;
          font-size: 13px;
          line-height: 1.7;
          color: #333;
          background: #f7f7f7;
          border: 1px solid #f0f0f0;
          border-radius: 4px;
        }

        ul.code-snippet__line-index {
          flex: none;
          padding: 12px 8px;
          margin: 0;
          color: #b2b2b2;
          text-align: right;
          list-style: none;
          counter-reset: line;
          user-select: none;
        }

        ul.code-snippet__line-index li {
          height: 1.7em;
          list-style: none;
        }

        ul.code-snippet__line-index li::before {
          counter-increment: line;
          content: counter(line);
        }

        pre.code-snippet__js {
          flex: 1;
          min-width: 0;
          padding: 12px;
          margin: 0;
          overflow-x: auto;
          font-family: inherit;
          background: transparent;
          border: none;
        }

        pre.code-snippet__js code {
          display: block;
          height: 1.7em;
          font-family: inherit;
          white-space: pre;
        }

        /* 预览里为「布局表格」（分栏卡片/画廊重建）补上浅灰细边框：提交到微信的这些表格自身无边框，
           预览中补线仅用于确认分栏/画廊已重建为表格布局、并排结构生效，不影响提交到微信的实际产物 */
        .wechat-layout-table td {
          border: 1px solid #e6e6e6;
        }

        /* 上下紧邻的布局表格（相邻的两个分栏卡片/画廊）之间留出间距：微信里每个分栏卡片/画廊各是
           一个独立表格（一个卡片 = 一个表格），紧贴显示时浅灰边框会连成一片、看起来像一个表格 */
        .wechat-layout-table + .wechat-layout-table {
          margin-top: 10px;
        }
        """;

    private WechatPreviewStyles() {
    }

    /**
     * 给预览正文带上公共样式：在正文 HTML 前插入携带 {@link #STYLE} 的 {@code <style>} 元素。
     *
     * <p>样式放在正文之前，客户端无论把它当作片段渲染（样式块随正文一起生效）还是放进 iframe 渲染，
     * 都能得到与 Console 预览一致的观感；正文本身原样保留（不包裹、不改写），因此与服务端返回的
     * 美化结果逐字一致。</p>
     *
     * @param content 美化后的正文 HTML，可为 {@code null}
     * @return 带样式块的正文 HTML；正文为空时原样返回（空内容加样式没有意义）
     */
    public static String withPreviewStyles(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        return "<style>" + STYLE + "</style>" + content;
    }
}
