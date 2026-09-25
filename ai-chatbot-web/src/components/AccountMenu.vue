<script setup>
import { computed, nextTick, ref } from 'vue'
import { onClickOutside } from '@vueuse/core'
import { ArrowLeftOnRectangleIcon, ChevronDownIcon, MoonIcon, SunIcon } from '@heroicons/vue/24/outline'
const props = defineProps({ user: Object, dark: Boolean })
const emit = defineEmits(['toggle-theme', 'logout'])
const open = ref(false), control = ref(null), trigger = ref(null), menu = ref(null)
const name = computed(() => props.user?.displayName || '当前用户')
const role = computed(() => props.user?.role === 'ADMIN' ? '管理员' : '成员')
const initial = computed(() => Array.from(name.value)[0].toUpperCase())
onClickOutside(control, () => { open.value = false })
async function show(focus = false) { open.value = !open.value; if (open.value && focus) { await nextTick(); menu.value?.querySelector('button')?.focus() } }
function keydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); open.value = false; trigger.value?.focus() }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = [...menu.value.querySelectorAll('button')]
  const index = buttons.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}
function focusout(event) { if (event.relatedTarget && !control.value?.contains(event.relatedTarget)) open.value = false }
</script>
<template>
  <div ref="control" class="account-control" @focusout="focusout">
    <button ref="trigger" class="account-trigger" type="button" :aria-label="`账户菜单：${name}`" :aria-expanded="open" aria-haspopup="menu" aria-controls="account-menu" @click="show()" @keydown.down.prevent="show(true)" @keydown.esc="open = false">
      <span class="account-avatar" aria-hidden="true">{{ initial }}</span><span class="account-name">{{ name }}</span><ChevronDownIcon />
    </button>
    <Transition name="account-pop">
      <div v-if="open" id="account-menu" ref="menu" class="account-menu" role="menu" aria-label="账户设置" @keydown="keydown">
        <div class="account-summary" role="presentation"><span class="account-avatar large" aria-hidden="true">{{ initial }}</span><div><strong>{{ name }}</strong><small>{{ user?.username }}</small></div><span class="role-badge">{{ role }}</span></div>
        <div class="account-section-label" role="presentation">偏好设置</div>
        <button class="account-item" type="button" role="menuitemcheckbox" :aria-checked="dark" @click="emit('toggle-theme')"><span class="item-icon"><MoonIcon v-if="!dark" /><SunIcon v-else /></span><span>深色模式</span><span class="theme-switch" :class="{ on: dark }" aria-hidden="true"><i></i></span></button>
        <div class="account-divider" role="separator"></div>
        <button class="account-item logout" type="button" role="menuitem" @click="open = false; emit('logout')"><span class="item-icon"><ArrowLeftOnRectangleIcon /></span><span>退出登录</span></button>
      </div>
    </Transition>
  </div>
</template>
<style scoped>
.account-control{position:relative}.account-trigger{display:flex;align-items:center;gap:10px;min-height:42px;max-width:230px;padding:5px 9px 5px 5px;border:1px solid transparent;border-radius:10px;background:transparent;color:var(--text-color);transition:background .15s,border-color .15s}.account-trigger:hover,.account-trigger[aria-expanded=true]{background:var(--surface-subtle);border-color:var(--border-color)}.account-avatar{width:30px;height:30px;flex:none;display:grid;place-items:center;border:1px solid color-mix(in srgb,var(--primary) 14%,var(--border-color));border-radius:9px;background:var(--primary-soft);color:var(--primary);font-size:12px;font-weight:700}.account-name{font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.account-trigger>svg{width:13px;flex:none;color:var(--text-soft);transition:transform .15s}.account-trigger[aria-expanded=true]>svg{transform:rotate(180deg)}.account-menu{position:absolute;top:calc(100% + 10px);right:0;width:264px;max-width:calc(100vw - 24px);padding:7px;background:var(--surface);border:1px solid var(--border-color);border-radius:13px;box-shadow:0 12px 36px rgba(15,23,42,.12),0 2px 6px rgba(15,23,42,.04);z-index:50}.account-summary{display:flex;align-items:center;gap:10px;padding:12px 9px 16px}.account-avatar.large{width:38px;height:38px;border-radius:11px;font-size:15px}.account-summary>div{min-width:0;flex:1}.account-summary strong,.account-summary small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.account-summary strong{font-size:12px;font-weight:650}.account-summary small{font-size:10px;color:var(--text-soft);margin-top:5px}.role-badge{flex:none;padding:3px 6px;border:1px solid var(--border-color);border-radius:5px;color:var(--text-muted);font-size:9px}.account-section-label{padding:10px 10px 5px;border-top:1px solid var(--border-color);font-size:10px;color:var(--text-soft)}.account-item{width:100%;display:flex;align-items:center;gap:10px;min-height:42px;padding:8px 10px;border:0;border-radius:7px;text-align:left;color:var(--text-color);background:transparent;font-size:12px;transition:background .15s}.account-item:hover{background:var(--surface-subtle)}.item-icon{display:grid;place-items:center;width:24px;color:var(--text-muted)}.item-icon svg{width:17px}.theme-switch{width:28px;height:16px;border-radius:9px;background:var(--border-color);margin-left:auto;padding:2px;box-sizing:border-box;transition:background .15s}.theme-switch i{display:block;width:12px;height:12px;border-radius:50%;background:var(--surface);box-shadow:0 1px 3px rgba(0,0,0,.18);transition:transform .15s}.theme-switch.on{background:var(--primary)}.theme-switch.on i{transform:translateX(12px);background:#fff}.account-divider{height:1px;background:var(--border-color);margin:6px 8px}.logout{color:var(--danger)}.logout .item-icon{color:inherit}.logout:hover{background:color-mix(in srgb,var(--danger) 7%,var(--surface))}.account-trigger:focus-visible,.account-item:focus-visible{outline:2px solid var(--primary);outline-offset:2px}.account-pop-enter-active,.account-pop-leave-active{transition:opacity .14s,transform .14s;transform-origin:top right}.account-pop-enter-from,.account-pop-leave-to{opacity:0;transform:translateY(-4px) scale(.98)}@media(max-width:1100px){.account-name{display:none}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}
</style>
