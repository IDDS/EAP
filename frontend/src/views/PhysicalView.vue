<template>
  <section class="page-grid physical-page">
    <ConsolePageHeader>
      <template #actions><div v-if="activeSection !== 'executions'" class="button-row">
        <el-select v-model="windowHours" aria-label="统计时间范围" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-button v-if="activeSection === 'overview' || activeSection === 'assets'" :icon="Search" :loading="scanLoading" :disabled="!permissions.can('deployAgents')" @click="triggerDiscovery">自主发现</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div></template>
    </ConsolePageHeader>



    <AgentExecutionExplorer v-if="activeSection === 'executions'" :initial-agent-id="contextAgentId" />
    <el-alert v-if="loadError && activeSection !== 'executions'" :title="loadError" type="warning" show-icon :closable="false" />
    <el-empty v-if="!summaryLoaded && activeSection !== 'executions'" :description="loading ? '正在加载遥测' : '遥测数据暂不可用'" />
    <template v-if="summaryLoaded && activeSection !== 'executions'">
    <div v-if="activeSection === 'overview'" class="analysis-hero physical-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">最近 {{ windowHours }} 小时</span>
        <h2>运行全景</h2>
        <p>定位运行位置、调用链路，以及 Token、费用和延迟的流向。</p>
      </div>
      <div class="physical-health-panel">
        <span class="analysis-eyebrow">运行状况</span>
        <div class="physical-health-grid">
          <div><strong>{{ summary.onlineRuntimeCount }}</strong><span>在线</span></div>
          <div><strong>{{ summary.degradedRuntimeCount }}</strong><span>降级</span></div>
          <div><strong>{{ summary.offlineRuntimeCount }}</strong><span>离线</span></div>
        </div>
        <div class="physical-health-foot"><span>端点覆盖</span><b>{{ summary.activeEndpoints }}/{{ summary.registeredEndpoints }}</b></div>
      </div>
    </div>

    <div v-if="activeSection === 'overview'" class="physical-ops-strip">
      <div class="physical-ops-card">
        <div><span>端点覆盖率</span><strong>{{ endpointCoveragePercent }}%</strong></div>
        <el-progress :percentage="endpointCoveragePercent" :show-text="false" :stroke-width="6" color="#3167e8" />
        <small>{{ summary.activeEndpoints }} 个活跃 / {{ summary.registeredEndpoints }} 个注册</small>
      </div>
      <div class="physical-ops-card">
        <div><span>运行实例健康度</span><strong>{{ runtimeHealthPercent }}%</strong></div>
        <el-progress :percentage="runtimeHealthPercent" :show-text="false" :stroke-width="6" color="#16a085" />
        <small>{{ summary.onlineRuntimeCount }} 在线 · {{ summary.degradedRuntimeCount }} 降级 · {{ summary.offlineRuntimeCount }} 离线</small>
      </div>
      <div class="physical-ops-card physical-ops-card-compact"><span>观测吞吐</span><strong>{{ formatNumber(summary.totalCalls) }}</strong><small>最近 {{ windowHours }} 小时模型调用</small></div>
      <div class="physical-ops-card physical-ops-card-compact"><span>延迟窗口</span><strong>{{ summary.averageLatencyMs }} ms</strong><small>P95 {{ summary.p95LatencyMs }} ms</small></div>
    </div>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前列表已按上下文收敛</span>
      <router-link v-if="contextAgentId" :to="`/agents/${contextAgentId}`">返回智能体</router-link>
      <el-button link type="primary" @click="clearDrilldown">查看全部记录</el-button>
    </div>

    <el-card v-if="contextTraceId" shadow="never" class="panel trace-inspector">
      <template #header>
        <div class="panel-header">
          <span>Trace 观测详情</span>
          <div class="trace-inspector-header-actions">
            <el-tag type="primary" effect="plain">{{ contextTraceId }}</el-tag>
            <router-link v-if="contextTaskId" :to="{ path: '/operations', query: { taskId: String(contextTaskId) } }">返回关联任务 #{{ contextTaskId }}</router-link>
            <el-button link type="primary" @click="openGovernanceFromTrace">查看治理链路</el-button>
          </div>
        </div>
      </template>
      <div class="trace-inspector-summary">
        <div><span>Span 总数</span><strong>{{ traceStats.total }}</strong></div>
        <div><span>模型 / 工具</span><strong>{{ traceStats.models }} / {{ traceStats.tools }}</strong></div>
        <div><span>MCP / RAG / A2A</span><strong>{{ traceStats.mcp }} / {{ traceStats.rag }} / {{ traceStats.a2a }}</strong></div>
        <div><span>最长 Span</span><strong>{{ traceStats.maxDuration }} ms</strong></div>
        <div><span>内容观测</span><strong>{{ permissions.can('viewContent') ? visibleContentObservations.length : '无权限' }}</strong></div>
      </div>
      <TraceDiagnostics :spans="visibleTraceSpans" :trace-id="contextTraceId" :possibly-truncated="traceSpans.length >= 500" />
      <div class="trace-inspector-grid">
        <div class="trace-timeline">
          <div v-for="(span, index) in visibleTraceSpans" :key="span.id" class="trace-timeline-item">
            <div class="trace-timeline-marker"><span>{{ String(index + 1).padStart(2, '0') }}</span></div>
            <div class="trace-timeline-body">
            <div class="trace-timeline-title"><strong>{{ span.spanName }}</strong><el-tag size="small" :type="traceTagType(span.spanKind)">{{ span.synthetic ? '遥测根 Span' : traceKindLabel(span.spanKind) }}</el-tag></div>
              <div class="muted">{{ span.operation || '未命名操作' }} · {{ invocationStatusLabel(span.status) }} · {{ span.durationMs }} ms</div>
              <div class="trace-timeline-links">{{ span.toolName || '—' }} · {{ span.mcpServerName || '—' }} · {{ span.ragSource || '—' }}</div>
            </div>
          </div>
          <el-empty v-if="visibleTraceSpans.length === 0" description="当前 Trace 尚无 Span 观测" />
        </div>
        <div v-if="permissions.can('viewContent')" class="trace-evidence-panel">
          <div class="trace-evidence-heading"><span>内容观测</span><small>仅展示脱敏元数据</small></div>
          <div v-for="observation in visibleContentObservations" :key="observation.id" class="trace-evidence-row">
            <el-tag size="small" :type="observation.direction === 'INPUT' ? 'warning' : 'success'">{{ contentDirectionLabel(observation.direction) }}</el-tag>
            <div><strong>{{ captureModeLabel(observation.captureMode) }}</strong><span>{{ observation.sensitivity || '未标注' }} · {{ observation.estimatedTokens }} Token</span></div>
          </div>
          <span v-if="visibleContentObservations.length === 0" class="muted">当前 Trace 没有内容观测记录</span>
        </div>
      </div>
    </el-card>

    <el-alert
      v-if="!summary.lastEventAt && (activeSection === 'overview' || activeSection === 'models')"
      type="warning"
      :closable="false"
      show-icon
      title="尚未收到真实模型调用遥测。物理界面不会使用模拟 Token 数据，请通过遥测接口或模型网关上报。"
    />

    <div v-show="activeSection === 'ingest'">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>模型调用接入验证</span>
          <span class="muted">先执行治理预检，通过后再写入真实遥测元数据</span>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="recordInvocation">
        <div class="form-row">
          <el-form-item label="调用事件标识" required>
            <el-input v-model="telemetryForm.eventKey" placeholder="例如 fin-analysis-call-001" />
          </el-form-item>
          <el-form-item label="关联智能体" required>
            <el-select v-model="telemetryForm.agentId" style="width: 100%" placeholder="选择智能体">
              <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} (${agent.code})`" :value="agent.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="模型供应商" required>
            <el-input v-model="telemetryForm.provider" placeholder="openai / azure-openai / local" />
          </el-form-item>
          <el-form-item label="模型名称" required>
            <el-input v-model="telemetryForm.modelName" placeholder="例如 gpt-5 / qwen3" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="数据类别" required>
            <el-input v-model="telemetryForm.dataCategory" placeholder="例如 BUSINESS_ANALYSIS" />
          </el-form-item>
          <el-form-item label="敏感级别" required>
            <el-select v-model="telemetryForm.sensitivity" style="width: 100%">
              <el-option label="公开" value="PUBLIC" />
              <el-option label="内部" value="INTERNAL" />
              <el-option label="机密" value="CONFIDENTIAL" />
              <el-option label="秘密" value="SECRET" />
              <el-option label="严格限制" value="RESTRICTED" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="共享 API Key 用量池别名">
          <el-input v-model="telemetryForm.usagePoolKey" placeholder="例如 shared-openai-production；不要填写真实 API Key" />
        </el-form-item>
        <div class="form-row telemetry-number-row">
          <el-form-item label="输入 Token"><el-input-number v-model="telemetryForm.inputTokens" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="输出 Token"><el-input-number v-model="telemetryForm.outputTokens" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="延迟 ms"><el-input-number v-model="telemetryForm.latencyMs" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="费用 USD"><el-input-number v-model="telemetryForm.moneyCost" :min="0" :precision="6" :controls="false" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="调用状态">
            <el-select v-model="telemetryForm.status" style="width: 100%">
              <el-option label="成功" value="SUCCEEDED" />
              <el-option label="失败" value="FAILED" />
              <el-option label="进行中" value="STARTED" />
              <el-option label="取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="DLP 检测类型">
            <el-input v-model="telemetryForm.detectedTypesText" placeholder="PHONE,ID_CARD；可留空" />
          </el-form-item>
        </div>
        <div class="governance-switch-row">
          <el-checkbox v-model="telemetryForm.internalData">包含内部信息</el-checkbox>
          <el-checkbox v-model="telemetryForm.companySecret">涉及公司保密信息</el-checkbox>
        </div>
        <div class="button-row governance-submit-row">
          <span class="muted">不提交提示词和模型输出正文；阻断或待脱敏时不会写入模型调用记录。</span>
          <el-button type="primary" :loading="telemetrySaving" :disabled="!permissions.can('governance')" @click="recordInvocation">预检并记录调用</el-button>
        </div>
      </el-form>
    </el-card>
    </div>

    <div v-show="activeSection === 'overview'" class="metric-strip">
      <div class="small-box box-primary">
        <div><strong>{{ formatNumber(summary.totalTokens) }}</strong><span>总 Token</span></div>
        <el-icon><Tickets /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ summary.totalCalls }}</strong><span>模型调用</span></div>
        <el-icon><Connection /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ successPercent }}%</strong><span>调用成功率</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
      <div class="small-box box-warning">
        <div><strong>{{ formatMoney(summary.moneyCost) }}</strong><span>模型费用 USD</span></div>
        <el-icon><Money /></el-icon>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="physical-stat-grid">
      <div><span>输入 Token</span><strong>{{ formatNumber(summary.inputTokens) }}</strong></div>
      <div><span>输出 Token</span><strong>{{ formatNumber(summary.outputTokens) }}</strong></div>
      <div><span>缓存输入</span><strong>{{ formatNumber(summary.cachedInputTokens) }}</strong></div>
      <div><span>推理 Token</span><strong>{{ formatNumber(summary.reasoningTokens) }}</strong></div>
      <div><span>平均延迟</span><strong>{{ summary.averageLatencyMs }} ms</strong></div>
      <div><span>P95 延迟</span><strong>{{ summary.p95LatencyMs }} ms</strong></div>
      <div><span>活跃端点</span><strong>{{ summary.activeEndpoints }}/{{ summary.registeredEndpoints }}</strong></div>
      <div><span>运行实例</span><strong>{{ summary.onlineRuntimeCount }} 在线</strong></div>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel usage-reconciliation-panel">
      <template #header>
        <div class="panel-header">
          <span>共享 API Key 用量对账</span>
          <span class="muted">供应商总账 − Agent 调用级遥测 = 未分配用量</span>
        </div>
      </template>
      <div class="physical-stat-grid usage-reconciliation-stats">
        <div><span>供应商快照</span><strong>{{ reconciliation.snapshotCount }}</strong></div>
        <div><span>共享用量池</span><strong>{{ reconciliation.usagePoolCount }}</strong></div>
        <div><span>未标池调用</span><strong>{{ reconciliation.unmappedTelemetryCalls }}</strong></div>
        <div><span>未标池 Token</span><strong>{{ formatNumber(reconciliation.unmappedTelemetryTokens) }}</strong></div>
      </div>
      <el-alert
        v-if="reconciliation.pools.length === 0"
        type="info"
        :closable="false"
        show-icon
        title="尚未形成共享用量池。调用侧请上报 usagePoolKey，供应商侧通过 provider-usage-snapshots 接口导入同名账单窗口。"
      />
      <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}`" v-else :data="reconciliation.pools" size="small">
        <el-table-column label="用量池" min-width="210">
          <template #default="{ row }">
            <strong class="mono">{{ row.usagePoolKey }}</strong>
            <div class="muted">{{ row.provider }} · {{ row.accountLabel || '未设置账户别名' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="供应商总量" width="135">
          <template #default="{ row }"><strong>{{ formatNumber(row.providerTotalTokens) }}</strong><div class="muted">{{ formatMoney(row.providerMoneyCost) }} {{ row.currency }}</div></template>
        </el-table-column>
        <el-table-column label="已归因" width="135">
          <template #default="{ row }"><strong>{{ formatNumber(row.attributedTotalTokens) }}</strong><div class="muted">{{ row.attributedAgents }} Agent · {{ row.attributedCalls }} 调用</div></template>
        </el-table-column>
        <el-table-column label="未分配" width="135">
          <template #default="{ row }"><strong>{{ formatNumber(row.unallocatedTokens) }}</strong><div class="muted">{{ formatMoney(row.unallocatedMoneyCost) }} {{ row.currency }}</div></template>
        </el-table-column>
        <el-table-column label="Token 覆盖率" width="160">
          <template #default="{ row }"><el-progress :percentage="Math.round(row.tokenCoverageRate * 100)" :stroke-width="7" /></template>
        </el-table-column>
        <el-table-column label="对账状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.snapshotCount === 0" type="info">待供应商快照</el-tag>
            <el-tag v-else-if="row.overAttributedTokens > 0" type="danger">超额 {{ formatNumber(row.overAttributedTokens) }}</el-tag>
            <el-tag v-else-if="row.unallocatedTokens > 0" type="warning">存在差额</el-tag>
            <el-tag v-else type="success">已对平</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账单窗口" min-width="205">
          <template #default="{ row }"><span>{{ row.providerWindowStart ? formatTime(row.providerWindowStart) : '尚无快照' }}</span><div class="muted">至 {{ row.providerWindowEnd ? formatTime(row.providerWindowEnd) : '—' }}</div></template>
        </el-table-column>
      </PaginatedTable>
      <div class="usage-reconciliation-note">这里只保存共享凭证别名，不保存 API Key；差额不会平均摊派给各智能体。</div>
    </el-card>

    <div v-show="activeSection === 'overview'" class="chart-grid physical-chart-grid">
      <el-card shadow="never" class="panel chart-panel chart-panel-wide">
        <template #header>
          <div class="panel-header">
            <span>调用 / Token / 延迟走势</span>
            <span class="muted">按 {{ windowHours }} 小时窗口聚合可见调用</span>
          </div>
        </template>
        <TrendChart :option="physicalTrendOption" />
      </el-card>
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>模型消耗直方图</span>
            <span class="muted">Token</span>
          </div>
        </template>
        <TrendChart :option="modelUsageOption" height="270px" />
      </el-card>
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>智能体 Token 消耗排行</span>
            <span class="muted">输入 / 输出</span>
          </div>
        </template>
        <TrendChart :option="agentTokenUsageOption" height="270px" />
      </el-card>
    </div>

    <el-card v-show="activeSection === 'assets'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>物理结构与端点分布</span>
          <span class="muted">端点 → 智能体 / 智能体项目 / MCP 配置 / 技能与插件</span>
        </div>
      </template>
      <div class="physical-node-grid">
        <div v-for="node in visiblePhysicalNodes" :key="node.key" class="physical-node-card">
          <div class="physical-node-head">
            <div>
              <strong>{{ node.hostName }}</strong>
              <span>{{ node.endpointKey || '本机扫描器' }}</span>
            </div>
            <el-tag :type="node.active ? 'success' : 'info'" effect="plain">{{ node.active ? '活跃' : '已观测' }}</el-tag>
          </div>
          <div class="physical-node-stats">
            <span>智能体 <strong>{{ node.agentCount }}</strong></span>
            <span>MCP <strong>{{ node.mcpCount }}</strong></span>
            <span>扩展 <strong>{{ node.extensionCount }}</strong></span>
            <span>高风险 <strong>{{ node.highRiskCount }}</strong></span>
          </div>
          <small>最后发现：{{ node.lastSeenAt ? formatTime(node.lastSeenAt) : '尚无资产观察' }}</small>
          <el-button v-if="node.endpointKey" class="node-drilldown-button" link type="primary" @click="openRegistry(node.endpointKey)">查看注册资产 →</el-button>
        </div>
        <el-empty v-if="visiblePhysicalNodes.length === 0" description="尚未发现端点或物理资产" />
      </div>
    </el-card>

    <div v-show="activeSection === 'assets'" class="physical-two-column">
      <el-card shadow="never" class="panel">
        <template #header>智能体 / 端点消耗排行</template>
        <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}`" :data="visibleSubjectUsage" size="small">
          <el-table-column prop="subjectName" label="智能体 / 端点" min-width="170" />
          <el-table-column prop="calls" label="调用" width="75" />
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="成功率" width="95">
            <template #default="{ row }">{{ Math.round(row.successRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="平均延迟" width="110">
            <template #default="{ row }">{{ row.averageLatencyMs }} ms</template>
          </el-table-column>
          <el-table-column label="费用" width="95">
            <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
          </el-table-column>
        </PaginatedTable>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>模型消耗排行</template>
        <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}`" :data="summary.modelUsage" size="small">
          <el-table-column label="模型" min-width="220"><template #default="{ row }"><div class="model-table-name"><ModelGlyph :provider="row.provider" size="xs" /><span><strong>{{ row.modelName }}</strong><small>{{ row.provider }}</small></span></div></template></el-table-column>
          <el-table-column prop="calls" label="调用" width="75" />
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="费用" width="95">
            <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
          </el-table-column>
        </PaginatedTable>
      </el-card>
    </div>

    <el-card v-show="activeSection === 'models'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>最近模型调用</span>
          <span class="muted">只保存物理遥测元数据，不在此表保存提示词与模型输出</span>
        </div>
      </template>
      <HistoryTable ref="modelHistory" :fetch-page="fetchModelHistory" :reset-key="route.fullPath" :statuses="['STARTED','SUCCEEDED','FAILED','CANCELLED']" placeholder="搜索标识、名称或 Trace">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="智能体 / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `智能体 ${row.agentId}` : row.endpointKey }}</strong>
            <div class="muted">{{ row.runtimeInstanceKey || row.traceId || row.eventKey }}</div>
            <div v-if="row.governanceDecisionKey" class="muted">治理 {{ row.governanceDecisionKey }}</div>
            <div class="table-tag-row">
              <el-tag size="small" effect="plain">{{ row.observationSource }}</el-tag>
              <el-tag v-if="row.usagePoolKey" size="small" type="info" effect="plain">池 {{ row.usagePoolKey }}</el-tag>
              <el-tag size="small" :type="row.governanceCovered ? 'success' : 'warning'">
                {{ row.governanceCovered ? '治理覆盖' : '被动发现' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="160">
          <template #default="{ row }"><div class="model-table-name"><ModelGlyph :provider="row.provider" size="xs" /><span><strong>{{ row.modelName }}</strong><small>{{ row.provider }}</small></span></div></template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ invocationStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Token" width="145">
          <template #default="{ row }">
            <strong>{{ formatNumber(row.totalTokens) }}</strong>
            <div class="muted">{{ formatNumber(row.inputTokens) }} / {{ formatNumber(row.outputTokens) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="100">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="工具 / MCP" width="115">
          <template #default="{ row }">{{ row.toolCallCount }} / {{ row.mcpServerCount }}</template>
        </el-table-column>
        <el-table-column label="费用" width="100">
          <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
        </el-table-column>
        <el-table-column label="钻取" width="120" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="openGovernance(row)">治理</el-button>
              <el-button link @click="openKnowledge(row)">知识</el-button>
              <el-button link :disabled="!row.traceId" @click="openTrace(row)">Trace</el-button>
            </div>
          </template>
        </el-table-column>
      </HistoryTable>
    </el-card>

    <el-card v-show="activeSection === 'trace'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>深度内容观测</span>
          <span class="muted">输入 / 输出正文仅在 REDACTED 模式保存；HASH_ONLY 只保留哈希、长度和 Token 估算</span>
        </div>
      </template>
      <el-alert v-if="!permissions.can('viewContent')" title="当前账号无内容查看权限；普通遥测与 Trace 元数据仍可查看。" type="info" :closable="false" />
      <el-alert v-else-if="contentError" :title="contentError" type="warning" :closable="false" />
      <HistoryTable v-if="permissions.can('viewContent')" ref="contentHistory" :fetch-page="fetchContentHistory" :reset-key="route.fullPath" :statuses="['INPUT','OUTPUT','TOOL_INPUT','TOOL_OUTPUT','SYSTEM']" placeholder="搜索标识、名称或 Trace">
        <el-table-column label="方向" width="100">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'INPUT' ? 'warning' : 'success'">{{ contentDirectionLabel(row.direction) }}</el-tag>
            <div class="muted">{{ captureModeLabel(row.captureMode) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="Trace / 事件" min-width="230">
          <template #default="{ row }">
            <strong>{{ row.traceId || '无 Trace' }}</strong>
            <div class="muted">{{ row.eventKey || row.observationKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="150">
          <template #default="{ row }"><div class="model-table-name"><ModelGlyph :provider="row.provider" size="xs" /><span><strong>{{ row.modelName || '-' }}</strong><small>{{ row.provider || '企业模型' }}</small></span></div></template>
        </el-table-column>
        <el-table-column label="正文观测" min-width="420">
          <template #default="{ row }">
            <pre v-if="row.contentJson" class="content-observation-preview">{{ contentPreview(row.contentJson) }}</pre>
            <div v-else class="muted">仅保留哈希 {{ row.contentHash }} · {{ row.contentBytes }} 字节 · 约 {{ row.estimatedTokens }} Token</div>
          </template>
        </el-table-column>
        <el-table-column label="治理" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.governanceAction" effect="plain">{{ row.governanceAction }}</el-tag>
            <div class="muted">{{ row.sensitivity || '未标注' }}<span v-if="row.redactionApplied"> · 已脱敏</span></div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="175">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
      <el-table-column label="诊断" width="90" fixed="right"><template #default="{ row }"><el-button link :disabled="!row.traceId" @click="openTrace(row)">Trace</el-button></template></el-table-column>
      </HistoryTable>
    </el-card>

    <el-card v-show="activeSection === 'trace'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>智能体深度 Trace</span>
          <span class="muted">已持久化 OTLP Span 历史；模型调用补充节点见上方诊断图</span>
        </div>
      </template>
      <HistoryTable ref="spanHistory" :fetch-page="fetchSpanHistory" :reset-key="route.fullPath" :statuses="['STARTED','SUCCEEDED','FAILED','CANCELLED']" placeholder="搜索标识、名称或 Trace">
        <el-table-column label="类型" width="100">
          <template #default="{ row }"><el-tag size="small" :type="traceTagType(row.spanKind)">{{ row.synthetic ? '遥测根 Span' : traceKindLabel(row.spanKind) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="Span" min-width="230">
          <template #default="{ row }"><strong>{{ row.spanName }}</strong><div class="muted">{{ row.traceId }} · {{ row.spanId }}</div></template>
        </el-table-column>
        <el-table-column label="工具 / MCP / RAG" min-width="220">
          <template #default="{ row }">{{ row.toolName || '-' }} · {{ row.mcpServerName || '-' }} · {{ row.ragSource || '-' }}</template>
        </el-table-column>
        <el-table-column label="智能体" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.agentId ? 'success' : 'info'">
              {{ row.agentId ? `#${row.agentId}` : '未归因' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态 / 延迟" width="145">
          <template #default="{ row }">{{ invocationStatusLabel(row.status) }} · {{ row.durationMs }} ms</template>
        </el-table-column>
        <el-table-column label="端点" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.endpointKey }}</template>
        </el-table-column>
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatTime(row.observedAt) }}</template></el-table-column>
      <el-table-column label="诊断" width="90" fixed="right"><template #default="{ row }"><el-button link :disabled="!row.traceId" @click="openTrace(row)">Trace</el-button></template></el-table-column>
      </HistoryTable>
    </el-card>
    <el-card v-show="activeSection === 'tools'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>工具调用明细</span>
          <span class="muted">记录工具/MCP 的实际观测、输入输出观测关联和调用状态</span>
        </div>
      </template>
      <HistoryTable ref="toolHistory" :fetch-page="fetchToolHistory" :reset-key="route.fullPath" :statuses="['REQUESTED','SUCCEEDED','FAILED','UNKNOWN']" placeholder="搜索标识、名称或 Trace">
        <el-table-column label="工具" min-width="190">
          <template #default="{ row }">
            <strong>{{ row.toolName }}</strong>
            <div class="muted">{{ row.mcpServerName || row.toolKind }}</div>
          </template>
        </el-table-column>
        <el-table-column label="智能体" width="95">
          <template #default="{ row }"><el-tag size="small" :type="row.agentId ? 'success' : 'info'">{{ row.agentId ? `#${row.agentId}` : '未归因' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="来源" width="95">
          <template #default="{ row }">{{ row.observationSource === 'OTLP' ? 'OTLP' : row.observationSource === 'RUNTIME_GUARD' ? '工具接入' : '模型网关' }}</template>
        </el-table-column>
        <el-table-column label="状态 / 耗时" width="145">
          <template #default="{ row }"><el-tag size="small" :type="toolStatusType(row.status)">{{ toolStatusLabel(row.status) }}</el-tag> · {{ row.durationMs }} ms</template>
        </el-table-column>
        <el-table-column label="Trace / Span" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.traceId || '-' }} · {{ row.spanId || '-' }}</template>
        </el-table-column>
        <el-table-column label="内容观测" min-width="180">
          <template #default="{ row }">
            <span>{{ row.inputObservationKey ? '有输入' : '无输入' }} · {{ row.outputObservationKey ? '有输出' : '无输出' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatTime(row.observedAt) }}</template></el-table-column>
      <el-table-column label="诊断" width="90" fixed="right"><template #default="{ row }"><el-button link :disabled="!row.traceId" @click="openTrace(row)">Trace</el-button></template></el-table-column>
      </HistoryTable>
    </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { usePageSection } from '@/composables/usePageSection'
import HistoryTable, { type HistoryParams } from '@/components/common/HistoryTable.vue'
import PaginatedTable from '@/components/common/PaginatedTable.vue'
import TraceDiagnostics from '@/components/trace/TraceDiagnostics.vue'
import AgentExecutionExplorer from '@/components/trace/AgentExecutionExplorer.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { CircleCheck, Connection, Money, Refresh, Search, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { usePermissions } from '@/stores/permissions'
import { EapApi } from '@/api/eap'
import { useRoute, useRouter } from 'vue-router'
import TrendChart from '@/components/TrendChart.vue'
import ModelGlyph from '@/components/ModelGlyph.vue'
import type { EChartsOption } from 'echarts'
import type {
  Agent,
  ContentObservation,
  DiscoveredAgentAsset,
  DiscoveryEndpoint,
  GovernanceDataSensitivity,
  ModelInvocationEvent,
  ModelInvocationStatus,
  PhysicalTelemetrySummary,
  UsageReconciliationSummary,
  TraceSpanObservation,
  TraceSpanKind,
  ToolInvocationEvent,
  ToolInvocationStatus,
} from '@/types/domain'

const permissions = usePermissions()
const summaryLoaded = ref(false)
const contentError = ref('')
const emptySummary: PhysicalTelemetrySummary = {
  windowHours: 24,
  totalCalls: 0,
  succeededCalls: 0,
  failedCalls: 0,
  successRate: 0,
  inputTokens: 0,
  outputTokens: 0,
  cachedInputTokens: 0,
  reasoningTokens: 0,
  totalTokens: 0,
  moneyCost: 0,
  averageLatencyMs: 0,
  p95LatencyMs: 0,
  registeredEndpoints: 0,
  activeEndpoints: 0,
  onlineRuntimeCount: 0,
  degradedRuntimeCount: 0,
  offlineRuntimeCount: 0,
  subjectUsage: [],
  modelUsage: [],
}

const emptyReconciliation: UsageReconciliationSummary = {
  windowHours: 24,
  windowStart: '',
  windowEnd: '',
  snapshotCount: 0,
  usagePoolCount: 0,
  unmappedTelemetryCalls: 0,
  unmappedTelemetryTokens: 0,
  unmappedTelemetryMoneyCost: 0,
  pools: [],
}

const windowHours = ref(24)
const activeSection = usePageSection()
const loading = ref(false)
const scanLoading = ref(false)
const telemetrySaving = ref(false)
const summary = ref<PhysicalTelemetrySummary>({ ...emptySummary })
const reconciliation = ref<UsageReconciliationSummary>({ ...emptyReconciliation })
const events = ref<ModelInvocationEvent[]>([])
const contentObservations = ref<ContentObservation[]>([])
const traceSpans = ref<TraceSpanObservation[]>([])
const toolInvocations = ref<ToolInvocationEvent[]>([])
const endpoints = ref<DiscoveryEndpoint[]>([])
const assets = ref<DiscoveredAgentAsset[]>([])
const agents = ref<Agent[]>([])
const telemetryForm = reactive({
  eventKey: '',
  agentId: undefined as number | undefined,
  provider: 'openai-compatible',
  modelName: 'demo-model',
  usagePoolKey: '',
  dataCategory: 'BUSINESS_ANALYSIS',
  sensitivity: 'INTERNAL' as GovernanceDataSensitivity,
  internalData: true,
  companySecret: false,
  detectedTypesText: '',
  status: 'SUCCEEDED' as ModelInvocationStatus,
  inputTokens: 0,
  outputTokens: 0,
  latencyMs: 0,
  moneyCost: 0,
})

const route = useRoute()
const router = useRouter()
const contextAgentId = computed(() => {
  const value = Number(route.query.agentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const contextTaskId = computed(() => { const id = Number(route.query.taskId); return Number.isSafeInteger(id) && id > 0 ? id : undefined })
const contextTraceId = computed(() => String(route.query.traceId || '').trim())
const contextEventKey = computed(() => String(route.query.eventKey || '').trim())
const drilldownLabel = computed(() => {
  const parts: string[] = []
  if (contextAgentId.value) parts.push(`智能体 #${contextAgentId.value}`)
  if (contextEndpointKey.value) parts.push(`端点 ${contextEndpointKey.value}`)
  if (contextTraceId.value) parts.push(`Trace ${contextTraceId.value}`)
  if (contextEventKey.value) parts.push(`事件 ${contextEventKey.value}`)
  return parts.join(' · ')
})


const successPercent = computed(() => Math.round(summary.value.successRate * 100))
const endpointCoveragePercent = computed(() => summary.value.registeredEndpoints > 0
  ? Math.min(100, Math.round(summary.value.activeEndpoints / summary.value.registeredEndpoints * 100))
  : 0)
const runtimeHealthPercent = computed(() => {
  const total = summary.value.onlineRuntimeCount + summary.value.degradedRuntimeCount + summary.value.offlineRuntimeCount
  return total > 0 ? Math.min(100, Math.round(summary.value.onlineRuntimeCount / total * 100)) : 0
})
const physicalNodes = computed(() => {
  const nodes = new Map<string, {
    key: string
    hostName: string
    endpointKey?: string
    active: boolean
    agentCount: number
    mcpCount: number
    extensionCount: number
    highRiskCount: number
    lastSeenAt?: string
  }>()
  endpoints.value.forEach((endpoint) => nodes.set(endpoint.hostName, {
    key: endpoint.endpointKey,
    hostName: endpoint.hostName,
    endpointKey: endpoint.endpointKey,
    active: endpoint.status === 'ACTIVE',
    agentCount: 0,
    mcpCount: 0,
    extensionCount: 0,
    highRiskCount: 0,
    lastSeenAt: endpoint.lastSeenAt,
  }))
  assets.value.forEach((asset) => {
    const host = asset.hostName || '未知设备'
    const node = nodes.get(host) || {
      key: `host:${host}`,
      hostName: host,
      active: false,
      agentCount: 0,
      mcpCount: 0,
      extensionCount: 0,
      highRiskCount: 0,
      lastSeenAt: undefined,
    }
    if (asset.assetType === 'MCP_CONFIGURATION') node.mcpCount++
    else if (asset.assetType === 'AGENT_SKILL' || asset.assetType === 'AGENT_PLUGIN') node.extensionCount++
    else node.agentCount++
    if (asset.riskLevel === 'HIGH' || asset.riskLevel === 'CRITICAL') node.highRiskCount++
    if (!node.lastSeenAt || new Date(asset.lastSeenAt) > new Date(node.lastSeenAt)) node.lastSeenAt = asset.lastSeenAt
    nodes.set(host, node)
  })
  return Array.from(nodes.values())
})
const visiblePhysicalNodes = computed(() => contextEndpointKey.value
  ? physicalNodes.value.filter(node => node.endpointKey === contextEndpointKey.value)
  : physicalNodes.value)
const visibleEvents = computed(() => events.value.filter(event => {
  if (contextAgentId.value && event.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && event.endpointKey !== contextEndpointKey.value) return false
  if (contextTraceId.value && event.traceId !== contextTraceId.value) return false
  if (contextEventKey.value && event.eventKey !== contextEventKey.value) return false
  return true
}))
const visibleSubjectUsage = computed(() => summary.value.subjectUsage.filter(subject => {
  if (contextAgentId.value && subject.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && subject.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const visibleContentObservations = computed(() => contentObservations.value.filter(observation => {
  if (contextAgentId.value && observation.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && observation.endpointKey !== contextEndpointKey.value) return false
  if (contextTraceId.value && observation.traceId !== contextTraceId.value) return false
  if (contextEventKey.value && observation.eventKey !== contextEventKey.value) return false
  return true
}))
const visibleTraceSpans = computed<TraceSpanObservation[]>(() => {
  const spans = traceSpans.value.filter(span => {
    if (contextAgentId.value && span.agentId !== contextAgentId.value) return false
    if (contextEndpointKey.value && span.endpointKey !== contextEndpointKey.value) return false
    if (contextTraceId.value && span.traceId !== contextTraceId.value) return false
    return true
  })
  const fallbackSpans = visibleEvents.value
    .filter(event => Boolean(event.traceId))
    .filter(event => !spans.some(span => representsModelEvent(span, event)))
    .map(event => ({
      id: -event.id,
      traceId: event.traceId as string,
      spanId: event.spanId || `model-${event.id}`,
      endpointKey: event.endpointKey || 'telemetry-api',
      spanName: `${event.provider} · ${event.modelName}`,
      spanKind: 'MODEL' as TraceSpanKind,
      operation: 'MODEL_INVOCATION',
      status: event.status,
      durationMs: event.latencyMs || 0,
      attributesJson: JSON.stringify({
        source: event.observationSource,
        eventKey: event.eventKey,
        totalTokens: event.totalTokens,
        governanceCovered: event.governanceCovered,
      }),
      observedAt: event.observedAt,
      synthetic: true,
    }))
  return [...spans, ...fallbackSpans].sort((left, right) =>
    new Date(left.observedAt).getTime() - new Date(right.observedAt).getTime())
})
function representsModelEvent(span: TraceSpanObservation, event: ModelInvocationEvent): boolean {
  if (span.spanKind !== 'MODEL' || span.traceId !== event.traceId) return false
  if (!event.spanId || span.spanId === event.spanId) return true
  try {
    const attributes = JSON.parse(span.attributesJson || '{}')
    return attributes?.['eap.projection.source'] === 'CONTENT_PROXY'
      && attributes?.['eap.projection.original_span_id'] === event.spanId
  } catch {
    return false
  }
}

const traceStats = computed(() => {
  const spans = visibleTraceSpans.value
  return {
    total: spans.length,
    models: spans.filter(span => span.spanKind === 'MODEL').length,
    tools: spans.filter(span => span.spanKind === 'TOOL').length,
    mcp: spans.filter(span => span.spanKind === 'MCP').length,
    rag: spans.filter(span => span.spanKind === 'RAG').length,
    a2a: spans.filter(span => span.spanKind === 'A2A').length,
    maxDuration: spans.reduce((max, span) => Math.max(max, span.durationMs || 0), 0),
  }
})
const physicalTrendOption = computed<EChartsOption>(() => {
  const bucketCount = windowHours.value <= 24 ? 12 : 16
  const timestamps = visibleEvents.value.map(event => new Date(event.observedAt).getTime()).filter(Number.isFinite)
  const end = timestamps.length ? Math.max(...timestamps) : Date.now()
  const windowMs = windowHours.value * 60 * 60 * 1000
  const start = end - windowMs
  const bucketMs = windowMs / bucketCount
  const calls = Array.from({ length: bucketCount }, () => 0)
  const tokens = Array.from({ length: bucketCount }, () => 0)
  const latencyTotals = Array.from({ length: bucketCount }, () => 0)
  const latencyCounts = Array.from({ length: bucketCount }, () => 0)
  visibleEvents.value.forEach(event => {
    const time = new Date(event.observedAt).getTime()
    const index = Math.max(0, Math.min(bucketCount - 1, Math.floor((time - start) / bucketMs)))
    calls[index]++
    tokens[index] += event.totalTokens || 0
    latencyTotals[index] += event.latencyMs || 0
    latencyCounts[index]++
  })
  const labels = Array.from({ length: bucketCount }, (_, index) => {
    const point = new Date(start + index * bucketMs)
    return windowHours.value <= 24
      ? point.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      : point.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
  })
  return {
    animationDuration: 400,
    color: ['#3167e8', '#16a085', '#d68b2c'],
    grid: { top: 38, right: 44, bottom: 28, left: 48 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels, axisLabel: { color: '#8795a9', fontSize: 10 } },
    yAxis: [
      { type: 'value', name: '调用 / Token', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
      { type: 'value', name: 'ms', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { show: false } },
    ],
    series: [
      { name: '调用', type: 'bar', barMaxWidth: 18, data: calls, itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: 'Token', type: 'line', smooth: true, yAxisIndex: 0, symbol: 'none', data: tokens },
      { name: '平均延迟', type: 'line', smooth: true, yAxisIndex: 1, symbol: 'circle', symbolSize: 5, data: latencyCounts.map((count, index) => count ? Math.round(latencyTotals[index] / count) : 0) },
    ],
  }
})
const modelUsageOption = computed<EChartsOption>(() => {
  const rows = [...summary.value.modelUsage].sort((left, right) => right.totalTokens - left.totalTokens).slice(0, 8)
  return {
    grid: { top: 8, right: 18, bottom: 22, left: 92 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => `${row.provider} · ${row.modelName}`), axisLabel: { color: '#5f7188', fontSize: 10 } },
    series: [{ type: 'bar', barMaxWidth: 18, data: rows.map(row => row.totalTokens), itemStyle: { color: '#7b61d9', borderRadius: [0, 5, 5, 0] } }],
  }
})
const agentTokenUsageOption = computed<EChartsOption>(() => {
  const rows = [...visibleSubjectUsage.value]
    .sort((left, right) => right.totalTokens - left.totalTokens)
    .slice(0, 10)
  return {
    color: ['#3167e8', '#0c9f9a'],
    grid: { top: 28, right: 18, bottom: 22, left: 112 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 10 } },
    xAxis: { type: 'value', name: 'Token', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => row.subjectName || (row.agentId ? `智能体 #${row.agentId}` : row.subjectKey)), axisLabel: { color: '#5f7188', fontSize: 10, width: 96, overflow: 'truncate' } },
    series: [
      { name: '输入 Token', type: 'bar', stack: 'tokens', barMaxWidth: 18, data: rows.map(row => row.inputTokens), itemStyle: { borderRadius: [4, 0, 0, 4] } },
      { name: '输出 Token', type: 'bar', stack: 'tokens', barMaxWidth: 18, data: rows.map(row => row.outputTokens), itemStyle: { borderRadius: [0, 4, 4, 0] } },
    ],
  }
})

function openRegistry(endpointKey?: string) {
  if (!endpointKey) return
  router.push({ path: '/registry', query: { endpointKey } })
}

function openGovernance(row: ModelInvocationEvent) {
  router.push({ path: '/governance', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
    eventKey: row.eventKey,
  } })
}

function openKnowledge(row: ModelInvocationEvent) {
  router.push({ path: '/knowledge', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
    traceId: row.traceId,
  } })
}

function openTrace(row: {agentId?:number;endpointKey?:string;traceId?:string}) {
  if (!row.traceId) return
  router.push({ path: '/physical', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
    traceId: row.traceId,
  } })
}

function openGovernanceFromTrace() {
  router.push({ path: '/governance', query: {
    agentId: contextAgentId.value ? String(contextAgentId.value) : undefined,
    endpointKey: contextEndpointKey.value || undefined,
  } })
}

function clearDrilldown() {
  router.replace({ path: '/physical', query: { section: activeSection.value } })
}

const modelHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchModelHistory(params:HistoryParams) { return EapApi.pageModelHistory({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,traceId:contextTraceId.value || undefined,eventKey:contextEventKey.value || undefined}) }
const contentHistory = ref<InstanceType<typeof HistoryTable>>()
async function fetchContentHistory(params:HistoryParams) {
  const user = permissions.state.user
  if (!permissions.can('viewContent')) return { items: [], totalElements: 0 }
  const result = await EapApi.pageContentHistory({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,traceId:contextTraceId.value || undefined,eventKey:contextEventKey.value || undefined})
  return user === permissions.state.user && permissions.can('viewContent') ? result : { items: [], totalElements: 0 }
}
async function fetchContentObservations() {
  const version = loadVersion
  const user = permissions.state.user
  if (!permissions.can('viewContent')) return []
  try {
    const rows = await EapApi.listContentObservations({ limit: 500, agentId: contextAgentId.value || undefined, endpointKey: contextEndpointKey.value || undefined, traceId: contextTraceId.value || undefined })
    return user === permissions.state.user && permissions.can('viewContent') ? rows : []
  } catch {
    if (version === loadVersion && user === permissions.state.user && permissions.can('viewContent')) contentError.value = '内容观测加载失败；普通遥测不受影响，请刷新重试。'
    return []
  }
}
const spanHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchSpanHistory(params:HistoryParams) { return EapApi.pageSpanHistory({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,traceId:contextTraceId.value || undefined}) }
const toolHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchToolHistory(params:HistoryParams) { return EapApi.pageToolHistory({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,traceId:contextTraceId.value || undefined}) }
const loadError = ref('')
let loadVersion = 0
let disposed = false
async function load() {
  const version = ++loadVersion
  summaryLoaded.value = false
  contentObservations.value = []
  if (!permissions.can('viewConsole')) { loading.value = false; return }
  loading.value = true
  contentError.value = ''
  loadError.value = ''
  try {
    const [nextSummary, nextReconciliation, nextEvents, nextEndpoints, nextAssets, nextAgents, nextContent, nextTraceSpans, nextToolInvocations] = await Promise.all([
      EapApi.getPhysicalTelemetrySummary(windowHours.value),
      EapApi.getUsageReconciliation(windowHours.value),
      EapApi.listModelInvocationEvents({ limit: 500, agentId: contextAgentId.value || undefined, endpointKey: contextEndpointKey.value || undefined, traceId: contextTraceId.value || undefined }),
      EapApi.listDiscoveryEndpoints(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listAgents(),
      fetchContentObservations(),
      EapApi.listTraceSpanObservations({ limit: 500, agentId: contextAgentId.value || undefined, endpointKey: contextEndpointKey.value || undefined, traceId: contextTraceId.value || undefined }),
      EapApi.listToolInvocationEvents({ limit: 500, agentId: contextAgentId.value || undefined, endpointKey: contextEndpointKey.value || undefined, traceId: contextTraceId.value || undefined }),
    ])
    if (disposed || version !== loadVersion) return
    summary.value = nextSummary
    reconciliation.value = nextReconciliation
    events.value = nextEvents
    endpoints.value = nextEndpoints
    assets.value = nextAssets
    agents.value = nextAgents
    contentObservations.value = permissions.can('viewContent') ? nextContent : []
    summaryLoaded.value = true
    traceSpans.value = nextTraceSpans
    toolInvocations.value = nextToolInvocations
    if (!telemetryForm.agentId) telemetryForm.agentId = nextAgents[0]?.id
    for (const table of [modelHistory,contentHistory,spanHistory,toolHistory]) void table.value?.reload()
  } catch {
    if (!disposed && version === loadVersion) loadError.value = '遥测加载失败，当前未显示旧数据或零值指标，请刷新重试。'
  } finally {
    if (!disposed && version === loadVersion) loading.value = false
  }
}

function traceTagType(kind: TraceSpanKind) {
  if (kind === 'MODEL') return 'primary'
  if (kind === 'MCP' || kind === 'TOOL') return 'warning'
  if (kind === 'RAG') return 'success'
  return 'info'
}

async function triggerDiscovery() {
  if (!permissions.can('deployAgents')) return
  scanLoading.value = true
  try {
    const run = await EapApi.triggerAgentDiscoveryScan()
    ElMessage.success(`自主发现完成：观察 ${run.observationsCount} 条，识别 ${run.discoveredCount} 个资产`)
    await load()
  } finally {
    scanLoading.value = false
  }
}

async function recordInvocation() {
  if (!permissions.can('governance')) return
  if (!telemetryForm.eventKey.trim() || !telemetryForm.agentId || !telemetryForm.provider.trim() || !telemetryForm.modelName.trim()) {
    ElMessage.warning('请填写事件标识、智能体、模型供应商和模型名称')
    return
  }
  telemetrySaving.value = true
  try {
    const eventKey = telemetryForm.eventKey.trim()
    const decision = await EapApi.evaluateGovernancePreflight({
      decisionKey: `physical-${eventKey}-preflight`,
      eventKey: `physical-${eventKey}-governance`,
      agentId: telemetryForm.agentId,
      direction: 'INPUT_TO_MODEL',
      dataCategory: telemetryForm.dataCategory.trim() || 'MODEL_INPUT',
      sensitivity: telemetryForm.sensitivity,
      internalData: telemetryForm.internalData,
      companySecret: telemetryForm.companySecret,
      tokenCount: telemetryForm.inputTokens,
      payloadBytes: 0,
      classifier: 'physical-console-manual',
      provider: telemetryForm.provider.trim(),
      modelName: telemetryForm.modelName.trim(),
      detectedTypes: telemetryForm.detectedTypesText.split(',').map(value => value.trim()).filter(Boolean),
    })
    if (!decision.requestAllowed) {
      ElMessage.error(`治理预检返回 ${decision.gatewayDirective}，模型调用遥测未写入`)
      return
    }
    await EapApi.recordModelInvocation({
      eventKey,
      traceId: `trace-${eventKey}`,
      agentId: telemetryForm.agentId,
      governanceDecisionKey: decision.decisionKey,
      provider: telemetryForm.provider.trim(),
      modelName: telemetryForm.modelName.trim(),
      status: telemetryForm.status,
      inputTokens: telemetryForm.inputTokens,
      outputTokens: telemetryForm.outputTokens,
      cachedInputTokens: 0,
      reasoningTokens: 0,
      latencyMs: telemetryForm.latencyMs,
      moneyCost: telemetryForm.moneyCost,
      currency: 'USD',
      toolCallCount: 0,
      mcpServerCount: 0,
      usagePoolKey: telemetryForm.usagePoolKey.trim() || undefined,
    })
    ElMessage.success(`模型调用已通过治理预检并记录：${decision.gatewayDirective}`)
    Object.assign(telemetryForm, {
      eventKey: '',
      detectedTypesText: '',
      inputTokens: 0,
      outputTokens: 0,
      latencyMs: 0,
      moneyCost: 0,
      companySecret: false,
    })
    await load()
  } finally {
    telemetrySaving.value = false
  }
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: value >= 1_000_000 ? 'compact' : 'standard' }).format(value)
}

function formatMoney(value: number) {
  return Number(value || 0).toFixed(4)
}

function contentPreview(value: string) {
  return value.length > 900 ? `${value.slice(0, 900)}…` : value
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function statusType(status: ModelInvocationStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'STARTED') return 'warning'
  return 'info'
}

function invocationStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    SUCCEEDED: '成功',
    FAILED: '失败',
    STARTED: '已开始',
    CANCELLED: '已取消',
    UNSET: '未设置',
  }
  return labels[value || ''] || value || '未设置'
}

function contentDirectionLabel(value: ContentObservation['direction']) {
  const labels: Record<string, string> = {
    INPUT: '输入',
    OUTPUT: '输出',
    TOOL_INPUT: '工具输入',
    TOOL_OUTPUT: '工具输出',
    SYSTEM: '系统',
  }
  return labels[value] || value
}

function captureModeLabel(value: ContentObservation['captureMode']) {
  const labels: Record<string, string> = {
    OFF: '关闭',
    HASH_ONLY: '仅哈希',
    REDACTED: '已脱敏',
  }
  return labels[value] || value
}

function traceKindLabel(value: TraceSpanKind) {
  const labels: Record<TraceSpanKind, string> = {
    MODEL: '模型调用',
    TOOL: '工具调用',
    MCP: 'MCP 工具',
    RAG: '知识检索',
    A2A: '智能体协作',
    AGENT: '智能体',
  }
  return labels[value] || value
}

function toolStatusType(status: ToolInvocationStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'REQUESTED') return 'warning'
  return 'info'
}

function toolStatusLabel(status: ToolInvocationStatus) {
  const labels: Record<ToolInvocationStatus, string> = {
    REQUESTED: '已请求',
    SUCCEEDED: '成功',
    FAILED: '失败',
    UNKNOWN: '未知',
  }
  return labels[status] || status
}

watch([contextAgentId, contextEndpointKey, contextTraceId, contextEventKey], () => {
  events.value = []
  traceSpans.value = []
  contentObservations.value = []
  toolInvocations.value = []
  void load()
})
watch(() => permissions.state.user, () => {
  contentObservations.value = []
  void load()
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; loadVersion++ })
onMounted(load)
</script>

<style scoped>
@media (max-width: 760px) {
  .physical-page, .physical-page > *, .analysis-hero-copy { min-width: 0; max-width: 100%; }
  .button-row { flex-wrap: wrap; align-items: center; }
  .button-row .el-button { margin-left: 0; }
  .physical-hero { display: grid; grid-template-columns: minmax(0, 1fr); }
  .physical-health-panel { min-width: 0; width: 100%; box-sizing: border-box; }
  .physical-health-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .interface-tabs { flex-wrap: wrap; }
}
</style>
