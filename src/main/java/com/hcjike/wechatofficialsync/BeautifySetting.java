package com.hcjike.wechatofficialsync;

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

    /** 正文文字颜色（段落、列表、表格正文与根节点）。 */
    private String textColor = "#3f3f3f";

    /** 链接文字颜色。 */
    private String linkColor = "#576b95";

    /** 行内代码文字颜色（默认对齐 doocs/md「经典」主题的 {@code #d14}）。 */
    private String inlineCodeColor = "#d14";

    /** 行内代码底色。 */
    private String inlineCodeBgColor = "#f2f3f5";

    /** 代码块主题：{@code light}（浅色，微信原生观感）或 {@code dark}（深色）。默认浅色。 */
    private String codeBlockTheme = "light";

    /** 引用块左侧强调边框颜色（十六进制）。默认微信绿。 */
    private String themeColor = "#07c160";

    /** 是否给 H2–H6 标题显示左侧强调边框（H1 居中不加边框）。默认关闭。 */
    private boolean headingBorderEnabled = false;

    /** 一级标题文字颜色。 */
    private String h1Color = "#222222";

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
}
