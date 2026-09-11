<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>审计日志</strong>
        <span>追踪控制命令、治理动作、安全决策和资源变更</span>
      </div>

    </div>

    <el-card shadow="never" class="panel">
      <HistoryTable :fetch-page="EapApi.pageAuditLogs" :statuses="['SUCCESS','FAILED']" placeholder="搜索操作者、动作、资源、Trace ID" @row-click="openDetail">
        <el-table-column prop="createdAt" label="时间" width="220" />
        <el-table-column prop="actor" label="操作者" width="150" />
        <el-table-column prop="action" label="动作" min-width="200" />
        <el-table-column label="资源" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.resourceType }}</strong>
            <div class="muted">{{ row.resourceId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'">{{ row.result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="traceId" label="Trace ID" min-width="260" show-overflow-tooltip />
      </HistoryTable>
    </el-card>

    <el-drawer v-model="drawerVisible" title="审计详情" size="520px">
      <div v-if="selectedLog" class="audit-detail">
        <div><span>操作者</span><strong>{{ selectedLog.actor }}</strong></div>
        <div><span>动作</span><strong>{{ selectedLog.action }}</strong></div>
        <div><span>资源</span><strong>{{ selectedLog.resourceType }} / {{ selectedLog.resourceId || '-' }}</strong></div>
        <div><span>结果</span><strong>{{ selectedLog.result }}</strong></div>
        <div><span>Trace</span><strong>{{ selectedLog.traceId }}</strong></div>
        <el-divider />
        <strong>Before</strong>
        <pre>{{ selectedLog.beforeJson || '{}' }}</pre>
        <strong>After</strong>
        <pre>{{ selectedLog.afterJson || '{}' }}</pre>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import HistoryTable from '@/components/common/HistoryTable.vue'
import { ref } from 'vue'
import { EapApi } from '@/api/eap'
import type { AuditLog } from '@/types/domain'
const drawerVisible=ref(false)
const selectedLog=ref<AuditLog>()
function openDetail(row:AuditLog){selectedLog.value=row;drawerVisible.value=true}
</script>
