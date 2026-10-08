<script setup lang="ts">
import { Toast, VButton } from '@halo-dev/components'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import IconCloseLine from '~icons/ri/close-line'
import IconWechatFill from '~icons/ri/wechat-fill'
import type { PreviewPayload } from '../utils/syncToWechat'

const props = defineProps<{
  /** 文章标题；预览接口返回截断后的草稿标题时以接口返回值为准（见 titleText）。 */
  title: string
  /** 拉取美化后的正文与上传后的草稿元信息（摘要 / 作者 / 原文链接 / 留言设置）。 */
  loadPreview: () => Promise<PreviewPayload>
  /** 提交同步任务；返回是否成功，失败提示已由调用方发出、弹窗保持打开以便重试。 */
  confirmSync: () => Promise<boolean>
}>()

const emit = defineEmits<{
  close: []
}>()

const html = ref('')
/** 上传后将使用的草稿元信息（标题 / 摘要 / 作者 / 原文链接 / 留言设置）。 */
const meta = ref<PreviewPayload | null>(null)

/**
 * 预览中展示的图文标题：优先用服务端返回的（已按微信 32 字上限截断的）标题，
 * 与最终写入草稿的标题保持一致；预览未返回时回退到调用方上送的文章标题。
 */
const titleText = computed(() => meta.value?.title || props.title)
/** 预览正文滚动区（滚轮 / 触屏手势据此判断放行或拦截）。 */
const bodyRef = ref<HTMLElement | null>(null)
/** 正文渲染宿主：正文渲染进它的 Shadow DOM，与 Console 页面样式互相隔离。 */
const contentRef = ref<HTMLElement | null>(null)
/** 正文末尾的哨兵：进入视口附近即追加下一批正文（见 setupLazyAppend）。 */
const sentinelRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

/** 上传后将使用的作者；为空表示插件「默认作者」与文章作者都未设置。 */
const authorText = computed(() => meta.value?.author || '未设置')

/** 加载骨架屏的正文占位行宽度：长短交替，接近真实段落的观感。 */
const SKELETON_LINES = ['100%', '94%', '98%', '62%', '100%', '88%', '96%', '70%', '92%', '56%']


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

/** 微信对草稿字段的长度上限（字）：标题 64、作者 8、摘要 120，与后端截断规则一致。 */
const FIELD_LIMITS = [
  { key: 'title', label: '标题', limit: 64 },
  { key: 'author', label: '作者', limit: 8 },
  { key: 'digest', label: '摘要', limit: 120 },
] as const

/** 本次被服务端按微信上限截断的字段名集合。 */
const truncatedKeys = computed(() => new Set(meta.value?.truncatedFields ?? []))

/** 该字段本次是否因超过微信长度上限被截断。 */
function isFieldTruncated(key: string) {
  return truncatedKeys.value.has(key)
}

/** 字段的微信长度上限（字），用于标识的说明文案。 */
function limitOf(key: string) {
  return FIELD_LIMITS.find((field) => field.key === key)?.limit ?? 0
}

/** 截断标识的悬停说明：说明该字段的上限，以及预览中显示的即截断后最终写入草稿的值。 */
function truncationHint(key: string, label: string) {
  return `${label}超过微信 ${limitOf(key)} 字上限，超出部分已自动截断，预览中显示的即最终写入草稿的内容`
}

/** 被截断字段的展示名（按标题 / 作者 / 摘要顺序）。 */
const truncatedLabels = computed(() =>
  FIELD_LIMITS.filter((field) => isFieldTruncated(field.key)).map((field) => field.label),
)

/** 本次确实有值、需要做长度检查的字段展示名。 */
const checkedLabels = computed(() => {
  const labels: string[] = []
  if (meta.value?.title) {
    labels.push('标题')
  }
  if (meta.value?.author) {
    labels.push('作者')
  }
  if (meta.value?.digest) {
    labels.push('摘要')
  }
  return labels
})

/** 长度检查结论：只有被截断时才提示「哪些字段被截断」，否则明确告知都在上限内。 */
const limitSummary = computed(() => {
  if (truncatedLabels.value.length > 0) {
    return `已按微信长度上限自动截断：${truncatedLabels.value.join('、')}`
  }
  if (checkedLabels.value.length > 0) {
    return `${checkedLabels.value.join('、')}均在微信长度限制内`
  }
  return '暂无可检查的字段'
})

