<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import TripDashboard from './views/TripDashboard.vue'
import TripDetail from './views/TripDetail.vue'
import LoginView from './views/LoginView.vue'
import AppRail from './components/AppRail.vue'
import GlobalHeader from './components/GlobalHeader.vue'
import WorkspaceDrawer from './components/WorkspaceDrawer.vue'
import { restoreSession, useAuth } from './composables/useAuth'
import type { Trip } from './types/trip'

const selectedTrip = ref<Trip | null>(null)
const { user, restoring, logout } = useAuth()
const detail = ref<InstanceType<typeof TripDetail> | null>(null)
const search = ref(''), active = ref('trips'), settingsOpen = ref(false), guide = ref('')
const username = computed(() => user.value?.display_name || user.value?.username || user.value?.email || user.value?.phone || '旅行者')
watch(() => user.value?.id, () => { selectedTrip.value = null; search.value = ''; active.value = 'trips'; guide.value = '' })
function selectTrip(trip: Trip) { selectedTrip.value = trip; search.value = ''; guide.value = ''; active.value = 'trips' }
function navigate(section: string) {
  if (section === 'settings') { settingsOpen.value = true; return }
  if (section === 'trips') { selectedTrip.value = null; active.value = 'trips'; guide.value = ''; return }
  if (!selectedTrip.value) { guide.value = '先选择一个旅行项目，再使用 AI 发现、收藏和资料。'; return }
  active.value = section; detail.value?.openSection(section)
}
function searchAll(query: string) { if (selectedTrip.value) detail.value?.searchWorkspace(query); else search.value = query }

onMounted(async () => {
  await restoreSession()
  // A different account must never inherit whatever trip was open before signing out.
  selectedTrip.value = null
})

function handleLogout(): void {
  logout()
  selectedTrip.value = null
  settingsOpen.value = false
}
</script>

<template>
  <p v-if="restoring" class="session-restoring" aria-live="polite">正在恢复登录状态…</p>
  <LoginView v-else-if="user === null" />
  <div v-else class="app-shell"><AppRail :active="active" @navigate="navigate" /><div class="app-body"><GlobalHeader :username="username" :in-trip="selectedTrip !== null" @search="searchAll" @sign-out="handleLogout" @settings="settingsOpen = true" /><p v-if="guide" class="app-guide" role="status">{{ guide }}</p><TripDashboard v-if="selectedTrip === null" :search="search" @select-trip="selectTrip" @sign-out="handleLogout" /><TripDetail v-else ref="detail" :key="selectedTrip.id" :trip="selectedTrip" @back="navigate('trips')" @section-closed="active = 'trips'" /></div></div>
  <WorkspaceDrawer v-model="settingsOpen" title="账号与工作台" size="min(420px, 100vw)"><div v-if="user" class="account-settings"><h3>{{ username }}</h3><p class="muted">当前账号 #{{ user.id }}</p><dl><dt>显示语言</dt><dd>简体中文</dd><dt>工作台快捷键</dt><dd>Ctrl / ⌘ + K · 全局搜索</dd><dt>地图</dt><dd>OpenStreetMap · 连线为地点顺序示意</dd></dl><el-button aria-label="退出登录" @click="handleLogout">退出登录</el-button></div></WorkspaceDrawer>
</template>

<style scoped>
.app-body{margin-left:72px;min-width:0}.app-guide{padding:12px 24px;margin:0 22px 10px;background:#edf3ff;color:#3764a8;border-radius:8px;font-size:13px}.account-settings h3{font-size:21px}.account-settings dl{font-size:13px;margin:28px 0}.account-settings dt{color:var(--secondary);margin:18px 0 8px}.account-settings dd{margin:0;line-height:1.7}@media(max-width:759px){.app-body{margin-left:56px}.app-guide{margin:0 12px 10px;padding:12px}}
.session-restoring {
  margin: 0;
  padding: 60px 20px;
  color: #6b7b86;
  font-size: 14px;
  text-align: center;
}
</style>
