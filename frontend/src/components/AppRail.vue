<script setup lang="ts">
import { House, Compass, Star, Document, Collection, Sunny, Setting } from '@element-plus/icons-vue'
import AppLogo from './AppLogo.vue'
withDefaults(defineProps<{ active?: string }>(), { active: 'trips' })
const emit = defineEmits<{ navigate: [section: string] }>()
const entries = [
  { id: 'trips', label: '我的旅行', icon: House },
  { id: 'planning', label: 'AI 发现', icon: Compass },
  { id: 'places', label: '收藏地点', icon: Star },
  { id: 'files', label: '上传资料', icon: Document },
  { id: 'templates', label: '行程模板', icon: Collection },
  { id: 'inspiration', label: '旅行灵感', icon: Sunny },
]
</script>
<template>
  <nav class="app-rail" aria-label="主导航">
    <button class="rail-brand" aria-label="旅迹 AI · 我的旅行" @click="emit('navigate', 'trips')"><AppLogo /></button>
    <div class="rail-entries">
      <el-tooltip v-for="entry in entries" :key="entry.id" :content="entry.label" placement="right" :show-after="350">
        <button class="rail-button" :class="{ active: active === entry.id }" :aria-label="entry.label" :aria-current="active === entry.id ? 'page' : undefined" @click="emit('navigate', entry.id)"><el-icon><component :is="entry.icon" /></el-icon></button>
      </el-tooltip>
    </div>
    <el-tooltip content="设置" placement="right"><button class="rail-button rail-settings" aria-label="设置" @click="emit('navigate', 'settings')"><el-icon><Setting /></el-icon></button></el-tooltip>
  </nav>
</template>
<style scoped>
.app-rail{position:fixed;inset:0 auto 0 0;width:72px;z-index:30;display:flex;align-items:center;flex-direction:column;background:#fbfcfe;border-right:1px solid var(--border)}
.rail-brand{border:0;background:none;padding:20px 0 32px;cursor:pointer}.rail-entries{display:flex;flex-direction:column;gap:14px;width:100%;align-items:center}.rail-button{position:relative;display:grid;place-items:center;width:44px;height:42px;padding:0;border:0;border-radius:8px;background:none;color:#58647e;cursor:pointer;font-size:21px}.rail-button:hover{background:var(--surface-hover);color:var(--primary)}.rail-button.active{color:var(--primary);background:#eaf1ff}.rail-button.active:before{content:'';position:absolute;left:-14px;top:6px;bottom:6px;width:3px;border-radius:0 3px 3px 0;background:var(--primary)}.rail-settings{margin-top:auto;margin-bottom:22px}
@media(max-width:759px){.app-rail{width:56px}.rail-brand{padding-top:17px}.rail-button{width:38px}.rail-button.active:before{left:-9px}.rail-entries{gap:13px}.app-logo{width:30px;height:30px}}
</style>
