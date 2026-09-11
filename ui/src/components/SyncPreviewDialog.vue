<script setup lang="ts">
import { VButton } from '@halo-dev/components'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import IconCloseLine from '~icons/ri/close-line'
import type { PreviewPayload } from '../utils/syncToWechat'

const props = defineProps<{
  /** 文章标题（预览中作为图文标题展示）。 */
  title: string
  /** 拉取美化后的正文与上传后的草稿元信息（作者 / 原文链接 / 留言设置）。 */
  loadPreview: () => Promise<PreviewPayload>
  /** 提交同步任务；返回是否成功，失败提示已由调用方发出、弹窗保持打开以便重试。 */
  confirmSync: () => Promise<boolean>
}>()

const emit = defineEmits<{
  close: []
}>()

const html = ref('')
/** 上传后将使用的草稿元信息（作者 / 原文链接 / 留言设置）。 */
const meta = ref<PreviewPayload | null>(null)
/** 预览正文滚动区（滚轮 / 触屏手势据此判断放行或拦截）。 */
const bodyRef = ref<HTMLElement | null>(null)
/** 正文渲染宿主：正文渲染进它的 Shadow DOM，与 Console 页面样式互相隔离。 */
const contentRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

/** 上传后将使用的作者；为空表示插件「默认作者」与文章作者都未设置。 */
const authorText = computed(() => meta.value?.author || '未设置')

/** 上传后将使用的留言设置文案。 */
const commentText = computed(() => {
  switch (meta.value?.commentMode) {
    case 'all':
      return '开启 · 所有人可留言'
    case 'fans':
      return '开启 · 仅关注的人可留言'
    case 'close':
      return '关闭'
    default:
      return '未设置'
  }
})

/** 请求预览内容；失败时给出提示并允许重试或直接确认同步。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const payload = await props.loadPreview()
    html.value = payload.content
    meta.value = payload
  } catch {
    error.value = '预览生成失败，可重试；也可直接确认同步'
  } finally {
    loading.value = false
  }
}

/**
 * 正文宿主的补充样式：随正文一起渲染进 Shadow DOM（见 renderContent），
 * 与页面样式互相隔离、以普通选择器书写（原 scoped CSS 中的 :deep 规则移到这里）。
 */
const PREVIEW_CONTENT_STYLES = `<style>
/* 预览页没有微信图文加载的全局样式，需补齐微信对原生代码块（code-snippet 结构）的渲染：
   左侧行号列由 CSS 计数器生成行号、右侧代码区每行一个块级 code（长行横向滚动），
   否则行号列与代码行会散架、所有代码行挤成一行 */
.code-snippet__fix {
  display: flex;
  margin: 0.9em 0;
  overflow: hidden;
  font-family: Menlo, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  background: #f7f7f7;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
}

ul.code-snippet__line-index {
  flex: none;
  padding: 12px 8px;
  margin: 0;
  color: #b2b2b2;
  text-align: right;
  list-style: none;
  counter-reset: line;
  user-select: none;
}

ul.code-snippet__line-index li {
  height: 1.7em;
  list-style: none;
}

ul.code-snippet__line-index li::before {
  counter-increment: line;
  content: counter(line);
}

pre.code-snippet__js {
  flex: 1;
  min-width: 0;
  padding: 12px;
  margin: 0;
  overflow-x: auto;
  font-family: inherit;
  background: transparent;
  border: none;
}

pre.code-snippet__js code {
  display: block;
  height: 1.7em;
  font-family: inherit;
  white-space: pre;
}

/* 预览里为「布局表格」（分栏卡片/画廊重建）补上浅灰细边框：提交到微信的这些表格自身无边框，
   预览中补线仅用于确认分栏/画廊已重建为表格布局、并排结构生效，不影响提交到微信的实际产物 */
.wechat-layout-table td {
  border: 1px solid #e6e6e6;
}

/* 上下紧邻的布局表格（相邻的两个分栏卡片/画廊）之间留出间距：微信里每个分栏卡片/画廊各是
   一个独立表格（一个卡片 = 一个表格），紧贴显示时浅灰边框会连成一片、看起来像一个表格 */
.wechat-layout-table + .wechat-layout-table {
  margin-top: 10px;
}
</style>`

/**
 * 把美化后的正文渲染进宿主的 Shadow DOM：页面全局样式影响不到正文，
 * 正文只按自己的行内样式渲染，正文样式也不会外泄影响页面。
 */
