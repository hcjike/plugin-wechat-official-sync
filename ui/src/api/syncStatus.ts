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

/** 存在「同步中」记录时的刷新间隔：同步在服务端后台执行，需持续拉取直到出结果。 */
const POLL_INTERVAL = 5000

/**
 * 单轮轮询的最长持续时间：低带宽 + 大量图片时同步可能耗时很久，超过后停止轮询，
 * 避免服务端异常导致状态永远停在「同步中」时无限请求；后续新任务会重新计时。
 */
const POLL_MAX_DURATION = 30 * 60 * 1000

/**
 * 模块级共享缓存：文章列表中每一行的状态字段组件复用同一份数据，避免逐行请求。
 */
const records = ref<Record<string, SyncRecord>>({})
let loaded = false
let pending: Promise<void> | null = null

/** 下一次状态刷新的定时器；非空表示已安排轮询。 */
let pollTimer: ReturnType<typeof setTimeout> | null = null
/** 当前轮询会话的截止时间戳；0 表示未开始计时。 */
let pollDeadline = 0

export function useSyncRecords(): Ref<Record<string, SyncRecord>> {
  return records
}

/** 本地即时写入某篇文章的状态（用于提交后立刻反馈）。 */
export function setLocalRecord(postName: string, record: SyncRecord): void {
  if (!postName) {
    return
  }
  records.value = { ...records.value, [postName]: record }
  if (record.status === 'PENDING') {
    // 提交后立即开始轮询，等待服务端写入最终结果
    schedulePolling()
  }
}

/** 是否仍有「同步中」的记录（需继续轮询）。 */
function hasPending(): boolean {
  return Object.values(records.value).some((record) => record?.status === 'PENDING')
}

/**
 * 有「同步中」记录时安排下一次状态刷新：按固定间隔刷新，
 * 直到所有任务落到最终状态或超过单轮轮询最长持续时间。
 */
function schedulePolling(): void {
  if (pollTimer) {
    return
  }
  if (pollDeadline === 0) {
    pollDeadline = Date.now() + POLL_MAX_DURATION
  }
  if (Date.now() >= pollDeadline) {
    // 超时后结束本次轮询会话，不再自动刷新
    pollDeadline = 0
    return
  }
  pollTimer = setTimeout(() => {
    pollTimer = null
    void loadRecords(true)
  }, POLL_INTERVAL)
}

/** 没有「同步中」记录时停止轮询。 */
function stopPolling(): void {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollDeadline = 0
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
      // 低带宽 + 大量图片时同步耗时可能远超提交后的首次刷新：只要还有「同步中」的
      // 记录就继续轮询，直到拉到成功/失败终态
      if (hasPending()) {
        schedulePolling()
      } else {
        stopPolling()
      }
    })
    .catch(() => {
      // 忽略：接口不可用或无权限时列表不展示状态，不影响其他功能；
      // 若仍有「同步中」记录则继续轮询，等待网络恢复或任务出结果
      if (hasPending()) {
        schedulePolling()
      }
    })
    .finally(() => {
      pending = null
    })
  return pending
}
