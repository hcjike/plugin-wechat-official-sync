import { axiosInstance, consoleApiClient, type ListedPost } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import { createApp } from 'vue'
import SyncPreviewDialog from '../components/SyncPreviewDialog.vue'
import { loadRecords, setLocalRecord } from '../api/syncStatus'

const SYNC_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/sync'
const PREVIEW_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/preview'

/** 预览接口返回：美化后的正文与上传后实际使用的草稿元信息。 */
export interface PreviewPayload {
  /** 美化后的正文 HTML（提交到微信草稿后的大致效果）。 */
  content: string
  /** 草稿作者：插件「默认作者」优先，留空回退文章作者；为空表示未设置。 */
  author: string
  /** 草稿「阅读原文」链接；为空表示不会生成（文章缺少路由或站点未配置「外部访问地址」）。 */
  sourceUrl: string
  /** 留言设置：close=关闭；all=所有人可留言；fans=仅关注的人可留言。 */
  commentMode: string
}

/**
 * 提取上送给服务端的文章字段：预览与提交共用同一来源，保证两者展示 / 提交的元信息一致。
 */
function syncFieldsOf(post: ListedPost) {
  return {
    postName: post.post.metadata.name,
    title: post.post.spec?.title || '',
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
    digest: '',
    content,
  })
  return {
    content: data?.content || '',
    author: data?.author || '',
    sourceUrl: data?.sourceUrl || '',
    commentMode: data?.commentMode || 'close',
  }
}

/**
 * 提交同步任务。
 */
async function submitSync(post: ListedPost, content: string) {
  const postName = post.post.metadata.name
  await axiosInstance.post(SYNC_URL, {
    ...syncFieldsOf(post),
    digest: '',
    content,
  })
  // 立即在列表状态列标记为「同步中」，随后延时刷新以捕获异步的最终结果
  setLocalRecord(postName, {
    status: 'PENDING',
    message: '同步任务已提交，正在处理…',
    time: new Date().toISOString(),
  })
  setTimeout(() => loadRecords(true), 4000)
  setTimeout(() => loadRecords(true), 12000)
}

/**
 * 打开「同步预览」弹窗：先展示美化后的正文（上传到公众号后的大致效果），
 * 用户确认后才提交同步任务；取消则直接关闭、不提交。
 *
 * 弹窗用 createApp 命令式挂载到 body：操作项组件被 Halo 渲染在下拉菜单的 popper 内，
 * 点击菜单项后 popper 会立即关闭，内嵌的 VModal（默认不 Teleport 到 body）会一并不可见；
 * 挂载到 body 的弹窗不受 popper 影响，行为与 Dialog 命令式 API 一致。
 */
export function confirmSyncToWechat(post: ListedPost) {
  if (!post?.post?.metadata?.name) {
    Toast.error('无法获取文章信息，请刷新页面后重试')
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
    } catch {
      Toast.error('提交同步任务失败，请检查插件配置或服务端日志')
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
