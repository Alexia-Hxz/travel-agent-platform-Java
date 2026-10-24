<script setup lang="ts">
import { Aim, ArrowRight, Close, Location, Minus, Plus, Van } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ItineraryItem } from '@/types/itinerary'
import { distanceKm, hasCoordinates } from '@/utils/workspace'
const props = defineProps<{ items: ItineraryItem[]; selectedId: number | null; destination: string }>()
const emit = defineEmits<{ select: [item: ItineraryItem] }>()
const root = ref<HTMLElement | null>(null), width = ref(440), height = ref(520)
const zoom = ref(13), center = ref({ x: .5, y: .5 }), labels = ref(true), detail = ref(false)
const located = computed(() => props.items.map((item, index) => ({ item, index })).filter(({ item }) => hasCoordinates(item.place)))
function project(lat: number, lon: number) { const sin = Math.sin(lat * Math.PI / 180); return { x: (lon + 180) / 360, y: .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI) } }
const worldSize = computed(() => 256 * 2 ** zoom.value)
const origin = computed(() => ({ x: center.value.x * worldSize.value - width.value / 2, y: center.value.y * worldSize.value - (height.value - 70) / 2 }))
const markers = computed(() => located.value.map(({ item, index }) => { const p = project(item.place.latitude!, item.place.longitude!); return { item, number: index + 1, x: p.x * worldSize.value - origin.value.x, y: p.y * worldSize.value - origin.value.y } }))
const segments = computed(() => markers.value.slice(1).flatMap((point, i) => { const previous = markers.value[i]; return point.number === previous.number + 1 ? [`${previous.x},${previous.y} ${point.x},${point.y}`] : [] }))
const totalDistance = computed(() => { if (props.items.length < 2 || located.value.length !== props.items.length) return null; return props.items.slice(1).reduce((sum, item, i) => sum + (distanceKm(props.items[i].place, item.place) ?? 0), 0) })
const selected = computed(() => props.items.find(item => item.id === props.selectedId))
function fit() {
  if (!located.value.length) return
  const points = located.value.map(({ item }) => project(item.place.latitude!, item.place.longitude!))
  const minX = Math.min(...points.map(p => p.x)), maxX = Math.max(...points.map(p => p.x)), minY = Math.min(...points.map(p => p.y)), maxY = Math.max(...points.map(p => p.y))
  center.value = { x: (minX + maxX) / 2, y: (minY + maxY) / 2 }
  const scale = Math.min(Math.max(100, width.value - 145) / Math.max(.00001, maxX - minX), Math.max(100, height.value - 220) / Math.max(.00001, maxY - minY))
  zoom.value = Math.max(2, Math.min(15, Math.log2(scale / 256)))
}
function zoomBy(delta: number) { zoom.value = Math.max(2, Math.min(18, zoom.value + delta)) }
let observer: ResizeObserver | undefined
onMounted(() => { if (typeof ResizeObserver !== 'undefined' && root.value) { observer = new ResizeObserver(entries => { const rect = entries[0].contentRect; if (rect.width > 0) { width.value = rect.width; height.value = rect.height; fit() } }); observer.observe(root.value) } fit() })
onBeforeUnmount(() => { observer?.disconnect() })
watch(located, () => { fit() })
watch(() => props.selectedId, () => { const item = selected.value; if (item && hasCoordinates(item.place)) center.value = project(item.place.latitude!, item.place.longitude!) })
let drag: { x: number; y: number; center: { x: number; y: number } } | null = null
function pointerDown(event: PointerEvent) { if ((event.target as HTMLElement).closest('button, a, label, input')) return; drag = { x: event.clientX, y: event.clientY, center: { ...center.value } }; root.value?.setPointerCapture(event.pointerId) }
function pointerMove(event: PointerEvent) { if (!drag) return; center.value = { x: drag.center.x - (event.clientX - drag.x) / worldSize.value, y: Math.max(0, Math.min(1, drag.center.y - (event.clientY - drag.y) / worldSize.value)) } }
/**
 * Open the stop in AMap, which is reachable from this network.
 *
 * This used to point at `openstreetmap.org`, and that page never finished loading here:
 * the base map could not fetch a single tile and the link sat on a blank tab forever.
 * AMap uses the same two numbers, and `coordinate=wgs84` tells it they are GPS values
 * rather than its own GCJ-02 grid, which is shifted a few hundred metres on the mainland.
 */
function amapLink(item: ItineraryItem) {
  const name = encodeURIComponent(item.place.name)
  return `https://uri.amap.com/marker?position=${item.place.longitude},${item.place.latitude}&name=${name}&coordinate=wgs84&callnative=0&src=lvji-ai`
}

