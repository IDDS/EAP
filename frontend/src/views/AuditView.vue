<template>
  <section class="page-grid audit-page">
    <ConsolePageHeader />

    <el-card shadow="never" class="panel">
      <template #header><div class="panel-header"><span>事件记录</span><span class="muted">按条件查询，打开详情查看变更前后记录</span></div></template>
      <HistoryTable class="audit-history" :fetch-page="EapApi.pageAuditLogs" :statuses="['SUCCESS','FAILED']" placeholder="搜索操作者、动作、资源、Trace ID" @row-click="openDetail">
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
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : row.result === 'FAILED' ? 'danger' : 'info'">{{ row.result === 'SUCCESS' ? '成功' : row.result === 'FAILED' ? '失败' : row.result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="traceId" label="Trace ID" min-width="260" show-overflow-tooltip />
        <el-table-column label="详情" width="96" fixed="right">
          <template #default="{ row }"><el-button link type="primary" :aria-label="`查看审计详情 #${row.id}`" @click.stop="openDetail(row)">查看详情</el-button></template>
        </el-table-column>
        <template #mobile="{ items, loading, error }">
          <ul v-if="items.length" class="audit-record-list" aria-label="审计记录">
            <li v-for="row in items" :key="row.id">
              <div class="audit-record-heading"><strong>{{ row.action }}</strong><el-tag :type="row.result === 'SUCCESS' ? 'success' : row.result === 'FAILED' ? 'danger' : 'info'">{{ row.result === 'SUCCESS' ? '成功' : row.result === 'FAILED' ? '失败' : row.result }}</el-tag></div>
              <time>{{ row.createdAt }}</time>
              <dl class="audit-record-facts">
                <div><dt>操作者</dt><dd>{{ row.actor }}</dd></div>
                <div><dt>资源</dt><dd>{{ row.resourceType }} / {{ row.resourceId || '—' }}</dd></div>
                <div><dt>Trace ID</dt><dd>{{ row.traceId || '未记录' }}</dd></div>
              </dl>
              <el-button type="primary" plain :aria-label="`查看审计详情 #${row.id}`" @click="openDetail(row)">查看详情</el-button>
            </li>
          </ul>
          <p v-else-if="!loading && !error" class="audit-empty" role="status">没有符合条件的审计记录，请调整搜索、状态或时间范围。</p>
          <p v-else-if="loading" class="audit-empty" role="status">正在查询审计记录…</p>
        </template>
      </HistoryTable>
    </el-card>

    <el-drawer v-model="drawerVisible" append-to-body :z-index="4000" class="audit-drawer" title="审计详情" size="min(720px, 100vw)">
      <div v-if="selectedLog" class="audit-detail">
        <div class="audit-detail-heading"><span>记录 #{{ selectedLog.id }}</span><h2>{{ selectedLog.action }}</h2><time>{{ selectedLog.createdAt }}</time></div>
        <dl class="audit-detail-facts">
          <div><dt>操作者</dt><dd>{{ selectedLog.actor }}</dd></div>
          <div><dt>结果</dt><dd><el-tag :type="selectedLog.result === 'SUCCESS' ? 'success' : selectedLog.result === 'FAILED' ? 'danger' : 'info'">{{ selectedLog.result === 'SUCCESS' ? '成功' : selectedLog.result === 'FAILED' ? '失败' : selectedLog.result }}</el-tag></dd></div>
          <div><dt>资源类型</dt><dd>{{ selectedLog.resourceType }}</dd></div>
          <div><dt>资源标识</dt><dd>{{ selectedLog.resourceId || '未记录' }}</dd></div>
          <div class="audit-detail-trace"><dt>Trace ID</dt><dd>{{ selectedLog.traceId || '未记录' }}</dd></div>
        </dl>
        <section class="audit-change" aria-label="变更前"><h3>变更前</h3><pre v-if="selectedLog.beforeJson">{{ selectedLog.beforeJson }}</pre><p v-else class="audit-missing">未记录变更前内容</p></section>
        <section class="audit-change" aria-label="变更后"><h3>变更后</h3><pre v-if="selectedLog.afterJson">{{ selectedLog.afterJson }}</pre><p v-else class="audit-missing">未记录变更后内容</p></section>
      </div>
      <template #footer><el-button @click="drawerVisible = false">关闭详情</el-button></template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import HistoryTable from '@/components/common/HistoryTable.vue'
import { ref } from 'vue'
import { EapApi } from '@/api/eap'
import type { AuditLog } from '@/types/domain'
const drawerVisible=ref(false)
const selectedLog=ref<AuditLog>()
function openDetail(row:AuditLog){selectedLog.value=row;drawerVisible.value=true}
</script>
