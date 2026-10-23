<template>
  <div class="history-pager">
    <el-alert v-if="state.error" :title="state.error" type="error" :closable="false" show-icon>
      <el-button link type="primary" :loading="state.loading" @click="$emit('page', state.requestedPage)">重试</el-button>
    </el-alert>
    <div class="history-pager-controls">
      <span class="muted">第 {{ state.page + 1 }} 页 · 本页 {{ count }} 条<span v-if="!state.hasNext && count"> · 已到末页</span></span>
      <div>
        <el-button size="small" :disabled="state.page === 0 || state.loading" @click="$emit('page', state.page - 1)">上一页</el-button>
        <el-button size="small" :loading="state.loading" :disabled="!state.hasNext || state.loading" @click="$emit('page', state.page + 1)">下一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  state: { page: number; requestedPage: number; loading: boolean; hasNext: boolean; error: string }
  count: number
}>()
defineEmits<{ page: [page: number] }>()
</script>

<style scoped>
.history-pager { margin-top: 12px; }
.history-pager-controls { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; }
</style>
