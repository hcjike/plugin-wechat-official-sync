<script setup lang="ts">
import type { ListedPost } from '@halo-dev/api-client'
import { VEntityField, vTooltip } from '@halo-dev/components'
import { computed, onMounted } from 'vue'
import IconWechatFill from '~icons/ri/wechat-fill'
import { loadRecords, useSyncRecords } from '../api/syncStatus'

const props = defineProps<{
  post: ListedPost
}>()

const records = useSyncRecords()

onMounted(() => {
  loadRecords()
})

const postName = computed(() => props.post?.post?.metadata?.name || '')
const record = computed(() => (postName.value ? records.value[postName.value] : undefined))

const statusText = computed(() => {
  switch (record.value?.status) {
    case 'SUCCESS':
      return '微信公众号同步成功'
    case 'FAILED':
      return '微信公众号同步失败'
    default:
      return '微信公众号同步中'
  }
})

function formatTime(iso?: string): string {
  if (!iso) {
    return ''
  }
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => {
    switch (ch) {
      case '&':
        return '&amp;'
      case '<':
        return '&lt;'
      case '>':
        return '&gt;'
      case '"':
        return '&quot;'
      default:
        return '&#39;'
    }
  })
}

// 复用 Halo 全局的 v-tooltip 指令（floating-vue）：气泡挂载到 body，不受列表 overflow 裁剪，
// 且与 Console 默认提示风格完全一致；内容以 HTML 字符串承载，动态部分做转义
const tooltip = computed(() => {
  const current = record.value
  if (!current) {
    return { content: '', html: true, theme: 'tooltip' }
  }
  const rows = [`<strong>${escapeHtml(statusText.value)}</strong>`]
  if (current.message) {
    rows.push(escapeHtml(current.message))
  }
  if (current.time) {
    rows.push(`时间：${escapeHtml(formatTime(current.time))}`)
  }
  // media_id 属于技术细节（仅成功时写入），无需向用户展示
  return {
    content: `<div style="max-width: 16rem; text-align: left; line-height: 1.6">${rows.join('<br />')}</div>`,
    html: true,
    theme: 'tooltip',
  }
})
</script>

<template>
  <VEntityField v-if="record">
    <template #description>
      <span
        v-tooltip="tooltip"
        class="sync-status"
        :data-status="record.status"
        :aria-label="statusText"
      >
        <IconWechatFill class="sync-status__logo" />
      </span>
    </template>
  </VEntityField>
</template>

<style scoped>
/* 仅保留微信 Logo 底图，颜色区分状态：绿色=成功、红色=失败、橙色=同步中 */
.sync-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #f59e0b;
  cursor: default;
}

.sync-status[data-status='SUCCESS'] {
  color: #07c160;
}

.sync-status[data-status='FAILED'] {
  color: #ef4444;
}

.sync-status__logo {
  display: block;
  font-size: 18px;
}

</style>
