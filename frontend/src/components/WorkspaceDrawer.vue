<script setup lang="ts">
withDefaults(defineProps<{ modelValue: boolean; title: string; enabled?: boolean; size?: string }>(), { enabled: true, size: 'min(760px, 100vw)' })
defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>
<template>
  <el-drawer v-if="enabled" :model-value="modelValue" :title="title" :size="size" class="workspace-drawer" append-to-body :destroy-on-close="false" @update:model-value="$emit('update:modelValue', $event)"><slot /></el-drawer>
  <slot v-else />
</template>
