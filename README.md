<h1 align="center">微信公众号同步（plugin-wechat-official-sync）</h1>

<p align="center">
<a href="https://github.com/halo-dev/halo"><img alt="Halo version" src="https://img.shields.io/badge/halo-2.26.0%2B-brightgreen?style=flat-square" /></a>
<a href="https://github.com/hcjike/plugin-wechat-official-sync/releases"><img alt="releases" src="https://img.shields.io/github/release/hcjike/plugin-wechat-official-sync.svg?style=flat-square"/></a>
<a href="https://github.com/hcjike/plugin-wechat-official-sync/blob/main/LICENSE"><img alt="license" src="https://img.shields.io/github/license/hcjike/plugin-wechat-official-sync?style=flat-square"/></a>
<a href="https://github.com/hcjike/plugin-wechat-official-sync/releases"><img alt="downloads" src="https://img.shields.io/github/downloads/hcjike/plugin-wechat-official-sync/total.svg?style=flat-square"/></a>
<a href="https://github.com/hcjike/plugin-wechat-official-sync/commits"><img alt="commits" src="https://img.shields.io/github/last-commit/hcjike/plugin-wechat-official-sync.svg?style=flat-square"/></a>
</p>

> 在 Halo 后台的文章列表中，一键将文章同步到微信公众号草稿箱。

