<script setup lang="ts">
import { Bell, Search, ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
defineProps<{ username: string; inTrip: boolean }>()
const emit = defineEmits<{ search: [query: string]; signOut: []; settings: [] }>()
const query = ref('')
const input = ref<HTMLInputElement | null>(null)
function shortcut(event: KeyboardEvent) { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); input.value?.focus() } }
function submit() { if (query.value.trim()) emit('search', query.value.trim()) }
onMounted(() => window.addEventListener('keydown', shortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', shortcut))
</script>
<template>
  <header class="global-header">
    <div class="global-brand"><span>旅迹 AI</span><span class="brand-note">用 AI，遇见更好的旅程</span></div>
    <form class="command-bar" role="search" @submit.prevent="submit"><el-icon><Search /></el-icon><input ref="input" v-model="query" :placeholder="inTrip ? '搜索目的地、景点、酒店，或直接向 AI 提问…' : '搜索我的旅行、目的地…'" aria-label="全局搜索" /><kbd v-if="!query">Ctrl K</kbd><button v-else class="icon-button" aria-label="搜索" type="submit"><el-icon><ArrowRight /></el-icon></button></form>
    <div class="global-account">
      <el-popover placement="bottom-end" :width="260" trigger="click"><template #reference><button class="icon-button notification-button" aria-label="通知"><el-icon><Bell /></el-icon></button></template><strong>通知</strong><p class="muted">暂无新通知。规划和调整结果会显示在当前旅行中。</p></el-popover>
      <el-dropdown trigger="click" @command="(command: string) => command === 'logout' ? emit('signOut') : emit('settings')">
        <button class="account-trigger" aria-label="当前账号"><span class="user-avatar">{{ username.slice(0, 1).toUpperCase() }}</span><span class="username">{{ username }}</span><el-icon><ArrowDown /></el-icon></button>
        <template #dropdown><el-dropdown-menu><el-dropdown-item command="settings">账号设置</el-dropdown-item><el-dropdown-item command="logout" divided><span aria-label="退出登录">退出登录</span></el-dropdown-item></el-dropdown-menu></template>
      </el-dropdown>
    </div>
  </header>
</template>
<style scoped>
.global-header{height:68px;display:flex;align-items:center;gap:24px;padding:0 26px;min-width:0}.global-brand{display:flex;align-items:center;gap:18px;flex-shrink:0}.global-brand>span:first-child{font-size:25px;font-weight:750;letter-spacing:-.035em;white-space:nowrap}.brand-note{border-left:1px solid var(--border);padding-left:18px;color:#7b8395;font-size:12px}.command-bar{display:flex;align-items:center;gap:10px;margin:auto;max-width:550px;flex:1;height:40px;padding:0 14px;background:#eef1f6;border-radius:24px;color:#66718a}.command-bar input{border:0;outline:none;background:none;min-width:0;flex:1;font-size:13px;color:var(--text)}.command-bar input::placeholder{color:#747d91}.command-bar:focus-within{outline:2px solid #9ebfff;outline-offset:2px}.command-bar kbd{font-family:inherit;font-size:11px;border:1px solid #d6dce7;border-radius:4px;padding:1px 5px;white-space:nowrap}.global-account{display:flex;gap:17px;align-items:center}.account-trigger{display:flex;gap:10px;align-items:center;background:none;border:0;padding:0;color:var(--text);cursor:pointer}.username{max-width:95px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:600}.notification-button{border-right:1px solid var(--border);border-radius:0;padding-right:17px}.user-avatar{display:grid;place-items:center;width:34px;height:34px;border-radius:50%;background:#e9edfa;color:#475e98;font-weight:650}
@media(max-width:1399px){.brand-note{display:none}.global-header{gap:26px;padding:0 20px}.command-bar{max-width:470px}}
@media(max-width:759px){.global-header{height:62px;padding:0 12px;gap:12px;flex-wrap:wrap}.global-brand>span:first-child{font-size:20px}.username,.notification-button{display:none}.global-account{margin-left:auto}.command-bar{order:3;flex-basis:100%;max-width:none;margin:0;height:38px}.global-header{height:112px;padding-bottom:12px}.account-trigger>.el-icon{display:none}.command-bar kbd{display:none}}
</style>
