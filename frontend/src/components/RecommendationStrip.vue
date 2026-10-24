<script setup lang="ts">
import { ArrowRight, MagicStick } from '@element-plus/icons-vue'
import { computed } from 'vue'
import type { PlannedRecommendation } from '@/composables/planningHandoff'
import PlaceThumbnail from './PlaceThumbnail.vue'
const props = defineProps<{ destination: string; recommendations: PlannedRecommendation[] }>()
const emit = defineEmits<{ discover: [query: string] }>()
const cards = computed(() => [
  { type: 'attraction', title: `${props.destination}，适合慢慢逛`, detail: '发现沿途风景，留一点自由时间', query: `推荐${props.destination}适合轻松慢游的景点，考虑旅行日期和天气。` },
  { type: 'restaurant', title: '当地美食，跟着口味去旅行', detail: '寻找地道风味与合适的人均预算', query: `推荐${props.destination}的当地餐厅，给出价格与选择理由。` },
  { type: 'hotel', title: '住得舒服，也离目的地更近', detail: '按行程位置和预算挑选酒店', query: `结合我的预算与行程推荐${props.destination}的酒店。` },
].map(card => { const item = props.recommendations.find(item => item.category === card.type); return { ...card, title: item?.name || card.title, detail: item?.reason || card.detail, image: item?.image_url } }))
</script>
<template><section class="recommendation-strip" aria-label="AI 为你推荐"><button class="recommendation-intro" @click="emit('discover', '')"><el-icon class="ai-spark"><MagicStick /></el-icon><span><strong>AI 为你推荐</strong><small>{{ recommendations.length ? '根据你的旅行偏好生成' : '从天气、口味和偏好开始' }}</small></span></button><button v-for="card in cards" :key="card.type" class="recommendation-link" @click="emit('discover', card.query)"><PlaceThumbnail :src="card.image" :type="card.type" :name="card.title" /><span class="recommendation-copy"><strong>{{ card.title }}</strong><small>{{ card.detail }}</small></span><el-icon><ArrowRight /></el-icon></button></section></template>
<style scoped>
.recommendation-strip{display:grid;grid-template-columns:.85fr 1fr 1fr 1fr;gap:8px;margin-bottom:22px}.recommendation-strip button{text-align:left;cursor:pointer;min-width:0;font:inherit}.recommendation-intro{display:flex;align-items:center;gap:12px;padding:14px;background:#f0f4ff;border:0;border-radius:10px}.recommendation-intro>span{min-width:0}.recommendation-strip strong{display:block;font-size:13px;line-height:1.5;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--text)}.recommendation-strip small{display:block;margin-top:5px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:11px;color:#6e7890}.ai-spark{font-size:25px;color:#7880fb;flex-shrink:0}.recommendation-link{display:flex;align-items:center;gap:10px;padding:9px;background:#fff;border:1px solid #eff1f6;border-radius:10px;min-height:76px}.recommendation-link:hover{border-color:#b9cefc;background:#fcfdff}.recommendation-link>.el-icon{margin-left:auto;flex-shrink:0;color:#7f8ba3;font-size:12px}.recommendation-copy{min-width:0;flex:1}.recommendation-link .place-thumbnail{width:49px;height:49px;border-radius:6px}
@media(max-width:1399px){.recommendation-strip{grid-template-columns:1fr 1fr 1fr}.recommendation-intro{display:none}.recommendation-link{gap:8px}.recommendation-link .place-thumbnail{width:40px;height:46px}}
@media(max-width:759px){.recommendation-strip{display:flex;overflow:auto;gap:8px;margin-bottom:16px;padding-bottom:3px}.recommendation-link{min-width:240px!important}.recommendation-link .place-thumbnail{width:46px}.recommendation-strip strong{font-size:12px}}
</style>
