<template>
  <PaginatedList :data="data" :reset-key="resetKey" v-slot="{ rows }">
    <el-table v-bind="$attrs" :data="rows">
      <template v-for="(_, name) in $slots" #[name]="scope"><slot :name="name" v-bind="scope || {}" /></template>
    </el-table>
  </PaginatedList>
</template>
<script setup lang="ts">
import PaginatedList from './PaginatedList.vue'
defineOptions({ inheritAttrs: false })
defineProps<{ data: any[]; resetKey?: unknown }>()
</script>
