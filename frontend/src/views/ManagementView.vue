<template>
  <section class="page-grid management-page">
    <ConsolePageHeader v-if="activeSection !== 'budget'">
      <template #actions><div v-if="activeSection !== 'budget'" class="button-row">
        <el-select v-model="windowHours" aria-label="统计时间范围" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-select v-model="selectedCurrency" aria-label="财务币种" style="width: 160px" @change="load">
          <el-option label="自动识别币种" value="" />
          <el-option v-for="currency in currencyOptions" :key="currency" :label="currency" :value="currency" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div></template>
    </ConsolePageHeader>


    <BudgetPanel v-if="activeSection === 'budget'" />

    <el-alert v-if="activeSection!=='budget'&&summary.mixedCurrencies" title="当前窗口包含多种币种，请选择财务币种后查看金额、净收益和投入产出比。" type="info" :closable="false" show-icon />
    <p v-if="activeSection!=='budget'" class="muted">财务口径：{{ summary.currency || '未选定（多币种）' }}；模型调用和价值事件按该币种统计，任务与知识指标仍覆盖整个窗口。</p>

    <div v-show="activeSection === 'overview'" class="analysis-hero management-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">价值分析 / 最近 {{ windowHours }} 小时</span>
        <h2>价值回路</h2>
        <p>把模型投入、任务执行、知识贡献与业务确认价值放在同一个回路里，避免只看调用量而忽略实际产出。</p>
      </div>
      <div class="management-value-card">
        <span class="analysis-eyebrow">价值信号</span>
        <strong>{{ roiLabel }}</strong>
        <span>投入产出比 · {{ summary.hasValidatedValue ? '业务方已确认' : '等待业务方确认' }}</span>
        <div class="management-value-meta"><span>净收益 <b>{{ formatMoney(summary.netBenefit) }}</b></span><span>确认价值 <b>{{ formatMoney(summary.validatedBusinessValue) }}</b></span></div>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="management-flow-strip">
      <div class="management-flow-step">
        <span class="management-flow-index">01</span>
        <div><span>投入</span><strong>{{ formatMoney(summary.totalCost) }}</strong><small>{{ formatNumber(summary.totalTokens) }} Token</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">02</span>
        <div><span>执行</span><strong>{{ formatNumber(summary.totalTasks) }}</strong><small>任务成功率 {{ successPercent }}%</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">03</span>
        <div><span>知识贡献</span><strong>{{ adoptionPercent }}% / {{ reusePercent }}%</strong><small>采用率 / 复用率</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">04</span>
        <div><span>业务价值</span><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><small>{{ summary.hasValidatedValue ? '已确认' : '待确认' }}</small></div>
      </div>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel management-usage-panel">
      <template #header>
        <div class="panel-header">
          <div><span>员工使用与业务效果</span><div class="muted">把“有人使用”“任务完成”和“问题解决”分开统计，避免把调用量误当成业务价值。</div></div>
          <div class="button-row">
            <el-tag size="small" :type="usageIdentityCaptured ? 'success' : 'warning'" effect="plain">{{ usageIdentityCaptured ? '已采集调用方' : '待接入员工身份' }}</el-tag>
            <el-tag size="small" :type="outcomeCoverageLabel === '未采集' ? 'warning' : 'info'" effect="plain">结果判断覆盖 {{ outcomeCoverageLabel }}</el-tag>
          </div>
        </div>
      </template>
      <div class="management-usage-metrics">
        <div><span>活跃员工 / 调用方</span><strong>{{ activeEmployeeCount }}</strong><small>窗口内有任务记录</small></div>
        <div><span>任务使用次数</span><strong>{{ formatNumber(windowTasks.length) }}</strong><small>日均 {{ averageDailyUses }} 次</small></div>
        <div><span>问题已解决</span><strong>{{ resolvedTaskCount }}</strong><small>明确标记为已解决</small></div>
        <div><span>问题解决率</span><strong>{{ resolutionRateLabel }}</strong><small>{{ resolutionObservedCount ? `基于 ${resolutionObservedCount} 条明确判断` : '尚未采集解决判断' }}</small></div>
      </div>
      <div class="management-usage-grid">
        <div>
          <div class="management-section-heading"><strong>员工使用频率</strong><span>按任务请求方统计</span></div>
          <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}:${selectedCurrency}`" :data="employeeUsage" size="small">
            <el-table-column prop="requester" label="员工 / 调用方" min-width="160" />
            <el-table-column prop="uses" label="使用次数" width="90" />
            <el-table-column prop="succeeded" label="任务成功" width="90" />
            <el-table-column label="解决问题" width="100"><template #default="{ row }">{{ row.resolved }} / {{ row.judged }}</template></el-table-column>
            <el-table-column label="最近使用" width="165"><template #default="{ row }">{{ formatTime(row.lastUsedAt) }}</template></el-table-column>
          </PaginatedTable>
          <el-empty v-if="!employeeUsage.length" description="当前窗口没有任务使用记录" :image-size="56" />
        </div>
        <div>
          <div class="management-section-heading"><strong>应用场景与效果</strong><span>按任务类型聚合</span></div>
          <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}:${selectedCurrency}`" :data="applicationUsage" size="small">
            <el-table-column label="应用场景" min-width="170"><template #default="{ row }"><strong>{{ applicationLabel(row.taskType) }}</strong><div class="muted">{{ row.taskType }}</div></template></el-table-column>
            <el-table-column prop="uses" label="使用次数" width="90" />
            <el-table-column label="完成率" width="105"><template #default="{ row }">{{ row.successRate }}%</template></el-table-column>
            <el-table-column label="解决率" width="105"><template #default="{ row }">{{ row.resolutionRate }}</template></el-table-column>
            <el-table-column label="确认价值" width="110"><template #default="{ row }">{{ row.validatedValue > 0 ? formatMoney(row.validatedValue) : '—' }}</template></el-table-column>
          </PaginatedTable>
          <el-empty v-if="!applicationUsage.length" description="当前窗口没有可识别应用场景" :image-size="56" />
        </div>
      </div>
      <div class="management-usage-note">JWT 模式会自动使用登录主体作为员工 / 调用方；开发模式或机器调用可显式传入 <code>requester</code>。问题解决来自任务完成后的明确判断，不会用模型调用次数或任务成功状态替代业务确认。</div>
    </el-card>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel management-funnel-panel">
      <template #header>
        <div class="panel-header">
          <div><span>业务价值漏斗</span><div class="muted">沿着“使用 → 执行 → 解决 → 证据 → 价值”定位业务价值流失点。</div></div>
          <span class="muted">当前窗口 · 最近 {{ windowHours }} 小时</span>
        </div>
      </template>
      <div class="management-funnel-list">
        <div v-for="(stage, index) in businessValueFunnel" :key="stage.key" class="management-funnel-stage" :class="{ 'is-dropoff': stage.dropoff }">
          <div class="management-funnel-stage-head">
            <span class="management-funnel-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="management-funnel-stage-copy"><strong>{{ stage.label }}</strong><small>{{ stage.description }}</small></div>
            <b>{{ formatNumber(stage.count) }}</b>
            <em>{{ stage.rateLabel }}</em>
          </div>
          <div class="management-funnel-track"><i :style="{ width: `${stage.percent}%` }"></i></div>
        </div>
      </div>
      <div class="management-funnel-note">转化率按相邻漏斗层计算；“已关联业务证据”只统计外部系统或带来源记录的价值事件，“已确认价值”只统计已关联任务的价值事件，不会用模型调用次数替代业务结果。</div>
    </el-card>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前价值数据已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-alert
      v-if="!summary.hasValidatedValue && (activeSection === 'overview' || activeSection === 'attribution' || activeSection === 'value')"
      type="warning"
      :closable="false"
      show-icon
      title="尚未录入业务方确认价值，投入产出比暂不计算。可在“价值事件”中登记与智能体或发现端点关联的记录。"
    />

    <div v-show="activeSection === 'overview'" class="metric-strip">
      <div class="small-box box-warning">
        <div><strong>{{ formatMoney(summary.totalCost) }}</strong><span>模型投入 {{ summary.currency || '待选币种' }}</span></div>
        <el-icon><Money /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><span>确认业务价值 {{ summary.currency || '待选币种' }}</span></div>
        <el-icon><TrendCharts /></el-icon>
      </div>
      <div class="small-box box-primary">
        <div><strong>{{ roiLabel }}</strong><span>投入产出比</span></div>
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ successPercent }}%</strong><span>任务成功率</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="management-stat-grid">
      <div><span>任务数</span><strong>{{ formatNumber(summary.totalTasks) }}</strong></div>
      <div><span>模型调用</span><strong>{{ formatNumber(summary.totalModelCalls) }}</strong></div>
      <div><span>总 Token</span><strong>{{ formatNumber(summary.totalTokens) }}</strong></div>
      <div><span>净收益</span><strong>{{ formatMoney(summary.netBenefit) }}</strong></div>
      <div><span>知识采用率</span><strong>{{ adoptionPercent }}%</strong></div>
      <div><span>知识复用率</span><strong>{{ reusePercent }}%</strong></div>
      <div><span>已测量改进</span><strong>{{ formatNumber(summary.measuredImpactEvents) }}</strong></div>
      <div><span>平均改进率</span><strong>{{ improvementPercent(summary.averageImprovementRate) }}</strong></div>
      <div><span>效率 / 业务改进</span><strong>{{ summary.efficiencyImpactEvents }} / {{ summary.businessImpactEvents }}</strong></div>
      <div><span>业务系统导入</span><strong>{{ summary.businessSystemValueEvents }}</strong></div>
      <div><span>多智能体归因</span><strong>{{ summary.multiAgentValueEvents }}</strong></div>
      <div><span>平均可信度</span><strong>{{ Math.round(summary.averageConfidence * 100) }}%</strong></div>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel management-decision-panel">
      <template #header>
        <div class="panel-header">
          <span>管理决策信号</span>
          <span class="muted">基于真实运行数据与业务方已确认价值，不推算未确认收益</span>
        </div>
      </template>
      <div class="management-decision-grid">
        <div v-for="signal in decisionSummary" :key="signal.key" class="management-decision-card" :class="`is-${signal.key.toLowerCase()}`">
          <div><span>{{ signal.label }}</span><strong>{{ signal.count }}</strong></div>
          <small>{{ signal.description }}</small>
        </div>
      </div>
      <div class="management-decision-note">决策信号是运营优先级提示：只有业务方登记确认价值后，才会参与 ROI 计算。</div>
    </el-card>

    <div v-show="activeSection === 'overview'" class="chart-grid management-chart-grid">
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>确认价值走势</span>
            <span class="muted">价值事件累计</span>
          </div>
        </template>
        <TrendChart v-if="summary.currency" :option="valueTrendOption" />
        <el-empty v-else description="选择币种后查看价值走势" />
      </el-card>
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>知识采用 / 复用对比</span>
            <span class="muted">智能体维度</span>
          </div>
        </template>
        <TrendChart :option="knowledgeRateOption" />
      </el-card>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel management-leaderboard-panel">
      <template #header>
        <div class="panel-header">
          <span>智能体价值排行</span>
          <span class="muted">优先展示已有业务价值归因的智能体</span>
        </div>
      </template>
      <div v-if="topAgentUsage.length" class="management-leaderboard">
        <div v-for="(row, index) in topAgentUsage" :key="row.subjectKey" class="management-leader-row">
          <span class="management-rank">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="management-leader-name"><strong>{{ row.subjectName }}</strong><span>{{ row.subjectKey }}</span></div>
          <div class="management-leader-metric"><span>投入产出比</span><strong>{{ row.validatedValue != null && row.validatedValue > 0 && row.roi != null ? row.roi.toFixed(4) : '—' }}</strong></div>
          <div class="management-leader-metric"><span>确认价值</span><strong>{{ formatMoney(row.validatedValue) }}</strong></div>
          <div class="management-leader-bars">
            <div><span>采用 {{ Math.round(row.knowledgeAdoptionRate * 100) }}%</span><i><b :style="{ width: `${Math.round(row.knowledgeAdoptionRate * 100)}%` }"></b></i></div>
            <div><span>复用 {{ Math.round(row.knowledgeReuseRate * 100) }}%</span><i><b class="reuse" :style="{ width: `${Math.round(row.knowledgeReuseRate * 100)}%` }"></b></i></div>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前窗口没有可排行的智能体价值数据" />
    </el-card>

    <div v-show="activeSection === 'attribution'" class="management-two-column">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>智能体投入产出与知识指标</span>
            <span class="muted">按窗口聚合真实任务、遥测和知识交互</span>
          </div>
        </template>
        <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}:${selectedCurrency}`" :data="visibleAgentUsage" v-loading="loading" size="small">
          <el-table-column label="智能体 / 端点" min-width="170">
            <template #default="{ row }">
              <strong>{{ row.subjectName }}</strong>
              <div class="muted">{{ row.subjectKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="任务" width="72">
            <template #default="{ row }">{{ row.succeededTasks }}/{{ row.tasks }}</template>
          </el-table-column>
          <el-table-column label="调用 / Token" width="128">
            <template #default="{ row }">{{ row.modelCalls }} / {{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="投入" width="92">
            <template #default="{ row }">{{ formatMoney(row.totalCost) }}</template>
          </el-table-column>
          <el-table-column label="确认价值" width="105">
            <template #default="{ row }">{{ formatMoney(row.validatedValue) }}</template>
          </el-table-column>
          <el-table-column label="管理信号" width="128">
            <template #default="{ row }">
              <el-tag size="small" :type="decisionTagType(decisionFor(row).key)">{{ decisionFor(row).label }}</el-tag>
              <div class="muted management-decision-reason">{{ decisionFor(row).reason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="投入产出比" width="85">
            <template #default="{ row }">{{ row.validatedValue != null && row.validatedValue > 0 && row.roi != null ? row.roi.toFixed(4) : '—' }}</template>
          </el-table-column>
          <el-table-column label="采用 / 复用" width="112">
            <template #default="{ row }">{{ Math.round(row.knowledgeAdoptionRate * 100) }}% / {{ Math.round(row.knowledgeReuseRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="钻取" width="92" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openPhysical(row)">物理观测</el-button></template>
          </el-table-column>
        </PaginatedTable>
        <el-empty v-if="visibleAgentUsage.length === 0" description="当前窗口没有可归因的运行数据" />
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>登记业务价值</span>
            <el-tag size="small" type="warning" effect="plain">需要业务方确认</el-tag>
          </div>
        </template>
        <el-form label-position="top" class="management-form" @submit.prevent="recordValue">
          <div class="form-row">
            <el-form-item label="事件标识" required>
              <el-input v-model="form.eventKey" placeholder="例如 task-2026-001-value" />
            </el-form-item>
            <el-form-item label="价值类型" required>
              <el-select v-model="form.valueType" style="width: 100%">
                <el-option label="效率提升" value="EFFICIENCY_GAIN" />
                <el-option label="节省人工成本" value="LABOR_SAVING" />
                <el-option label="成本下降" value="COST_REDUCTION" />
                <el-option label="提升收入" value="REVENUE" />
                <el-option label="减少损失" value="LOSS_AVOIDANCE" />
                <el-option label="质量提升" value="QUALITY_IMPROVEMENT" />
                <el-option label="其他已验证价值" value="OTHER" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="业务指标名称">
              <el-input v-model="form.metricName" placeholder="例如处理时长、日处理量、缺陷率" />
            </el-form-item>
            <el-form-item label="指标单位">
              <el-input v-model="form.metricUnit" placeholder="小时、件/天、%" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="数据来源">
              <el-select v-model="form.sourceType" style="width: 100%">
                <el-option label="人工确认" value="MANUAL" />
                <el-option label="业务系统导入" value="BUSINESS_SYSTEM" />
                <el-option label="链路推导" value="DERIVED" />
              </el-select>
            </el-form-item>
            <el-form-item label="可信度">
              <el-input-number v-model="form.confidence" :min="0" :max="1" :step="0.05" :precision="2" :controls="false" style="width: 100%" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="来源系统">
              <el-input v-model="form.sourceSystem" placeholder="例如 CRM、工单系统、ERP" />
            </el-form-item>
            <el-form-item label="来源记录标识">
              <el-input v-model="form.sourceRecordKey" placeholder="用于外部系统幂等导入" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="改善方向">
              <el-select v-model="form.improvementDirection" style="width: 100%">
                <el-option label="数值越高越好" value="HIGHER_IS_BETTER" />
                <el-option label="数值越低越好" value="LOWER_IS_BETTER" />
              </el-select>
            </el-form-item>
            <div class="management-metric-hint">填写基线和当前值后，系统会计算已确认的效率/业务改进率。</div>
          </div>
          <div class="form-row">
            <el-form-item label="智能体 ID">
              <el-input-number v-model="form.agentId" :min="1" :controls="false" style="width: 100%" placeholder="可选" />
            </el-form-item>
            <el-form-item label="发现端点标识">
              <el-input v-model="form.endpointKey" placeholder="可选，至少填写智能体或端点之一" />
            </el-form-item>
          </div>
          <div class="management-contribution-block">
            <div class="management-contribution-heading">
              <div><strong>多智能体贡献归因</strong><span>可选；启用后权重之和必须为 100%</span></div>
              <el-button size="small" @click="addContribution">添加智能体</el-button>
            </div>
            <div v-for="(item, index) in contributions" :key="index" class="management-contribution-row">
              <el-select v-model="item.agentId" placeholder="选择智能体" filterable>
                <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
              </el-select>
              <el-input-number v-model="item.contributionWeight" :min="0" :max="1" :step="0.05" :precision="2" :controls="false" />
              <el-select v-model="item.attributionMethod">
                <el-option label="业务确认" value="BUSINESS_CONFIRMED" />
                <el-option label="Trace 推导" value="TRACE_DERIVED" />
                <el-option label="平均分配" value="EQUAL_SPLIT" />
              </el-select>
              <el-input v-model="item.evidenceReference" placeholder="证据/工单/Trace 标识" />
              <el-button link type="danger" @click="removeContribution(index)">删除</el-button>
            </div>
            <span v-if="contributions.length === 0" class="muted">未设置时，价值归属到上方单一智能体或端点。</span>
          </div>
          <div class="form-row">
            <el-form-item label="确认价值金额" required>
              <el-input-number v-model="form.benefitMoney" :min="0" :precision="6" :controls="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="币种">
              <el-input v-model="form.currency" maxlength="16" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="使用前基线值">
              <el-input-number v-model="form.baselineValue" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="使用后当前值">
              <el-input-number v-model="form.currentValue" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
          </div>
          <el-form-item label="确认人 / 业务方" required>
            <el-input v-model="form.validatedBy" placeholder="例如 finance@example.com 或 财务部" />
          </el-form-item>
          <el-form-item label="价值说明">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="2048" show-word-limit placeholder="只填写脱敏后的业务结果，不要粘贴公司机密或模型原文" />
          </el-form-item>
          <div class="button-row management-submit-row">
            <span class="muted">提交后会计入投入产出汇总，并保留幂等事件标识。</span>
            <el-button type="primary" :loading="saving" @click="recordValue">登记确认价值</el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <div v-show="activeSection === 'connectors'" class="management-connector-layout">
      <el-card shadow="never" class="panel management-connector-list-panel">
        <template #header>
          <div class="panel-header">
            <div><span>业务价值连接器</span><div class="muted">从 CRM、ERP、工单等系统增量采集已发生的业务结果</div></div>
            <el-button size="small" @click="resetConnectorForm">新建连接器</el-button>
          </div>
        </template>
        <div v-if="connectors.length" class="management-connector-list">
          <div v-for="connector in connectors" :key="connector.id" class="management-connector-card">
            <div class="management-connector-card-head">
              <div>
                <strong>{{ connector.name }}</strong>
                <span>{{ connector.sourceSystem }} · 每 {{ connector.syncIntervalMinutes }} 分钟</span>
              </div>
              <el-tag size="small" :type="connectorStatusType(connector.status)">{{ connectorStatusLabel(connector.status) }}</el-tag>
            </div>
            <div class="management-connector-endpoint">{{ connector.endpointUrl }}</div>
            <div class="management-connector-stats">
              <span><b>{{ connector.lastImportedCount }}</b> 最近导入</span>
              <span><b>{{ connector.lastSkippedCount }}</b> 幂等跳过</span>
              <span><b>{{ formatTime(connector.lastSuccessAt) }}</b> 最近成功</span>
            </div>
            <el-alert v-if="connector.lastError" type="error" :closable="false" :title="connector.lastError" />
            <div class="button-row management-connector-actions">
              <el-switch :model-value="connector.enabled" inline-prompt active-text="启" inactive-text="停" @change="onConnectorEnabledChange(connector, $event)" />
              <el-button link type="primary" @click="editConnector(connector)">配置</el-button>
              <el-button type="primary" plain size="small" :loading="syncingConnectorId === connector.id" @click="syncConnector(connector)">立即同步</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="尚未配置业务系统连接器" />
      </el-card>

      <el-card shadow="never" class="panel management-connector-form-panel">
        <template #header>
          <div class="panel-header">
            <span>{{ editingConnectorId ? '编辑连接器' : '新建连接器' }}</span>
            <el-tag size="small" type="success" effect="plain">凭据仅引用环境变量</el-tag>
          </div>
        </template>
        <el-alert type="info" :closable="false" title="EAP 不保存 API Key。请填写后端容器中已有的环境变量名，连接时才会读取实际凭据。" />
        <el-form label-position="top" class="management-form management-connector-form" @submit.prevent="saveConnector">
          <div class="form-row">
            <el-form-item label="连接器名称" required><el-input v-model="connectorForm.name" placeholder="例如 CRM 成交价值" /></el-form-item>
            <el-form-item label="连接器标识" required><el-input v-model="connectorForm.connectorKey" placeholder="crm-deals" /></el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="系统类型" required>
              <el-select v-model="connectorForm.systemType" style="width: 100%">
                <el-option label="CRM" value="CRM" /><el-option label="ERP" value="ERP" />
                <el-option label="工单 / 客服" value="TICKETING" /><el-option label="研发管理" value="DEVOPS" />
                <el-option label="自定义 HTTP" value="CUSTOM" />
              </el-select>
            </el-form-item>
            <el-form-item label="来源系统名称" required><el-input v-model="connectorForm.sourceSystem" placeholder="例如 Dynamics 365" /></el-form-item>
          </div>
          <el-form-item label="JSON API 地址" required><el-input v-model="connectorForm.endpointUrl" placeholder="https://crm.example.com/api/value-records" /></el-form-item>
          <div class="form-row">
            <el-form-item label="默认归属智能体">
              <el-select v-model="connectorForm.defaultAgentId" clearable filterable style="width: 100%">
                <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="默认发现端点"><el-input v-model="connectorForm.defaultEndpointKey" placeholder="与智能体二选一" /></el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="记录数组路径"><el-input v-model="connectorForm.recordsPath" placeholder="data.records；根数组可留空" /></el-form-item>
            <el-form-item label="记录唯一编号路径" required><el-input v-model="connectorForm.recordKeyPath" placeholder="id" /></el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="关联 EAP 任务 ID 路径"><el-input v-model="connectorForm.taskIdPath" placeholder="taskId；用于把业务结果回链到任务" /></el-form-item>
            <div class="management-metric-hint">配置后，CRM / 工单 / 运维记录会显示在对应智能体任务的业务价值链路中。</div>
          </div>
          <div class="form-row">
            <el-form-item label="价值金额路径" required><el-input v-model="connectorForm.benefitMoneyPath" placeholder="benefitMoney" /></el-form-item>
            <el-form-item label="业务时间路径"><el-input v-model="connectorForm.observedAtPath" placeholder="closedAt" /></el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="说明字段路径"><el-input v-model="connectorForm.descriptionPath" placeholder="description" /></el-form-item>
            <el-form-item label="固定价值类型">
              <el-select v-model="connectorForm.fixedValueType" style="width: 100%">
                <el-option label="效率提升" value="EFFICIENCY_GAIN" /><el-option label="节省人工成本" value="LABOR_SAVING" />
                <el-option label="成本下降" value="COST_REDUCTION" /><el-option label="提升收入" value="REVENUE" />
                <el-option label="减少损失" value="LOSS_AVOIDANCE" /><el-option label="质量提升" value="QUALITY_IMPROVEMENT" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="认证方式">
              <el-select v-model="connectorForm.authType" style="width: 100%">
                <el-option label="无需认证" value="NONE" /><el-option label="Bearer 已登记凭据" value="BEARER_ENV" />
                <el-option label="Header 已登记凭据" value="HEADER_ENV" />
              </el-select>
            </el-form-item>
            <el-form-item label="平台凭据引用"><el-input v-model="connectorForm.credentialEnvKey" :disabled="connectorForm.authType === 'NONE'" placeholder="由平台管理员提供，如 crm-readonly" /></el-form-item>
          </div>
          <p v-if="connectorForm.authType !== 'NONE'" class="management-metric-hint">凭据引用须由平台管理员登记并绑定此连接器和 HTTPS 目标；认证请求头必须与登记值一致。</p>
          <div v-if="connectorForm.authType === 'HEADER_ENV'" class="form-row">
            <el-form-item label="认证 Header"><el-input v-model="connectorForm.authHeader" placeholder="X-API-Key" /></el-form-item>
            <el-form-item label="值前缀"><el-input v-model="connectorForm.authPrefix" placeholder="可留空" /></el-form-item>
          </div>
          <el-collapse class="management-connector-advanced">
            <el-collapse-item title="高级字段映射与增量同步" name="advanced">
              <div class="form-row">
                <el-form-item label="基线值路径"><el-input v-model="connectorForm.baselineValuePath" placeholder="baseline" /></el-form-item>
                <el-form-item label="当前值路径"><el-input v-model="connectorForm.currentValuePath" placeholder="current" /></el-form-item>
              </div>
              <div class="form-row">
                <el-form-item label="固定指标名称"><el-input v-model="connectorForm.fixedMetricName" placeholder="例如平均处理时长" /></el-form-item>
                <el-form-item label="固定指标单位"><el-input v-model="connectorForm.fixedMetricUnit" placeholder="分钟、件、%" /></el-form-item>
              </div>
              <div class="form-row">
                <el-form-item label="增量时间参数"><el-input v-model="connectorForm.incrementalParameter" placeholder="updatedSince" /></el-form-item>
                <el-form-item label="每次最大记录"><el-input-number v-model="connectorForm.maxRecords" :min="1" :max="5000" :controls="false" style="width: 100%" /></el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
          <div class="form-row">
            <el-form-item label="同步周期（分钟）"><el-input-number v-model="connectorForm.syncIntervalMinutes" :min="1" :max="10080" :controls="false" style="width: 100%" /></el-form-item>
            <el-form-item label="证据可信度"><el-input-number v-model="connectorForm.confidence" :min="0" :max="1" :step="0.05" :precision="2" :controls="false" style="width: 100%" /></el-form-item>
          </div>
          <el-form-item label="业务确认主体" required><el-input v-model="connectorForm.validatedBy" placeholder="例如 销售运营系统 / 财务系统" /></el-form-item>
          <div class="button-row management-submit-row">
            <el-switch v-model="connectorForm.enabled" active-text="启用定时同步" />
            <div><el-button @click="resetConnectorForm">重置</el-button><el-button type="primary" :loading="connectorSaving" @click="saveConnector">保存连接器</el-button></div>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card v-show="activeSection === 'value'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>价值事件记录</span>
          <span class="muted">按下方时间范围检索历史，未选择时查询全部时间</span>
        </div>
      </template>
      <HistoryTable ref="eventHistory" :fetch-page="fetchValueHistory" :reset-key="`${route.fullPath}:${summary.currency}`" :statuses="[]" placeholder="搜索价值类型、来源、确认人、说明">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="智能体 / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `智能体 ${row.agentId}` : row.endpointKey || `${row.agentContributions.length} 个智能体协作` }}</strong>
            <div class="muted">{{ row.eventKey }}</div>
            <div v-if="row.agentContributions.length" class="management-attribution-tags">
              <el-tag v-for="item in row.agentContributions" :key="item.agentId" size="small" effect="plain">
                {{ agentName(item.agentId) }} {{ Math.round(item.contributionWeight * 100) }}%
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="数据来源" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ sourceTypeLabel(row.sourceType) }}</el-tag>
            <div class="muted">{{ row.sourceSystem || 'EAP 管理台' }}<span v-if="row.sourceRecordKey"> · {{ row.sourceRecordKey }}</span> · {{ Math.round(row.confidence * 100) }}%</div>
          </template>
        </el-table-column>
        <el-table-column label="关联任务" width="95">
          <template #default="{ row }">{{ row.taskId ? `#${row.taskId}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="价值类型" width="150">
          <template #default="{ row }">{{ managementValueTypeLabel(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="确认价值" width="125">
          <template #default="{ row }">{{ row.benefitMoney.toFixed(2) }} {{ row.currency }}</template>
        </el-table-column>
        <el-table-column label="业务指标改善" min-width="180">
          <template #default="{ row }">
            <span v-if="row.improvementRate !== undefined && row.improvementRate !== null">
              {{ row.metricName || '业务指标' }} · {{ improvementPercent(row.improvementRate) }}
            </span>
            <span v-else class="muted">未填写基线 / 当前值</span>
          </template>
        </el-table-column>
        <el-table-column label="确认人" width="150" prop="validatedBy" />
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
      </HistoryTable>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { usePageSection } from '@/composables/usePageSection'
