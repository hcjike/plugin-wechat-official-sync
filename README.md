# 微信公众号同步（plugin-wechat-official-sync）

> 在 Halo 后台的文章列表中，一键将文章同步到微信公众号草稿箱。

一款 [Halo](https://docs.halo.run) 插件：无需离开 Halo 控制台，即可把已写好的文章推送到微信公众号的**草稿箱**，封面与正文图片会自动转存到微信素材库，同步结果实时显示在文章列表。

- **插件名称**：`plugin-wechat-official-sync`（微信公众号同步）
- **适配版本**：Halo `>= 2.26.0`
- **许可证**：[GPL-3.0](./LICENSE)
- **作者**：宏尘极客 · <https://www.hcjike.com>
- **仓库**：<https://github.com/hcjike/plugin-wechat-official-sync>

---

## 功能特性

- **一键同步**：文章行的操作菜单中新增「同步到微信公众号」，确认后即提交同步任务。
- **自动创建草稿**：调用微信 `draft/add` 接口，把文章标题、作者、摘要、正文写入公众号草稿箱。
- **封面处理**：将文章封面下载后上传为微信**永久图片素材**，作为草稿封面（`thumb_media_id`）。
- **正文图片转存**：解析正文 HTML，把其中的图片逐张转存到微信域名（`media/uploadimg`）并替换链接，避免微信过滤外部图片。
- **webp 自动转换**：微信素材仅支持 `bmp/png/jpeg/jpg/gif`；插件会把 `webp` 等格式自动解码并重编码为 `png/jpg` 再上传。
- **异步不阻塞**：接口立即返回 `202 Accepted`，实际同步在后台线程执行，不卡住控制台。
- **状态可视化**：文章列表新增状态列，用颜色编码的微信 Logo 展示每篇文章最近一次同步结果，鼠标悬停查看详细信息。

## 使用方式

1. 在文章列表找到目标文章，点击行尾的操作菜单（`···`）。
2. 选择 **同步到微信公众号**，在弹窗中点击「确认同步」。
3. 状态列的微信 Logo 会先变为**橙色（同步中）**，稍后刷新为**绿色（成功）**或**红色（失败）**。
4. 同步成功后，前往公众号后台的**草稿箱**即可看到该文章。

### 状态列颜色含义

| 颜色 | 状态 | 说明 |
| --- | --- | --- |
| 🟢 绿色 `#07c160` | 成功 | 已写入公众号草稿箱 |
| 🔴 红色 `#ef4444` | 失败 | 悬停查看失败原因 |
| 🟠 橙色 `#f59e0b` | 同步中 | 任务已提交，正在处理 |

> 鼠标悬停在 Logo 上会显示状态文案、说明与更新时间；成功时不展示 `media_id` 等技术细节。

## 环境要求

- Halo `>= 2.26.0`
- Java 21+
- Node.js `>= 22.12.0`
- pnpm
- 一个微信公众号（订阅号或服务号），且已开通素材管理、草稿箱等接口权限

## 安装

方式一：从 Release 下载构建好的 jar，在 Halo 控制台「插件」页面上传安装。

方式二：自行构建（见下方[构建](#构建)），产物位于 `build/libs/*.jar`。

## 配置

安装并启用插件后，进入插件的「设置」，在 **微信公众号** 分组中填写：

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| AppID | 是 | 公众平台「设置与开发 - 基本配置」中的开发者 ID |
| AppSecret | 是 | 公众平台的开发者密码（以密文存储） |
| 默认作者 | 否 | 同步到公众号时展示的作者名，留空则使用文章作者 |
| 开启评论 | 否 | 同步生成的草稿是否开启评论，默认关闭 |

### 还需要在微信公众平台 / Halo 侧完成的准备

- **IP 白名单**：在公众平台「基本配置 - IP 白名单」中加入 **Halo 服务器的公网出口 IP**，否则无法获取 `access_token`。
- **外部访问地址**：若文章封面或正文图片使用**相对路径**，需在 Halo「设置 - 基本设置 - 外部访问地址」中配置可公网访问的站点地址，插件据此拼接出图片的绝对地址后再下载转存（会自动兼容地址尾部有无 `/`）。

## 权限说明

插件提供了名为 **发布到微信公众号** 的角色模板（在权限列表中归属「微信公众号同步」分组），用于把同步能力授予超级管理员以外的用户。

- 默认情况下，插件的自定义接口仅 **超级管理员** 可访问。
- 若要让其他角色也能同步，请在 Halo「用户与权限 - 角色」中编辑目标角色，勾选 **微信公众号同步 → 发布到微信公众号** 权限。
- 该权限同时控制两个层面：
  - **接口访问**：`POST .../sync`（提交同步）与 `GET .../status`（查询状态）；
  - **界面展示**：未授权用户在文章列表的操作菜单中**看不到**「同步到微信公众号」入口。

## 工作原理

一次同步的完整流程（响应式、后台异步执行）：

```
解析外部访问地址（Halo 基本设置，回退 ExternalUrlSupplier）
        │
        ▼
获取并缓存 access_token
        │
        ▼
上传封面为永久图片素材（add_material?type=image，webp 自动转码）
        │
        ▼
转存正文图片（解析 HTML → uploadimg → 替换为微信图片地址）
        │
        ▼
创建图文草稿（draft/add）→ 返回草稿 media_id
        │
        ▼
写入同步状态（PENDING / SUCCESS / FAILED）到插件专用 ConfigMap
```

- 同步状态持久化在名为 `wechat-official-sync-records` 的 ConfigMap 中，供文章列表渲染状态列。
- `access_token` 带内存缓存并在到期前自动刷新，避免频繁请求。

## 接口

插件对外提供以下自定义接口（需登录控制台，随插件权限校验）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/apis/api.wechat-sync.halo.run/v1alpha1/sync` | 提交同步任务，立即返回 `202`，后台异步执行 |
| `GET` | `/apis/api.wechat-sync.halo.run/v1alpha1/status` | 返回全部文章的最近同步状态，键为文章 `name` |

`POST /sync` 请求体示例：

```json
{
  "postName": "my-post",
  "title": "文章标题",
  "digest": "摘要",
  "content": "<p>渲染后的正文 HTML</p>",
  "cover": "/upload/cover.jpg",
  "author": "作者名"
}
```

## 开发

```bash
# 克隆仓库
git clone https://github.com/hcjike/plugin-wechat-official-sync.git
cd plugin-wechat-official-sync

# 启动一个集成了本插件的 Halo 开发实例
./gradlew haloServer

# 前端开发（另开终端）
cd ui
pnpm install
pnpm dev
```

> Windows 下将 `./gradlew` 替换为 `./gradlew.bat`，`pnpm` 若无法直接调用可用 `pnpm.cmd`。

## 构建

```bash
./gradlew build
```

构建完成后，插件 jar 位于 `build/libs/` 目录。该任务会一并构建前端（`ui`）并把产物打包进插件 jar。

## 技术栈

- **后端**：Java 21、Spring WebFlux（响应式 `WebClient`）、Halo Plugin API、[jsoup](https://jsoup.org)（解析正文 HTML）、[TwelveMonkeys ImageIO WebP](https://github.com/haraldk/TwelveMonkeys)（webp 解码）
- **前端**：Vue 3、TypeScript、Vite、`@halo-dev/components`、`@halo-dev/api-client`、unplugin-icons
- **构建**：Gradle + `run.halo.plugin.devtools`、pnpm

## 常见问题（FAQ）

**Q：同步失败，提示「微信公众号草稿必须包含封面图」？**
微信草稿**强制要求**封面。请为文章设置封面后重试；若封面是相对路径，请确认已在 Halo 配置「外部访问地址」。

**Q：提示获取 `access_token` 失败？**
检查 AppID / AppSecret 是否正确，以及是否已在公众平台配置 **IP 白名单**（Halo 服务器公网出口 IP）。

**Q：提示接口无权限 / 48001 等错误？**
草稿箱、素材管理等接口需要**已认证**的公众号并开通对应权限，未认证或个人订阅号可能无法调用。

**Q：封面或图片是 webp，能同步吗？**
可以。插件会自动把 webp 解码并重编码为微信支持的 `png/jpg` 再上传。

**Q：报 `412 Precondition Failed`？**
这是历史版本的已知问题（微信校验 `Content-Length`，而 Spring 6.1+ 默认分块传输）。当前版本已通过手动构造 multipart 报文并显式设置 `Content-Length` 修复。

**Q：正文里的图片同步后不显示？**
微信会过滤文章正文中的外部图片链接。插件已通过 `media/uploadimg` 将正文图片转存到微信域名；若个别图片转存失败会保留原地址，请确认这些图片可被 Halo 服务器正常访问。

## 许可证

[GPL-3.0](./LICENSE) © 宏尘极客（hcjike）
