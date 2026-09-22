package com.hcjike.wechatofficialsync.config;

import lombok.Data;

/**
 * 正文美化相关的插件配置，对应 settings.yaml 中 group 为 {@value #GROUP} 的独立表单标签。
 *
 * <p>与公众号凭据（{@link WechatSetting}）分开维护：本类只描述「提交草稿前如何美化正文」的排版偏好。
 * 所有字段均带内置默认值，用户未打开过该设置标签或某项缺失时，回退到这些默认值即可正常工作，
 * 因此美化流程不会因配置缺省而中断。</p>
 *
 * @author hcjike
 * @since 1.0.0
 */
@Data
public class BeautifySetting {

    public static final String GROUP = "beautify";

    /** {@link #columnsLayoutStyle} / {@link #galleryLayoutStyle} 取值：重建为表格（默认）——分栏各列并排、画廊多图并排。 */
    public static final String LAYOUT_STYLE_TABLE = "table";

    /** {@link #columnsLayoutStyle} / {@link #galleryLayoutStyle} 取值：独占一行——分栏每栏一行、画廊每张图片一行。 */
    public static final String LAYOUT_STYLE_STACKED = "stacked";

    /** {@link #tableWidthMode} 取值：保持比例（默认）——保留文章表格的原始列宽，列宽超出屏宽时由外层容器横向滚动查看全貌。 */
    public static final String TABLE_WIDTH_MODE_PROPORTIONAL = "proportional";

    /** {@link #tableWidthMode} 取值：宽度铺满——列宽按比例压缩进屏宽，表格始终铺满屏幕、不产生横向滚动。 */
    public static final String TABLE_WIDTH_MODE_FILL = "fill";

    /** {@link #attachmentLinkDisplay} 取值：显示链接地址（默认）——以正文里写的原始地址文本呈现。 */
    public static final String ATTACHMENT_LINK_DISPLAY_ADDRESS = "address";

    /** {@link #attachmentLinkDisplay} 取值：显示链接内容——以正文里链接自身的文字呈现。 */
    public static final String ATTACHMENT_LINK_DISPLAY_CONTENT = "content";

    /** {@link #h1Align} 取值：左对齐。 */
    public static final String H1_ALIGN_LEFT = "left";

    /** {@link #h1Align} 取值：居中（默认）。 */
    public static final String H1_ALIGN_CENTER = "center";

    /** {@link #h1Align} 取值：右对齐。 */
    public static final String H1_ALIGN_RIGHT = "right";

    /** 正文文字颜色（段落、列表、表格正文与根节点）。 */
    private String textColor = "#3f3f3f";

    /** 链接文字颜色。 */
    private String linkColor = "#576b95";

    /**
     * 不可提交到微信的正文链接（pdf、zip 等非图片附件——微信图文外链点不开、文件又无法转存）在草稿里
     * 以纯文本呈现时，显示<b>链接地址</b>（{@link #ATTACHMENT_LINK_DISPLAY_ADDRESS}，默认，即正文里
     * 写的原始地址，如 {@code /upload/2026/09/manual.pdf}）还是<b>链接内容</b>
     * （{@link #ATTACHMENT_LINK_DISPLAY_CONTENT}，即链接自身的文字，如「下载手册」）。取值非法时按显示地址处理。
     */
    private String attachmentLinkDisplay = ATTACHMENT_LINK_DISPLAY_ADDRESS;

    /** 行内代码文字颜色（默认对齐 doocs/md「经典」主题的 {@code #d14}）。 */
    private String inlineCodeColor = "#d14";

    /** 行内代码底色。 */
    private String inlineCodeBgColor = "#f2f3f5";

    /** 是否显示引用块左侧强调边框。默认开启。 */
    private boolean blockquoteBorderEnabled = true;

    /** 引用块左侧强调边框颜色（十六进制）。默认微信绿。 */
    private String themeColor = "#07c160";

    /** 引用块背景颜色。默认浅灰。 */
    private String blockquoteBgColor = "#f7f7f7";

    /** 是否给 H2–H6 标题显示左侧强调边框（H1 不加边框）。默认关闭。 */
    private boolean headingBorderEnabled = false;

    /** 一级标题文字颜色。 */
    private String h1Color = "#222222";

