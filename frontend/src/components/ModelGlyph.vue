<template>
  <span class="model-glyph" :class="[`model-glyph-${tone}`, `model-glyph-${size}`]" :title="label || providerLabel">
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path class="model-glyph-orbit" d="M16 5.5c5.9 0 10.5 4.7 10.5 10.5S21.9 26.5 16 26.5 5.5 21.8 5.5 16 10.1 5.5 16 5.5Z" />
      <path class="model-glyph-node" d="M16 10.2 20 16l-4 5.8-4-5.8 4-5.8Z" />
      <circle class="model-glyph-dot" cx="16" cy="16" r="2.1" />
      <path class="model-glyph-ray" d="M16 2.8v4M16 25.2v4M2.8 16h4M25.2 16h4" />
    </svg>
    <b v-if="showProvider">{{ providerMark }}</b>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  provider?: string
  label?: string
  size?: 'xs' | 'sm' | 'md' | 'lg'
  showProvider?: boolean
}>(), { provider: '', size: 'sm', showProvider: true })

const normalizedProvider = computed(() => (props.provider || '').toLowerCase())
const tone = computed(() => {
  if (normalizedProvider.value.includes('anthropic') || normalizedProvider.value.includes('claude')) return 'violet'
  if (normalizedProvider.value.includes('google') || normalizedProvider.value.includes('gemini')) return 'coral'
  if (normalizedProvider.value.includes('deepseek')) return 'teal'
  if (normalizedProvider.value.includes('qwen') || normalizedProvider.value.includes('通义')) return 'amber'
  if (normalizedProvider.value.includes('智谱') || normalizedProvider.value.includes('zhipu')) return 'rose'
  return 'blue'
})
const providerLabel = computed(() => props.provider || '企业模型')
const providerMark = computed(() => {
  const value = providerLabel.value
  if (value.includes('Anthropic') || value.includes('Claude')) return 'C'
  if (value.includes('Google') || value.includes('Gemini')) return 'G'
  if (value.toLowerCase().includes('deepseek')) return 'DS'
  if (value.includes('Qwen') || value.includes('通义')) return 'Q'
  if (value.includes('智谱') || value.toLowerCase().includes('zhipu')) return 'Z'
  if (value.toLowerCase().includes('openai') || value.toLowerCase().includes('gpt')) return 'GPT'
  return 'AI'
})
</script>
