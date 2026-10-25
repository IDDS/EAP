<script setup lang="ts">
withDefaults(defineProps<{
  currentPage: number
  pageSize: number
  total: number
  pageSizes?: number[]
  showTotal?: boolean
  showJumper?: boolean
}>(), { pageSizes: () => [10, 20, 50, 100] })
const emit = defineEmits<{
  'update:currentPage': [value: number]
  'update:pageSize': [value: number]
}>()
function changeSize(event: Event) {
  emit('update:pageSize', Number((event.target as HTMLSelectElement).value))
  emit('update:currentPage', 1)
}
</script>

<template>
  <div class="page-controls" role="group" aria-label="记录分页">
    <label>每页
      <select :value="pageSize" aria-label="每页记录数" @change="changeSize">
        <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option>
      </select>
    </label>
    <el-pagination :current-page="currentPage" :page-size="pageSize" :total="total"
      :pager-count="5" :layout="`${showTotal ? 'total, ' : ''}prev, pager, next${showJumper ? ', jumper' : ''}`" size="small"
      @update:current-page="emit('update:currentPage', $event)" />
  </div>
</template>

<style scoped>
.page-controls{display:flex;align-items:center;justify-content:flex-end;gap:12px;flex-wrap:wrap;max-width:100%}
label{display:flex;align-items:center;gap:8px;color:var(--console-muted);font-size:13px}
select{min-height:36px;padding:6px 24px 6px 10px;border:1px solid var(--console-line);border-radius:4px;background:var(--console-surface);color:var(--console-ink);font:inherit}
select:focus-visible{outline:2px solid var(--console-accent);outline-offset:2px}
@media(max-width:600px){.page-controls{justify-content:flex-start;gap:8px}select{min-height:44px}.page-controls :deep(.el-pagination){--el-pagination-button-width:28px;--el-pagination-button-height:44px;flex-wrap:wrap}}
</style>
