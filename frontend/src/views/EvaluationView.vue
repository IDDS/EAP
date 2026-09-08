<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>评估与可靠性</strong>
        <span>把 Trace、任务结果和治理证据沉淀为可版本化评估</span>
      </div>
      <div class="toolbar-actions">
        <el-button :loading="syncing" @click="synchronize">异步同步近期观测</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="metric-strip">
      <div class="small-box" style="background: linear-gradient(135deg, #123c66, #1b6ca8)"><strong>{{ evaluators.length }}</strong><span>已注册评估器</span></div>
      <div class="small-box" style="background: linear-gradient(135deg, #166534, #2f9e65)"><strong>{{ datasets.length }}</strong><span>评估数据集</span></div>
      <div class="small-box" style="background: linear-gradient(135deg, #7c3aed, #8b5cf6)"><strong>{{ results.length }}</strong><span>最近评估结果</span></div>
      <div class="small-box" style="background: linear-gradient(135deg, #9a3412, #ea580c)"><strong>{{ passRate }}%</strong><span>最近通过率</span></div>
    </div>

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

    <div class="content-grid content-grid-wide">
      <el-card shadow="never" class="panel">
        <template #header><div class="panel-heading"><strong>评估器目录</strong><span>当前为规则评分，后续可替换为本地 SLM / LLM Judge</span></div></template>
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

      <el-card shadow="never" class="panel">
        <template #header><div class="panel-heading"><strong>运行一次评估</strong><span>显式执行，不会自动改变现有控制决策</span></div></template>
        <el-form label-position="top" size="small">
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

    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>最近结果</strong><span>分数、置信度和证据可回溯到 Agent / Trace / Span</span></div></template>
      <el-table :data="results" v-loading="loading" size="small" row-key="id" @row-click="openResult">
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

    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>投影任务</strong><span>评估扫描进入队列后由后台 Worker 执行，不占用页面请求线程</span></div></template>
      <el-table :data="jobs" v-loading="loading" size="small">
        <el-table-column prop="jobKey" label="任务" min-width="230" show-overflow-tooltip />
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="jobTagType(row.status)">{{ jobStatus(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="扫描/评估" width="130"><template #default="{ row }">{{ row.scanned }} / {{ row.evaluated }}</template></el-table-column>
        <el-table-column prop="failed" label="失败" width="80" />
        <el-table-column label="提交时间" width="180"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!jobs.length && !loading" description="还没有后台投影任务" />
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>数据集</strong><span>版本化管理脱敏样本，并通过人工标签校准评估器</span></div></template>
      <div class="dataset-actions">
        <div v-for="item in datasets" :key="item.datasetKey" class="dataset-item" :class="{ 'is-selected': item.datasetKey === selectedDatasetKey }" role="button" tabindex="0" @click="selectDataset(item.datasetKey)" @keydown.enter="selectDataset(item.datasetKey)">
          <div><strong>{{ item.name }}</strong><span>{{ item.datasetKey }} · {{ item.version }}</span></div>
          <el-tag size="small">{{ item.itemCount }} 条</el-tag>
        </div>
        <el-button plain @click="createDemoDataset">创建示例数据集</el-button>
        <el-button v-if="selectedDatasetKey" type="primary" plain :loading="calibrating" @click="runDatasetCalibration">运行数据集校准</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-heading">
          <strong>SME 标注样本</strong>
          <div class="dataset-annotation-toolbar">
            <el-select v-model="selectedDatasetKey" size="small" placeholder="选择数据集" style="width: 230px" @change="selectDataset">
              <el-option v-for="item in datasets" :key="item.datasetKey" :label="`${item.name} · ${item.version}`" :value="item.datasetKey" />
            </el-select>
            <el-input v-model="annotationActor" size="small" placeholder="标注人" style="width: 150px" />
          </div>
        </div>
      </template>
      <div class="annotation-note">只保存现有观测模式允许的脱敏/哈希 JSON；人工标签用于后续评估器校准，不会直接改变线上控制决策。</div>
      <el-table :data="datasetItems" v-loading="datasetItemsLoading" size="small" row-key="id">
        <el-table-column prop="itemKey" label="样本" min-width="180" show-overflow-tooltip />
        <el-table-column prop="evaluatorKey" label="评估器" min-width="190" />
        <el-table-column label="人工标签" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.groundTruthLabel" size="small" :type="annotationTagType(row.groundTruthLabel)">{{ annotationLabel(row.groundTruthLabel) }}</el-tag>
            <span v-else class="muted">未标注</span>
          </template>
        </el-table-column>
        <el-table-column prop="annotatedBy" label="标注人" width="130" show-overflow-tooltip />
        <el-table-column prop="annotationRationale" label="标注说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" plain @click="annotateItem(row, 'PASS')">通过</el-button>
            <el-button size="small" type="danger" plain @click="annotateItem(row, 'FAIL')">失败</el-button>
            <el-button size="small" plain @click="annotateItem(row, 'ABSTAIN')">弃权</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!datasetItems.length && !datasetItemsLoading" description="当前数据集还没有样本，可创建示例数据集开始标注" />
    </el-card>

    <el-card v-if="selectedDatasetKey" shadow="never" class="panel">
      <template #header><div class="panel-heading"><strong>评估器校准</strong><span>{{ selectedDatasetKey }} · 对比 SME ground truth 与评估器预测，弃权样本不进入混淆矩阵</span></div></template>
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

    <el-drawer v-model="detailVisible" title="评估证据详情" size="620px">
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
        <el-table v-loading="traceLoading" :data="traceSpans" size="small">
          <el-table-column label="类型" width="92"><template #default="{ row }">{{ traceKindLabel(row.spanKind) }}</template></el-table-column>
          <el-table-column label="Span" min-width="180"><template #default="{ row }"><strong>{{ row.spanName }}</strong><div class="muted">{{ row.spanId }}</div></template></el-table-column>
          <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCEEDED' || row.status === 'OK' ? 'success' : row.status ? 'danger' : 'info'">{{ row.status || '未知' }}</el-tag></template></el-table-column>
          <el-table-column prop="durationMs" label="耗时" width="80"><template #default="{ row }">{{ row.durationMs }} ms</template></el-table-column>
        </el-table>
        <el-empty v-if="selectedResult.traceId && !traceSpans.length && !traceLoading" description="该 Trace 暂无 Span 观测" />
        <el-empty v-if="!selectedResult.traceId" description="该评估结果没有关联 Trace" />
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { EapApi } from '@/api/eap'
import type { EvaluationDataset, EvaluationDatasetCalibration, EvaluationCalibrationRunSummary, EvaluationCalibrationRunDetail, EvaluationDatasetItem, EvaluationGroundTruthLabel, EvaluationJob, EvaluationJobStatus, EvaluationResult, EvaluationSummary, EvaluatorDefinition, TraceSpanKind, TraceSpanObservation } from '@/types/domain'

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
const selectedResult = ref<EvaluationResult>()
const detailVisible = ref(false)
const traceSpans = ref<TraceSpanObservation[]>([])
const traceLoading = ref(false)
const router = useRouter()
const loading = ref(false)
const running = ref(false)
const syncing = ref(false)
const form = reactive({
  evaluatorKey: 'ACTION_COMPLETION',
  traceId: '',
  spanId: '',
  inputJson: '{}',
  actualJson: '{"completed":true}',
  expectedJson: '{}',
})

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
  loading.value = true
  try {
    const [evaluatorItems, datasetItems, resultItems, jobItems, summaryItem] = await Promise.all([
      EapApi.listEvaluators(), EapApi.listEvaluationDatasets(), EapApi.listEvaluationResults({ limit: 100 }), EapApi.listEvaluationJobs(), EapApi.getEvaluationSummary(24),
    ])
    evaluators.value = evaluatorItems
    datasets.value = datasetItems
    results.value = resultItems
    jobs.value = jobItems
    summary.value = summaryItem
    if (!selectedDatasetKey.value || !datasetItems.some(item => item.datasetKey === selectedDatasetKey.value)) {
      selectedDatasetKey.value = datasetItems[0]?.datasetKey || ''
      selectedCalibrationRunId.value = ''
    }
    if (selectedDatasetKey.value) await Promise.all([loadDatasetItems(selectedDatasetKey.value), loadDatasetCalibration(selectedDatasetKey.value), loadCalibrationHistory(selectedDatasetKey.value)])
    if (evaluatorItems.length && !evaluatorItems.some(item => item.evaluatorKey === form.evaluatorKey)) {
      form.evaluatorKey = evaluatorItems[0].evaluatorKey
      applyExample()
    }
  } finally {
    loading.value = false
  }
}

function applyExample() {
  if (form.evaluatorKey === 'TOOL_ARGUMENT_VALIDITY') {
    form.inputJson = '{"requiredArguments":["accountId","region"]}'
    form.actualJson = '{"arguments":{"accountId":"a-1"}}'
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
  try {
    const input = JSON.parse(form.inputJson) as Record<string, unknown>
    const actual = JSON.parse(form.actualJson) as Record<string, unknown>
    const expected = form.evaluatorKey === 'EXACT_MATCH' ? JSON.parse(form.expectedJson) : undefined
    running.value = true
    await EapApi.runEvaluation({
      evaluatorKey: form.evaluatorKey, evaluatorVersion: 'v1', traceId: form.traceId || undefined,
      spanId: form.spanId || undefined, input, actual, expected,
    })
    ElMessage.success('评估完成，结果已保存')
    await load()
  } catch (error) {
    if (error instanceof SyntaxError) ElMessage.error('JSON 格式不正确，请检查输入')
  } finally {
    running.value = false
  }
}

let pollingDisposed = false
let pollTimer: number | undefined
let finishPollDelay: (() => void) | undefined

function stopPolling() {
  pollingDisposed = true
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  finishPollDelay?.()
  pollTimer = undefined
  finishPollDelay = undefined
}

async function synchronize() {
  syncing.value = true
  try {
    const job = await EapApi.submitEvaluationJob(200)
    if (pollingDisposed) return
    ElMessage.success(`评估投影任务已入队：${job.jobKey}`)
    await waitForJob(job.id)
    if (!pollingDisposed) await load()
  } finally {
    syncing.value = false
  }
}

async function waitForJob(id: number) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (pollingDisposed) return
    await new Promise<void>(resolve => {
      finishPollDelay = resolve
      pollTimer = window.setTimeout(resolve, 1000)
    })
    pollTimer = undefined
    finishPollDelay = undefined
    if (pollingDisposed) return
    const job = await EapApi.getEvaluationJob(id)
    if (pollingDisposed) return
    if (job.status === 'SUCCEEDED') {
      ElMessage.success(`后台评估完成：扫描 ${job.scanned} 条观测，生成 ${job.evaluated} 条结果`)
      return
    }
    if (job.status === 'FAILED') {
      ElMessage.error(job.errorMessage || '后台评估任务失败')
      return
    }
  }
  ElMessage.info('任务仍在后台执行，可稍后刷新查看结果')
}

function jobStatus(status: EvaluationJobStatus) {
  return ({ QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' } as Record<EvaluationJobStatus, string>)[status]
}

function jobTagType(status: EvaluationJobStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
}

async function createDemoDataset() {
  const key = `eap-demo-${Date.now()}`
  await EapApi.createEvaluationDataset({ datasetKey: key, name: 'EAP 示例回归集', sourceType: 'MANUAL', version: 'v1', itemCount: 0 })
  await EapApi.createEvaluationDatasetItem(key, {
    itemKey: 'action-completion-demo', evaluatorKey: 'ACTION_COMPLETION', evaluatorVersion: 'v1',
    input: { task: '完成示例任务' }, actual: { completed: true },
  })
  ElMessage.success('示例数据集和样本已创建，可在下方进行 SME 标注')
  await load()
}

async function selectDataset(datasetKey: string) {
  selectedDatasetKey.value = datasetKey
  selectedCalibrationRunId.value = ''
  await Promise.all([loadDatasetItems(datasetKey), loadDatasetCalibration(datasetKey), loadCalibrationHistory(datasetKey)])
}

async function loadDatasetItems(datasetKey = selectedDatasetKey.value) {
  const requestId = ++datasetItemsRequestId
  datasetItems.value = []
  datasetItemsLoading.value = false
  if (!datasetKey) return
  datasetItemsLoading.value = true
  try {
    const items = await EapApi.listEvaluationDatasetItems(datasetKey)
    if (requestId === datasetItemsRequestId && datasetKey === selectedDatasetKey.value) datasetItems.value = items
  } finally {
    if (requestId === datasetItemsRequestId) datasetItemsLoading.value = false
  }
}

async function annotateItem(item: EvaluationDatasetItem, label: EvaluationGroundTruthLabel) {
  if (!selectedDatasetKey.value || datasetItemsLoading.value || !datasetItems.value.includes(item)) return
  await EapApi.annotateEvaluationDatasetItem(selectedDatasetKey.value, item.itemKey, {
    groundTruthLabel: label,
    annotatedBy: annotationActor.value.trim() || '控制台用户',
    rationale: label === 'PASS' ? 'SME 确认样本结果符合预期' : label === 'FAIL' ? 'SME 确认样本结果不符合预期' : 'SME 暂不对该样本下结论',
  })
  ElMessage.success(`样本 ${item.itemKey} 已标注为${annotationLabel(label)}`)
  await Promise.all([loadDatasetItems(), loadDatasetCalibration()])
}

async function loadCalibrationHistory(datasetKey = selectedDatasetKey.value) {
  const requestId = ++calibrationHistoryRequestId
  calibrationRuns.value = []
  if (!datasetKey) return
  const runs = await EapApi.listEvaluationCalibrationRuns(datasetKey)
  if (requestId === calibrationHistoryRequestId && datasetKey === selectedDatasetKey.value) calibrationRuns.value = runs
}

async function selectCalibrationRun(runId: string) {
  selectedCalibrationRunId.value = runId
  await loadDatasetCalibration()
}

async function loadDatasetCalibration(datasetKey = selectedDatasetKey.value) {
  const requestId = ++calibrationRequestId
  const runId = selectedCalibrationRunId.value
  calibration.value = { ...emptyCalibration, evaluators: [] }
  calibrationDetail.value = undefined
  if (!datasetKey) return
  if (runId) {
    const detail = await EapApi.getEvaluationCalibrationRun(datasetKey, runId)
    if (requestId === calibrationRequestId && datasetKey === selectedDatasetKey.value && runId === selectedCalibrationRunId.value) {
      calibration.value = detail.calibration
      calibrationDetail.value = detail
    }
  } else {
    const result = await EapApi.getEvaluationDatasetCalibration(datasetKey)
    if (requestId === calibrationRequestId && datasetKey === selectedDatasetKey.value && !selectedCalibrationRunId.value) calibration.value = result
  }
}

async function runDatasetCalibration() {
  if (!selectedDatasetKey.value) return
  calibrating.value = true
  try {
    const datasetKey = selectedDatasetKey.value
    const requestId = ++calibrationRequestId
    const result = await EapApi.runEvaluationDataset(datasetKey, 200)
    if (datasetKey === selectedDatasetKey.value && requestId === calibrationRequestId) {
      selectedCalibrationRunId.value = result.runId || ''
      calibration.value = result
    }
    ElMessage.success(`数据集校准完成：运行 ${result.evaluatedItems} 条，F1 ${percent(result.f1)}`)
    await load()
  } finally {
    calibrating.value = false
  }
}

function annotationLabel(label?: EvaluationGroundTruthLabel) {
  return ({ PASS: '通过', FAIL: '失败', ABSTAIN: '弃权' } as Record<EvaluationGroundTruthLabel, string>)[label || 'ABSTAIN']
}

function annotationTagType(label?: EvaluationGroundTruthLabel) {
  return label === 'PASS' ? 'success' : label === 'FAIL' ? 'danger' : 'info'
}

async function openResult(result: EvaluationResult) {
  const requestId = ++traceRequestId
  traceLoading.value = false
  selectedResult.value = result
  detailVisible.value = true
  traceSpans.value = []
  if (!result.traceId) return
  traceLoading.value = true
  try {
    const spans = await EapApi.listTraceSpanObservations({ traceId: result.traceId, limit: 100 })
    if (requestId === traceRequestId) traceSpans.value = spans
  } finally {
    if (requestId === traceRequestId) traceLoading.value = false
  }
}

function openTraceWorkbench() {
  if (selectedResult.value?.traceId) {
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

onBeforeUnmount(stopPolling)
onMounted(load)
</script>