import HistoryTable, { type HistoryParams } from '@/components/common/HistoryTable.vue'
import PaginatedTable from '@/components/common/PaginatedTable.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheck, DataAnalysis, Money, Refresh, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import { useRoute, useRouter } from 'vue-router'
import TrendChart from '@/components/TrendChart.vue'
import BudgetPanel from '@/components/BudgetPanel.vue'
import type { EChartsOption } from 'echarts'
import type {
  Agent,
  AgentTask,
  ManagementConnectorRequest,
  ManagementConnectorStatus,
  ManagementSummary,
  ManagementValueConnector,
  ManagementValueEvent,
} from '@/types/domain'

const emptySummary: ManagementSummary = {
  windowHours: 24,
  currency: 'USD',
  availableCurrencies: [],
  mixedCurrencies: false,
  totalTasks: 0,
  succeededTasks: 0,
  taskSuccessRate: 0,
  totalModelCalls: 0,
  totalTokens: 0,
  totalCost: 0,
  validatedBusinessValue: 0,
  netBenefit: 0,
  roi: 0,
  adoptedKnowledgeInteractions: 0,
  reusedKnowledgeInteractions: 0,
  knowledgeAdoptionRate: 0,
  knowledgeReuseRate: 0,
  measuredImpactEvents: 0,
  averageImprovementRate: 0,
  efficiencyImpactEvents: 0,
  businessImpactEvents: 0,
  businessSystemValueEvents: 0,
  multiAgentValueEvents: 0,
  averageConfidence: 0,
  hasValidatedValue: false,
  agentUsage: [],
}