一款 [Halo](https://docs.halo.run) 插件：无需离开 Halo 控制台，即可把已写好的文章推送到微信公众号的**草稿箱**，封面与正文图片会自动转存到微信素材库，同步结果实时显示在文章列表。

- **插件名称**：`plugin-wechat-official-sync`（微信公众号同步）
- **适配版本**：Halo `>= 2.26.0`
- **许可证**：[GPL-3.0](./LICENSE)
- **作者**：宏尘极客 · <https://www.hcjike.com>
- **仓库**：<https://github.com/hcjike/plugin-wechat-official-sync>

---

## 功能特性

- **一键同步**：文章行的操作菜单中新增「同步到微信公众号」，先在弹窗中预览上传后的排版效果，并核对本次上传的作者、原文链接与留言设置，确认后即提交同步任务。
- **自动创建草稿**：调用微信 `draft/add` 接口，把文章标题、作者、摘要、正文写入公众号草稿箱，并把「原文链接（阅读原文）」填为文章在站点上的地址。
- **封面处理**：将文章封面下载后上传为微信**永久图片素材**，作为草稿封面（`thumb_media_id`）。
- **正文图片转存**：解析正文 HTML，把其中的图片逐张转存到微信域名（`media/uploadimg`）并替换链接，避免微信过滤外部图片。
- **正文排版美化**：微信图文会剥离外部 CSS 与 `class`，只保留行内 `style`。插件在提交草稿前用 jsoup 按标签为标题、段落、引用、代码块、图片、列表、表格等注入微信友好的内联样式，让排版贴合公众号阅读体验（你在编辑器里已设置的行内样式优先保留）。代码块采用微信编辑器**原生代码块结构**：自带行号（删除代码行时行号自动减少）、内容不折行，行号与代码行严格对应，微信会按代码语言自动高亮；表格对齐**微信编辑器插入表格的原生观感**：默认**铺满屏宽**（页面宽度自适应）、1px 浅灰细边框、表头加粗无底色，列宽按文章中设定的比例渲染、单元格内容自动换行；若文章为表格设置了超出屏宽的固定宽度，才由外层容器**横向滚动查看全貌**。引用块边框（可开关，默认显示）、引用块背景色、标题边框（可开关）、各级标题颜色、行内代码配色均可在插件设置的 **正文美化** 标签中配置。
- **webp 自动转换**：微信素材仅支持 `bmp/png/jpeg/jpg/gif`；插件会把 `webp` 等格式自动解码并重编码为 `png/jpg` 再上传。
- **异步不阻塞**：接口立即返回 `202 Accepted`，实际同步在后台线程执行，不卡住控制台。
- **状态可视化**：文章列表新增状态列，用颜色编码的微信 Logo 展示每篇文章最近一次同步结果，鼠标悬停查看详细信息。

## 使用方式

1. 在文章列表找到目标文章，点击行尾的操作菜单（`···`）。
2. 选择 **同步到微信公众号**，弹窗中会按手机端图文版式展示文章标题与正文上传到公众号后的大致效果，并列出本次上传使用的作者、原文链接与留言设置；确认无误后点击「确认同步」，不满意可取消、不提交。
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

**方式一：应用商店安装（推荐）**

- 在 Halo 后台左侧菜单进入 **应用市场**，搜索 **微信公众号同步**，点击进入详情页后一键安装；安装后新版本发布时可在后台直接升级。
- 也可直接在浏览器打开商店页面 [微信公众号同步 - Halo 应用商店](https://www.halo.run/store/apps/app-nahksfoe/releases) 下载安装。

**方式二：手动上传安装**

从 [GitHub Releases](https://github.com/hcjike/plugin-wechat-official-sync/releases) 下载构建好的 jar，在 Halo 后台「插件」页面点击「安装」并上传 jar 文件。

**方式三：自行构建**

见下方[构建](#构建)，产物位于 `build/libs/*.jar`，再按方式二上传安装。

## 配置

安装并启用插件后，进入插件的「设置」，分为 **微信公众号** 与 **正文美化** 两个标签。

**微信公众号** 标签：

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| AppID | 是 | 公众平台「设置与开发 - 基本配置」中的开发者 ID |
| AppSecret | 是 | 公众平台的开发者密码。通过 Halo **Secret** 组件托管：值保存在独立的 `Secret` 资源中，插件设置（ConfigMap）**只保存该 Secret 的名称，不保存任何明文** |
| 接口地址 | 否 | 微信接口基址。留空则直连官方 `https://api.weixin.qq.com`；无固定公网 IP 时填自建反向代理地址，详见[接口地址与反向代理](#接口地址与反向代理) |
| 默认作者 | 否 | 同步到公众号时展示的作者名，留空则使用文章作者 |
| 留言设置 | 否 | 同步生成的草稿的留言权限，可选「关闭留言」（默认）/「所有人可留言」/「已关注的人可留言」，对应微信 `need_open_comment` 与 `only_fans_can_comment`；「已关注 7 天及以上」仅支持在公众号后台设置 |
| 图片下载白名单 | 否 | 多行文本，每行一个信任的域名/IP/CIDR。**留空（默认）= 不放行任何内网地址**；仅当 Halo 部署在内网、图片也在内网地址时才需配置，详见[内网部署与图片下载白名单](#内网部署与图片下载白名单) |

**正文美化** 标签（控制提交草稿前的排版美化，均已提供默认值）：

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| 正文文字颜色 | 是 | 段落、列表、表格正文的文字颜色，默认 `#3f3f3f` |
| 链接颜色 | 是 | 正文中超链接的文字颜色，默认 `#576b95` |
| 行内代码颜色 | 是 | 行内 `code` 的文字颜色，默认 `#d14`（对齐微信图文经典风格） |
| 行内代码底色 | 是 | 行内 `code` 的背景色，默认 `#f2f3f5` |
| 引用块显示边框 | 否 | 开关；开启后引用块显示左侧强调边框（颜色由「引用块边框颜色」决定），默认开启 |
| 引用块边框颜色 | 是 | 引用块左侧强调边框的颜色，默认微信绿 `#07c160` |
| 引用块背景色 | 是 | 引用块的背景颜色，默认 `#f7f7f7` |
| 标题显示边框 | 否 | 开关；开启后 H2–H6 标题显示左侧强调边框（H1 居中不加），默认关闭 |
| 一级标题颜色 | 是 | H1 标题文字颜色，默认 `#222222` |
| 二级标题颜色 / 二级标题边框颜色 | 是 | H2 文字颜色（默认 `#222222`）与左侧边框颜色（默认 `#07c160`，仅开关开启时显示） |
| 三级标题颜色 / 三级标题边框颜色 | 是 | H3 文字颜色（默认 `#222222`）与左侧边框颜色（默认 `#07c160`，仅开关开启时显示） |
| 四级标题颜色 / 四级标题边框颜色 | 是 | H4 文字颜色（默认 `#222222`）与左侧边框颜色（默认 `#07c160`，仅开关开启时显示） |
| 五级标题颜色 / 五级标题边框颜色 | 是 | H5 文字颜色（默认 `#333333`）与左侧边框颜色（默认 `#07c160`，仅开关开启时显示） |
| 六级标题颜色 / 六级标题边框颜色 | 是 | H6 文字颜色（默认 `#888888`）与左侧边框颜色（默认 `#07c160`，仅开关开启时显示） |

### 还需要在微信公众平台 / Halo 侧完成的准备

- **IP 白名单**：在公众平台「基本配置 - IP 白名单」中加入 **Halo 服务器的公网出口 IP**，否则无法获取 `access_token`。若 Halo 服务器**没有固定公网 IP**（动态 IP、家用宽带、部署在 NAT 之后），请改用[接口地址与反向代理](#接口地址与反向代理)方案：用一台有固定公网 IP 的服务器做反向代理，把**该代理服务器的固定 IP** 加入白名单。
- **外部访问地址**：若文章封面或正文图片使用**相对路径**，需在 Halo「设置 - 基本设置 - 外部访问地址」中配置可公网访问的站点地址，插件据此拼接出图片的绝对地址后再下载转存（会自动兼容地址尾部有无 `/`）；草稿的「原文链接」同样基于该地址与文章路由拼接。

### 接口地址与反向代理

微信要求**获取 `access_token` 的服务器出口 IP** 必须在公众号「IP 白名单」中。若你的 Halo 服务器**没有固定公网 IP**（动态 IP、家用宽带、部署在 NAT 之后等），白名单会频繁失效，导致同步失败。

解决办法：准备一台**有固定公网 IP** 的低配服务器（VPS）作为**反向代理 / 白名单代理**，只把**这台代理服务器的固定 IP** 加入微信 IP 白名单。Halo 将微信接口请求发往「接口地址」，由代理转发到 `api.weixin.qq.com`——微信看到的出口 IP 始终是代理的固定 IP，从而绕开 Halo 无固定公网 IP 的限制。

```
Halo 服务器（无固定公网 IP）
        │  请求发往插件设置的「接口地址」
        ▼
反向代理服务器（固定公网 IP，已加入微信 IP 白名单）
        │  原样转发到
        ▼
https://api.weixin.qq.com
```

**配置**：在插件设置的 **接口地址** 中填入代理服务器地址（如 `https://wechat-proxy.example.com`）；留空则直连微信官方接口。地址支持带路径前缀（如 `https://example.com/wechat-proxy`），插件会保留前缀并去除尾部 `/`。

> ⚠️ **重要提醒**：代理服务器会获取请求中的**敏感信息**（包括但不限于 `access_token`、AppID 等敏感信息），务必使用**自行部署或可信的服务器**进行代理，切勿使用来源不明的公共代理，以免造成信息泄露。

> ⚠️ 你**必须**在该地址上反向代理插件用到的**全部**微信接口，并**保持原始请求路径不变**，否则相应步骤会失败。

**插件用到的微信接口**（相对于「接口地址」基址，均为微信官方路径）：

| 方法 | 路径 | 用途 | 请求体 |
| --- | --- | --- | --- |
| `GET` | `/cgi-bin/token` | 获取 `access_token` | 查询参数 |
| `POST` | `/cgi-bin/media/uploadimg` | 上传正文图片 | `multipart/form-data` |
| `POST` | `/cgi-bin/material/add_material?type=image` | 上传封面为永久图片素材 | `multipart/form-data` |
| `POST` | `/cgi-bin/draft/add` | 创建图文草稿 | `application/json` |

**Nginx 反向代理示例**（部署在代理服务器上，将上述路径整体转发到微信）：

```nginx
server {
    listen 443 ssl;
    server_name wechat-proxy.example.com;

    # ssl_certificate     /path/to/fullchain.pem;
    # ssl_certificate_key /path/to/privkey.pem;

    # 仅放行插件用到的 4 个接口路径，其余一律拒绝，避免沦为开放代理
    location ~ ^/cgi-bin/(token|media/uploadimg|material/add_material|draft/add)$ {
        proxy_pass https://api.weixin.qq.com;   # 不含 URI，nginx 会原样透传路径与查询参数
        proxy_set_header Host api.weixin.qq.com;
        proxy_ssl_server_name on;               # 关键：向微信发起 TLS 时携带 SNI
        proxy_ssl_name api.weixin.qq.com;

        client_max_body_size 20m;               # 素材上传可能较大，按需调整
        proxy_request_buffering off;
        proxy_read_timeout 60s;
    }

    location / {
        return 403;
    }
}
```

**1Panel 反向代理示例**（适用于通过 1Panel 建站面板管理的服务器）：

```nginx
location ~ ^/cgi-bin/(token|media/uploadimg|material/add_material|draft/add)$ {
    proxy_pass https://api.weixin.qq.com;
    proxy_set_header Host api.weixin.qq.com;
    proxy_ssl_server_name on;
    proxy_ssl_name api.weixin.qq.com;
    client_max_body_size 30m; # 素材上传可能较大，按需调整
    proxy_request_buffering off;
    proxy_read_timeout 120s;
}
```

> 在 1Panel 中创建「反向代理」网站后，进入该网站的 **反向代理**，将上方整段 `location` 配置粘贴到源文文件中保存即可生效。


要点：
- `proxy_pass` 到 `https://` 上游时务必开启 `proxy_ssl_server_name on;`（SNI），否则与微信的 TLS 握手可能失败。
- **保持路径原样转发**：`proxy_pass` 后不要带会改写路径的 URI 部分，让 nginx 原样透传 `/cgi-bin/...` 路径与查询参数。
- 代理服务器的**出口 IP** 必须与加入微信白名单的 IP 一致。
- 建议用 `location` 精确匹配上面 4 个路径、拒绝其它请求，防止代理被滥用。
- 生产环境请为代理配置 `https://` 与合法证书；仅内网测试时可用 `http://`。

## 权限说明

插件提供了名为 **发布到微信公众号** 的角色模板（在权限列表中归属「微信公众号同步」分组），用于把同步能力授予超级管理员以外的用户。

- 默认情况下，插件的自定义接口仅 **超级管理员** 可访问。
- 若要让其他角色也能同步，请在 Halo「用户与权限 - 角色」中编辑目标角色，勾选 **微信公众号同步 → 发布到微信公众号** 权限。
- 该权限同时控制两个层面：
  - **接口访问**：`POST .../sync`（提交同步）、`POST .../preview`（生成排版预览与草稿元信息）与 `GET .../status`（查询状态）；
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
美化正文排版（按标签注入微信友好的内联样式，代码块重建为微信原生结构、表格默认铺满屏宽且超宽时支持横向滚动，安全清理 script / 事件属性）
        │
        ▼
创建图文草稿（draft/add，含原文链接与留言设置）→ 返回草稿 media_id
        │
        ▼
写入同步状态（PENDING / SUCCESS / FAILED）到插件专用 ConfigMap
```

- 同步状态持久化在名为 `wechat-official-sync-records` 的 ConfigMap 中，供文章列表渲染状态列。
- `access_token` 带内存缓存并在到期前自动刷新，避免频繁请求。
- 所有微信接口请求都发往设置的 **接口地址**（留空为官方 `https://api.weixin.qq.com`），便于经自建反向代理转发。

## 安全说明

### 敏感凭据存储

- **AppSecret 不落明文**：AppSecret 通过 Halo 官方的 **Secret** 组件（`$formkit: secret`）配置，其值保存在独立的 `Secret` 资源中，插件的设置项（`ConfigMap`）**只保存该 Secret 的名称**，不保存也不返回任何明文。
- 服务端通过 `ReactiveExtensionClient` 按名称读取该 `Secret`，在内存中解析出 AppSecret 后仅用于向微信换取 `access_token`；**不写入日志、异常消息、资源状态或任何持久化位置**。
- 面向用户的角色模板（发布到微信公众号）**不授予** `Secret` 的读取权限，读取仅发生在插件服务端内部。

### 出站图片下载的 SSRF 防护

封面图与正文图片的地址来自用户可控的请求体与正文 HTML，插件会从 Halo 服务端主动拉取这些地址，属于典型的 SSRF 面。为此在**统一下载入口**施加了纵深防御：

1. **URL 结构校验**：用 URI 解析器处理地址，仅允许 `http`/`https`，必须含合法主机名，禁止携带用户名/密码（`userinfo`）等易被用于绕过的结构。
2. **解析后的 IP 校验**：发起下载前解析目标主机的**全部** IPv4/IPv6 地址，逐一拒绝环回、私网（站点本地）、链路本地、组播、未指定地址，以及运营商级 NAT（`100.64.0.0/10`，含云平台元数据地址）、IPv6 唯一本地地址（`fc00::/7`）与各类测试网段。
3. **实际连接目标校验**：下载客户端使用自定义地址解析器，在 Netty **真正建立连接前**对解析到的每个 IP 再次执行同一套限制，堵住「预检解析到公网、连接时解析到内网」的 DNS rebinding（TOCTOU）时间差。
4. **禁止重定向**：下载客户端关闭自动重定向，遇到 `3xx` 直接拒绝，防止公网地址跳转到内网绕过校验。
5. **超时与体积上限**：为连接、读写与整体响应设置超时，并限制响应体最大 16 MB，避免出站请求长期占用连接、事件循环或内存。

> 说明：默认会拒绝内网/环回地址，因此封面与正文图片需为**公网可访问**的地址。若使用相对路径，请在 Halo「基本设置 - 外部访问地址」中配置可公网访问的站点地址（详见[配置](#配置)）。**若 Halo 本身部署在内网**、图片也位于内网地址，请按[内网部署与图片下载白名单](#内网部署与图片下载白名单)配置白名单。

### 内网部署与图片下载白名单

防 SSRF 的默认策略会拒绝一切指向环回、内网、链路本地与云元数据的地址。这对公网站点是安全的，但对**部署在内网的 Halo**（封面/正文图片的绝对地址解析到内网 IP）会造成同步失败。为此插件提供了一个**显式、默认关闭的内网白名单**，而非「一键放开全部内网」的开关（后者会重新打开审核所指的 SSRF 风险）。

**配置位置**：插件设置 →「微信公众号」→「图片下载内网白名单」（多行文本框）。

**默认行为**：留空 = 不放行任何内网地址（等价于开关关闭，最安全，也是过审的默认状态）。

**支持的四类条目**（每行一个，以 `#` 开头的行或行内 `#` 之后的内容为注释）：

| 类型 | 示例 | 匹配含义 |
| --- | --- | --- |
| 域名精确 | `halo.internal` | 仅放行主机名为 `halo.internal` 的目标 |
| 子域通配 | `*.example.com` | 放行 `example.com` 及其全部子域（如 `img.example.com`） |
| 单个 IP | `192.168.1.10`、`fd00::1` | 按 /32、/128 处理，仅放行该 IP |
| CIDR 网段 | `192.168.0.0/16`、`10.0.0.0/8`、`fd00::/8` | 放行整个网段 |

**配置示例**（内网 Halo，站点地址为 `http://192.168.1.20:8090`，图片均由该服务器提供）：

```text
# 只信任自站内网地址，其余内网/元数据地址仍拒绝
192.168.1.20
# 若整个内网段都有可信图片源，可改用网段（谨慎，范围越大风险越高）：
# 192.168.1.0/24
```

若图片由内网的另一台图床/对象存储提供，则把其域名或 IP/网段加入即可。

**安全提示**：

- 白名单**仅放宽“允许访问受限网段”这一条**，URL 结构校验（仅 http/https、禁 userinfo）、禁止重定向、超时与响应体上限仍然生效。
- 请按**最小必要**原则配置：能用单个 IP 就不用 /24，能用 /24 就不用 /8；切勿为方便而加入 `0.0.0.0/0` 或直接写入云元数据网段。
- 白名单属**全局插件配置**，需管理员权限才能修改；面向普通用户的角色无法通过同步请求影响它。
- 白名单在**预检与连接期两处一致生效**：即便域名重新解析（DNS rebinding），只要目标不在白名单且为受限地址，连接仍会被拒绝。

## 接口

插件对外提供以下自定义接口（需登录控制台，随插件权限校验）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/apis/api.wechat-sync.halo.run/v1alpha1/sync` | 提交同步任务，立即返回 `202`，后台异步执行 |
| `POST` | `/apis/api.wechat-sync.halo.run/v1alpha1/preview` | 按与同步一致的规则美化正文，并返回上传后将使用的作者、原文链接与留言设置，供确认前预览（不调用微信接口、不落库） |
| `GET` | `/apis/api.wechat-sync.halo.run/v1alpha1/status` | 返回全部文章的最近同步状态，键为文章 `name` |

`POST /sync` 请求体示例：

```json
{
  "postName": "my-post",
  "title": "文章标题",
  "digest": "摘要",
  "content": "<p>渲染后的正文 HTML</p>",
  "cover": "/upload/cover.jpg",
  "author": "作者名",
  "permalink": "/archives/my-post"
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

- **后端**：Java 21、Spring WebFlux（响应式 `WebClient`）、Halo Plugin API、[jsoup](https://jsoup.org)（解析正文 HTML、注入内联样式美化排版）、[TwelveMonkeys ImageIO WebP](https://github.com/haraldk/TwelveMonkeys)（webp 解码）
- **前端**：Vue 3、TypeScript、Vite、`@halo-dev/components`、`@halo-dev/api-client`、unplugin-icons
- **构建**：Gradle + `run.halo.plugin.devtools`、pnpm

## 常见问题（FAQ）

**Q：同步失败，提示「微信公众号草稿必须包含封面图」？**
微信草稿**强制要求**封面。请为文章设置封面后重试；若封面是相对路径，请确认已在 Halo 配置「外部访问地址」。

**Q：提示获取 `access_token` 失败？**
检查 AppID / AppSecret 是否正确，以及是否已在公众平台配置 **IP 白名单**（Halo 服务器公网出口 IP）。

**Q：Halo 服务器没有固定公网 IP，白名单总是失效怎么办？**
用一台有固定公网 IP 的服务器做反向代理，把**代理服务器的 IP** 加入微信白名单，并在插件「接口地址」中填入代理地址。详见[接口地址与反向代理](#接口地址与反向代理)。

**Q：提示接口无权限 / 48001 等错误？**
草稿箱、素材管理等接口需要**已认证**的公众号并开通对应权限，未认证或个人订阅号可能无法调用。

**Q：封面或图片是 webp，能同步吗？**
可以。插件会自动把 webp 解码并重编码为微信支持的 `png/jpg` 再上传。

**Q：报 `412 Precondition Failed`？**
这是历史版本的已知问题（微信校验 `Content-Length`，而 Spring 6.1+ 默认分块传输）。当前版本已通过手动构造 multipart 报文并显式设置 `Content-Length` 修复。

**Q：正文里的图片同步后不显示？**
微信会过滤文章正文中的外部图片链接。插件已通过 `media/uploadimg` 将正文图片转存到微信域名；若个别图片转存失败会保留原地址，请确认这些图片可被 Halo 服务器正常访问。

**Q：预览里的效果和草稿最终效果一致吗？**

预览展示的正文美化结果，以及作者、原文链接、留言设置，均来自与同步流程完全相同的解析规则（作者优先插件设置的「默认作者」、留空回退文章作者；原文链接由站点「外部访问地址」与文章路由拼接；留言设置取插件配置），版式按手机端图文观感模拟。正文图片在**提交后**才会真正转存到微信素材库（预览中仍显示原图地址）；字体渲染等细节以公众号后台的「发布预览」为准。

**Q：同步后草稿的排版和网站上不一样？**
这是微信的限制而非缺陷：微信图文会**剥离外部 CSS、`<style>` 与 `class`/`id` 属性**，只保留元素上的行内 `style`。Halo 正文靠主题 class + 外部 CSS 排版，直接塞进草稿会丢样式。插件已在提交前按标签注入微信友好的内联样式做美化；如需完全自定义排版，可在正文编辑器里对元素设置行内样式（其优先级高于插件默认样式）。引用块边框（可开关）与背景色、标题边框、H1–H6 标题颜色、行内代码配色均可在设置的 **正文美化** 标签中调整。

## 许可证

[GPL-3.0](./LICENSE) © 宏尘极客（hcjike）
