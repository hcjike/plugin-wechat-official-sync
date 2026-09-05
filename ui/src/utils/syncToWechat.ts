import { axiosInstance, consoleApiClient, type ListedPost } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'
import { loadRecords, setLocalRecord } from '../api/syncStatus'

const SYNC_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/sync'

/**
 * 拉取渲染后的正文并提交同步任务。
 */
async function submitSync(post: ListedPost) {
  const postName = post.post.metadata.name
  // 获取渲染后的正文 HTML（content 字段），缺失时回退到 raw
  const { data } = await consoleApiClient.content.post.fetchPostHeadContent({ name: postName })
  const content: string = data?.content || data?.raw || ''
  await axiosInstance.post(SYNC_URL, {
    postName,
    title: post.post.spec?.title || '',
    digest: '',
    content,
    cover: post.post.spec?.cover || '',
    author: post.owner?.displayName || '',
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
 * 弹出确认框，确认后异步提交同步任务。
 *
 * 使用 Dialog 命令式 API 而非 VModal：操作项组件被 Halo 渲染在下拉菜单的 popper 内，
 * 点击菜单项后 popper 会立即关闭，内嵌的 VModal（默认不 Teleport 到 body）会一并不可见。
 */
export function confirmSyncToWechat(post: ListedPost) {
  if (!post?.post?.metadata?.name) {
    Toast.error('无法获取文章信息，请刷新页面后重试')
    return
  }
  const title = post.post.spec?.title || '（无标题）'
  Dialog.info({
    title: '同步到微信公众号',
    description: `确认将《${title}》同步到微信公众号草稿箱？`,
    confirmText: '确认同步',
    cancelText: '取消',
    onConfirm: async () => {
      try {
        await submitSync(post)
        Toast.success('同步任务已提交，请稍后前往公众号草稿箱查看')
      } catch {
        Toast.error('提交同步任务失败，请检查插件配置或服务端日志')
      }
    },
  })
}
