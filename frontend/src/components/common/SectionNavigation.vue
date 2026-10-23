<script setup lang="ts" generic="T extends string">
defineProps<{
  modelValue: T
  label: string
  items: readonly { value: T; label: string }[]
}>()
const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
</script>

<template>
  <nav class="section-navigation" :aria-label="label">
    <button
      v-for="item in items"
      :key="item.value"
      type="button"
      :aria-pressed="modelValue === item.value"
      @click="emit('update:modelValue', item.value)"
    >{{ item.label }}</button>
  </nav>
</template>

<style scoped>
.section-navigation { display: flex; flex-wrap: wrap; gap: 4px; padding: 5px; border: 1px solid var(--console-line); border-radius: var(--console-radius); background: var(--console-surface); }
.section-navigation button { flex: 1 1 100px; min-width: 0; min-height: 44px; padding: 10px 16px; border: 1px solid transparent; border-radius: 4px; background: transparent; color: var(--console-muted); font: inherit; font-size: 14px; line-height: 1.5; text-align: center; cursor: pointer; touch-action: manipulation; }
.section-navigation button:hover { background: var(--console-canvas); color: var(--console-ink); }
.section-navigation button[aria-pressed='true'] { border-color: var(--console-line); background: var(--console-accent-soft); color: var(--el-color-primary-dark-2); font-weight: 600; }
.section-navigation button:focus-visible { outline: 2px solid var(--console-accent); outline-offset: -2px; }
@media (max-width: 600px) { .section-navigation { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
