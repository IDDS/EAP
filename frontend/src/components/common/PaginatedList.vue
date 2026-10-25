<template>
  <div class="paginated-list">
    <slot :rows="rows" />
    <div v-if="data.length" class="list-pagination" aria-label="列表分页">
      <span class="list-range" aria-live="polite">已加载 {{ data.length }} 条 · 显示 {{ start + 1 }}–{{ Math.min(start + pageSize, data.length) }} 条</span>
      <PageControls v-if="data.length > 10" v-model:current-page="page" v-model:page-size="pageSize"
        :total="data.length" :page-sizes="[10, 20, 50]"
         />
    </div>
  </div>
</template>

<script setup lang="ts" generic="T">
import PageControls from '@/components/common/PageControls.vue'
import { computed, ref, watch } from 'vue'
const props = defineProps<{ data: T[]; resetKey?: unknown }>()
const page = ref(1)
const pageSize = ref(10)
const pageCount = computed(() => Math.max(1, Math.ceil(props.data.length / pageSize.value)))
const start = computed(() => (Math.min(page.value, pageCount.value) - 1) * pageSize.value)
const rows = computed(() => props.data.slice(start.value, start.value + pageSize.value))
watch(pageCount, count => { page.value = Math.min(page.value, count) })
watch(() => props.resetKey, () => { page.value = 1 })
</script>

<style scoped>
.paginated-list{min-width:0;width:100%}
.list-pagination{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;padding:14px 0 2px}
.list-range{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5}
.list-pagination :deep(.el-pagination){flex-wrap:wrap;row-gap:8px;margin-left:auto}
@media(max-width:600px){.list-pagination{align-items:flex-start}.list-pagination :deep(.el-pagination){margin-left:0;--el-pagination-button-width:26px}.list-pagination :deep(.el-pagination__sizes){margin-right:8px}}
</style>
