package com.hcjike.wechatofficialsync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code extensions/settings.yaml} 与配置类的契约验证。
 *
 * <p>设置表单由 Halo 在<b>运行时</b>解析：YAML 缩进写错、选项取值与 {@link WechatSetting} 里的常量对不上、
 * 表单字段名与配置类字段名不一致，都不会有编译期报错，只会在用户打开设置页时静默出问题。
 * 故这里把文件真解析一遍，并把表单字段与配置类字段做一次一致性比对。</p>
 */
class SettingsYamlTest {

    private static final String SETTINGS_RESOURCE = "/extensions/settings.yaml";

    @Test
    void settingsYamlIsParseableAndFormsMatchConfigClasses() throws Exception {
        Map<String, Object> root = loadSettings();

        assertThat(root).containsEntry("kind", "Setting");
        Map<String, Object> wechatForm = formOf(root, WechatSetting.GROUP);
        // 表单字段名必须都能在配置类上找到同名字段，否则读取配置时永远取不到用户填的值
        assertFormFieldsExist(wechatForm, WechatSetting.class);
        assertFormFieldsExist(formOf(root, BeautifySetting.GROUP), BeautifySetting.class);
    }

    @Test
    void cacheRetentionDaysOffersExpectedOptions() throws Exception {
        Map<String, Object> field = formField(formOf(loadSettings(), WechatSetting.GROUP),
            "cacheRetentionDays");

        // 下拉可选天数 + 「全部保留」；取值与 WechatSetting 中的常量保持一致
        assertThat(optionValues(field)).contains("7", "15", "30", "45", "60", "90", "365",
            WechatSetting.CACHE_RETENTION_NEVER);
        // 默认值必须是可选项之一，否则设置页会显示成空白
        assertThat(optionValues(field)).contains(String.valueOf(field.get("value")));
        assertThat(field).containsEntry("value", "30");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSettings() throws Exception {
        try (InputStream in = SettingsYamlTest.class.getResourceAsStream(SETTINGS_RESOURCE)) {
            assertThat(in).as("未找到设置表单 %s", SETTINGS_RESOURCE).isNotNull();
            return new Yaml().load(in);
        }
    }

    /** 找出指定 group 的表单定义。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> formOf(Map<String, Object> root, String group) {
        Map<String, Object> spec = (Map<String, Object>) root.get("spec");
        List<Map<String, Object>> forms = (List<Map<String, Object>>) spec.get("forms");
        return forms.stream()
            .filter(form -> group.equals(form.get("group")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("settings.yaml 缺少 group=" + group + " 的表单"));
    }

    /** 按 name 取出表单里的某一项。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> formField(Map<String, Object> form, String name) {
        List<Map<String, Object>> schema = (List<Map<String, Object>>) form.get("formSchema");
        return schema.stream()
            .filter(item -> name.equals(item.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("设置表单缺少字段 " + name));
    }

    /** 断言表单里的每个 name 都在配置类上有对应的字段。 */
    @SuppressWarnings("unchecked")
    private static void assertFormFieldsExist(Map<String, Object> form, Class<?> configClass) {
        List<Map<String, Object>> schema = (List<Map<String, Object>>) form.get("formSchema");
        for (Map<String, Object> item : schema) {
            Object name = item.get("name");
            if (name == null) {
                continue;
            }
            assertThatCode(() -> configClass.getDeclaredField(String.valueOf(name)))
                .as("设置项 %s 在 %s 上找不到同名字段", name, configClass.getSimpleName())
                .doesNotThrowAnyException();
        }
    }

    /** 下拉项的取值列表。 */
    @SuppressWarnings("unchecked")
    private static List<String> optionValues(Map<String, Object> field) {
        List<Map<String, Object>> options = (List<Map<String, Object>>) field.get("options");
        return options.stream()
            .map(option -> Objects.toString(option.get("value"), null))
            .collect(Collectors.toList());
    }
}