/**
 * The same stop in Baidu Maps, offered beside AMap because the two cover foreign places
 * differently: AMap draws overseas labels in the local language, while Baidu's overseas map
 * is aimed at Chinese readers and labels more of them in Chinese. Which one reads better
 * depends on the destination, so the traveller gets both rather than our guess.
 *
 * Two traps worth keeping in mind. `location` is latitude,longitude here -- the opposite of
 * AMap's `position` -- and `output=html` is what makes this a web page instead of a deep
 * link into the app.
 */
function baiduLink(item: ItineraryItem) {
  const name = encodeURIComponent(item.place.name)
  return `https://api.map.baidu.com/marker?location=${item.place.latitude},${item.place.longitude}&title=${name}&content=${name}&output=html&coord_type=wgs84&src=lvji-ai`
}
</script>
<template>
  <section ref="root" class="trip-map" aria-label="当日行程位置示意图" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="drag = null" @pointercancel="drag = null">
    <svg v-if="located.length" class="map-paths" :viewBox="`0 0 ${width} ${height}`" aria-hidden="true"><polyline v-for="(segment, i) in segments" :key="i" :points="segment" fill="none" stroke="white" stroke-width="6" stroke-linecap="round"/><polyline v-for="(segment, i) in segments" :key="`blue-${i}`" :points="segment" fill="none" stroke="#246BFD" stroke-width="3" stroke-linecap="round" stroke-dasharray="7 4"/></svg>
    <button v-for="marker in markers" :key="marker.item.id" class="map-waypoint" :class="{ active: marker.item.id === selectedId }" :style="{ left: `${marker.x}px`, top: `${marker.y}px` }" :aria-label="`第 ${marker.number} 站 ${marker.item.place.name}`" @click="emit('select', marker.item)"><span>{{ marker.number }}</span><strong v-if="labels">{{ marker.item.place.name }}</strong></button>
    <div class="map-toolbar"><span class="map-mode"><el-icon><Van /></el-icon>路线视图</span><label><input v-model="labels" type="checkbox" />显示地名</label></div>
    <div v-if="located.length" class="map-zoom"><button aria-label="放大地图" :disabled="zoom >= 18" @click="zoomBy(1)"><el-icon><Plus /></el-icon></button><button aria-label="缩小地图" :disabled="zoom <= 2" @click="zoomBy(-1)"><el-icon><Minus /></el-icon></button><button aria-label="显示完整路线" @click="fit"><el-icon><Aim /></el-icon></button></div>
    <div v-if="!located.length" class="map-empty"><el-icon><Location /></el-icon><h3>{{ items.length ? '这些好去处，等待落在地图上' : `${destination}，下一站见` }}</h3><p>{{ items.length ? '当前地点没有经纬度。添加含坐标的地点后，就能在这里查看位置。' : '排入当天行程后，路线将在这里展开。' }}</p><ol v-if="items.length"><li v-for="(item, index) in items" :key="item.id"><span>{{ index + 1 }}</span>{{ item.place.name }}</li></ol></div>
    <div v-if="selected && hasCoordinates(selected.place)" class="map-selection"><strong>{{ selected.place.name }}</strong><span class="map-open"><a :href="amapLink(selected)" target="_blank" rel="noopener noreferrer">高德地图<el-icon><ArrowRight /></el-icon></a><a :href="baiduLink(selected)" target="_blank" rel="noopener noreferrer">百度地图<el-icon><ArrowRight /></el-icon></a></span></div>
    <div class="map-footer"><span class="route-icon"><el-icon><Van /></el-icon></span><div><strong>{{ totalDistance === null ? `${items.length} 个行程地点` : `途经地点约 ${totalDistance.toFixed(1)} 公里` }}</strong><small>{{ located.length ? `大致位置与直线连线示意，非道路导航${located.length < items.length ? ` · ${items.length - located.length} 处缺少坐标` : ''}` : '随每天的行程同步更新' }}</small></div><button class="route-detail" @click="detail = true">查看详细路线<el-icon><ArrowRight /></el-icon></button></div>
    <el-drawer v-model="detail" title="当天的详细路线" size="min(420px, 100vw)" append-to-body><ol class="route-detail-list"><li v-for="(item, i) in items" :key="item.id"><span>{{ i + 1 }}</span><div><strong>{{ item.place.name }}</strong><p>{{ item.start_time.slice(0,5) }}–{{ item.end_time.slice(0,5) }} · {{ item.place.address || '地址待补充' }}</p><a v-if="hasCoordinates(item.place)" :href="amapLink(item)" target="_blank" rel="noopener noreferrer">高德地图</a><a v-if="hasCoordinates(item.place)" :href="baiduLink(item)" target="_blank" rel="noopener noreferrer">百度地图</a><small v-else>暂无坐标</small></div></li></ol><p v-if="!items.length" class="muted">这一天还没有行程。添加第一站后即可查看。</p><p v-else class="muted">以上按时间排序。位置为大致坐标，实际道路、交通方式和耗时请点上方链接到高德地图确认。</p></el-drawer>
  </section>