function renderContent() {
  const host = contentRef.value
  if (!host || !html.value) {
    return
  }
  const root = host.shadowRoot ?? host.attachShadow({ mode: 'open' })
  root.innerHTML = PREVIEW_CONTENT_STYLES + html.value
}

// 正文或加载状态变化后（内容区挂载 / 重建）等 DOM 更新完再渲染
watch([html, loading], async () => {
  await nextTick()
  renderContent()
})

async function confirm() {
  if (submitting.value || loading.value) {
    return
  }
  submitting.value = true
  try {
    if (await props.confirmSync()) {
      emit('close')
    }
  } finally {
    submitting.value = false
  }
}

function cancel() {
  // 提交过程中不允许关闭，避免用户误以为已取消
  if (submitting.value) {
    return
  }
  emit('close')
}

/**
 * 预览为只读展示：拦截正文内链接的点击跳转。
 * 正文位于 Shadow DOM 内，点击事件的目标会被重定向为宿主元素，
 * 须沿 composedPath 查找真实点击的链接。
 */
function blockLinkNavigation(event: MouseEvent) {
  const clickedLink = event
    .composedPath()
    .some((node) => node instanceof Element && node.tagName === 'A')
  if (clickedLink) {
    event.preventDefault()
  }
}

/**
 * 弹窗的滚轮手势不应透传给背后的文章列表：
 * 只有落在正文滚动区、且该方向仍能滚动时放行，其余（遮罩、标题栏、底栏、滚动到边界后）一律拦截。
 */
function onPreviewWheel(event: WheelEvent) {
  const scroller = bodyRef.value
  if (!scroller || !(event.target instanceof Node) || !scroller.contains(event.target)) {
    event.preventDefault()
    return
  }
  const atTop = scroller.scrollTop <= 0
  const atBottom = scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 1
  if ((event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom)) {
    event.preventDefault()
  }
}

/**
 * 触屏拖拽与滚轮同理：仅放行正文滚动区内的可滚动拖拽，
 * 其余（遮罩、标题栏、底栏，或正文未超出、无需滚动时）一律拦截，避免带动背后的文章列表。
 */
function onPreviewTouchMove(event: TouchEvent) {
  const scroller = bodyRef.value
  const target = event.target
  const inside = scroller !== null && target instanceof Node && scroller.contains(target)
  const scrollable = scroller !== null && scroller.scrollHeight > scroller.clientHeight + 1
  if (!inside || !scrollable) {
    event.preventDefault()
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    cancel()
  }
}

onMounted(() => {
  load()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="sync-preview" @wheel="onPreviewWheel" @touchmove="onPreviewTouchMove">
    <div class="sync-preview__mask" @click.self="cancel"></div>
    <section
      class="sync-preview__panel"
      role="dialog"
      aria-modal="true"
      aria-label="同步预览"
    >
      <header class="sync-preview__header">
        <span class="sync-preview__heading">同步预览</span>
        <button class="sync-preview__close" type="button" aria-label="关闭" @click="cancel">
          <IconCloseLine />
        </button>
      </header>
      <div ref="bodyRef" class="sync-preview__body">
        <div v-if="loading" class="sync-preview__status">
          <span class="sync-preview__spinner"></span>
          <span>正在生成预览…</span>
        </div>
        <div v-else-if="error" class="sync-preview__status">
          <p class="sync-preview__error">{{ error }}</p>
          <VButton size="sm" @click="load">重新加载</VButton>
        </div>
        <template v-else>
          <div class="sync-preview__phone">
            <h1 class="sync-preview__title">{{ title }}</h1>
            <div ref="contentRef" class="sync-preview__content" @click="blockLinkNavigation"></div>
          </div>
          <div class="sync-preview__details">
            <div class="sync-preview__detail">
              <span class="sync-preview__detail-label">作者</span>
              <span class="sync-preview__detail-value">{{ authorText }}</span>
            </div>
            <div class="sync-preview__detail">
              <span class="sync-preview__detail-label">原文链接</span>
              <a
                v-if="meta?.sourceUrl"
                class="sync-preview__detail-link"
                :href="meta.sourceUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ meta.sourceUrl }}
              </a>
              <span v-else class="sync-preview__detail-value sync-preview__detail-value--muted">
                未生成（文章缺少路由或站点未配置「外部访问地址」）
              </span>
            </div>
            <div class="sync-preview__detail">
              <span class="sync-preview__detail-label">留言</span>
              <span class="sync-preview__detail-value">{{ commentText }}</span>
            </div>
          </div>
        </template>
      </div>
      <footer class="sync-preview__footer">
        <span class="sync-preview__hint">提交后正文图片将自动转存到微信素材库</span>
        <div class="sync-preview__actions">
          <VButton size="sm" :disabled="submitting" @click="cancel">取消</VButton>
          <VButton
            size="sm"
            type="primary"
            :loading="submitting"
            :disabled="loading"
            @click="confirm"
          >
            确认同步
          </VButton>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.sync-preview__mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  /* 遮罩上的拖拽/滑动/滚轮不产生默认行为，避免透传到背后的文章列表 */
  touch-action: none;
  user-select: none;
  background: rgb(0 0 0 / 45%);
}

.sync-preview__panel {
  position: fixed;
  z-index: 2101;
  top: 50%;
  left: 50%;
  display: flex;
  flex-direction: column;
  width: 640px;
  max-width: calc(100vw - 32px);
  max-height: min(82vh, 720px);
  overflow: hidden;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 12px 32px rgb(0 0 0 / 18%);
  transform: translate(-50%, -50%);
}

.sync-preview__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #e5e7eb;
  /* 触摸滑动不从标题栏透传到背后列表 */
  touch-action: none;
}