    /**
     * 一级标题的对齐方式：{@link #H1_ALIGN_LEFT} / {@link #H1_ALIGN_CENTER}（默认）/ {@link #H1_ALIGN_RIGHT}。
     * 仅对<b>未自带对齐方式</b>的 H1 生效——正文中的 H1 若已有内联 {@code text-align} 或 {@code align}
     * 属性，则保持原样、不被本项覆盖。取值非法时按居中处理。
     */
    private String h1Align = H1_ALIGN_CENTER;

    /** 二级标题文字颜色。 */
    private String h2Color = "#222222";

    /** 二级标题左侧强调边框颜色（需开启 {@link #headingBorderEnabled}）。默认微信绿。 */
    private String h2BorderColor = "#07c160";

    /** 三级标题文字颜色。 */
    private String h3Color = "#222222";

    /** 三级标题左侧强调边框颜色（需开启 {@link #headingBorderEnabled}）。默认微信绿。 */
    private String h3BorderColor = "#07c160";

    /** 四级标题文字颜色。 */
    private String h4Color = "#222222";

    /** 四级标题左侧强调边框颜色（需开启 {@link #headingBorderEnabled}）。默认微信绿。 */
    private String h4BorderColor = "#07c160";

    /** 五级标题文字颜色。 */
    private String h5Color = "#333333";

    /** 五级标题左侧强调边框颜色（需开启 {@link #headingBorderEnabled}）。默认微信绿。 */
    private String h5BorderColor = "#07c160";

    /** 六级标题文字颜色。 */
    private String h6Color = "#888888";

    /** 六级标题左侧强调边框颜色（需开启 {@link #headingBorderEnabled}）。默认微信绿。 */
    private String h6BorderColor = "#07c160";

    /** 折叠块（Halo 折叠内容）标题栏的背景颜色。默认浅灰。 */
    private String detailsTitleBgColor = "#f7f7f7";

    /** 折叠块内容区的背景颜色。默认白色（与正文背景一致）。 */
    private String detailsContentBgColor = "#ffffff";

    /** 折叠块卡片边框（含标题栏分隔线）的颜色。默认浅灰。 */
    private String detailsBorderColor = "#e6e6e6";

    /**
     * 视频/音频提示卡片（微信不支持正文内嵌视频与音乐，编辑器插入的 {@code <video>}/{@code <audio>}
     * 同步时会重建为该卡片）的背景颜色。默认浅灰（与引用块背景一致）。
     */
    private String mediaCardBgColor = "#f7f7f7";

    /** 视频/音频提示卡片主行文字颜色（「▶ 视频」/「♪ 音频」）。默认深灰。 */
    private String mediaCardTitleColor = "#333333";

    /** 视频/音频提示卡片引导语文字颜色（「请点击文末『阅读原文』观看/收听」）。默认浅灰。 */
    private String mediaCardHintColor = "#999999";

    /** 视频/音频提示卡片标记符号颜色（▶ / ♪）。默认微信绿。 */
    private String mediaCardMarkerColor = "#07c160";

    /**
     * 分栏卡片的同步版式：{@link #LAYOUT_STYLE_TABLE}（默认，重建为表格、各列并排）或
     * {@link #LAYOUT_STYLE_STACKED}（独占一行——每栏一行，栏内内容照常保留）。取值非法时按表格处理。
     */
    private String columnsLayoutStyle = LAYOUT_STYLE_TABLE;

    /**
     * 画廊的同步版式：{@link #LAYOUT_STYLE_TABLE}（默认，重建为表格、多图并排）或
     * {@link #LAYOUT_STYLE_STACKED}（独占一行——每张图片一行，图片与描述内容照常保留）。取值非法时按表格处理。
     */
    private String galleryLayoutStyle = LAYOUT_STYLE_TABLE;

    /**
     * 表格宽度模式：{@link #TABLE_WIDTH_MODE_PROPORTIONAL}（默认，保持比例——列宽按文章原样保留，
     * 列宽总和超出屏宽时表格由外层容器横向滚动查看全貌）或 {@link #TABLE_WIDTH_MODE_FILL}（宽度铺满
     * ——列宽按比例归一化为百分比并强制表格宽度铺满，始终收敛在屏宽内）。取值非法时按保持比例处理。
     */
    private String tableWidthMode = TABLE_WIDTH_MODE_PROPORTIONAL;
}