/** 长度检查结论的样式：有字段被截断时用警示色，否则用次要色。 */
const limitsClass = computed(() =>
  truncatedLabels.value.length > 0 ? 'sync-preview__limits--warn' : 'sync-preview__limits--ok',
)

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
 *
 * 这里的颜色只影响预览中的可见性（代码块底色、行号、布局表格的预览补线），
 * 与提交到微信的产物无关：正文仍是原样提交，补线不会进入草稿。
 *
 * 后端 MCP 预览工具（见 src/main/java/com/hcjike/wechatofficialsync/content/WechatPreviewStyles.java）
 * 覆盖同样的两处观感，但做法不同：那里把样式**内联到元素上**（并只保留一处样式来源——不额外下发
 * <style> 块，行号也写成字面数字），因为 AI 对话界面等客户端可能丢弃样式标签、或由 AI 转述 HTML。
 * 两处样式分别维护，修改时须同步。
 */
const PREVIEW_CONTENT_CSS = `/* 预览页没有微信图文加载的全局样式，需补齐微信对原生代码块（code-snippet 结构）的渲染：
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
`

/** 首屏渲染的正文顶层节点数：够铺满一屏即可，其余等滚动接近时再追加。 */
const FIRST_CHUNK_SIZE = 12
/** 后续每批追加的正文顶层节点数。 */
const CHUNK_SIZE = 12
/** 哨兵进入视口前多远开始预加载下一批（px）。 */
const APPEND_MARGIN = '600px 0px'

/** 尚未渲染的正文顶层节点（按文档顺序）。 */
let pendingNodes: ChildNode[] = []
/** 驱动分段追加的观察器；正文换血或弹窗关闭时断开。 */
let appendObserver: IntersectionObserver | null = null

/** 停止分段追加并清理观察器。 */
function stopLazyAppend() {
  appendObserver?.disconnect()
  appendObserver = null
}

/**
 * 把美化后的正文渲染进宿主的 Shadow DOM：页面全局样式影响不到正文，
 * 正文只按自己的行内样式渲染，正文样式也不会外泄影响页面。
 *
 * 超长正文（长文、上百张图）一次性插入会明显卡顿，故按顶层节点分块：
 * 先渲染首屏所需的一批，其余在滚动接近末尾时逐批追加；正文中的图片统一改为
 * 懒加载，未滚到的图片不发起请求。复制 / 提交始终用完整的 html，不受分块影响。
 */
function renderContent() {
  const host = contentRef.value
  if (!host || !html.value) {
    return
  }
  stopLazyAppend()
  const root = host.shadowRoot ?? host.attachShadow({ mode: 'open' })
  const parsed = new DOMParser().parseFromString(html.value, 'text/html')
  // 图片懒加载只作用于预览渲染：复制与提交仍使用原始 html（含原图地址）
  parsed.body.querySelectorAll('img').forEach((img) => {
    img.setAttribute('loading', 'lazy')
    img.setAttribute('decoding', 'async')
  })
  pendingNodes = Array.from(parsed.body.childNodes)
  const style = document.createElement('style')
  style.textContent = PREVIEW_CONTENT_CSS
  root.replaceChildren(style, ...pendingNodes.splice(0, FIRST_CHUNK_SIZE))
  setupLazyAppend()
}

/**
 * 正文分段追加：哨兵（正文末尾）进入视口附近即追加下一批。
 * 追加后哨兵可能仍在预加载范围内、intersecting 状态未变而不再回调，
 * 故重新 observe 一次以继续评估，直到全部正文渲染完。
 */
function setupLazyAppend() {
  const sentinel = sentinelRef.value
  const scroller = bodyRef.value
  if (!sentinel || !scroller || pendingNodes.length === 0) {
    return
  }
  appendObserver = new IntersectionObserver(
    (entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) {
        return
      }
      const root = contentRef.value?.shadowRoot
      const chunk = pendingNodes.splice(0, CHUNK_SIZE)
      if (!root || chunk.length === 0) {
        stopLazyAppend()
        return
      }
      root.append(...chunk)
      if (pendingNodes.length === 0) {
        stopLazyAppend()
        return
      }
      // 哨兵仍在预加载范围内时重新观察，触发下一批的评估
      appendObserver?.unobserve(sentinel)
      appendObserver?.observe(sentinel)
    },
    { root: scroller, rootMargin: APPEND_MARGIN },
  )
  appendObserver.observe(sentinel)
}

// 正文或加载状态变化后（内容区挂载 / 重建）等 DOM 更新完再渲染
watch([html, loading], async () => {
  await nextTick()
  renderContent()
})