.sync-preview__heading {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.sync-preview__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  font-size: 18px;
  color: #6b7280;
  cursor: pointer;
  background: none;
  border: none;
}

.sync-preview__close:hover {
  color: #111827;
}

.sync-preview__body {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
  /* 预览滚动到底后不再链式滚动背后的文章列表 */
  overscroll-behavior: contain;
  background: #f3f4f6;
}

.sync-preview__status {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  font-size: 13px;
  color: #6b7280;
}

.sync-preview__error {
  margin: 0;
  text-align: center;
}

.sync-preview__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid #d1d5db;
  border-top-color: #111827;
  border-radius: 50%;
  animation: sync-preview-spin 0.8s linear infinite;
}

@keyframes sync-preview-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 上传后草稿的元信息：作者 / 原文链接 / 留言设置，放在正文下方，宽度与正文卡片对齐 */
.sync-preview__details {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 16px;
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgb(0 0 0 / 8%);
}

.sync-preview__detail {
  display: flex;
  gap: 8px;
}

.sync-preview__detail-label {
  flex: none;
  width: 52px;
  color: #9ca3af;
}

.sync-preview__detail-value {
  flex: 1;
  color: #374151;
  word-break: break-all;
}

.sync-preview__detail-value--muted {
  color: #9ca3af;
}

/* 原文链接沿用微信图文链接色（#576b95），可点击在新窗口核实 */
.sync-preview__detail-link {
  flex: 1;
  color: #576b95;
  word-break: break-all;
}

/* 正文预览卡片：占满预览区可用宽度（窄屏自适应 100%），贴齐微信图文的观感 */
.sync-preview__phone {
  width: 100%;
  padding: 20px 14px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgb(0 0 0 / 8%);
}

/* 与微信图文标题观感一致（对齐美化器对正文 H1 的处理） */
.sync-preview__title {
  margin: 0 0 0.9em;
  font-size: 22px;
  font-weight: bold;
  line-height: 1.4;
  color: #222;
  text-align: center;
}

.sync-preview__footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-top: 1px solid #e5e7eb;
  /* 触摸滑动不从底栏透传到背后列表 */
  touch-action: none;
}

.sync-preview__hint {
  font-size: 12px;
  color: #9ca3af;
}

.sync-preview__actions {
  display: flex;
  flex: none;
  gap: 8px;
}

/* 窄屏（手机操作 Console）：收紧文章内边距，让正文尽量占满可用宽度 */
@media (max-width: 520px) {
  .sync-preview__phone {
    padding: 20px 12px;
  }
}

/* 正文渲染在 .sync-preview__content 的 Shadow DOM 内（与页面样式完全隔离、只按正文自己的
   行内样式渲染），其专属补充样式——代码块渲染、布局表格预览标记——见脚本中的
   PREVIEW_CONTENT_STYLES 常量 */
</style>