const windowHours = ref(24)
const selectedCurrency = ref('')
const currencyOptions = computed(() => [...new Set([...summary.value.availableCurrencies, ...(selectedCurrency.value ? [selectedCurrency.value] : [])])].sort())
const route = useRoute()
let loadRequestId = 0
const activeSection = usePageSection()
const loading = ref(false)
const saving = ref(false)
const connectorSaving = ref(false)
const syncingConnectorId = ref<number>()
const editingConnectorId = ref<number>()
const summary = ref<ManagementSummary>({ ...emptySummary })
const valueEvents = ref<ManagementValueEvent[]>([])
const tasks = ref<AgentTask[]>([])
const agents = ref<Agent[]>([])
const connectors = ref<ManagementValueConnector[]>([])
type ContributionDraft = {
  agentId?: number
  contributionWeight: number
  attributionMethod: 'BUSINESS_CONFIRMED' | 'TRACE_DERIVED' | 'EQUAL_SPLIT'
  evidenceReference: string
}
const contributions = ref<ContributionDraft[]>([])
const form = reactive({
  eventKey: '',
  agentId: undefined as number | undefined,
  endpointKey: '',
  valueType: 'LABOR_SAVING',
  description: '',
  validatedBy: '',
  benefitMoney: 0,
  currency: 'USD',
  metricName: '',
  metricUnit: '',
  baselineValue: undefined as number | undefined,
  currentValue: undefined as number | undefined,
  improvementDirection: 'HIGHER_IS_BETTER' as 'HIGHER_IS_BETTER' | 'LOWER_IS_BETTER',
  sourceType: 'MANUAL' as 'MANUAL' | 'BUSINESS_SYSTEM' | 'DERIVED',
  sourceSystem: '',
  sourceRecordKey: '',
  confidence: 1,
})
function emptyConnectorForm(): ManagementConnectorRequest {
  return {
    connectorKey: '',
    name: '',
    systemType: 'CRM',
    sourceSystem: '',
    endpointUrl: '',
    enabled: true,
    syncIntervalMinutes: 60,
    authType: 'NONE',
    credentialEnvKey: '',
    authHeader: 'X-API-Key',
    authPrefix: '',
    recordsPath: 'data.records',
    recordKeyPath: 'id',
    observedAtPath: 'observedAt',
    valueTypePath: '',
    fixedValueType: 'OTHER',
    descriptionPath: 'description',
    benefitMoneyPath: 'benefitMoney',
    currencyPath: '',
    fixedCurrency: 'USD',
    metricNamePath: '',
    fixedMetricName: '',
    metricUnitPath: '',
    fixedMetricUnit: '',
    baselineValuePath: '',
    currentValuePath: '',
    improvementDirection: 'HIGHER_IS_BETTER',
    agentIdPath: '',
    defaultAgentId: undefined,
    endpointKeyPath: '',
    taskIdPath: '',
    defaultEndpointKey: '',
    validatedBy: '业务系统自动同步',
    confidence: 0.9,
    measurementWindowStartPath: '',
    measurementWindowEndPath: '',
    incrementalParameter: 'updatedSince',
    maxRecords: 500,
  }
}
const connectorForm = reactive<ManagementConnectorRequest>(emptyConnectorForm())
const router = useRouter()
const contextAgentId = computed(() => {
  const value = Number(route.query.agentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const drilldownLabel = computed(() => {
  const parts: string[] = []
  if (contextAgentId.value) parts.push(`智能体 #${contextAgentId.value}`)
  if (contextEndpointKey.value) parts.push(`端点 ${contextEndpointKey.value}`)
  return parts.join(' · ')
})

const windowTasks = computed(() => {
  const since = Date.now() - windowHours.value * 60 * 60 * 1000
  return tasks.value.filter(task => new Date(task.createdAt).getTime() >= since && (!contextAgentId.value || task.agentId === contextAgentId.value))
})
const employeeUsage = computed(() => {
  const grouped = new Map<string, { requester: string; uses: number; succeeded: number; resolved: number; judged: number; lastUsedAt: string }>()
  windowTasks.value.forEach(task => {
    const requester = task.requester?.trim() || '未采集调用方'
    const current = grouped.get(requester) || { requester, uses: 0, succeeded: 0, resolved: 0, judged: 0, lastUsedAt: task.createdAt }
    current.uses += 1
    current.succeeded += task.status === 'SUCCEEDED' ? 1 : 0
    current.resolved += task.problemResolved === true ? 1 : 0
    current.judged += task.problemResolved === undefined ? 0 : 1
    if (new Date(task.createdAt).getTime() > new Date(current.lastUsedAt).getTime()) current.lastUsedAt = task.createdAt
    grouped.set(requester, current)
  })
  return [...grouped.values()].sort((left, right) => right.uses - left.uses)
})
const applicationUsage = computed(() => {
  const since = Date.now() - windowHours.value * 60 * 60 * 1000
  const valueByTask = new Map<number, number>()
  visibleValueEvents.value.forEach(event => {
    if (event.taskId && new Date(event.observedAt).getTime() >= since) {
      valueByTask.set(event.taskId, (valueByTask.get(event.taskId) || 0) + event.benefitMoney)
    }
  })
  const grouped = new Map<string, { taskType: string; uses: number; succeeded: number; resolved: number; judged: number; validatedValue: number }>()
  windowTasks.value.forEach(task => {
    const current = grouped.get(task.taskType) || { taskType: task.taskType, uses: 0, succeeded: 0, resolved: 0, judged: 0, validatedValue: 0 }
    current.uses += 1
    current.succeeded += task.status === 'SUCCEEDED' ? 1 : 0
    current.resolved += task.problemResolved === true ? 1 : 0
    current.judged += task.problemResolved === undefined ? 0 : 1
    current.validatedValue += valueByTask.get(task.id) || 0
    grouped.set(task.taskType, current)
  })
  return [...grouped.values()].map(item => ({
    ...item,
    successRate: item.uses ? Math.round(item.succeeded / item.uses * 100) : 0,
    resolutionRate: item.judged ? `${Math.round(item.resolved / item.judged * 100)}%` : '未采集',
  })).sort((left, right) => right.uses - left.uses)
})
const activeEmployeeCount = computed(() => employeeUsage.value.filter(item => item.requester !== '未采集调用方').length)
const usageIdentityCaptured = computed(() => activeEmployeeCount.value > 0)
const resolvedTaskCount = computed(() => windowTasks.value.filter(task => task.problemResolved === true).length)
const resolutionObservedCount = computed(() => windowTasks.value.filter(task => task.problemResolved !== undefined).length)
const resolutionRateLabel = computed(() => resolutionObservedCount.value ? `${Math.round(resolvedTaskCount.value / resolutionObservedCount.value * 100)}%` : '未采集')
const outcomeCoverageLabel = computed(() => windowTasks.value.length ? `${Math.round(resolutionObservedCount.value / windowTasks.value.length * 100)}%` : '未采集')
const averageDailyUses = computed(() => {
  const days = Math.max(1, windowHours.value / 24)
  return (windowTasks.value.length / days).toFixed(1)
})

function applicationLabel(taskType: string) {
  const labels: Record<string, string> = {
    FINANCE_ANALYSIS: '经营分析与异常解释',
    HR_POLICY_QA: '员工制度与流程问答',
    INCIDENT_TRIAGE: '运维告警与事件研判',
    GOVERNED_ANALYSIS: '治理约束下的分析',
  }
  return labels[taskType] || taskType.replaceAll('_', ' ')
}

const successPercent = computed(() => Math.round(summary.value.taskSuccessRate * 100))
const adoptionPercent = computed(() => Math.round(summary.value.knowledgeAdoptionRate * 100))
const reusePercent = computed(() => Math.round(summary.value.knowledgeReuseRate * 100))
function improvementPercent(value: number) {
  return `${value >= 0 ? '+' : ''}${(value * 100).toFixed(1)}%`
}
const roiLabel = computed(() => summary.value.hasValidatedValue && summary.value.roi != null ? summary.value.roi.toFixed(4) : '—')
const visibleAgentUsage = computed(() => summary.value.agentUsage.filter(subject => {
  if (contextAgentId.value && subject.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && subject.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const visibleValueEvents = computed(() => valueEvents.value.filter(event => {
  if (summary.value.currency && (event.currency?.trim().toUpperCase() || 'USD') !== summary.value.currency) return false
  if (contextAgentId.value && event.agentId !== contextAgentId.value
    && !event.agentContributions.some(item => item.agentId === contextAgentId.value)) return false
  if (contextEndpointKey.value && event.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const valueEventsInWindow = computed(() => {
  const since = Date.now() - windowHours.value * 60 * 60 * 1000
  return visibleValueEvents.value.filter(event => new Date(event.observedAt).getTime() >= since)
})
const businessValueFunnel = computed(() => {
  const tasksInWindow = windowTasks.value
  const evidenceTaskIds = new Set<number>()
  const confirmedValueTaskIds = new Set<number>()
  valueEventsInWindow.value.forEach(event => {
    if (!event.taskId) return
    if (event.sourceType !== 'MANUAL' || Boolean(event.sourceSystem) || Boolean(event.sourceRecordKey)) evidenceTaskIds.add(event.taskId)
    if (event.benefitMoney > 0) confirmedValueTaskIds.add(event.taskId)
  })
  const counts = [
    { key: 'used', label: '使用任务', description: '窗口内有任务记录', count: tasksInWindow.length },
    { key: 'succeeded', label: '执行成功', description: '任务状态为 SUCCEEDED', count: tasksInWindow.filter(task => task.status === 'SUCCEEDED').length },
    { key: 'judged', label: '结果已判断', description: '业务方明确记录已解决或未解决', count: tasksInWindow.filter(task => task.problemResolved !== undefined).length },
    { key: 'resolved', label: '问题已解决', description: '明确标记为 problemResolved=true', count: tasksInWindow.filter(task => task.problemResolved === true).length },
    { key: 'evidenced', label: '已关联业务证据', description: '外部系统或来源记录已回链', count: tasksInWindow.filter(task => evidenceTaskIds.has(task.id)).length },
    { key: 'valued', label: '已确认价值', description: '关联价值事件且金额大于 0', count: tasksInWindow.filter(task => confirmedValueTaskIds.has(task.id)).length },
  ]
  return counts.map((stage, index) => {
    const previous = index === 0 ? stage.count : counts[index - 1].count
    return {
      ...stage,
      percent: counts[0].count ? Math.round(stage.count / counts[0].count * 100) : 0,
      rateLabel: index === 0 ? (stage.count ? '100%' : '—') : previous ? `${Math.round(stage.count / previous * 100)}% 转化` : '—',
      dropoff: index > 0 && previous > 0 && stage.count < previous,
    }
  })
})
const topAgentUsage = computed(() => [...visibleAgentUsage.value].filter(row => row.validatedValue != null && row.totalCost != null)
  .sort((left, right) => (right.validatedValue ?? 0) - (left.validatedValue ?? 0) || (right.totalCost ?? 0) - (left.totalCost ?? 0))
  .slice(0, 3))
type DecisionKey = 'SCALE' | 'OPTIMIZE' | 'VALIDATE' | 'OBSERVE'
type DecisionSignal = { key: DecisionKey, label: string, reason: string, description: string, count: number }

function decisionFor(row: ManagementSummary['agentUsage'][number]): { key: DecisionKey, label: string, reason: string } {
  const successRate = row.tasks > 0 ? row.succeededTasks / row.tasks : undefined
  if (row.validatedValue == null || row.totalCost == null || row.roi == null) {
    return { key: 'OBSERVE', label: '选择币种', reason: '多币种金额不可直接比较' }
  }
  if (row.validatedValue > 0 && row.totalCost > 0 && row.roi > 0 && (successRate === undefined || successRate >= 0.8)) {
    return { key: 'SCALE', label: '扩大投入', reason: '价值为正' }
  }
  if ((successRate !== undefined && successRate < 0.8) || (row.validatedValue > 0 && row.roi <= 0)) {
    return { key: 'OPTIMIZE', label: '优先优化', reason: successRate !== undefined && successRate < 0.8 ? '成功率偏低' : '投入未覆盖价值' }
  }
  if (row.modelCalls > 0 || row.tasks > 0 || row.knowledgeInteractions > 0) {
    return { key: 'VALIDATE', label: '补充确认', reason: '尚无确认价值' }
  }
  return { key: 'OBSERVE', label: '持续观察', reason: '运行数据不足' }
}

const decisionSummary = computed<DecisionSignal[]>(() => {
  const definitions: Array<[DecisionKey, string, string]> = [
    ['SCALE', '扩大投入', '已确认价值覆盖投入且运行稳定'],
    ['OPTIMIZE', '优先优化', '成功率或投入产出仍需改善'],
    ['VALIDATE', '补充确认', '已有运行数据，但尚无业务价值确认'],
    ['OBSERVE', '持续观察', '当前窗口运行证据不足'],
  ]
  return definitions.map(([key, label, description]) => ({
    key, label, description, reason: '',
    count: visibleAgentUsage.value.filter(row => decisionFor(row).key === key).length,
  }))
})

function decisionTagType(key: DecisionKey) {
  return ({ SCALE: 'success', OPTIMIZE: 'danger', VALIDATE: 'warning', OBSERVE: 'info' } as Record<DecisionKey, 'success' | 'danger' | 'warning' | 'info'>)[key]
}

const valueTrendOption = computed<EChartsOption>(() => {
  const rows = (summary.value.currency ? [...valueEventsInWindow.value] : []).sort((left, right) => new Date(left.observedAt).getTime() - new Date(right.observedAt).getTime()).slice(-16)
  let cumulative = 0
  const cumulativeValues = rows.map(row => {
    cumulative += row.benefitMoney || 0
    return Number(cumulative.toFixed(4))
  })
  return {
    color: ['#d68b2c', '#16a085'],
    grid: { top: 38, right: 42, bottom: 28, left: 54 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map(row => new Date(row.observedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })), axisLabel: { color: '#8795a9', fontSize: 10 } },
    yAxis: { type: 'value', name: summary.value.currency || '', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    series: [
      { name: '单次确认价值', type: 'bar', barMaxWidth: 18, data: rows.map(row => row.benefitMoney), itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: '累计确认价值', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: cumulativeValues },
    ],
  }
})
const knowledgeRateOption = computed<EChartsOption>(() => {
  const rows = [...visibleAgentUsage.value].sort((left, right) => right.knowledgeAdoptionRate - left.knowledgeAdoptionRate).slice(0, 8)
  return {
    color: ['#3167e8', '#16a085'],
    grid: { top: 34, right: 18, bottom: 22, left: 104 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', max: 100, axisLabel: { color: '#8795a9', fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => row.subjectName), axisLabel: { color: '#5f7188', fontSize: 10 } },
    series: [
      { name: '采用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.knowledgeAdoptionRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
      { name: '复用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.knowledgeReuseRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
    ],
  }
})

function openPhysical(row: { agentId?: number, endpointKey?: string }) {
  router.push({ path: '/physical', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
  } })
}

function clearDrilldown() {
  router.replace({ path: '/management', query: {} })
}

const eventHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchValueHistory(params:HistoryParams) { return EapApi.pageManagementValues({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,currency:summary.value.currency || undefined}) }
async function load() {
  const requestId = ++loadRequestId
  loading.value = true
  try {
    const [nextSummary, nextEvents, nextAgents, nextConnectors, nextTasks] = await Promise.all([
      EapApi.getManagementSummary(windowHours.value, selectedCurrency.value || undefined),
      EapApi.listManagementValueEvents(100),
      EapApi.listAgents(),
      EapApi.listManagementConnectors(),
      EapApi.listTasks().catch(() => [] as AgentTask[]),
    ])
    if (requestId !== loadRequestId) return
    summary.value = nextSummary
    valueEvents.value = nextEvents
    agents.value = nextAgents
    connectors.value = nextConnectors
    tasks.value = nextTasks
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

async function recordValue() {
  if (!form.eventKey.trim() || !form.validatedBy.trim() || !form.valueType.trim()) {
    ElMessage.warning('请填写事件标识、价值类型和确认人')
    return
  }
  const validContributions = contributions.value.filter(item => item.agentId)
  if (!form.agentId && !form.endpointKey.trim() && validContributions.length === 0) {
    ElMessage.warning('请至少关联智能体 ID 或发现端点标识')
    return
  }
  if (validContributions.length > 0) {
    const totalWeight = validContributions.reduce((total, item) => total + item.contributionWeight, 0)
    if (Math.abs(totalWeight - 1) > 0.000001) {
      ElMessage.warning('多智能体贡献权重之和必须为 100%')
      return
    }
  }
  saving.value = true
  try {
    await EapApi.recordManagementValueEvent({
      eventKey: form.eventKey.trim(),
      agentId: form.agentId,
      endpointKey: form.endpointKey.trim() || undefined,
      valueType: form.valueType,
      description: form.description.trim() || undefined,
      validatedBy: form.validatedBy.trim(),
      benefitMoney: form.benefitMoney,
      currency: form.currency.trim() || 'USD',
      metricName: form.metricName.trim() || undefined,
      metricUnit: form.metricUnit.trim() || undefined,
      baselineValue: form.baselineValue,
      currentValue: form.currentValue,
      improvementDirection: form.improvementDirection,
      sourceType: form.sourceType,
      sourceSystem: form.sourceSystem.trim() || undefined,
      sourceRecordKey: form.sourceRecordKey.trim() || undefined,
      confidence: form.confidence,
      agentContributions: validContributions.map(item => ({
        agentId: item.agentId as number,
        contributionWeight: item.contributionWeight,
        attributionMethod: item.attributionMethod,
        evidenceReference: item.evidenceReference.trim() || undefined,
      })),
    })
    ElMessage.success('业务价值事件已登记')
    Object.assign(form, { eventKey: '', agentId: undefined, endpointKey: '', description: '', validatedBy: '', benefitMoney: 0, metricName: '', metricUnit: '', baselineValue: undefined, currentValue: undefined, sourceType: 'MANUAL', sourceSystem: '', sourceRecordKey: '', confidence: 1 })
    contributions.value = []
    await load()
  } finally {
    saving.value = false
  }
}

function addContribution() {
  contributions.value.push({ agentId: undefined, contributionWeight: contributions.value.length === 0 ? 1 : 0, attributionMethod: 'BUSINESS_CONFIRMED', evidenceReference: '' })
}

function removeContribution(index: number) {
  contributions.value.splice(index, 1)
}

function connectorRequestFrom(connector: ManagementValueConnector): ManagementConnectorRequest {
  const request = emptyConnectorForm()
  for (const key of Object.keys(request) as Array<keyof ManagementConnectorRequest>) {
    if (key in connector) (request as Record<string, unknown>)[key] = connector[key]
  }
  return request
}

function resetConnectorForm() {
  editingConnectorId.value = undefined
  Object.assign(connectorForm, emptyConnectorForm())
}

function editConnector(connector: ManagementValueConnector) {
  editingConnectorId.value = connector.id
  Object.assign(connectorForm, connectorRequestFrom(connector))
}

async function saveConnector() {
  if (!connectorForm.name.trim() || !connectorForm.connectorKey.trim() || !connectorForm.sourceSystem.trim()
    || !connectorForm.endpointUrl.trim() || !connectorForm.recordKeyPath.trim()
    || !connectorForm.benefitMoneyPath.trim() || !connectorForm.validatedBy.trim()) {
    ElMessage.warning('请填写连接器名称、标识、来源系统、API 地址和关键字段映射')
    return
  }
  if (!connectorForm.defaultAgentId && !connectorForm.defaultEndpointKey?.trim()
    && !connectorForm.agentIdPath?.trim() && !connectorForm.endpointKeyPath?.trim()) {
    ElMessage.warning('请配置默认智能体/端点，或填写对应的字段路径')
    return
  }
  connectorSaving.value = true
  try {
    if (editingConnectorId.value) await EapApi.updateManagementConnector(editingConnectorId.value, { ...connectorForm })
    else await EapApi.createManagementConnector({ ...connectorForm })
    ElMessage.success(editingConnectorId.value ? '连接器配置已更新' : '连接器已创建')
    resetConnectorForm()
    connectors.value = await EapApi.listManagementConnectors()
  } finally {
    connectorSaving.value = false
  }
}

async function toggleConnector(connector: ManagementValueConnector, enabled: boolean) {
  const updated = await EapApi.setManagementConnectorEnabled(connector.id, enabled)
  Object.assign(connector, updated)
  ElMessage.success(enabled ? '定时同步已启用' : '定时同步已停用')
}

function onConnectorEnabledChange(connector: ManagementValueConnector, value: string | number | boolean) {
  return toggleConnector(connector, Boolean(value))
}

async function syncConnector(connector: ManagementValueConnector) {
  syncingConnectorId.value = connector.id
  try {
    const result = await EapApi.syncManagementConnector(connector.id)
    if (result.success) ElMessage.success(`同步完成：导入 ${result.importedRecords} 条，跳过 ${result.skippedRecords} 条`)
    else ElMessage.warning(result.message)
    connectors.value = await EapApi.listManagementConnectors()
    if (result.importedRecords > 0) await load()
  } finally {
    syncingConnectorId.value = undefined
  }
}

function connectorStatusLabel(status: ManagementConnectorStatus) {
  return ({ NEVER_SYNCED: '待首次同步', RUNNING: '同步中', HEALTHY: '正常', DEGRADED: '部分失败', ERROR: '异常', DISABLED: '已停用' } as Record<ManagementConnectorStatus, string>)[status]
}

function connectorStatusType(status: ManagementConnectorStatus): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  return ({ NEVER_SYNCED: 'info', RUNNING: 'primary', HEALTHY: 'success', DEGRADED: 'warning', ERROR: 'danger', DISABLED: 'info' } as const)[status]
}

function agentName(agentId?: number) {
  if (!agentId) return '未知智能体'
  return agents.value.find(agent => agent.id === agentId)?.name || `智能体 ${agentId}`
}

function sourceTypeLabel(value: string) {
  return ({ MANUAL: '人工确认', BUSINESS_SYSTEM: '业务系统', DERIVED: '链路推导' } as Record<string, string>)[value] || value
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: value >= 1_000_000 ? 'compact' : 'standard' }).format(value)
}

function formatMoney(value: number | null) {
  if (value == null || !summary.value.currency) return '—'
  return `${new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)} ${summary.value.currency}`
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function managementValueTypeLabel(value: string) {
  const labels: Record<string, string> = {
    LABOR_SAVING: '节省人工成本',
    EFFICIENCY_GAIN: '效率提升',
    COST_REDUCTION: '成本下降',
    REVENUE: '提升收入',
    LOSS_AVOIDANCE: '减少损失',
    QUALITY_IMPROVEMENT: '质量提升',
    OTHER: '其他已验证价值',
  }
  return labels[value] || value
}

onMounted(load)
</script>