/**
 * 复制美化后的正文到剪贴板：写入 text/html（保留行内样式），供用户在同步失败时
 * 直接粘贴到公众号编辑器手动排版；同时写入 text/plain 兜底（不支持富文本的目标）。
 */
async function copyContent() {
  if (!html.value) {
    return
  }
  try {
    await writeRichClipboard(html.value, htmlToPlainText(html.value))
    Toast.success('已复制正文，可直接粘贴到公众号编辑器')
  } catch {
    Toast.error('复制失败，请在预览中手动选择正文后复制')
  }
}

/**
 * 富文本复制：优先用异步剪贴板写入 text/html；浏览器不支持（非安全上下文、旧内核）时
 * 回退到「临时容器选中 + execCommand('copy')」，同样能保留行内样式。
 */
async function writeRichClipboard(html: string, plain: string) {
  if (navigator.clipboard && typeof ClipboardItem !== 'undefined') {
    await navigator.clipboard.write([
      new ClipboardItem({
        'text/html': new Blob([html], { type: 'text/html' }),
        'text/plain': new Blob([plain], { type: 'text/plain' }),
      }),
    ])
    return
  }
  copyViaSelection(html)
}

/** 回退复制：把正文放进视口外的可编辑临时容器、全选后执行 copy，结束后清理选区与容器。 */
function copyViaSelection(html: string) {
  const holder = document.createElement('div')
  holder.innerHTML = html
  holder.setAttribute('contenteditable', 'true')
  holder.style.position = 'fixed'
  holder.style.top = '0'
  holder.style.left = '-9999px'
  document.body.appendChild(holder)
  try {
    const range = document.createRange()
    range.selectNodeContents(holder)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    if (!document.execCommand('copy')) {
      throw new Error('execCommand copy failed')
    }
  } finally {
    window.getSelection()?.removeAllRanges()
    holder.remove()
  }
}

/** 从正文 HTML 提取纯文本，作为剪贴板的 text/plain 兜底内容。 */
function htmlToPlainText(html: string) {
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  return parsed.body?.textContent || ''
}

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
  stopLazyAppend()
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
        <span class="sync-preview__heading">
          <IconWechatFill class="sync-preview__brand" />
          同步预览
        </span>
        <button class="sync-preview__close" type="button" aria-label="关闭" @click="cancel">
          <IconCloseLine />
        </button>
      </header>
      <div ref="bodyRef" class="sync-preview__body">
        <!-- 加载骨架屏：按真实结构占位（标题 / 署名 / 正文行 / 元信息），内容到位时不跳动 -->
        <div
          v-if="loading"
          class="sync-preview__skeleton"
          role="status"
          aria-busy="true"
          aria-live="polite"
        >
          <span class="sync-preview__sr-only">正在生成预览…</span>
          <div class="sync-preview__phone">
            <div class="sync-preview__sk-title"></div>
            <div class="sync-preview__sk-byline"></div>
            <div class="sync-preview__divider" role="separator"></div>
            <div
              v-for="(width, index) in SKELETON_LINES"
              :key="index"
              class="sync-preview__sk-line"
              :style="{ width }"
            ></div>
          </div>
          <div class="sync-preview__details">
            <div v-for="row in 3" :key="row" class="sync-preview__sk-row"></div>
          </div>
        </div>
        <div v-else-if="error" class="sync-preview__status">
          <p class="sync-preview__error">{{ error }}</p>
          <VButton size="sm" @click="load">重新加载</VButton>
        </div>
        <template v-else>
          <div class="sync-preview__phone">
            <!-- 标题超长被截断时，紧随标题标出「已截断」（悬停可看上限与说明） -->
            <h1 class="sync-preview__title">
              <span>{{ titleText }}</span>
              <span
                v-if="isFieldTruncated('title')"
                class="sync-preview__trim-tag"
                :title="truncationHint('title', '标题')"
              >
                已截断
              </span>
            </h1>
            <!-- 标题与正文的分隔线：标题区（含截断标识）与正文视觉分开 -->
            <div class="sync-preview__divider" role="separator"></div>
            <div ref="contentRef" class="sync-preview__content" @click="blockLinkNavigation"></div>
            <!-- 分段渲染的哨兵：正文末尾，滚到附近即追加下一批正文 -->
            <div ref="sentinelRef" class="sync-preview__sentinel" aria-hidden="true"></div>
          </div>
          <!-- 摘要：未填写时整块不显示；单独一张卡片，位于正文与草稿元信息（作者等）之间 -->
          <div v-if="meta?.digest" class="sync-preview__digest">
            <span class="sync-preview__detail-label">摘要</span>
            <span class="sync-preview__detail-value">
              {{ meta.digest }}
              <span
                v-if="isFieldTruncated('digest')"
                class="sync-preview__trim-tag"
                :title="truncationHint('digest', '摘要')"
              >
                已截断
              </span>
            </span>
          </div>
          <div class="sync-preview__details">
            <div class="sync-preview__detail">
              <span class="sync-preview__detail-label">作者</span>
              <span class="sync-preview__detail-value">
                {{ authorText }}
                <span
                  v-if="isFieldTruncated('author')"
                  class="sync-preview__trim-tag"
                  :title="truncationHint('author', '作者')"
                >
                  已截断
                </span>
              </span>
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
          <!-- 长度检查结论：有字段被截断时明确列出，全部在上限内时也给出结论，避免用户不敢确认 -->
          <p class="sync-preview__limits" :class="limitsClass">{{ limitSummary }}</p>
        </template>
      </div>
      <footer class="sync-preview__footer">
        <span class="sync-preview__hint">提交后正文图片将自动转存到微信素材库</span>
        <div class="sync-preview__actions">
          <!-- 同步失败时的兜底出口：把美化后的正文按富文本复制走，手动粘贴到公众号编辑器 -->
          <VButton
            class="sync-preview__copy"
            size="sm"
            :disabled="loading || !html || submitting"
            title="复制美化后的正文（保留行内样式），可粘贴到公众号编辑器手动排版；正文图片仍是原图地址、封面不在复制内容里，微信通常不会自动转存，图片与封面需自行上传处理"
            @click="copyContent"
          >
            复制正文
          </VButton>
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
  /* 加深遮罩并轻微模糊：拉开弹窗与背后文章列表的层级，聚焦预览内容 */
  background: rgb(15 23 42 / 55%);
  -webkit-backdrop-filter: blur(2px);
  backdrop-filter: blur(2px);
}

