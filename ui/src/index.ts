import { VDropdownItem } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import SyncStatusField from './components/SyncStatusField.vue'
import { confirmSyncToWechat } from './utils/syncToWechat'

export default definePlugin({
  extensionPoints: {
    // 文章行操作菜单：同步到公众号
    'post:list-item:operation:create': (post) => {
      return [
        {
          priority: 21,
          component: markRaw(VDropdownItem),
          label: '同步到微信公众号',
          // 需拥有角色模板「发布到微信公众号」的 UI 权限才展示（超管默认拥有）
          permissions: ['plugin:plugin-wechat-official-sync:post:sync'],
          action: (item) => confirmSyncToWechat(item ?? post.value),
        },
      ]
    },
    // 文章列表状态列：展示每篇文章最近一次同步结果（成功/失败/同步中）
    // 字段按 priority 升序排列，Halo 预置的 end 字段最大为 50
    'post:list-item:field:create': (post) => {
      return [
        {
          priority: 30,
          position: 'end',
          component: markRaw(SyncStatusField),
          props: {
            post: post.value,
          },
          hidden: false,
        },
      ]
    },
  },
})
