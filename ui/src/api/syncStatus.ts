import { axiosInstance } from '@halo-dev/api-client'
import { ref, type Ref } from 'vue'

export type SyncStatus = 'PENDING' | 'SUCCESS' | 'FAILED'

export interface SyncRecord {
  status: SyncStatus
  message?: string
  time?: string
  mediaId?: string
}

const STATUS_URL = '/apis/api.wechat-sync.halo.run/v1alpha1/status'

/**
 * 模块级共享缓存：文章列表中每一行的状态字段组件复用同一份数据，避免逐行请求。
 */
const records = ref<Record<string, SyncRecord>>({})
let loaded = false
let pending: Promise<void> | null = null

export function useSyncRecords(): Ref<Record<string, SyncRecord>> {
  return records
}

/** 本地即时写入某篇文章的状态（用于提交后立刻反馈）。 */
export function setLocalRecord(postName: string, record: SyncRecord): void {
  if (!postName) {
    return
  }
  records.value = { ...records.value, [postName]: record }
}

/** 拉取全部同步状态；force 为 true 时忽略缓存强制刷新。 */
export function loadRecords(force = false): Promise<void> {
  if (loaded && !force) {
    return Promise.resolve()
  }
  if (pending) {
    return pending
  }
  pending = axiosInstance
    .get<Record<string, SyncRecord>>(STATUS_URL)
    .then(({ data }) => {
      records.value = data || {}
      loaded = true
    })
    .catch(() => {
      // 忽略：接口不可用或无权限时列表不展示状态，不影响其他功能
    })
    .finally(() => {
      pending = null
    })
  return pending
}
