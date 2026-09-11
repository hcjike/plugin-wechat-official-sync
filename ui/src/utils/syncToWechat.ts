import { axiosInstance, consoleApiClient, type ListedPost } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import { createApp } from 'vue'
import SyncPreviewDialog from '../components/SyncPreviewDialog.vue'
import { loadRecords, setLocalRecord } from '../api/syncStatus'

const SYNC_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/sync'
const PREVIEW_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/preview'
const VALIDATE_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/validate'

/** 预览接口返回：美化后的正文与上传后实际使用的草稿元信息。 */
export interface PreviewPayload {
  /** 美化后的正文 HTML（提交到微信草稿后的大致效果）。 */
  content: string
  /** 草稿摘要；为空表示未填写（提交时不传 digest，微信默认抓取正文前 54 个字）。 */
  digest: string
  /** 草稿作者：插件「默认作者」优先，留空回退文章作者；为空表示未设置。 */
  author: string
  /** 草稿「阅读原文」链接；为空表示不会生成（文章缺少路由或站点未配置「外部访问地址」）。 */
  sourceUrl: string
  /** 留言设置：close=关闭；all=所有人可留言；fans=仅关注的人可留言。 */
  commentMode: string
}

/**
 * 读取文章的摘要（Halo「摘要」字段）作为草稿 digest：
 * 空摘要返回空串（服务端不传该字段，由微信默认抓取正文前 54 个字）；
 * 非空时原样上送、不做长度截断，超长摘要完整同步到草稿，由用户在发布时自行取舍。
 */
function digestOf(post: ListedPost): string {
  return post.post.spec?.excerpt?.raw?.trim() || ''
}

/**
 * 提取上送给服务端的文章字段：预览与提交共用同一来源，保证两者展示 / 提交的元信息一致。
 */
function syncFieldsOf(post: ListedPost) {
  return {
    postName: post.post.metadata.name,
    title: post.post.spec?.title || '',
    digest: digestOf(post),
    cover: post.post.spec?.cover || '',
    author: post.owner?.displayName || '',
    // 文章路由（如 /archives/xxx），服务端与站点「外部访问地址」拼为草稿「原文链接」
    permalink: post.post.status?.permalink || '',
  }
}

/**
 * 拉取文章渲染后的正文 HTML（content 字段），缺失时回退到 raw。
 */
async function fetchRenderedContent(post: ListedPost): Promise<string> {
  const postName = post.post.metadata.name
  const { data } = await consoleApiClient.content.post.fetchPostHeadContent({ name: postName })
  return data?.content || data?.raw || ''
}

/**
 * 调用预览接口：返回美化后的正文与上传后的草稿元信息（作者 / 原文链接 / 留言设置）。
 */
async function fetchPreview(post: ListedPost, content: string): Promise<PreviewPayload> {
  const { data } = await axiosInstance.post<Partial<PreviewPayload>>(PREVIEW_URL, {
    ...syncFieldsOf(post),
    content,
  })
  return {
    content: data?.content || '',
    digest: data?.digest || '',
    author: data?.author || '',
    sourceUrl: data?.sourceUrl || '',
    commentMode: data?.commentMode || 'close',
  }
}

/**
 * 同步前预检：微信配置缺失、封面图缺失或无法解析、文章正在同步中等「提交前即可发现」的
 * 已知错误在打开预览前直接报告；返回需要拦截本次同步的错误提示，空数组表示校验通过。
 *
 * 预检接口自身不可用（如网络异常、旧角色未授予 validate 权限的 403）时按通过处理：
 * 预检只是提前提示、尽力而为，服务端在提交同步时仍会兜底校验，不能因预检故障阻断同步。
 */
async function fetchValidationErrors(post: ListedPost): Promise<string[]> {
  try {
    const { data } = await axiosInstance.post<{ errors?: string[] }>(VALIDATE_URL, syncFieldsOf(post))
    return Array.isArray(data?.errors) ? data.errors : []
  } catch {
    return []
  }
}

/**
 * 提交同步任务。
 */
async function submitSync(post: ListedPost, content: string) {
  const postName = post.post.metadata.name
  await axiosInstance.post(SYNC_URL, {
    ...syncFieldsOf(post),
    content,
  })
  // 立即在列表状态列标记为「同步中」，并刷新一次服务端记录（接口返回 202 时服务端已落库 PENDING）；
  // 后续由 syncStatus 的自动轮询持续刷新，直到任务落到成功/失败终态——低带宽 + 大量
  // 图片时上传耗时会超出固定次数的延时刷新，必须轮询才能捕获真正的结果
  setLocalRecord(postName, {
    status: 'PENDING',
    message: '同步任务已提交，正在处理…',
    time: new Date().toISOString(),
  })
  void loadRecords(true)
}

/**
 * 提取服务端返回的错误提示（如「该文章正在同步中」的 409），结构不符时返回空串。
 */
function serverErrorMessage(error: unknown): string {
  const message = (error as { response?: { data?: { message?: unknown } } })?.response?.data?.message
  return typeof message === 'string' ? message : ''
}

/**
 * 「同步到微信公众号」入口：先调用预检接口验证微信配置、封面图与同步状态等已知错误，
 * 有问题直接报告并中止（不打开预览、不提交）；校验通过后才打开「同步预览」弹窗——
 * 弹窗先展示美化后的正文（上传到公众号后的大致效果），用户确认后才提交同步任务；取消则直接关闭、不提交。
 *
 * 弹窗用 createApp 命令式挂载到 body：操作项组件被 Halo 渲染在下拉菜单的 popper 内，
 * 点击菜单项后 popper 会立即关闭，内嵌的 VModal（默认不 Teleport 到 body）会一并不可见；
 * 挂载到 body 的弹窗不受 popper 影响，行为与 Dialog 命令式 API 一致。
 */
export async function confirmSyncToWechat(post: ListedPost) {
  if (!post?.post?.metadata?.name) {
    Toast.error('无法获取文章信息，请刷新页面后重试')
    return
  }
  // 基础预检：不通过时直接提示错误并中止，不进入预览与同步流程
  const errors = await fetchValidationErrors(post)
  if (errors.length > 0) {
    Toast.error(errors.join('；'))
    return
  }
  const title = post.post.spec?.title || '（无标题）'
  // 预览与提交共用同一份正文快照：预览加载成功即缓存，提交时优先复用，避免两次请求内容不一致
  let content = ''
  const loadPreview = async () => {
    content = await fetchRenderedContent(post)
    return fetchPreview(post, content)
  }
  const confirmSync = async (): Promise<boolean> => {
    try {
      await submitSync(post, content || (await fetchRenderedContent(post)))
      Toast.success('同步任务已提交，请稍后前往公众号草稿箱查看')
      return true
    } catch (e) {
      // 服务端拒绝提交（如同步中重复提交）时会返回带 message 的错误体，优先展示
      Toast.error(serverErrorMessage(e) || '提交同步任务失败，请检查插件配置或服务端日志')
      return false
    }
  }
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(SyncPreviewDialog, {
    title,
    loadPreview,
    confirmSync,
    onClose: () => {
      app.unmount()
      container.remove()
    },
  })
  app.mount(container)
}
