<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Location, House, ForkSpoon, Promotion, Van } from '@element-plus/icons-vue'
import { resolveAssetUrl } from '@/api/assets'
const props = defineProps<{ src?: string | null; type?: string; name?: string }>()
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
const image = computed(() => resolveAssetUrl(props.src))
const icon = computed(() => ({ hotel: House, restaurant: ForkSpoon, airport: Promotion, station: Van }[props.type ?? ''] ?? Location))
</script>
<template><span class="place-thumbnail" :class="`thumb-${type || 'attraction'}`"><img v-if="image && !failed" :src="image" :alt="name ?? ''" loading="lazy" @error="failed = true" /><span v-else class="thumbnail-fallback" :aria-label="`${name || '地点'} · 暂无照片`" role="img"><el-icon><component :is="icon" /></el-icon></span></span></template>
<style scoped>.place-thumbnail{display:inline-flex;flex-shrink:0;width:74px;height:58px;border-radius:8px;overflow:hidden;background:#eaf0eb;color:#6c8c78;vertical-align:middle}.place-thumbnail img{width:100%;height:100%;object-fit:cover}.thumbnail-fallback{display:grid;width:100%;place-items:center;font-size:23px}.thumb-hotel{background:#eeeae6;color:#938371}.thumb-restaurant{background:#f6eee1;color:#a89065}.thumb-airport,.thumb-station{background:#e8eef8;color:#7c91b3}</style>