.sync-preview__panel {
  position: fixed;
  z-index: 2101;
  top: 50%;
  left: 50%;
  display: flex;
  flex-direction: column;
  /* 宽度收窄、高度加高，更接近手机图文的窄长屏观感 */
  width: 560px;
  max-width: calc(100vw - 24px);
  /* 高度上限约 92% 视口且不超过 880px；dvh 兼容手机浏览器动态工具栏，避免弹窗超出屏幕 */
  max-height: min(92vh, 880px);
  max-height: min(92dvh, 880px);
  overflow: hidden;
  background: #fff;
  border-radius: 12px;
  /* 双层阴影：近距描边 + 远距投影，弹窗浮起感更明确 */
  box-shadow:
    0 2px 8px rgb(15 23 42 / 12%),
    0 24px 60px rgb(15 23 42 / 28%);
  transform: translate(-50%, -50%);
  animation: sync-preview-in 0.18s ease-out;
}

@keyframes sync-preview-in {
  from {
    opacity: 0;
    transform: translate(-50%, calc(-50% + 8px));
  }
}

.sync-preview__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  /* 触摸滑动不从标题栏透传到背后列表 */
  touch-action: none;
}

.sync-preview__heading {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

/* 标题栏的微信绿品牌图标：点明这是「同步到公众号」的预览 */
.sync-preview__brand {
  font-size: 17px;
  color: #07c160;
}

.sync-preview__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  font-size: 18px;
  color: #6b7280;
  cursor: pointer;
  background: none;
  border: none;
  border-radius: 6px;
  transition: background-color 0.15s, color 0.15s;
}

.sync-preview__close:hover {
  color: #111827;
  background: #f3f4f6;
}

.sync-preview__body {
  flex: 1;
  /* 卡片与弹窗边框的留白：够分开层级即可，过宽会白白吃掉正文的可用宽度 */
  padding: 6px;
  overflow-y: auto;
  /* 预览滚动到底后不再链式滚动背后的文章列表 */
  overscroll-behavior: contain;
  background: #f1f3f5;
  scrollbar-width: thin;
  scrollbar-color: #c8d0da transparent;
}

.sync-preview__body::-webkit-scrollbar {
  width: 10px;
}

.sync-preview__body::-webkit-scrollbar-thumb {
  background: #c8d0da;
  background-clip: content-box;
  border: 3px solid transparent;
  border-radius: 999px;
}