</template>
<style scoped>
.trip-map{position:relative;min-width:0;min-height:0;height:100%;overflow:hidden;border-radius:12px;background:#f4f7fb;background-image:radial-gradient(circle,#dbe3ee 1px,transparent 1px);background-size:26px 26px;touch-action:none;isolation:isolate}.map-paths{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}.map-toolbar{position:absolute;top:16px;left:14px;right:53px;display:flex;justify-content:space-between;align-items:center;gap:8px}.map-mode,.map-toolbar label{display:flex;align-items:center;gap:7px;background:white;border-radius:8px;padding:10px 12px;font-size:11px;font-weight:600;white-space:nowrap}.map-mode>.el-icon{font-size:17px}.map-toolbar label{padding:8px;font-size:10px;color:#69748a}.map-toolbar input{accent-color:var(--primary);margin:0}.map-zoom{position:absolute;right:12px;top:15px;display:flex;flex-direction:column;background:white;border-radius:8px;overflow:hidden}.map-zoom button{height:34px;width:32px;display:grid;place-items:center;font-size:16px;border:0;background:white;color:var(--text);cursor:pointer}.map-zoom button+button{border-top:1px solid var(--border)}.map-zoom button:hover{background:#edf3ff}.map-zoom button:disabled{color:#c5cbd5}.map-waypoint{position:absolute;transform:translate(-14px,-18px);display:flex;align-items:center;gap:5px;padding:0;border:0;background:none;cursor:pointer;white-space:nowrap;z-index:1}.map-waypoint>span{display:grid;place-items:center;width:28px;height:32px;background:var(--primary);color:white;border:2px solid white;border-radius:15px 15px 15px 4px;font-size:13px;font-weight:600}.map-waypoint strong{font-size:11px;text-shadow:0 1px white,1px 0 white,-1px 0 white,0 -1px white;color:#344260}.map-waypoint.active{z-index:2}.map-waypoint.active>span{background:#114cc8;outline:3px solid #b6cfff}.map-footer{position:absolute;bottom:24px;left:10px;right:10px;display:flex;gap:10px;align-items:center;padding:16px 12px;background:#fff;border-radius:10px}.route-icon{display:grid;place-items:center;width:33px;height:37px;border-radius:8px;background:#f5f7fb;font-size:19px;flex-shrink:0}.map-footer>div{min-width:0}.map-footer strong{font-size:13px;display:block;margin-bottom:7px}.map-footer small{font-size:10px;color:#788198;display:block;line-height:1.5}.route-detail{display:flex;align-items:center;gap:4px;margin-left:auto;border:1px solid #e6ecf6;background:white;color:var(--primary);border-radius:20px;padding:7px 9px;white-space:nowrap;font-size:10px;cursor:pointer}.map-empty{position:absolute;inset:65px 20px 100px;display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center;color:#668174}.map-empty>.el-icon{font-size:35px;margin-bottom:14px}.map-empty h3{font-size:15px;margin:0 0 10px;color:#495f56}.map-empty p{font-size:12px;line-height:1.7;max-width:265px;margin:0;color:#64786e}.map-empty ol{display:flex;flex-wrap:wrap;justify-content:center;gap:10px;list-style:none;padding:0}.map-empty li{font-size:11px;display:flex;gap:5px;align-items:center}.map-empty li span{background:#dce7e0;border-radius:50%;padding:3px 6px}.map-error button{border:0;background:none;color:var(--primary);text-decoration:underline;cursor:pointer;padding:5px}.map-selection{position:absolute;left:15px;bottom:130px;background:white;display:flex;gap:12px;align-items:center;padding:12px;border-radius:8px;font-size:12px}.map-open{display:flex;gap:10px;align-items:center}.map-selection a{font-size:11px;display:flex;align-items:center;gap:6px}.route-detail-list{list-style:none;margin:0;padding:0}.route-detail-list li{display:flex;gap:15px;padding:18px 0;border-bottom:1px solid var(--border)}.route-detail-list li>span{color:var(--primary);font-weight:650}.route-detail-list p,.route-detail-list small{font-size:12px;color:var(--secondary);line-height:1.6}.route-detail-list a{font-size:12px}.route-detail-list a+a{margin-left:12px}
@media(max-width:1399px){.map-footer{padding:12px 10px;flex-wrap:wrap}.map-footer strong{font-size:12px}.route-detail{margin-left:43px}.map-toolbar label{font-size:9px;padding:8px 5px}.map-mode{padding:10px 8px}.map-footer small{font-size:9px}}
@media(max-width:759px){.trip-map{height:500px}.map-footer{flex-wrap:nowrap}.route-detail{margin-left:auto}.map-toolbar label{font-size:11px}.map-mode{font-size:12px}}
</style>
