<template>
  <section class="page-grid evaluation-workspace">
    <div class="toolbar console-page-heading">
      <div class="toolbar-title">
        <h2>质量评估</h2>
        <span>跟踪运行质量，管理回归样本与评估规则</span>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="permissions.can('manageEvaluations')" type="primary" :loading="syncing" :disabled="writeBusy" @click="synchronize">处理下一批观测</el-button>
        <el-button :loading="loading" :disabled="!permissions.can('viewConsole') || writeBusy" @click="load">刷新</el-button>
      </div>
    </div>

    <nav class="evaluation-tabs" aria-label="评估工作区">
      <button v-for="tab in workspaceTabs" :key="tab.key" :class="{ active: activeTab === tab.key }" :aria-current="activeTab === tab.key ? 'page' : undefined" :aria-label="tab.label" @click="activeTab = tab.key"><span>{{ tab.label }}</span><small aria-hidden="true">{{ tab.description }}</small></button>
    </nav>
    <el-alert v-if="pageError" :title="pageError" type="warning" :closable="false" show-icon />
    <el-alert v-if="!permissions.can('viewConsole')" title="当前账号暂无评估工作区访问权限，请确认登录状态。" type="info" :closable="false" />
    <div v-show="activeTab === 'overview'" class="evaluation-overview">
      <div class="evaluation-kpis">
        <div><el-icon class="kpi-icon blue"><DataAnalysis /></el-icon><div><span>近 {{ summary.windowHours }} 小时评估</span><strong>{{ summary.total }}</strong></div></div>
        <div><el-icon class="kpi-icon green"><CircleCheck /></el-icon><div><span>规则通过率</span><strong>{{ summary.total ? `${passRate}%` : '—' }}</strong></div></div>
        <div><el-icon class="kpi-icon amber"><Warning /></el-icon><div><span>未通过评估</span><strong>{{ summary.failed }}</strong></div></div>
        <div><el-icon class="kpi-icon slate"><Collection /></el-icon><div><span>回归数据集</span><strong>{{ datasets.length }}</strong></div></div>
      </div>
      <div class="evaluation-process-layout">
      <EvaluationRunProgress :job="activeJob" />
    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>通信自动分析</strong><span>{{ contentAnalysis ? (contentAnalysis.enabled ? '已启用' : '已暂停') : '状态暂不可用' }}</span></div></template>
      <p class="annotation-note">根据调用元数据生成时间线和状态评估。</p>
      <div v-if="contentAnalysis" class="evaluation-summary-grid">
        <div><span>待处理</span><strong>{{ contentAnalysis.counts.PENDING || 0 }}</strong></div>
        <div><span>已生成时间线</span><strong>{{ contentAnalysis.counts.DONE || 0 }}</strong></div>
        <div><span>缺少关联标识</span><strong>{{ contentAnalysis.counts.SKIPPED || 0 }}</strong></div>
        <div><span>处理失败</span><strong>{{ contentAnalysis.counts.FAILED || 0 }}</strong></div>
      </div>
      <p class="annotation-note">调用成功不等于任务完成。</p>
      <p v-if="contentAnalysis?.counts.FAILED" class="annotation-note">部分记录重试后仍未完成分析，已采集的内容仍保留。请联系管理员排查。</p>
    </el-card>

      </div>
      <div class="evaluation-overview-note"><span>{{ syncing ? '正在跟踪任务进度' : '每批按已保存进度继续处理' }}</span><el-button v-if="!syncing && activeJob && ['QUEUED', 'RUNNING'].includes(activeJob.status)" link type="primary" @click="resumeJob(activeJob.id)">继续跟踪</el-button><el-button link type="primary" @click="activeTab = 'results'">查看评估结果 →</el-button></div>
    </div>
    <template v-if="activeTab === 'results'">
    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>评估窗口摘要</strong><span>按最近 {{ summary.windowHours }} 小时的完整结果统计，不受结果表展示条数限制</span></div></template>
      <div class="evaluation-summary-grid">
        <div><span>评估总数</span><strong>{{ summary.total }}</strong></div>
        <div><span>通过 / 未通过</span><strong>{{ summary.passed }} / {{ summary.failed }}</strong></div>
        <div><span>平均分数</span><strong>{{ percent(summary.averageScore) }}</strong></div>
        <div><span>平均置信度</span><strong>{{ percent(summary.averageConfidence) }}</strong></div>
      </div>
      <el-table :data="summary.evaluators" size="small" class="evaluation-summary-table">
        <el-table-column prop="name" label="评估器" min-width="180" />
        <el-table-column prop="evaluatorKey" label="标识" min-width="210" />
        <el-table-column label="通过率" width="170"><template #default="{ row }"><el-progress :percentage="Math.round(row.passRate * 100)" :status="row.failed ? 'exception' : 'success'" /></template></el-table-column>
        <el-table-column label="通过 / 未通过" width="140"><template #default="{ row }">{{ row.passed }} / {{ row.failed }}</template></el-table-column>
        <el-table-column label="平均分" width="100"><template #default="{ row }">{{ percent(row.averageScore) }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!summary.evaluators.length" description="当前窗口还没有评估结果" />
    </el-card>

    </template>
    <template v-if="activeTab === 'rules'">
    <div class="content-grid content-grid-wide">
      <el-card shadow="never" class="panel">
        <template #header><div class="panel-heading"><strong>评估器目录</strong><span>规则与适用范围</span></div></template>
        <el-table :data="evaluators" v-loading="loading" size="small">
          <el-table-column prop="name" label="评估器" min-width="150" />
          <el-table-column prop="evaluatorKey" label="标识" min-width="180" />
          <el-table-column prop="level" label="层级" width="100" />
          <el-table-column label="阈值" width="90">
            <template #default="{ row }">{{ row.threshold.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag size="small">{{ row.evaluatorType }}</el-tag></template></el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="permissions.can('manageEvaluations')" shadow="never" class="panel">
        <template #header><div class="panel-heading"><strong>运行一次评估</strong><span>显式执行，不会自动改变现有控制决策</span></div></template>
        <el-form label-position="top" size="small" :disabled="writeBusy">
          <el-form-item label="评估器">
            <el-select v-model="form.evaluatorKey" style="width: 100%" @change="applyExample">
              <el-option v-for="item in evaluators" :key="item.evaluatorKey" :label="`${item.name} · ${item.evaluatorKey}`" :value="item.evaluatorKey" />
            </el-select>
          </el-form-item>
          <div class="form-grid-two">
            <el-form-item label="Trace ID"><el-input v-model="form.traceId" placeholder="可选，例如 trace-001" /></el-form-item>
            <el-form-item label="Span ID"><el-input v-model="form.spanId" placeholder="可选，例如 span-001" /></el-form-item>
          </div>
          <el-form-item label="输入契约 JSON"><el-input v-model="form.inputJson" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="实际结果 JSON"><el-input v-model="form.actualJson" type="textarea" :rows="3" /></el-form-item>
          <el-form-item v-if="form.evaluatorKey === 'EXACT_MATCH'" label="期望结果 JSON"><el-input v-model="form.expectedJson" type="textarea" :rows="2" /></el-form-item>
          <el-button type="primary" :loading="running" @click="run">执行评估</el-button>
        </el-form>
      </el-card>
    </div>

    </template>
    <template v-if="activeTab === 'results'">
    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>最近结果</strong><span>分数、置信度和证据可回溯到 Agent / Trace / Span</span></div></template>
      <el-table :data="results" class="interactive-table" v-loading="loading" size="small" row-key="id" @row-click="openResult">
        <el-table-column prop="evaluatorKey" label="评估器" width="210" />
        <el-table-column prop="traceId" label="Trace" width="180" show-overflow-tooltip />
        <el-table-column label="分数" width="110">
          <template #default="{ row }"><el-progress :percentage="Math.round(row.score * 100)" :status="row.passed ? 'success' : 'exception'" /></template>
        </el-table-column>
        <el-table-column label="结果" width="100"><template #default="{ row }"><el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column>
        <el-table-column prop="rationale" label="理由" min-width="260" show-overflow-tooltip />
        <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatTime(row.evaluatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openResult(row)">详情</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!results.length && !loading" description="还没有评估结果，可先运行一次评估" />
    </el-card>

    </template>
    <template v-if="activeTab === 'overview'">
    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>处理记录</strong><span>最近 20 批 · 点击记录查看过程</span></div></template>
      <el-table :data="jobs" class="interactive-table" v-loading="loading" size="small" @row-click="selectJob">
        <el-table-column prop="jobKey" label="任务" min-width="230" show-overflow-tooltip />
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="jobTagType(row.status)">{{ jobStatus(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="扫描/评估" width="130"><template #default="{ row }">{{ row.scanned }} / {{ row.evaluated }}</template></el-table-column>
        <el-table-column prop="skipped" label="跳过" width="80" />
        <el-table-column prop="failed" label="失败" width="80" />
        <el-table-column label="进度" width="120"><template #default="{ row }">{{ row.processed || 0 }} / {{ row.total || 0 }}</template></el-table-column>
        <el-table-column label="提交时间" width="180"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!jobs.length && !loading" description="还没有后台投影任务" />
    </el-card>

    </template>
    <template v-if="activeTab === 'datasets'">
    <el-card shadow="never" class="panel dataset-library">
      <template #header><div class="panel-heading"><strong>选择数据集</strong><span>版本化管理脱敏样本，并通过人工标签校准评估器</span></div></template>
      <div class="dataset-actions">
        <button v-for="item in datasets" :key="item.datasetKey" class="dataset-item" :class="{ 'is-selected': item.datasetKey === selectedDatasetKey }" type="button" :aria-pressed="item.datasetKey === selectedDatasetKey" :disabled="writeBusy || loading" @click="selectDataset(item.datasetKey)">
          <div><strong>{{ item.name }}</strong><span>{{ item.datasetKey }} · {{ item.version }}</span></div>
          <el-tag size="small">{{ item.itemCount }} 条</el-tag>
        </button>
      </div>
      <p v-if="!datasets.length && !loading" class="annotation-note">{{ permissions.can('manageEvaluations') ? '暂无数据集，可新建数据集并录入回归样本。' : '暂无可查看的数据集。' }}</p>
      <div class="dataset-library-actions">
        <el-button v-if="permissions.can('manageEvaluations')" plain :loading="creatingDataset" :disabled="writeBusy" @click="createDemoDataset">创建示例数据集</el-button>
        <el-button v-if="selectedDatasetKey && permissions.can('manageEvaluations')" type="primary" plain :loading="calibrating" :disabled="writeBusy" @click="runDatasetCalibration">运行数据集校准</el-button>
      </div>
    </el-card>

    </template>
    <EvaluationDatasetEditor v-show="activeTab === 'datasets'" :dataset-key="selectedDatasetKey" :evaluators="evaluators" :busy="evaluationWriteBusy || loading" @locked="editorLocked = $event" @saved="datasetSaved" />
    <template v-if="activeTab === 'datasets'">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-heading">
          <strong>人工标注样本</strong>
          <div class="dataset-annotation-toolbar">
            <el-select v-model="selectedDatasetKey" size="small" placeholder="选择数据集" style="width: 230px" :disabled="editorLocked" @change="selectDataset">
              <el-option v-for="item in datasets" :key="item.datasetKey" :label="`${item.name} · ${item.version}`" :value="item.datasetKey" />
            </el-select>
            <el-input v-if="permissions.can('annotateEvaluations') && permissions.state.user?.mode === 'DEVELOPMENT'" v-model="annotationActor" size="small" placeholder="标注人" style="width: 150px" :disabled="writeBusy" />
            <span v-else-if="permissions.can('annotateEvaluations')" class="annotation-note">标注人使用当前认证身份</span>
          </div>
        </div>
      </template>
      <div class="annotation-note">只保存现有观测模式允许的脱敏/哈希 JSON；人工标签用于后续评估器校准，不会直接改变线上控制决策。</div>
      <el-table :data="datasetItems" v-loading="datasetItemsLoading" size="small" row-key="id">
        <el-table-column prop="itemKey" label="样本" min-width="180" show-overflow-tooltip />
        <el-table-column prop="evaluatorKey" label="评估器" min-width="190" />
        <el-table-column label="来源引用（正文手填）" min-width="250"><template #default="{ row }"><div v-if="row.agentId || row.traceId || row.spanId" class="sample-references"><span>{{ row.sourceReference?.state === 'OBSERVATION_MATCHED' ? '观测引用已核对（保存时快照）' : '手填引用，未核对' }}</span><template v-if="row.sourceReference?.state === 'OBSERVATION_MATCHED'"><span>观测 #{{ row.sourceReference.observationId }} · {{ row.sourceReference.endpointKey }}</span><span>核对时间 {{ row.sourceReference.checkedAt }}</span></template><span v-if="row.agentId">Agent {{ row.agentId }}</span><code v-if="row.traceId">Trace {{ row.traceId }}</code><code v-if="row.spanId">Span {{ row.spanId }}</code></div><span v-else class="muted">未填写</span></template></el-table-column>
        <el-table-column label="人工标签" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.groundTruthLabel" size="small" :type="annotationTagType(row.groundTruthLabel)">{{ annotationLabel(row.groundTruthLabel) }}</el-tag>
            <span v-else class="muted">未标注</span>
          </template>
        </el-table-column>
        <el-table-column prop="annotatedBy" label="标注人" width="130" show-overflow-tooltip />
        <el-table-column prop="annotationRationale" label="标注说明" min-width="220" show-overflow-tooltip />
        <el-table-column v-if="permissions.can('annotateEvaluations')" label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" plain :disabled="writeBusy" @click="annotateItem(row, 'PASS')">通过</el-button>
            <el-button size="small" type="danger" plain :disabled="writeBusy" @click="annotateItem(row, 'FAIL')">失败</el-button>
            <el-button size="small" plain :disabled="writeBusy" @click="annotateItem(row, 'ABSTAIN')">弃权</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!datasetItems.length && !datasetItemsLoading" description="当前数据集还没有样本，可创建示例数据集开始标注" />
    </el-card>

    <el-card v-if="selectedDatasetKey" shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>评估器校准</strong><span>{{ selectedDatasetKey }} · 对比人工标注与规则预测；弃权不计入准确率</span></div></template>
      <div class="button-row">
        <el-select v-model="selectedCalibrationRunId" aria-label="校准历史" style="width: 440px; max-width: 100%" @change="selectCalibrationRun">
          <el-option label="当前标注预览（随标注与评估器变化）" value="" />
          <el-option v-for="run in calibrationRuns" :key="run.runId" :value="run.runId" :label="`${formatTime(run.calculatedAt)} · ${run.datasetVersion} · ${run.evaluatedItems}/${run.totalItems} 条 · ${run.runId.slice(-8)}`" />
        </el-select>
        <el-tag v-if="calibration.runId" type="success" effect="plain">已保存的历史快照</el-tag>
      </div>
      <p class="annotation-note">数据集版本 {{ calibration.datasetVersion || '—' }} · {{ formatTime(calibration.calculatedAt) }}<template v-if="calibration.runId"> · {{ calibration.runId }}；后续标注与评估器变更不会修改本次结果。</template></p>
      <div class="evaluation-summary-grid evaluation-calibration-overview">
        <div><span>样本总数</span><strong>{{ calibration.totalItems }}</strong></div>
        <div><span>已运行 / 跳过</span><strong>{{ calibration.evaluatedItems }} / {{ calibration.skippedItems }}</strong></div>
        <div><span>未标注 / 弃权</span><strong>{{ calibration.unannotatedItems }} / {{ calibration.abstainedItems }}</strong></div>
        <div><span>执行失败</span><strong>{{ calibration.failedItems }}</strong></div>
      </div>
      <div class="evaluation-calibration-metrics">
        <div><span>Precision</span><strong>{{ percent(calibration.precision) }}</strong></div>
        <div><span>Recall</span><strong>{{ percent(calibration.recall) }}</strong></div>
        <div><span>F1</span><strong>{{ percent(calibration.f1) }}</strong></div>
        <div><span>Accuracy</span><strong>{{ percent(calibration.accuracy) }}</strong></div>
      </div>
      <div class="evaluation-calibration-content">
        <div>
          <div class="calibration-section-title">混淆矩阵</div>
          <el-table :data="confusionRows" size="small" border>
            <el-table-column prop="prediction" label="预测 \\ 人工" width="130" />
            <el-table-column prop="pass" label="人工通过 PASS" />
            <el-table-column prop="fail" label="人工失败 FAIL" />
          </el-table>
        </div>
        <div>
          <div class="calibration-section-title">按评估器</div>
          <el-table :data="calibration.evaluators" size="small">
            <el-table-column prop="evaluatorKey" label="评估器" min-width="190" />
            <el-table-column prop="evaluatorVersion" label="版本" width="90" />
            <el-table-column label="P / R / F1" width="150"><template #default="{ row }">{{ percent(row.precision) }} / {{ percent(row.recall) }} / {{ percent(row.f1) }}</template></el-table-column>
            <el-table-column prop="evaluatedItems" label="已运行" width="80" />
          </el-table>
        </div>
      </div>
      <el-collapse v-if="calibrationDetail">
        <el-collapse-item title="本次评估器定义与样本证据" name="snapshot">
          <el-table :data="calibrationDetail.evaluatorDefinitions" size="small">
            <el-table-column prop="evaluatorKey" label="评估器" min-width="180" />
            <el-table-column prop="evaluatorVersion" label="版本" width="90" />
            <el-table-column prop="implementationKey" label="实现" min-width="180" />
            <el-table-column prop="threshold" label="阈值" width="90" />
          </el-table>
          <el-table :data="calibrationDetail.samples" size="small">
            <el-table-column prop="itemKey" label="样本" min-width="150" />
            <el-table-column prop="evaluatorVersion" label="评估器版本" width="110" />
            <el-table-column prop="groundTruthLabel" label="当时 SME 标注" width="130" />
            <el-table-column label="当时预测" width="100"><template #default="{ row }">{{ row.passed == null ? '—' : row.passed ? 'PASS' : 'FAIL' }}</template></el-table-column>
            <el-table-column prop="outcome" label="执行结果" width="110" />
            <el-table-column prop="reason" label="跳过 / 失败原因" min-width="180" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <div class="annotation-note">正文未采集、仅保存哈希或内容已隐藏时会跳过样本；可解析的脱敏 JSON 可用于运行。历史快照仅保存指标、定义和标注预测证据，不保存输入输出正文。</div>
    </el-card>

    </template>
    <el-drawer v-model="detailVisible" title="评估证据详情" size="min(760px, 100vw)">
      <template v-if="selectedResult">
        <div class="evaluation-detail-heading">
          <div>
            <el-tag :type="selectedResult.passed ? 'success' : 'danger'">{{ selectedResult.passed ? '通过' : '未通过' }}</el-tag>
            <strong>{{ selectedResult.evaluatorKey }}</strong>
          </div>
          <p>{{ selectedResult.rationale || '该结果没有额外说明。' }}</p>
        </div>
        <div class="evaluation-detail-grid">
          <div><span>分数</span><strong>{{ percent(selectedResult.score) }}</strong></div>
          <div><span>置信度</span><strong>{{ percent(selectedResult.confidence) }}</strong></div>
          <div><span>层级</span><strong>{{ selectedResult.level }}</strong></div>
          <div><span>版本</span><strong>{{ selectedResult.evaluatorVersion || '-' }}</strong></div>
          <div><span>Agent</span><strong>{{ selectedResult.agentId ?? '未关联' }}</strong></div>
          <div><span>评估时间</span><strong>{{ formatTime(selectedResult.evaluatedAt) }}</strong></div>
        </div>
        <div class="evaluation-detail-identifiers">
          <span>Result Key</span><code>{{ selectedResult.resultKey }}</code>
          <span>Trace ID</span><code>{{ selectedResult.traceId || '未关联 Trace' }}</code>
          <span>Span ID</span><code>{{ selectedResult.spanId || '未关联 Span' }}</code>
        </div>
        <div class="evidence-json-block evaluation-evidence-block"><span>评估证据（脱敏/结构化）</span><pre>{{ prettyJson(selectedResult.evidence) }}</pre></div>
        <div class="evaluation-trace-heading"><strong>关联 Trace / Span</strong><el-button v-if="selectedResult.traceId" link type="primary" @click="openTraceWorkbench">打开 Trace 工作台</el-button></div>
        <TraceEvidenceBars :spans="traceSpans" />
        <el-table v-loading="traceLoading" :data="traceSpans" size="small">
          <el-table-column label="类型" width="92"><template #default="{ row }">{{ traceKindLabel(row.spanKind) }}</template></el-table-column>
          <el-table-column label="Span" min-width="180"><template #default="{ row }"><strong>{{ row.spanName }}</strong><div class="muted">{{ row.spanId }}</div></template></el-table-column>
          <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCEEDED' || row.status === 'OK' ? 'success' : !row.status || row.status === 'UNKNOWN' ? 'info' : 'danger'">{{ row.status || '未知' }}</el-tag></template></el-table-column>
          <el-table-column prop="durationMs" label="耗时" width="80"><template #default="{ row }">{{ row.durationMs }} ms</template></el-table-column>
        </el-table>
        <el-empty v-if="selectedResult.traceId && !traceSpans.length && !traceLoading" description="该 Trace 暂无 Span 观测" />
        <el-empty v-if="!selectedResult.traceId" description="该评估结果没有关联 Trace" />
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, CircleCheck, Warning, Collection } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import EvaluationRunProgress from '@/components/evaluation/EvaluationRunProgress.vue'
import EvaluationDatasetEditor from '@/components/evaluation/EvaluationDatasetEditor.vue'
import TraceEvidenceBars from '@/components/evaluation/TraceEvidenceBars.vue'
import type { EvaluationDataset, EvaluationDatasetCalibration, EvaluationCalibrationRunSummary, EvaluationCalibrationRunDetail, EvaluationDatasetItem, EvaluationGroundTruthLabel, EvaluationJob, EvaluationJobStatus, EvaluationResult, EvaluationSummary, EvaluatorDefinition, TraceSpanKind, TraceSpanObservation } from '@/types/domain'

const workspaceTabs = [{ key: 'overview', label: '运行概览', description: '处理进度与质量概况' }, { key: 'results', label: '评估结果', description: '分数、结论与执行证据' }, { key: 'datasets', label: '数据集与校准', description: '回归样本与人工标注' }, { key: 'rules', label: '评估规则', description: '评估器与适用范围' }]
const permissions = usePermissions()
let accessEpoch = 0
const activeTab = ref('overview')
const activeJob = ref<EvaluationJob>()
const pageError = ref('')
let loadRequestId = 0
const evaluators = ref<EvaluatorDefinition[]>([])
const datasets = ref<EvaluationDataset[]>([])
const results = ref<EvaluationResult[]>([])
const jobs = ref<EvaluationJob[]>([])
const datasetItems = ref<EvaluationDatasetItem[]>([])
const selectedDatasetKey = ref('')
const annotationActor = ref('控制台用户')
const calibrationRuns = ref<EvaluationCalibrationRunSummary[]>([])
const selectedCalibrationRunId = ref('')
const calibrationDetail = ref<EvaluationCalibrationRunDetail>()
let calibrationHistoryRequestId = 0
const datasetItemsLoading = ref(false)
const calibration = ref<EvaluationDatasetCalibration>({ datasetKey: '', datasetVersion: '', requestedLimit: 200, totalItems: 0, evaluatedItems: 0, skippedItems: 0, failedItems: 0, unannotatedItems: 0, abstainedItems: 0, truePositive: 0, falsePositive: 0, trueNegative: 0, falseNegative: 0, precision: 0, recall: 0, f1: 0, accuracy: 0, calculatedAt: '', evaluators: [] })
const emptyCalibration = { ...calibration.value }
let datasetItemsRequestId = 0
let calibrationRequestId = 0
let traceRequestId = 0
const calibrating = ref(false)
const summary = ref<EvaluationSummary>({ windowHours: 24, total: 0, passed: 0, failed: 0, passRate: 0, averageScore: 0, averageConfidence: 0, evaluators: [] })
const contentAnalysis = ref<{ enabled: boolean; counts: Record<string, number> } | null>(null)
const selectedResult = ref<EvaluationResult>()
const detailVisible = ref(false)
const traceSpans = ref<TraceSpanObservation[]>([])
const traceLoading = ref(false)
const router = useRouter()
const loading = ref(false)
const running = ref(false)
const syncing = ref(false)
const submittingJob = ref(false), creatingDataset = ref(false), annotating = ref(false)
const editorLocked = ref(false)
const evaluationWriteBusy = computed(() => running.value || submittingJob.value || creatingDataset.value || calibrating.value || annotating.value)
const writeBusy = computed(() => evaluationWriteBusy.value || editorLocked.value)
const form = reactive({
  evaluatorKey: 'ACTION_COMPLETION',
  traceId: '',
  spanId: '',
  inputJson: '{}',
  actualJson: '{"completed":true}',
  expectedJson: '{}',
})

function currentAccess(epoch: number, capability: 'viewConsole' | 'manageEvaluations' | 'annotateEvaluations' = 'viewConsole') {
  return !pollingDisposed && epoch === accessEpoch && permissions.can('viewConsole') && permissions.can(capability)
}

function canWrite(capability: 'manageEvaluations' | 'annotateEvaluations') {
  return currentAccess(accessEpoch, capability) && !writeBusy.value
}

const passRate = computed(() => {
  if (summary.value.total) return Math.round(summary.value.passRate * 100)
  if (!results.value.length) return 0
  return Math.round(results.value.filter(item => item.passed).length / results.value.length * 100)
})

const confusionRows = computed(() => [
  { prediction: '评估通过', pass: `TP ${calibration.value.truePositive}`, fail: `FP ${calibration.value.falsePositive}` },
  { prediction: '评估未通过', pass: `FN ${calibration.value.falseNegative}`, fail: `TN ${calibration.value.trueNegative}` },
])

async function load() {
  if (!currentAccess(accessEpoch) || editorLocked.value) return
  const epoch = accessEpoch
  const requestId = ++loadRequestId
  loading.value = true
  pageError.value = ''
  try {
    const [evaluatorItems, datasetItems, resultItems, jobItems, summaryItem, analysisStatus] = await Promise.all([
      EapApi.listEvaluators(), EapApi.listEvaluationDatasets(), EapApi.listEvaluationResults({ limit: 100 }), EapApi.listEvaluationJobs(), EapApi.getEvaluationSummary(24),
      EapApi.getContentAnalysisStatus().catch(() => null),
    ])
    if (!currentAccess(epoch) || requestId !== loadRequestId || editorLocked.value) return
    evaluators.value = evaluatorItems
    datasets.value = datasetItems
    results.value = resultItems
    jobs.value = jobItems
    if (!syncing.value) activeJob.value = jobItems.find(item => ['QUEUED', 'RUNNING'].includes(item.status)) || jobItems[0]
    summary.value = summaryItem
    contentAnalysis.value = analysisStatus
    if (!selectedDatasetKey.value || !datasetItems.some(item => item.datasetKey === selectedDatasetKey.value)) {
      selectedDatasetKey.value = datasetItems[0]?.datasetKey || ''
      selectedCalibrationRunId.value = ''
    }
    if (selectedDatasetKey.value) await Promise.all([loadDatasetItems(selectedDatasetKey.value), loadDatasetCalibration(selectedDatasetKey.value), loadCalibrationHistory(selectedDatasetKey.value)])
    if (!currentAccess(epoch) || requestId !== loadRequestId) return
    if (evaluatorItems.length && !evaluatorItems.some(item => item.evaluatorKey === form.evaluatorKey)) {
      form.evaluatorKey = evaluatorItems[0].evaluatorKey
      applyExample()
    }
  } catch {
    if (currentAccess(epoch) && requestId === loadRequestId) pageError.value = '数据加载失败，已保留当前账号的内容，请刷新重试'
  } finally {
    if (currentAccess(epoch) && requestId === loadRequestId) loading.value = false
  }
}

function applyExample() {
  if (!canWrite('manageEvaluations')) return
  if (form.evaluatorKey === 'TOOL_ARGUMENT_VALIDITY') {
    form.inputJson = '{"requiredArguments":["accountId","region"]}'
    form.actualJson = '{"arguments":{"accountId":"a-1"}}'
  } else if (form.evaluatorKey === 'MODEL_CALL_STATUS') {
    form.inputJson = '{}'
    form.actualJson = '{"outcome":"SUCCEEDED"}'
  } else if (form.evaluatorKey === 'TOOL_STATUS') {
    form.inputJson = '{}'
    form.actualJson = '{"status":"SUCCEEDED"}'
  } else if (form.evaluatorKey === 'PII_POLICY') {
    form.inputJson = '{}'
    form.actualJson = '{"containsPii":false}'
  } else if (form.evaluatorKey === 'SENSITIVE_APPROVAL') {
    form.inputJson = '{}'
    form.actualJson = '{"approved":true}'
  } else if (form.evaluatorKey === 'COST_BUDGET') {
    form.inputJson = '{"maxCost":2.0}'
    form.actualJson = '{"cost":1.2}'
  } else if (form.evaluatorKey === 'TRACE_ACTION_COMPLETION') {
    form.inputJson = '{}'
    form.actualJson = '{"completed":true,"inferenceMode":"EXPLICIT","heuristic":false}'
  } else if (form.evaluatorKey === 'RAG_CONTEXT_COVERAGE') {
    form.inputJson = '{}'
    form.actualJson = '{"status":"SUCCEEDED","inputSourceCount":2,"outputSourceCount":2,"inputRefsCount":2,"outputRefsCount":2,"referenceOverlapCount":2,"adopted":true,"qualityScore":85}'
  } else if (form.evaluatorKey === 'EXACT_MATCH') {
    form.inputJson = '{}'
    form.expectedJson = '{"answer":"ok"}'
    form.actualJson = '{"answer":"ok"}'
  } else {
    form.inputJson = '{}'
    form.actualJson = '{"completed":true}'
  }
}

async function run() {
  if (!canWrite('manageEvaluations')) return
  const epoch = accessEpoch
  running.value = true
  try {
    const input = JSON.parse(form.inputJson) as Record<string, unknown>
    const actual = JSON.parse(form.actualJson) as Record<string, unknown>
    const expected = form.evaluatorKey === 'EXACT_MATCH' ? JSON.parse(form.expectedJson) : undefined
    await EapApi.runEvaluation({
      evaluatorKey: form.evaluatorKey, evaluatorVersion: 'v1', traceId: form.traceId || undefined,
      spanId: form.spanId || undefined, input, actual, expected,
    })
    if (!currentAccess(epoch, 'manageEvaluations')) return
    ElMessage.success('评估完成，结果已保存')
    await load()
  } catch (error) {
    if (!currentAccess(epoch, 'manageEvaluations')) return
    if (error instanceof SyntaxError) ElMessage.error('JSON 格式不正确，请检查输入')
    else pageError.value = '评估结果未确认，请刷新最近结果核对；再次执行会提交新的评估。'
  } finally {
    if (currentAccess(epoch, 'manageEvaluations')) running.value = false
  }
}

let pollingDisposed = false
let pollGeneration = 0
let pollTimer: number | undefined
let finishPollDelay: (() => void) | undefined

function cancelPolling() {
  pollGeneration++
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  finishPollDelay?.()
  pollTimer = undefined
  finishPollDelay = undefined
}

function stopPolling() {
  pollingDisposed = true
  accessEpoch++
  cancelPolling()
  syncing.value = false
  submittingJob.value = false
}

async function synchronize() {
  if (syncing.value || !canWrite('manageEvaluations')) return
  const epoch = accessEpoch
  syncing.value = true
  submittingJob.value = true
  try {
    const job = await EapApi.submitEvaluationJob(200)
    if (!currentAccess(epoch, 'manageEvaluations')) return
    submittingJob.value = false
    activeJob.value = job
    jobs.value = [job, ...jobs.value.filter(item => item.id !== job.id)].slice(0, 20)
    activeTab.value = 'overview'
    ElMessage.success('评估任务已加入队列')
    await waitForJob(job.id, epoch)
    if (currentAccess(epoch) && activeJob.value && !['QUEUED', 'RUNNING'].includes(activeJob.value.status)) await load()
  } catch {
    if (currentAccess(epoch)) pageError.value = '提交状态未确认，请刷新处理记录后再试'
  } finally {
    if (currentAccess(epoch)) { syncing.value = false; submittingJob.value = false }
  }
}

async function waitForJob(id: number, epoch = accessEpoch) {
  const generation = ++pollGeneration
  const current = () => currentAccess(epoch) && generation === pollGeneration
  for (let attempt = 0; attempt < 900; attempt += 1) {
    if (!current()) return
    await new Promise<void>(resolve => {
      finishPollDelay = resolve
      pollTimer = window.setTimeout(resolve, 1000)
    })
    if (!current()) return
    pollTimer = undefined
    finishPollDelay = undefined
    let job: EvaluationJob
    try { job = await EapApi.getEvaluationJob(id) }
    catch {
      if (current()) pageError.value = '进度连接中断，后台任务继续运行，可点击继续跟踪'
      return
    }
    if (!current()) return
    activeJob.value = job
    jobs.value = jobs.value.map(item => item.id === job.id ? job : item)
    if (job.status === 'PARTIAL') {
      ElMessage.warning(`本批处理完成，${job.failed} 条处理失败，后续扫描会重试`)
      return
    }
    if (job.status === 'SUCCEEDED') {
      ElMessage.success(`后台评估完成：扫描 ${job.scanned} 条观测，评估/复用 ${job.evaluated} 条结果`)
      return
    }
    if (job.status === 'FAILED') {
      ElMessage.error(job.errorMessage || '后台评估任务失败')
      return
    }
  }
  if (current()) ElMessage.info('任务仍在后台执行，可稍后刷新查看结果')
}

function selectJob(job: EvaluationJob) {
  if (currentAccess(accessEpoch) && !syncing.value) activeJob.value = job
}

async function resumeJob(id: number) {
  if (syncing.value || !currentAccess(accessEpoch)) return
  const epoch = accessEpoch
  syncing.value = true
  try { pageError.value = ''; await waitForJob(id, epoch) }
  finally { if (currentAccess(epoch)) syncing.value = false }
}

function jobStatus(status: EvaluationJobStatus) {
  return ({ QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', PARTIAL: '部分失败', FAILED: '失败' } as Record<EvaluationJobStatus, string>)[status]
}

function jobTagType(status: EvaluationJobStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' || status === 'PARTIAL' ? 'warning' : 'info'
}

async function createDemoDataset() {
  if (!canWrite('manageEvaluations')) return
  const epoch = accessEpoch, key = `eap-demo-${crypto.randomUUID()}`
  creatingDataset.value = true
  try {
    await EapApi.createEvaluationDataset({ datasetKey: key, name: 'EAP 示例回归集', sourceType: 'MANUAL', version: 'v1', itemCount: 0 })
    if (!currentAccess(epoch, 'manageEvaluations')) return
    await EapApi.createEvaluationDatasetItem(key, {
      itemKey: 'action-completion-demo', evaluatorKey: 'ACTION_COMPLETION', evaluatorVersion: 'v1',
      input: { task: '完成示例任务' }, actual: { completed: true },
    })
    if (!currentAccess(epoch, 'manageEvaluations')) return
    ElMessage.success('示例数据集和样本已创建，可在下方进行 SME 标注')
    await load()
  } catch {
    if (currentAccess(epoch)) pageError.value = `示例创建未完成或结果未确认，请刷新核对数据集 ${key}；不会自动重试。`
  } finally {
    if (currentAccess(epoch)) creatingDataset.value = false
  }
}

async function selectDataset(datasetKey: string) {
  if (!currentAccess(accessEpoch) || editorLocked.value) return
  selectedDatasetKey.value = datasetKey
  selectedCalibrationRunId.value = ''
  await Promise.all([loadDatasetItems(datasetKey), loadDatasetCalibration(datasetKey), loadCalibrationHistory(datasetKey)])
}

async function datasetSaved(datasetKey: string) {
  if (!currentAccess(accessEpoch) || editorLocked.value) return
  selectedDatasetKey.value = datasetKey
  selectedCalibrationRunId.value = ''
  await load()
}

async function loadDatasetItems(datasetKey = selectedDatasetKey.value) {
  const epoch = accessEpoch
  const requestId = ++datasetItemsRequestId
  datasetItems.value = []
  datasetItemsLoading.value = false
  if (!datasetKey || !currentAccess(epoch)) return
  datasetItemsLoading.value = true
  try {
    const items = await EapApi.listEvaluationDatasetItems(datasetKey)
    if (currentAccess(epoch) && requestId === datasetItemsRequestId && datasetKey === selectedDatasetKey.value) datasetItems.value = items
  } catch {
    if (currentAccess(epoch) && requestId === datasetItemsRequestId) pageError.value = '样本读取失败，请刷新当前数据集。'
  } finally {
    if (currentAccess(epoch) && requestId === datasetItemsRequestId) datasetItemsLoading.value = false
  }
}

async function annotateItem(item: EvaluationDatasetItem, label: EvaluationGroundTruthLabel) {
  if (!canWrite('annotateEvaluations') || !selectedDatasetKey.value || datasetItemsLoading.value || !datasetItems.value.includes(item)) return
  const epoch = accessEpoch, datasetKey = selectedDatasetKey.value
  annotating.value = true
  try {
    await EapApi.annotateEvaluationDatasetItem(datasetKey, item.itemKey, {
      groundTruthLabel: label,
      annotatedBy: permissions.state.user?.mode === 'DEVELOPMENT' ? annotationActor.value.trim() || '控制台用户' : undefined,
      rationale: label === 'PASS' ? 'SME 确认样本结果符合预期' : label === 'FAIL' ? 'SME 确认样本结果不符合预期' : 'SME 暂不对该样本下结论',
    })
    if (!currentAccess(epoch, 'annotateEvaluations') || datasetKey !== selectedDatasetKey.value) return
    ElMessage.success(`样本 ${item.itemKey} 已标注为${annotationLabel(label)}`)
    await Promise.all([loadDatasetItems(datasetKey), loadDatasetCalibration(datasetKey)])
  } catch {
    if (currentAccess(epoch) && datasetKey === selectedDatasetKey.value) pageError.value = '标注结果未确认，请刷新当前样本核对。'
  } finally {
    if (currentAccess(epoch)) annotating.value = false
  }
}

async function loadCalibrationHistory(datasetKey = selectedDatasetKey.value) {
  const epoch = accessEpoch
  const requestId = ++calibrationHistoryRequestId
  calibrationRuns.value = []
  if (!datasetKey || !currentAccess(epoch)) return
  try {
    const runs = await EapApi.listEvaluationCalibrationRuns(datasetKey)
    if (currentAccess(epoch) && requestId === calibrationHistoryRequestId && datasetKey === selectedDatasetKey.value) calibrationRuns.value = runs
  } catch {
    if (currentAccess(epoch) && requestId === calibrationHistoryRequestId) pageError.value = '校准历史读取失败，请刷新当前数据集。'
  }
}

async function selectCalibrationRun(runId: string) {
  if (!currentAccess(accessEpoch)) return
  selectedCalibrationRunId.value = runId
  await loadDatasetCalibration()
}

async function loadDatasetCalibration(datasetKey = selectedDatasetKey.value) {
  const epoch = accessEpoch
  const requestId = ++calibrationRequestId
  const runId = selectedCalibrationRunId.value
  calibration.value = { ...emptyCalibration, evaluators: [] }
  calibrationDetail.value = undefined
  if (!datasetKey || !currentAccess(epoch)) return
  try { if (runId) {
    const detail = await EapApi.getEvaluationCalibrationRun(datasetKey, runId)
    if (currentAccess(epoch) && requestId === calibrationRequestId && datasetKey === selectedDatasetKey.value && runId === selectedCalibrationRunId.value) {
      calibration.value = detail.calibration
      calibrationDetail.value = detail
    }
  } else {
    const result = await EapApi.getEvaluationDatasetCalibration(datasetKey)
    if (currentAccess(epoch) && requestId === calibrationRequestId && datasetKey === selectedDatasetKey.value && !selectedCalibrationRunId.value) calibration.value = result
  } } catch {
    if (currentAccess(epoch) && requestId === calibrationRequestId) pageError.value = '校准结果读取失败，请重新选择或刷新。'
  }
}

async function runDatasetCalibration() {
  if (!canWrite('manageEvaluations') || !selectedDatasetKey.value) return
  const epoch = accessEpoch
  calibrating.value = true
  try {
    const datasetKey = selectedDatasetKey.value
    const requestId = ++calibrationRequestId
    const result = await EapApi.runEvaluationDataset(datasetKey, 200)
    if (!currentAccess(epoch, 'manageEvaluations')) return
    if (datasetKey === selectedDatasetKey.value && requestId === calibrationRequestId) {
      selectedCalibrationRunId.value = result.runId || ''
      calibration.value = result
    }
    ElMessage.success(`数据集校准完成：运行 ${result.evaluatedItems} 条，F1 ${percent(result.f1)}`)
    await load()
  } catch {
    if (currentAccess(epoch)) pageError.value = '校准结果未确认，请刷新校准历史核对；再次运行会创建新的校准记录。'
  } finally {
    if (currentAccess(epoch)) calibrating.value = false
  }
}

function annotationLabel(label?: EvaluationGroundTruthLabel) {
  return ({ PASS: '通过', FAIL: '失败', ABSTAIN: '弃权' } as Record<EvaluationGroundTruthLabel, string>)[label || 'ABSTAIN']
}

function annotationTagType(label?: EvaluationGroundTruthLabel) {
  return label === 'PASS' ? 'success' : label === 'FAIL' ? 'danger' : 'info'
}

async function openResult(result: EvaluationResult) {
  if (!currentAccess(accessEpoch)) return
  const epoch = accessEpoch
  const requestId = ++traceRequestId
  traceLoading.value = false
  selectedResult.value = result
  detailVisible.value = true
  traceSpans.value = []
  if (!result.traceId) return
  traceLoading.value = true
  try {
    const spans = await EapApi.listTraceSpanObservations({ traceId: result.traceId, limit: 100 })
    if (currentAccess(epoch) && requestId === traceRequestId) traceSpans.value = spans
  } catch {
    if (currentAccess(epoch) && requestId === traceRequestId) pageError.value = '关联 Trace 读取失败，请重新打开详情。'
  } finally {
    if (currentAccess(epoch) && requestId === traceRequestId) traceLoading.value = false
  }
}

function openTraceWorkbench() {
  if (currentAccess(accessEpoch) && selectedResult.value?.traceId) {
    router.push({ path: '/physical', query: { traceId: selectedResult.value.traceId } })
  }
}

function prettyJson(value: unknown) {
  if (value === undefined || value === null) return '{}'
  return JSON.stringify(value, null, 2)
}

function percent(value: number) {
  return `${Math.round(value * 100)}%`
}

function traceKindLabel(kind: TraceSpanKind) {
  return ({ MODEL: '模型', TOOL: '工具', MCP: 'MCP', RAG: '知识检索', A2A: '智能体协作', AGENT: '智能体' } as Record<TraceSpanKind, string>)[kind] || kind
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

async function resumeCurrentJob(epoch: number) {
  const job = activeJob.value
  if (currentAccess(epoch) && job && ['QUEUED', 'RUNNING'].includes(job.status)) await resumeJob(job.id)
}

onBeforeUnmount(stopPolling)
watch([() => permissions.state.user, () => permissions.can('viewConsole'), () => permissions.can('manageEvaluations'), () => permissions.can('annotateEvaluations')], async () => {
  const epoch = ++accessEpoch
  cancelPolling()
  loadRequestId++; datasetItemsRequestId++; calibrationRequestId++; calibrationHistoryRequestId++; traceRequestId++
  evaluators.value = []; datasets.value = []; results.value = []; jobs.value = []; datasetItems.value = []
  selectedDatasetKey.value = ''; selectedCalibrationRunId.value = ''; calibrationRuns.value = []
  calibration.value = { ...emptyCalibration, evaluators: [] }; calibrationDetail.value = undefined
  summary.value = { windowHours: 24, total: 0, passed: 0, failed: 0, passRate: 0, averageScore: 0, averageConfidence: 0, evaluators: [] }
  contentAnalysis.value = null; activeJob.value = undefined; selectedResult.value = undefined; traceSpans.value = []
  detailVisible.value = false; annotationActor.value = '控制台用户'; pageError.value = ''
  Object.assign(form, { evaluatorKey: 'ACTION_COMPLETION', traceId: '', spanId: '', inputJson: '{}', actualJson: '{}', expectedJson: '{}' })
  loading.value = false; datasetItemsLoading.value = false; traceLoading.value = false
  running.value = false; syncing.value = false; submittingJob.value = false; creatingDataset.value = false; annotating.value = false; calibrating.value = false
  if (!currentAccess(epoch)) return
  await load()
  await resumeCurrentJob(epoch)
}, { immediate: true, flush: 'sync' })
</script>

<style scoped>
.sample-references{display:grid;gap:4px;font-size:12px}.sample-references code{white-space:normal;overflow-wrap:anywhere}
.evaluation-workspace{gap:18px}.evaluation-process-layout{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:18px}.evaluation-process-layout .evaluation-summary-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.evaluation-process-layout :deep(.el-card__body){padding:16px}.evaluation-process-layout .annotation-note{margin:0 0 12px;line-height:1.7}.evaluation-process-layout :deep(.el-card__header){padding:16px}.evaluation-process-layout .evaluation-summary-grid strong{font-size:21px}.evaluation-process-layout .evaluation-summary-grid>div{padding:12px}@media(max-width:1150px){.evaluation-process-layout{grid-template-columns:minmax(0,1fr)}}.evaluation-tabs{display:flex;gap:4px;border-bottom:1px solid #dbe3eb;overflow-x:auto}.evaluation-tabs button{background:transparent;border:0;border-bottom:3px solid transparent;padding:13px 20px;color:#526175;font:inherit;white-space:nowrap;cursor:pointer;min-height:44px}.evaluation-tabs button.active{color:#176b9b;border-bottom-color:#2683b8;font-weight:600}.evaluation-tabs button:hover{background:#eef4f8}.evaluation-tabs button:focus-visible{outline:2px solid #2683b8;outline-offset:-3px}.evaluation-overview{display:grid;gap:16px}.evaluation-kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.evaluation-kpis>div{display:flex;gap:16px;align-items:center;padding:18px;background:white;border:1px solid #e2e8f0;border-radius:6px}.evaluation-kpis>div>div{display:grid;gap:5px}.evaluation-kpis span:not(.kpi-icon){font-size:13px;color:#64748b}.evaluation-kpis strong{font-size:27px;font-weight:600;line-height:1.2;color:#26374b;font-variant-numeric:tabular-nums}.kpi-icon{display:grid;place-items:center;width:46px;height:46px;border-radius:6px;font-size:19px;flex-shrink:0}.blue{background:#e6f1f8;color:#2679a7}.green{background:#e8f4ee;color:#277a58}.amber{background:#fff3e4;color:#a96623}.slate{background:#edf0f5;color:#63768c}.evaluation-overview-note{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;color:#64748b;font-size:13px}.evaluation-workspace :deep(.panel){border-radius:6px;border-color:#e2e8f0}.evaluation-workspace :deep(.panel-heading)>span{color:#64748b;font-size:13px}.evaluation-workspace :deep(.interactive-table .el-table__row){cursor:pointer}@media(max-width:1000px){.evaluation-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.evaluation-kpis{gap:10px}.evaluation-kpis>div{padding:12px;gap:10px}.kpi-icon{width:34px;height:38px;font-size:16px}.evaluation-kpis strong{font-size:23px}.evaluation-tabs button{padding:12px}.evaluation-workspace :deep(.panel-heading){align-items:flex-start;gap:8px;flex-wrap:wrap}}
</style>
