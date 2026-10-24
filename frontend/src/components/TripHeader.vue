<script setup lang="ts">
import { ArrowDown, Calendar, Document, Edit, Location, MoreFilled, Share, User, Wallet } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import type { Trip } from '@/types/trip'
import type { ItineraryItem } from '@/types/itinerary'
import { itineraryText, peopleCount } from '@/utils/workspace'
const props = defineProps<{ trip: Trip; items: ItineraryItem[] }>()
const emit = defineEmits<{ back: []; summary: []; edit: []; copilot: [] }>()
const people = computed(() => peopleCount(props.trip))
const sharing = ref(false), copied = ref(false)
const content = computed(() => itineraryText(props.trip, props.items))
async function copy() { try { await navigator.clipboard.writeText(content.value); copied.value = true } catch { copied.value = false } }
function exportTrip() {
  const url = URL.createObjectURL(new Blob([content.value], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = `${props.trip.title.replace(/[<>:"/\\|?*]/g, '_')}.md`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <header class="trip-header">
    <div class="trip-heading"><div class="trip-title"><h1>{{ trip.title }}</h1><button class="icon-button title-edit" aria-label="查看旅行信息" title="查看旅行信息" @click="emit('edit')"><el-icon><Edit /></el-icon></button></div><div class="trip-metadata"><span><el-icon><Location /></el-icon>{{ trip.destination }}</span><i>·</i><span><el-icon><Calendar /></el-icon>{{ trip.start_date }} 至 {{ trip.end_date }}</span><i>·</i><span><el-icon><Wallet /></el-icon>{{ trip.budget === null ? '预算待定' : `¥${trip.budget.toLocaleString('zh-CN')}` }}</span><span><el-icon><User /></el-icon>{{ people ? `${people} 人同行` : '同行人数未设置' }}</span></div></div>
    <div class="trip-actions"><div v-if="people" class="traveler-group" :aria-label="`${people} 人同行`"><span v-for="i in Math.min(people, 3)" :key="i" class="traveler-avatar"><el-icon><User /></el-icon></span><span v-if="people > 3" class="traveler-count">+{{ people - 3 }}</span></div><el-button :icon="Share" @click="sharing = true; copied = false">分享</el-button><el-dropdown trigger="click" @command="(value: string) => value === 'export' ? exportTrip() : emit('summary')"><el-button type="primary">导出行程<el-icon class="button-trailing"><ArrowDown /></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="export">下载 Markdown 行程</el-dropdown-item><el-dropdown-item command="summary">生成行程介绍与总结</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-dropdown trigger="click" @command="(value: string) => value === 'back' ? emit('back') : emit('summary')"><button class="icon-button more-action" aria-label="更多旅行操作"><el-icon><MoreFilled /></el-icon></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="summary">行程介绍与总结</el-dropdown-item><el-dropdown-item command="back">返回旅行项目</el-dropdown-item></el-dropdown-menu></template></el-dropdown><button class="button copilot-toggle" @click="emit('copilot')">AI Copilot</button></div>
  </header>
  <el-dialog v-model="sharing" title="分享行程" width="min(560px, calc(100vw - 24px))"><p class="muted">复制当前行程文字，分享给同行伙伴。</p><el-input :model-value="content" type="textarea" :rows="12" readonly aria-label="可复制的行程文字" /><template #footer><el-button :icon="Document" type="primary" @click="copy">{{ copied ? '已复制' : '复制行程' }}</el-button></template></el-dialog>
</template>
<style scoped>
.trip-header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;padding:6px 2px 22px}.trip-heading{min-width:0}.trip-title{display:flex;align-items:center;gap:9px;margin-bottom:12px}.trip-title h1{margin:0;font-size:30px;font-weight:700;letter-spacing:-.025em;line-height:1.25;overflow-wrap:anywhere}.title-edit{font-size:17px;color:#798299}.trip-metadata{display:flex;flex-wrap:wrap;align-items:center;gap:12px;color:#647088;font-size:13px}.trip-metadata span{display:inline-flex;align-items:center;gap:6px}.trip-metadata i{font-style:normal;color:#c5ccd7}.trip-actions{display:flex;align-items:center;gap:10px;padding-top:5px;flex-shrink:0}.trip-actions .el-button+.el-button{margin:0}.traveler-group{display:flex;margin-right:10px;padding-right:16px;border-right:1px solid var(--border)}.traveler-avatar,.traveler-count{display:grid;place-items:center;height:30px;width:30px;border:2px solid white;border-radius:50%;background:#e9eff5;color:#7991aa;margin-left:-8px;font-size:16px}.traveler-avatar:nth-child(2){background:#eee4dc;color:#a58976}.traveler-avatar:nth-child(3){background:#e4ece6;color:#7b9586}.traveler-count{background:#fff;font-size:11px;border:1px solid #dce1ee;color:#68758c;margin-left:-2px}.more-action{border:1px solid var(--border);height:34px;width:36px;font-size:16px}.copilot-toggle{display:none}.button-trailing{margin-left:12px}
@media(min-width:1100px) and (max-width:1399px){.traveler-group{display:none}.trip-header{flex-wrap:wrap;gap:12px}.trip-actions{margin-left:auto;padding-top:0}.trip-metadata{gap:10px}.trip-title h1{font-size:28px}}
@media(max-width:1099px){.copilot-toggle{display:inline-flex}.traveler-group{display:none}}
@media(max-width:759px){.trip-header{flex-direction:column;gap:8px;padding:4px 0 18px}.trip-title h1{font-size:26px}.trip-actions{width:100%;flex-wrap:wrap}.trip-metadata{gap:8px}.trip-metadata i{display:none}.trip-actions>.el-button{padding:8px 11px}.copilot-toggle{margin-left:auto;font-size:12px}}
</style>