.sync-preview__body::-webkit-scrollbar-thumb:hover {
  background: #aeb8c6;
  background-clip: content-box;
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

/* 加载骨架屏：占位块共用「浅灰底 + 高光扫过」，结构与真实内容一致，内容到位时不跳动 */
.sync-preview__sk-title,
.sync-preview__sk-byline,
.sync-preview__sk-line,
.sync-preview__sk-row {
  background-color: #eef0f3;
  background-image: linear-gradient(90deg, #eef0f3 25%, #e2e5ea 37%, #eef0f3 63%);
  background-size: 400% 100%;
  border-radius: 4px;
  animation: sync-preview-shimmer 1.4s ease infinite;
}

@keyframes sync-preview-shimmer {
  0% {
    background-position: 100% 50%;
  }

  100% {
    background-position: 0 50%;
  }
}

.sync-preview__sk-title {
  width: 72%;
  height: 26px;
  margin: 0 auto 12px;
}

.sync-preview__sk-byline {
  width: 38%;
  height: 12px;
  margin: 0 auto;
}

.sync-preview__sk-line {
  height: 13px;
  margin-bottom: 11px;
}

.sync-preview__sk-line:last-child {
  margin-bottom: 0;
}

.sync-preview__sk-row {
  width: 100%;
  height: 12px;
}

/* 仅供读屏的提示文案（骨架屏用图形表达加载中，视觉上不重复这段文字） */
.sync-preview__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

/* 摘要卡片：未填写时不渲染；单独占一张卡片，与下方草稿元信息卡片分开 */
.sync-preview__digest {
  display: flex;
  gap: 8px;
  width: 100%;
  padding: 12px 16px;
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  background: #fff;
  /* 细边框 + 双层软阴影：白卡片压在浅灰底上时边界清晰、层级立得住 */
  border: 1px solid #eceff3;
  border-radius: 10px;
  box-shadow:
    0 1px 2px rgb(16 24 40 / 6%),
    0 4px 12px rgb(16 24 40 / 6%);
}

/* 上传后草稿的元信息：作者 / 原文链接 / 留言设置，放在正文下方，宽度与正文卡片对齐 */
.sync-preview__details {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 16px;
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  background: #fff;
  /* 细边框 + 双层软阴影：白卡片压在浅灰底上时边界清晰、层级立得住 */
  border: 1px solid #eceff3;
  border-radius: 10px;
  box-shadow:
    0 1px 2px rgb(16 24 40 / 6%),
    0 4px 12px rgb(16 24 40 / 6%);
}

.sync-preview__detail {
  display: flex;
  gap: 8px;
}

/* 字段名（摘要 / 作者 / 原文链接 / 留言）：加深到中性深灰并略加字重，与取值拉开层次又都清晰可读 */
.sync-preview__detail-label {
  flex: none;
  width: 56px;
  font-weight: 500;
  color: #4b5563;
}

.sync-preview__detail-value {
  flex: 1;
  color: #1f2937;
  word-break: break-all;
}

.sync-preview__detail-value--muted {
  color: #4b5563;
}

/* 原文链接：沿用微信图文链接色系但加深一档（原 #576b95 偏淡），悬停加下划线点明可点击 */
.sync-preview__detail-link {
  flex: 1;
  color: #3f5a8c;
  word-break: break-all;
}

.sync-preview__detail-link:hover {
  text-decoration: underline;
}

/* 正文预览卡片：占满预览区可用宽度（窄屏自适应 100%），贴齐微信图文的观感 */
.sync-preview__phone {
  width: 100%;
  /* 左右留白放宽到 18px，贴近公众号图文的正文边距 */
  padding: 22px 18px;
  background: #fff;
  /* 细边框 + 双层软阴影：白卡片压在浅灰底上时边界清晰、层级立得住 */
  border: 1px solid #eceff3;
  border-radius: 10px;
  box-shadow:
    0 1px 2px rgb(16 24 40 / 6%),
    0 4px 12px rgb(16 24 40 / 6%);
}

/* 与微信图文标题观感一致（对齐美化器对正文 H1 的处理）；下边距交给下方分隔线统一控制 */
.sync-preview__title {
  margin: 0;
  font-size: 22px;
  font-weight: bold;
  line-height: 1.4;
  color: #222;
  text-align: center;
}

/* 分段渲染哨兵：紧贴正文末尾、无视觉呈现，仅用于触发下一批正文的追加 */
.sync-preview__sentinel {
  height: 1px;
  margin-top: -1px;
}

/* 标题与正文的分隔线：中段稍深、两端渐隐的浅灰细线——比正文块级间距更能明确切分标题区与正文区，
   又因两端渐隐、颜色只到中性灰（不到正文黑）而保持克制，不抢标题与正文的视觉焦点 */
.sync-preview__divider {
  height: 1px;
  margin: 18px 0 22px;
  /* 中段加深到 #b6c1ce：原 #c3cbd6 在白底上几乎看不见，切分不出标题区与正文区 */
  background: linear-gradient(
    to right,
    rgb(182 193 206 / 0%),
    #c2ccd8 18%,
    #b6c1ce 50%,
    #c2ccd8 82%,
    rgb(182 193 206 / 0%)
  );
}

/* 「已截断」标识：内联在字段值之后，悬停可查看上限与截断说明 */
.sync-preview__trim-tag {
  display: inline-block;
  padding: 0 4px;
  margin-left: 4px;
  font-size: 11px;
  line-height: 16px;
  /* 小字号标识对比度要求更高：由 #b45309 加深到琥珀深色调 */
  color: #92400e;
  vertical-align: middle;
  background: #fef3c7;
  border-radius: 3px;
  cursor: help;
}

/* 标题是加粗大字号，紧随其后的截断标识需保持常规字重与小字号，避免跟着标题放大 */
.sync-preview__title .sync-preview__trim-tag {
  font-weight: 400;
}

/* 长度检查结论：位于草稿元信息卡片下方 */
.sync-preview__limits {
  width: 100%;
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  text-align: right;
  word-break: break-all;
}

/* 长度结论带状态符号：不看颜色也能分辨「已截断 / 在限制内」 */
.sync-preview__limits--warn {
  color: #92400e;
}

.sync-preview__limits--warn::before {
  content: '⚠ ';
}

.sync-preview__limits--ok {
  color: #4b5563;
}

.sync-preview__limits--ok::before {
  content: '✓ ';
  color: #07c160;
}

.sync-preview__footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-top: 1px solid #e5e7eb;
  /* 触摸滑动不从底栏透传到背后列表 */
  touch-action: none;
}

.sync-preview__hint {
  font-size: 12px;
  color: #525b66;
}

.sync-preview__actions {
  display: flex;
  flex: none;
  gap: 8px;
}

/* 「复制正文」：中等饱和的绿填充 + 深绿字，与「取消（默认灰）/ 确认同步（主色实心）」区分。
   刻意不与主按钮同为实心填充——它是同步失败时的兜底出口，视觉重量低于主操作，
   避免两个同级实心按钮并列削弱「确认同步」的引导性 */
.sync-preview__copy {
  color: #046c3c;
  background-color: #b7ead0;
  border-color: #6ecf9f;
}

.sync-preview__copy:hover:not(:disabled) {
  color: #03522d;
  background-color: #a2e2c2;
  border-color: #3fbe82;
}

.sync-preview__copy:disabled {
  color: #9ca3af;
  background-color: #f4f5f7;
  border-color: #e5e7eb;
}

/* 窄屏（手机操作 Console）：弹窗接近全屏、四周留 8px 边距（不超出屏幕）；收紧文章内边距，让正文尽量占满可用宽度 */
@media (max-width: 520px) {
  .sync-preview__panel {
    max-width: calc(100vw - 16px);
    max-height: calc(100vh - 16px);
    max-height: calc(100dvh - 16px);
  }

  /* 底栏：提示文案换到按钮下方，让「复制正文 / 取消 / 确认同步」三个按钮排得下 */
  .sync-preview__footer {
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .sync-preview__hint {
    order: 1;
    width: 100%;
    text-align: left;
  }

  .sync-preview__phone {
    padding: 20px 14px;
  }
}

/* 正文渲染在 .sync-preview__content 的 Shadow DOM 内（与页面样式完全隔离、只按正文自己的
   行内样式渲染），其专属补充样式——代码块渲染、布局表格预览标记——见脚本中的
   PREVIEW_CONTENT_CSS 常量 */

/* 系统开启「减少动态效果」时关掉入场与骨架屏动画，只保留静态呈现 */
@media (prefers-reduced-motion: reduce) {
  .sync-preview__panel,
  .sync-preview__sk-title,
  .sync-preview__sk-byline,
  .sync-preview__sk-line,
  .sync-preview__sk-row {
    animation: none;
  }
}
</style>
