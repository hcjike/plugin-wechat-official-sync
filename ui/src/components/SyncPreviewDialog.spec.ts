import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SyncPreviewDialog from './SyncPreviewDialog.vue'

// @halo-dev/components 依赖 Halo 运行时的 peer（如 @vueuse/core），测试环境没有、也不需要真实实现
vi.mock('@halo-dev/components', () => ({
  Toast: { success: vi.fn(), error: vi.fn() },
  VButton: { template: '<button><slot /></button>' },
}))

/** 构造预览正文：count 个顶层段落（每个 <p> 一个顶层节点），便于断言分块数量。 */
function buildHtml(count: number) {
  return Array.from({ length: count }, (_, index) => `<p>段落 ${index}</p>`).join('')
}

/** 记录被创建的观察器，用于在测试中手动触发「哨兵进入视口」。 */
let observers: MockObserver[] = []

class MockObserver {
  readonly callback: IntersectionObserverCallback

  readonly targets = new Set<Element>()

  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback
    observers.push(this)
  }

  observe(target: Element) {
    this.targets.add(target)
  }

  unobserve(target: Element) {
    this.targets.delete(target)
  }

  disconnect() {
    this.targets.clear()
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

/** 取正文分段追加用的观察器；未创建时直接报错，避免测试里到处判空。 */
function firstObserver(): MockObserver {
  const observer = observers[0]
  if (!observer) {
    throw new Error('组件未创建 IntersectionObserver')
  }
  return observer
}

/** 模拟哨兵进入（或离开）视口，驱动一批正文的追加。 */
function trigger(observer: MockObserver, isIntersecting = true) {
  observer.callback(
    [{ isIntersecting } as IntersectionObserverEntry],
    observer as unknown as IntersectionObserver,
  )
}

function mountDialog(content: string) {
  return mount(SyncPreviewDialog, {
    props: {
      title: '文章标题',
      loadPreview: async () => ({
        content,
        title: '文章标题',
        digest: '',
        author: '张三',
        sourceUrl: '',
        commentMode: 'close',
        truncatedFields: [],
      }),
      confirmSync: async () => true,
    },
    global: {
      stubs: { VButton: true },
    },
  })
}

/** 取正文宿主（Shadow DOM）中已渲染的顶层段落数量。 */
function renderedParagraphs(host: HTMLElement) {
  return host.shadowRoot?.querySelectorAll('p').length ?? 0
}

describe('SyncPreviewDialog', () => {
  beforeEach(() => {
    observers = []
    vi.stubGlobal('IntersectionObserver', MockObserver)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('长正文只先渲染首屏一批，哨兵进入视口后逐批追加', async () => {
    const wrapper = mountDialog(buildHtml(30))
    await flushPromises()
    const host = wrapper.find('.sync-preview__content').element as HTMLElement

    // 首屏：只渲染第一批
    expect(renderedParagraphs(host)).toBe(12)
    expect(observers).toHaveLength(1)

    trigger(firstObserver())
    await flushPromises()
    expect(renderedParagraphs(host)).toBe(24)

    trigger(firstObserver())
    await flushPromises()
    expect(renderedParagraphs(host)).toBe(30)

    // 全部渲染完后停止观察，不再追加
    expect(firstObserver().targets.size).toBe(0)
    trigger(firstObserver())
    await flushPromises()
    expect(renderedParagraphs(host)).toBe(30)

    wrapper.unmount()
  })

  it('正文中的图片在预览里改为懒加载', async () => {
    const wrapper = mountDialog('<img src="https://example.com/a.png" />')
    await flushPromises()
    const host = wrapper.find('.sync-preview__content').element as HTMLElement
    const img = host.shadowRoot?.querySelector('img')

    expect(img?.getAttribute('loading')).toBe('lazy')
    expect(img?.getAttribute('decoding')).toBe('async')
    wrapper.unmount()
  })

})
