<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'

import { panelCommand, readPanelPreference, writePanelPreference } from '@/composables/panelState'

/**
 * One foldable block of a page: a header that is always visible, and a body that can be
 * put away.
 *
 * The page grew long because every feature kept its whole output on screen at once --
 * the recommendation gallery, the suggested days, the saved places, the daily itinerary,
 * the itinerary editor, the file list and the summary. Folding them is the fix, so the
 * header always says what is inside (title + count) and where the actions live: the
 * buttons sit in `#actions`, OUTSIDE the folded body, so 清空收藏 and 新增项目 stay
 * reachable while the block is closed.
 *
 * The body is hidden with `v-show`, never `v-if`: the images inside keep loading (the
 * planning agent watches them for its timeout fallback), and the DOM the tests and the
 * app rely on stays in place.
 */
const props = withDefaults(
  defineProps<{
    /** Main heading, e.g. 收藏地点. */
    title: string
    /** Stable name for this panel, used to remember the open/closed choice. */
    panelKey: string
    /** Trip id (or another scope) so two trips remember their own choices. */
    scope?: string | number | null
    /** Small eyebrow line above the title, e.g. SAVED PLACES. */
    label?: string
    /** Shown next to the title while the body is put away, e.g. "12 个地点". */
    count?: number | string | null
    /** Heading level, so the page keeps one h2 per feature. */
    headingTag?: 'h2' | 'h3' | 'h4'
    /** Open before the user ever touches it. Long lists pass false. */
    defaultOpen?: boolean
    /**
     * Change this to any new value to open the panel once, for a result that was just
     * produced (a new plan, a new draft). It is deliberately NOT remembered: the user's
     * own fold choice survives the next visit.
     */
    autoOpenToken?: string | number | boolean | null
  }>(),
  {
    scope: null,
    label: '',
    count: null,
    headingTag: 'h3',
    defaultOpen: true,
    autoOpenToken: null,
  },
)

let panelCounter = 0

const storageKey = computed(() => `${props.scope ?? 'global'}:${props.panelKey}`)
const bodyId = `collapsible-body-${(panelCounter += 1)}`
// A result panel may be mounted only after its result arrives. In that case the token
// is already set, so a watcher alone never sees a change and a long result stays folded.
const initialResult = props.autoOpenToken !== null && props.autoOpenToken !== undefined
  && props.autoOpenToken !== 0 && props.autoOpenToken !== false && props.autoOpenToken !== ''
const open = ref(initialResult || (readPanelPreference(storageKey.value) ?? props.defaultOpen))

// A different trip is a different set of panels, so its own stored choice applies.
watch(storageKey, () => {
  open.value = readPanelPreference(storageKey.value) ?? props.defaultOpen
})

watch(
  () => props.autoOpenToken,
  (value, previous) => {
    if (value !== previous) open.value = true
  },
)

watch(panelCommand, (command) => {
  if (!command) return
  open.value = command.action === 'open'
  writePanelPreference(storageKey.value, open.value)
})

function toggle(): void {
  open.value = !open.value
  writePanelPreference(storageKey.value, open.value)
}
</script>

<template>
  <section class="collapsible-panel" :class="{ 'is-collapsed': !open }">
    <div class="panel-head">
      <component :is="headingTag" class="panel-heading">
        <button
          type="button"
          class="panel-toggle"
          :aria-expanded="open"
          :aria-controls="bodyId"
          :aria-label="`${open ? '收起' : '展开'}${title}`"
          @click="toggle"
        >
          <el-icon class="panel-chevron" :class="{ 'is-open': open }"><ArrowDown /></el-icon>
          <span class="panel-titles">
            <span v-if="label" class="panel-label">{{ label }}</span>
            <span class="panel-title">{{ title }}</span>
          </span>
          <span v-if="count !== null && count !== undefined" class="panel-count">{{ count }}</span>
        </button>
      </component>
      <div class="panel-actions">
        <slot name="actions" />
      </div>
    </div>

    <!-- Status lines stay outside the folded body, so "已清空 2 个收藏地点" cannot be hidden. -->
    <div class="panel-status">
      <slot name="status" />
    </div>

    <div v-show="open" :id="bodyId" class="panel-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.collapsible-panel {
  display: block;
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.panel-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.panel-toggle {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  padding: 4px 8px 4px 4px;
  color: #111827;
  font-size: inherit;
  font-weight: inherit;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
}

.panel-toggle:hover {
  background: #f0f4fb;
  border-color: #cddcd7;
}

.panel-toggle:focus-visible {
  outline: 2px solid #246bfd;
  outline-offset: 2px;
}

.panel-chevron {
  color: #6b7890;
  transition: transform 0.15s ease;
}

.panel-chevron.is-open {
  transform: rotate(0deg);
}

.panel-chevron:not(.is-open) {
  transform: rotate(-90deg);
}

.panel-titles {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
}

.panel-label {
  color: #6b7890;
  font-size: 12px;
  font-weight: 700;
}

.panel-title {
  font-size: inherit;
  font-weight: inherit;
}

.panel-count {
  padding: 1px 8px;
  color: #49678f;
  font-size: 12px;
  font-weight: 600;
  background: #eff3f9;
  border-radius: 999px;
}

/*
 * The badge exists to say what is hidden. Once the body is on screen the count is already
 * in it (the 16 rows, the 744 CNY total), so keeping both reads as a duplicate.
 */
.collapsible-panel:not(.is-collapsed) .panel-count {
  display: none;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.panel-status:empty {
  display: none;
}

.panel-status:not(:empty) {
  margin-top: 8px;
}

.panel-body {
  margin-top: 12px;
}
</style>
