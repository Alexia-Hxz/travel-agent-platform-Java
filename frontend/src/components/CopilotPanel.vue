<script setup lang="ts">
import { Close, MagicStick, MoreFilled } from '@element-plus/icons-vue'
import { onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; editor: [] }>()
const narrow = ref(false), panel = ref<HTMLElement | null>(null)
let media: MediaQueryList | undefined, lastFocus: HTMLElement | null = null
function resize() { narrow.value = media?.matches ?? false }
onMounted(() => { if (typeof window.matchMedia === 'function') { media = window.matchMedia('(max-width: 1099px)'); resize(); media.addEventListener('change', resize) } })
onBeforeUnmount(() => media?.removeEventListener('change', resize))
watch(() => props.open, async value => { if (!narrow.value) return; if (value) { lastFocus = document.activeElement as HTMLElement; await nextTick(); panel.value?.focus() } else lastFocus?.focus() })
function keyboard(event: KeyboardEvent) {
  if (!narrow.value || !props.open) return
  if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  if (event.key === 'Tab') { const nodes = Array.from(panel.value?.querySelectorAll<HTMLElement>('button:not([disabled]),textarea,input,a[href],[tabindex="0"]') ?? []).filter(node => node.offsetParent !== null); const first = nodes[0], last = nodes[nodes.length - 1]; if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { event.preventDefault(); last?.focus() } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) { event.preventDefault(); first?.focus() } }
}
</script>
<template><button v-if="open && narrow" class="copilot-backdrop" aria-label="关闭 AI Copilot" @click="emit('close')" /><aside ref="panel" class="copilot-panel" :class="{ 'is-open': open }" :role="narrow ? 'dialog' : 'complementary'" :aria-modal="narrow && open ? true : undefined" :aria-hidden="narrow && !open ? true : undefined" :inert="narrow && !open" aria-label="AI Copilot" tabindex="-1" @keydown="keyboard"><header class="copilot-header"><el-icon class="copilot-spark"><MagicStick /></el-icon><h2>AI Copilot</h2><span class="copilot-beta">BETA</span><el-dropdown trigger="click" @command="emit('editor')"><button class="icon-button" aria-label="Copilot 更多操作"><el-icon><MoreFilled /></el-icon></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="editor">编辑行程与预算明细</el-dropdown-item></el-dropdown-menu></template></el-dropdown><button class="icon-button copilot-close" aria-label="关闭 Copilot" @click="emit('close')"><el-icon><Close /></el-icon></button></header><slot /></aside></template>
<style scoped>.copilot-panel{display:flex;flex-direction:column;min-width:0;background:white;border-radius:12px;padding:20px 18px 18px;height:auto;min-height:0;overflow:hidden;outline:none}.copilot-header{display:flex;gap:10px;align-items:center;margin-bottom:24px}.copilot-header h2{font-size:20px;letter-spacing:-.03em;white-space:nowrap;margin:0;font-weight:650}.copilot-spark{color:#8a6ef8;font-size:24px}.copilot-beta{font-size:10px;color:#7564dc;background:#f0edff;border-radius:6px;padding:4px 6px}.copilot-header>.el-dropdown{margin-left:auto}.copilot-header .icon-button{font-size:16px}.copilot-close{display:none}.copilot-backdrop{position:fixed;inset:0;background:#16233f42;z-index:89;border:0}.copilot-panel :deep(.compact-adjustment){min-height:0;flex:1}.copilot-header .icon-button{width:25px}@media(max-width:1099px){.copilot-panel{display:none;position:fixed;right:0;top:0;bottom:0;width:min(400px,100vw);z-index:90;height:100dvh;min-height:0;border-radius:12px 0 0 12px;padding:24px 18px}.copilot-panel.is-open{display:flex}.copilot-close{display:grid}}</style>
