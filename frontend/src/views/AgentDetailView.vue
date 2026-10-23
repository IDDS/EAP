<template>
  <section class="page-grid agent-detail-page" v-loading="loading">
    <div class="agent-detail-topbar">
      <el-button text :icon="ArrowLeft" @click="router.push('/agents')">返回智能体目录</el-button>
      <div class="agent-detail-actions"><el-button :icon="Refresh" :loading="loading" @click="load">刷新观测</el-button><el-button type="primary" :disabled="loading || !agent || !permissions.can('deployAgents')" @click="changeStatus(agent?.lifecycleStatus === 'ACTIVE' ? 'PAUSED' : 'ACTIVE')">{{ agent?.lifecycleStatus === 'ACTIVE' ? '暂停智能体' : '启用智能体' }}</el-button></div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

    <el-card v-if="agent" shadow="never" class="panel agent-profile-hero">
      <div class="agent-profile-layout">
        <div class="agent-profile-primary">
          <div class="agent-profile-heading">
            <div class="agent-avatar agent-profile-avatar" :class="`agent-avatar-${agent.riskLevel.toLowerCase()}`">
              <span>{{ initials(agent.name) }}</span>
              <i :class="`risk-signal-${agent.riskLevel.toLowerCase()}`"></i>
            </div>
            <div class="agent-profile-copy">
              <div class="agent-profile-eyebrow">
                <span>智能体资产档案</span>
                <b v-if="primaryDiscovery"><i></i>自主发现·已纳管</b>
              </div>
              <div class="agent-profile-title">
                <h2>{{ agent.name }}</h2>
                <span class="agent-state-pill" :class="`agent-state-${agent.lifecycleStatus.toLowerCase()}`">{{ statusText(agent.lifecycleStatus) }}</span>
                <span class="agent-risk-pill" :class="`agent-risk-${agent.riskLevel.toLowerCase()}`">{{ riskText(agent.riskLevel) }}风险</span>
              </div>
              <p>{{ profileSummary }}</p>
            </div>
          </div>

          <details class="agent-profile-disclosure">
            <summary>身份与运行线索 <span>编码、厂商、主机与进程</span></summary>
          <div class="agent-profile-facts">
            <div>
              <span>资产编码</span>
              <strong class="mono">{{ agent.code }}</strong>
            </div>
            <div>
              <span>厂商 / 产品</span>
              <strong>{{ primaryDiscovery ? `${primaryDiscovery.vendor || '未知厂商'} · ${primaryDiscovery.product || agent.name}` : agent.name }}</strong>
            </div>
            <div>
              <span>运行主机</span>
              <strong>{{ primaryDiscovery?.hostName || '尚未关联' }}</strong>
              <small v-if="primaryDiscovery?.accountName">账户 {{ primaryDiscovery.accountName }}</small>
            </div>
            <div>
              <span>运行进程</span>
              <strong>{{ primaryDiscovery?.processName || '尚未发现' }}</strong>
              <small v-if="primaryDiscovery?.executablePath" :title="primaryDiscovery.executablePath">{{ primaryDiscovery.executablePath }}</small>
            </div>
          </div>
          </details>

          <div class="agent-profile-footer">
            <div class="agent-profile-badges">
              <span v-if="primaryDiscovery">发现来源 <b>{{ discoverySourceSummary }}</b></span>
              <span v-if="primaryDiscovery">置信度 <b>{{ primaryDiscovery.confidence }}%</b></span>
              <span>责任归属 <b>{{ ownerSummary }}</b></span>
            </div>
            <small>档案更新 {{ formatDate(agent.updatedAt) }}</small>
          </div>
        </div>

        <aside class="agent-profile-health" :class="`agent-health-${healthTone}`">
          <div class="agent-health-heading">
            <span>运行观测</span>
            <i></i>
          </div>
          <strong>{{ healthLabel }}</strong>
          <p>{{ healthHint }}</p>
          <div class="agent-health-divider"></div>
          <div class="agent-health-meta">
            <span>运行实例 <b>{{ runtimes.length }}</b></span>
            <span>{{ lastObservedLabel }}</span>
          </div>
        </aside>
      </div>
    </el-card>

    <el-card v-if="agentOpsState" shadow="never" class="panel agentops-detail-axis-panel">
      <template #header><div class="panel-header"><span>EAP 五轴状态</span><EvidenceBadge :basis="agentOpsState.statusBasis" :source="`最近 ${context.windowHours} 小时信号`" /></div></template>
      <div class="agentops-detail-axis-grid">
        <div><span>生命周期</span><StatusAxis axis="lifecycle" :value="agentOpsState.lifecycleStatus" /></div>
        <div><span>运行健康</span><StatusAxis axis="health" :value="agentOpsState.operationalHealth" /></div>
        <div><span>接入纳管</span><StatusAxis axis="onboarding" :value="agentOpsState.onboardingState" /></div>
        <div><span>观测覆盖</span><StatusAxis axis="coverage" :value="agentOpsState.observationCoverage" /><small>{{ agentOpsState.coverageScore }} 类信号</small></div>
        <div><span>风险态势</span><StatusAxis axis="risk" :value="agentOpsState.riskPosture" /></div>
        <div><span>数据新鲜度</span><StatusAxis axis="freshness" :value="agentOpsState.dataFreshness" /><small>{{ formatDate(agentOpsState.latestObservedAt) }}</small></div>
      </div>
    </el-card>

    <el-alert v-if="!agent" type="warning" :closable="false" title="未找到该智能体，请返回目录重新选择。" />

    <details v-if="agent" class="agent-setup-guide" :open="route.query.setup === '1'">
      <summary>创建与接入进度 <span>登记、部署与试运行状态</span></summary>
      <div><strong>创建与接入进度</strong><p>登记身份 → 配置部署或关联实例 → 启用 → 试运行与观测验证</p></div>
      <div class="setup-guide-actions"><el-tag>身份已登记</el-tag><el-tag :type="agent.lifecycleStatus === 'ACTIVE' ? 'success' : 'info'">{{ agent.lifecycleStatus === 'ACTIVE' ? '已启用' : '未启用' }}</el-tag><el-tag :type="runtimes.length ? 'success' : 'warning'">{{ runtimes.length ? '已有运行登记' : '待接入运行实例' }}</el-tag><el-tag :type="modelEvents.length && spans.length ? 'success' : 'info'">{{ modelEvents.length && spans.length ? '当前窗口有模型与调用链观测' : '执行与观测待验证' }}</el-tag></div>
      <div class="setup-guide-actions"><el-button link type="primary" @click="activeTab='deploy'">配置部署与试运行</el-button><el-button link type="primary" @click="activeTab='runtime'">查看运行接入</el-button><router-link to="/registry?section=environment">关联已有实例</router-link></div>
    </details>
    <div v-if="agent" class="agent-detail-tabs" role="group" aria-label="智能体详情视图">
      <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" :aria-label="tab.label" :aria-pressed="activeTab === tab.key" @click="activeTab = tab.key"><el-icon aria-hidden="true"><component :is="tab.icon" /></el-icon>{{ tab.label }}<b v-if="tab.count !== undefined">{{ tab.count }}</b></button>
    </div>

    <template v-if="agent">
      <el-alert title="模型、Trace、工具、内容、知识和治理记录分别分页；以下统计基于各区块当前页，不代表全历史总量。" type="info" :closable="false" show-icon />
      <div v-show="activeTab === 'overview'" class="agent-detail-content">
        <div class="metric-strip agent-detail-metrics"><div class="small-box box-primary"><div><strong>{{ formatNumber(totalTokens) }}</strong><span>本页 Token</span></div><el-icon><Tickets /></el-icon></div><div class="small-box box-success"><div><strong>{{ modelEvents.length }}</strong><span>本页模型调用</span></div><el-icon><Connection /></el-icon></div><div class="small-box box-info"><div><strong>{{ tools.length }}</strong><span>本页工具调用</span></div><el-icon><Tools /></el-icon></div><div class="small-box box-warning"><div><strong>{{ formatMoney(totalCost) }}</strong><span>本页费用 USD</span></div><el-icon><Money /></el-icon></div></div>
        <div class="agent-detail-grid agent-detail-grid-main"><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>智能体摘要</span><el-tag size="small" effect="plain">实时数据</el-tag></div></template><div class="attribute-grid"><div><span>业务类别</span><strong>{{ agent.category || '未分类' }}</strong></div><div><span>运行实例</span><strong>{{ runtimes.length }} 个</strong></div><div><span>能力标签</span><strong>{{ capabilities.length }} 项</strong></div><div><span>本页 Trace</span><strong class="mono">{{ latestTraceId || '暂无' }}</strong></div><div><span>成功率</span><strong>{{ successRate }}%</strong></div><div><span>平均延迟</span><strong>{{ averageLatency }} ms</strong></div></div></el-card><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>运行健康</span><span class="muted">当前实例状态</span></div></template><div class="runtime-health-list"><div v-for="runtime in runtimes" :key="runtime.id"><span class="runtime-status-dot" :class="runtime.status.toLowerCase()"></span><div><strong>{{ runtime.instanceKey }}</strong><small>{{ runtime.endpoint }} · {{ runtime.protocol }}</small></div><el-tag size="small" :type="runtime.status === 'ONLINE' ? 'success' : runtime.status === 'DEGRADED' ? 'warning' : 'info'">{{ runtimeStatusLabel(runtime.status) }}</el-tag></div><el-empty v-if="!runtimes.length" description="尚未登记运行实例" :image-size="60" /></div></el-card></div>
        <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>关联运营事项</span><span class="muted">{{ workItems.length }} 项待处理</span></div></template><WorkItemTable :items="workItems" @inspect="inspectWorkItem" @transition="transitionWorkItem" @open-agent="openAgent" /></el-card>
        <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>本页活动</span><el-button link type="primary" @click="activeTab = 'trace'">查看完整 Trace</el-button></div></template><el-table :data="recentActivity" size="small"><el-table-column prop="time" label="时间" width="170" /><el-table-column prop="kind" label="类型" width="110" /><el-table-column prop="title" label="活动" min-width="240" /><el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip /><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.tone">{{ row.status }}</el-tag></template></el-table-column></el-table><el-empty v-if="!recentActivity.length" description="暂无活动记录" :image-size="70" /></el-card>
      </div>

      <div v-show="activeTab === 'runtime'" class="agent-detail-content"><RuntimeInventoryPanel v-if="activeTab === 'runtime' && runtimes.some(r => ['SUBSTRATE_OBSERVED','SUBSTRATE'].includes(r.protocol))" :agent-id="agent.id" /><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>运行实例</span><span class="muted">心跳、端点与负载</span></div></template><el-table :data="runtimes"><el-table-column prop="instanceKey" label="实例" min-width="180" /><el-table-column prop="endpoint" label="端点" min-width="250" /><el-table-column prop="protocol" label="协议" width="110" /><el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : row.status === 'DEGRADED' ? 'warning' : 'info'">{{ runtimeStatusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column label="负载" width="180"><template #default="{ row }"><el-progress :percentage="Math.round(row.loadScore * 100)" :stroke-width="7" :show-text="false" /><small>{{ Math.round(row.loadScore * 100) }}%</small></template></el-table-column><el-table-column label="最后心跳" width="190"><template #default="{ row }">{{ formatDate(row.lastHeartbeatAt) }}</template></el-table-column></el-table><el-empty v-if="!runtimes.length" description="尚未登记运行实例" /></el-card><el-card shadow="never" class="panel"><template #header><span>能力与技能</span></template><div class="capability-list"><el-tag v-for="capability in capabilities" :key="capability.id" effect="plain" size="large">{{ capability.name }}<small>{{ capability.capability }}</small></el-tag><el-empty v-if="!capabilities.length" description="尚未登记能力" :image-size="70" /></div></el-card></div>

      <div v-if="activeTab === 'deploy' && agent" class="agent-detail-content"><SubstrateDeployPanel :agent-id="agent.id" /></div>

      <div v-show="activeTab === 'trace'" class="agent-detail-content"><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>Trace 时间线</span><span class="muted">{{ spans.length }} 个 Span · {{ contentAccessDenied ? '内容观测无访问权限' : `${content.length} 条内容观测` }}</span></div></template><div class="agent-trace-timeline" v-loading="history.spans.loading"><div v-for="(span, index) in spans" :key="span.id" class="agent-trace-row"><div class="agent-trace-index">{{ String(index + 1).padStart(2, '0') }}</div><div class="agent-trace-line"></div><div class="agent-trace-body"><div><strong>{{ span.spanName }}</strong><el-tag size="small" :type="traceTone(span.spanKind)">{{ traceKindLabel(span.spanKind) }}</el-tag><el-tag v-if="span.status" size="small" effect="plain">{{ statusLabel(span.status) }}</el-tag></div><p>{{ span.operation || '未命名操作' }} · {{ span.durationMs }} ms · {{ formatDate(span.observedAt) }}</p><small class="mono">Trace {{ span.traceId }} · Span {{ span.spanId }}</small><div class="trace-attribute-chips"><span v-if="span.toolName">工具：{{ span.toolName }}</span><span v-if="span.mcpServerName">MCP：{{ span.mcpServerName }}</span><span v-if="span.ragSource">知识源：{{ span.ragSource }}</span></div></div></div><el-empty v-if="!spans.length && !history.spans.loading && !history.spans.error" description="该智能体暂无 Trace。请确认 OTLP 或网关遥测已接入。" /></div><HistoryPager :state="history.spans" :count="spans.length" @page="page => loadHistory('spans', page)" /></el-card><el-card shadow="never" class="panel"><template #header><span>输入 / 输出观测</span></template><el-alert v-if="contentAccessDenied" title="当前账号没有查看输入 / 输出观测的权限；其他智能体观测仍可正常查看。" type="warning" :closable="false" show-icon /><el-table v-else :data="content" size="small"><el-table-column label="方向" width="110"><template #default="{ row }"><el-tag size="small" :type="row.direction === 'INPUT' ? 'warning' : 'success'">{{ directionLabel(row.direction) }}</el-tag></template></el-table-column><el-table-column prop="modelName" label="模型" width="160" /><el-table-column prop="captureMode" label="捕获模式" width="120" /><el-table-column prop="estimatedTokens" label="Token" width="100" /><el-table-column prop="sensitivity" label="敏感级别" width="120" /><el-table-column prop="contentHash" label="内容指纹" min-width="250" show-overflow-tooltip /><el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDate(row.observedAt) }}</template></el-table-column></el-table><el-empty v-if="!contentAccessDenied && !content.length && !history.content.loading && !history.content.error" description="暂无内容观测（当前仅展示脱敏元数据）" /><HistoryPager v-if="!contentAccessDenied" :state="history.content" :count="content.length" @page="page => loadHistory('content', page)" /></el-card></div>

      <div v-show="activeTab === 'quality'" class="agent-detail-content">
        <div class="metric-strip agent-detail-metrics">
          <div class="small-box box-primary"><div><strong>{{ evaluationResults.length }}</strong><span>评估次数</span></div><el-icon><DataAnalysis /></el-icon></div>
          <div class="small-box box-success"><div><strong>{{ evaluationPassRate }}%</strong><span>评估通过率</span></div><el-icon><Connection /></el-icon></div>
          <div class="small-box box-info"><div><strong>{{ evaluationAverageScore }}%</strong><span>平均分数</span></div><el-icon><Tickets /></el-icon></div>
          <div class="small-box box-warning"><div><strong>{{ evaluationFailureCount }}</strong><span>未通过结果</span></div><el-icon><Lock /></el-icon></div>
        </div>
        <el-card shadow="never" class="panel">
          <template #header><div class="panel-header"><span>评估器质量分布</span><el-button link type="primary" @click="router.push('/learning')">反馈与提示词对照</el-button><el-button link type="primary" @click="router.push('/evaluation')">打开评估工作台</el-button></div></template>
          <el-table :data="evaluationByEvaluator" size="small">
            <el-table-column prop="evaluatorKey" label="评估器" min-width="220" />
            <el-table-column prop="total" label="次数" width="85" />
            <el-table-column label="通过率" width="180"><template #default="{ row }"><el-progress :percentage="row.passRate" :status="row.failed ? 'exception' : 'success'" /></template></el-table-column>
            <el-table-column label="平均分" width="110"><template #default="{ row }">{{ row.averageScore }}%</template></el-table-column>
            <el-table-column label="平均置信度" width="125"><template #default="{ row }">{{ row.averageConfidence }}%</template></el-table-column>
            <el-table-column prop="failed" label="未通过" width="90" />
          </el-table>
          <el-empty v-if="!evaluationByEvaluator.length" description="该智能体暂无评估结果，可在评估工作台同步近期观测" />
        </el-card>
        <el-card shadow="never" class="panel">
          <template #header><div class="panel-header"><span>最近质量结果</span><span class="muted">结果保留评估版本和 Trace 证据</span></div></template>
          <el-table :data="evaluationResults" size="small">
            <el-table-column prop="evaluatorKey" label="评估器" width="220" />
            <el-table-column label="分数" width="125"><template #default="{ row }"><el-progress :percentage="Math.round(row.score * 100)" :status="row.passed ? 'success' : 'exception'" /></template></el-table-column>
            <el-table-column label="结果" width="95"><template #default="{ row }"><el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column>
            <el-table-column label="置信度" width="100"><template #default="{ row }">{{ Math.round(row.confidence * 100) }}%</template></el-table-column>
            <el-table-column prop="rationale" label="理由" min-width="260" show-overflow-tooltip />
            <el-table-column label="Trace" min-width="210" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.traceId || '未关联' }}</span></template></el-table-column>
            <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDate(row.evaluatedAt) }}</template></el-table-column>
            <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEvaluationTrace(row)">{{ row.traceId ? '查看 Trace' : '评估工作台' }}</el-button></template></el-table-column>
          </el-table>
          <el-empty v-if="!evaluationResults.length" description="该智能体暂无质量结果" />
        </el-card>
      </div>

      <div v-show="activeTab === 'models'" class="agent-detail-content"><div class="agent-detail-grid agent-detail-grid-main"><el-card shadow="never" class="panel"><template #header><span>本页 Token 消耗趋势</span></template><TrendChart :option="tokenTrendOption" height="280px" /></el-card><el-card shadow="never" class="panel"><template #header><span>模型分布</span></template><div class="model-breakdown-list"><div v-for="item in modelBreakdown" :key="item.model"><div class="model-breakdown-name"><ModelGlyph :provider="item.provider" size="xs" /><span><strong>{{ item.model }}</strong><small>{{ item.provider }}</small></span></div><b>{{ formatNumber(item.tokens) }}<small> Token</small></b></div><el-empty v-if="!modelBreakdown.length" description="暂无模型调用" :image-size="60" /></div></el-card></div><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>大模型调用记录</span><span class="muted">完整元数据，不展示提示词正文</span></div></template><el-table v-loading="history.models.loading" :data="modelEvents" size="small"><el-table-column prop="observedAt" label="时间" width="175"><template #default="{ row }">{{ formatDate(row.observedAt) }}</template></el-table-column><el-table-column label="模型" min-width="190"><template #default="{ row }"><div class="model-table-name"><ModelGlyph :provider="row.provider" size="xs" /><span><strong>{{ row.modelName }}</strong><small>{{ row.provider }}</small></span></div></template></el-table-column><el-table-column label="Token" width="140"><template #default="{ row }">{{ formatNumber(row.totalTokens) }}（入 {{ row.inputTokens }} / 出 {{ row.outputTokens }}）</template></el-table-column><el-table-column prop="latencyMs" label="延迟" width="100"><template #default="{ row }">{{ row.latencyMs }} ms</template></el-table-column><el-table-column prop="moneyCost" label="费用" width="110"><template #default="{ row }">{{ row.moneyCost.toFixed(4) }} {{ row.currency }}</template></el-table-column><el-table-column label="Trace" min-width="220"><template #default="{ row }"><span class="mono">{{ row.traceId || '—' }}</span></template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'">{{ modelStatusLabel(row.status) }}</el-tag></template></el-table-column></el-table><el-empty v-if="!modelEvents.length && !history.models.loading && !history.models.error" description="暂无模型调用记录" /><HistoryPager :state="history.models" :count="modelEvents.length" @page="page => loadHistory('models', page)" /></el-card></div>

      <div v-show="activeTab === 'tools'" class="agent-detail-content"><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>工具与 MCP 调用</span><span class="muted">{{ tools.length }} 条观测</span></div></template><el-table v-loading="history.tools.loading" :data="tools"><el-table-column prop="observedAt" label="时间" width="175"><template #default="{ row }">{{ formatDate(row.observedAt) }}</template></el-table-column><el-table-column prop="toolName" label="工具名称" min-width="180" /><el-table-column prop="toolKind" label="类型" width="110"><template #default="{ row }">{{ row.toolKind === 'MCP' ? 'MCP 服务' : '工具' }}</template></el-table-column><el-table-column prop="mcpServerName" label="MCP 服务" min-width="150" /><el-table-column prop="durationMs" label="耗时" width="100"><template #default="{ row }">{{ row.durationMs }} ms</template></el-table-column><el-table-column label="Trace" min-width="220"><template #default="{ row }"><span class="mono">{{ row.traceId || '—' }}</span></template></el-table-column><el-table-column label="状态" width="105"><template #default="{ row }"><el-tag size="small" :type="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'">{{ toolStatusLabel(row.status) }}</el-tag></template></el-table-column></el-table><el-empty v-if="!tools.length && !history.tools.loading && !history.tools.error" description="暂无工具调用记录" /><HistoryPager :state="history.tools" :count="tools.length" @page="page => loadHistory('tools', page)" /></el-card><div class="agent-detail-grid"><el-card shadow="never" class="panel"><template #header><span>知识使用（本页）</span></template><div class="attribute-grid"><div><span>交互次数</span><strong>{{ knowledge.length }}</strong></div><div><span>已采用</span><strong>{{ knowledge.filter(item => item.adopted).length }}</strong></div><div><span>已复用</span><strong>{{ knowledge.filter(item => item.reused).length }}</strong></div><div><span>平均质量</span><strong>{{ knowledgeQuality }}</strong></div></div></el-card><el-card shadow="never" class="panel"><template #header><span>知识交互记录</span></template><div class="compact-record-list"><div v-for="item in knowledge" :key="item.id"><strong>{{ item.inputSummary || item.interactionKey }}</strong><span>{{ statusLabel(item.status) }} · {{ formatDate(item.createdAt) }}</span></div><el-empty v-if="!knowledge.length && !history.knowledge.loading && !history.knowledge.error" description="暂无知识交互" :image-size="60" /></div><HistoryPager :state="history.knowledge" :count="knowledge.length" @page="page => loadHistory('knowledge', page)" /></el-card></div></div>

      <div v-show="activeTab === 'governance'" class="agent-detail-content"><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>治理与数据访问</span><span class="muted">本页内部信息、保密信息与策略动作</span></div></template><div class="governance-agent-summary"><div><span>治理事件</span><strong>{{ governance.length }}</strong></div><div><span>内部信息</span><strong class="is-warning">{{ governance.filter(item => item.internalData).length }}</strong></div><div><span>公司保密信息</span><strong class="is-danger">{{ governance.filter(item => item.companySecret).length }}</strong></div><div><span>阻断 / 脱敏</span><strong>{{ governance.filter(item => ['BLOCK', 'REDACT'].includes(item.action)).length }}</strong></div></div><el-table v-loading="history.governance.loading" :data="governance"><el-table-column prop="observedAt" label="时间" width="175"><template #default="{ row }">{{ formatDate(row.observedAt) }}</template></el-table-column><el-table-column label="方向" width="145"><template #default="{ row }">{{ directionGovernanceLabel(row.direction) }}</template></el-table-column><el-table-column prop="dataCategory" label="数据类别" min-width="180" /><el-table-column prop="sensitivity" label="敏感级别" width="125" /><el-table-column prop="action" label="策略动作" width="130"><template #default="{ row }"><el-tag size="small" :type="row.action === 'BLOCK' ? 'danger' : row.action === 'REDACT' ? 'warning' : 'success'">{{ actionLabel(row.action) }}</el-tag></template></el-table-column><el-table-column label="Trace" min-width="220"><template #default="{ row }"><span class="mono">{{ row.traceId || '—' }}</span></template></el-table-column></el-table><el-empty v-if="!governance.length && !history.governance.loading && !history.governance.error" description="暂无治理事件" /><HistoryPager :state="history.governance" :count="governance.length" @page="page => loadHistory('governance', page)" /></el-card></div>

      <div v-show="activeTab === 'identity'" class="agent-detail-content"><el-card shadow="never" class="panel"><template #header><span>身份与管理属性</span></template><div class="agent-identity-grid"><div><span>智能体 ID</span><strong class="mono">{{ agent.id }}</strong></div><div><span>唯一编码</span><strong class="mono">{{ agent.code }}</strong></div><div><span>责任部门</span><strong>{{ agent.ownerDepartment || '未分配' }}</strong></div><div><span>责任人</span><strong>{{ agent.ownerUser || '未分配' }}</strong></div><div><span>业务类别</span><strong>{{ agent.category || '未分类' }}</strong></div><div><span>创建时间</span><strong>{{ formatDate(agent.createdAt) }}</strong></div><div><span>更新时间</span><strong>{{ formatDate(agent.updatedAt) }}</strong></div><div><span>风险等级</span><strong>{{ riskText(agent.riskLevel) }}</strong></div></div></el-card><el-card v-if="discoveryAssets.length" shadow="never" class="panel"><template #header><div class="panel-header"><span>自主发现证据</span><el-tag type="success" effect="plain">已关联目录身份</el-tag></div></template><div v-for="asset in discoveryAssets" :key="asset.id" class="agent-discovery-evidence"><div class="agent-identity-grid"><div><span>厂商 / 产品</span><strong>{{ asset.vendor }} · {{ asset.product }}</strong></div><div><span>发现主机</span><strong>{{ asset.hostName || '未知' }}</strong></div><div><span>系统账户</span><strong>{{ asset.accountName || '未知' }}</strong></div><div><span>运行进程</span><strong>{{ asset.processName || '未发现' }}</strong></div><div><span>执行路径</span><strong :title="asset.executablePath">{{ asset.executablePath || '未知' }}</strong></div><div><span>发现来源</span><strong>{{ asset.sourceTypes.map(discoverySourceLabel).join(' / ') }}</strong></div><div><span>置信度</span><strong>{{ asset.confidence }}%</strong></div><div><span>证据数量</span><strong>{{ asset.evidenceCount }} 条</strong></div><div><span>最后发现</span><strong>{{ formatDate(asset.lastSeenAt) }}</strong></div></div></div></el-card><el-card shadow="never" class="panel"><template #header><span>关联任务</span></template><el-table :data="tasks"><el-table-column prop="taskType" label="任务类型" min-width="180" /><el-table-column prop="inputSummary" label="输入摘要" min-width="260" show-overflow-tooltip /><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="tokenCost" label="Token" width="100" /><el-table-column prop="createdAt" label="创建时间" width="175"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column></el-table><el-empty v-if="!tasks.length" description="暂无关联任务" /></el-card></div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Collection, Connection, DataAnalysis, Document, Lock, Money, Refresh, Tickets, Tools, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { EChartsOption } from 'echarts'
import TrendChart from '@/components/TrendChart.vue'
import HistoryPager from '@/components/HistoryPager.vue'
import type { Ref } from 'vue'
import ModelGlyph from '@/components/ModelGlyph.vue'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import WorkItemTable from '@/components/WorkItemTable.vue'
import RuntimeInventoryPanel from '@/components/RuntimeInventoryPanel.vue'
import SubstrateDeployPanel from '@/components/substrate/SubstrateDeployPanel.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import { usePermissions } from '@/stores/permissions'
import type { Agent, AgentCapability, AgentOpsAgentState, AgentOpsWorkItem, AgentRuntimeInstance, AgentTask, ContentDirection, ContentObservation, DiscoveredAgentAsset, EvaluationResult, GovernanceDataAction, GovernanceDataEvent, KnowledgeInteraction, ModelInvocationEvent, ModelInvocationStatus, RiskLevel, RuntimeStatus, ToolInvocationEvent, TraceSpanKind, TraceSpanObservation, WorkItemStatus } from '@/types/domain'

const permissions = usePermissions()
const route = useRoute(); const router = useRouter()
const context = useAgentOpsContextStore()
const loading = ref(false); const agent = ref<Agent>(); const activeTab = ref('overview')
const capabilities = ref<AgentCapability[]>([]); const runtimes = ref<AgentRuntimeInstance[]>([]); const modelEvents = ref<ModelInvocationEvent[]>([]); const spans = ref<TraceSpanObservation[]>([]); const tools = ref<ToolInvocationEvent[]>([]); const content = ref<ContentObservation[]>([]); const knowledge = ref<KnowledgeInteraction[]>([]); const governance = ref<GovernanceDataEvent[]>([]); const tasks = ref<AgentTask[]>([])
const contentAccessDenied = ref(false)
const loadError = ref('')
const discoveryAssets = ref<DiscoveredAgentAsset[]>([])
const evaluationResults = ref<EvaluationResult[]>([])
const agentOpsState = ref<AgentOpsAgentState>()
const workItems = ref<AgentOpsWorkItem[]>([])
const agentId = computed(() => Number(route.params.id))
const tabs = computed(() => [{ key: 'overview', label: '总览', icon: DataAnalysis }, { key: 'runtime', label: '运行与能力', icon: Connection, count: runtimes.value.length }, { key: 'deploy', label: '部署与运行', icon: Upload }, { key: 'trace', label: 'Trace 观测', icon: Document, count: spans.value.length }, { key: 'quality', label: '评估质量', icon: DataAnalysis, count: evaluationResults.value.length }, { key: 'models', label: '模型与 Token', icon: Tickets, count: modelEvents.value.length }, { key: 'tools', label: '工具与知识', icon: Tools, count: tools.value.length }, { key: 'governance', label: '治理风险', icon: Lock, count: governance.value.length }, { key: 'identity', label: '身份属性', icon: Collection }])
const primaryDiscovery = computed(() => discoveryAssets.value[0])
const profileSummary = computed(() => primaryDiscovery.value ? '由 EAP 自主发现并纳入企业智能体目录，可统一查看运行、调用链路与治理风险。' : agent.value?.description || '尚未填写业务说明')
const discoverySourceSummary = computed(() => primaryDiscovery.value?.sourceTypes.map(discoverySourceLabel).join(' / ') || '未知')
const ownerSummary = computed(() => [agent.value?.ownerDepartment, agent.value?.ownerUser].filter(Boolean).join(' · ') || '待认领')
const totalTokens = computed(() => modelEvents.value.reduce((sum, item) => sum + (item.totalTokens || 0), 0)); const totalCost = computed(() => modelEvents.value.reduce((sum, item) => sum + (item.moneyCost || 0), 0)); const successRate = computed(() => modelEvents.value.length ? Math.round(modelEvents.value.filter(item => item.status === 'SUCCEEDED').length / modelEvents.value.length * 100) : 0); const averageLatency = computed(() => modelEvents.value.length ? Math.round(modelEvents.value.reduce((sum, item) => sum + item.latencyMs, 0) / modelEvents.value.length) : 0); const latestTraceId = computed(() => modelEvents.value.find(item => item.traceId)?.traceId || spans.value[0]?.traceId || ''); const healthLabel = computed(() => runtimes.value.some(item => item.status === 'ONLINE') ? '健康' : runtimes.value.some(item => item.status === 'DEGRADED') ? '降级' : runtimes.value.length ? '离线' : '未接入'); const healthTone = computed(() => healthLabel.value === '健康' ? 'success' : healthLabel.value === '降级' ? 'warning' : healthLabel.value === '未接入' ? 'neutral' : 'danger'); const healthHint = computed(() => healthLabel.value === '健康' ? '运行实例心跳正常，观测链路可用' : healthLabel.value === '降级' ? '部分实例存在异常，建议及时检查' : healthLabel.value === '离线' ? '已登记实例，但当前未收到心跳' : '尚未发现运行实例心跳'); const lastObservedLabel = computed(() => modelEvents.value[0] ? `本页首条模型观测 ${formatDate(modelEvents.value[0].observedAt)}` : '暂无模型调用观测')
const knowledgeQuality = computed(() => knowledge.value.length ? `${Math.round(knowledge.value.reduce((sum, item) => sum + (item.qualityScore || 0), 0) / knowledge.value.length * 100)}%` : '—')
const evaluationPassRate = computed(() => evaluationResults.value.length ? Math.round(evaluationResults.value.filter(item => item.passed).length / evaluationResults.value.length * 100) : 0)
const evaluationAverageScore = computed(() => evaluationResults.value.length ? Math.round(evaluationResults.value.reduce((sum, item) => sum + item.score, 0) / evaluationResults.value.length * 100) : 0)
const evaluationFailureCount = computed(() => evaluationResults.value.filter(item => !item.passed).length)
const evaluationByEvaluator = computed(() => {
  const grouped = new Map<string, { evaluatorKey: string; total: number; passed: number; failed: number; score: number; confidence: number }>()
  evaluationResults.value.forEach(item => {
    const current = grouped.get(item.evaluatorKey) || { evaluatorKey: item.evaluatorKey, total: 0, passed: 0, failed: 0, score: 0, confidence: 0 }
    current.total += 1
    current.passed += item.passed ? 1 : 0
    current.failed += item.passed ? 0 : 1
    current.score += item.score
    current.confidence += item.confidence
    grouped.set(item.evaluatorKey, current)
  })
  return [...grouped.values()].map(item => ({
    evaluatorKey: item.evaluatorKey,
    total: item.total,
    failed: item.failed,
    passRate: Math.round(item.passed / item.total * 100),
    averageScore: Math.round(item.score / item.total * 100),
    averageConfidence: Math.round(item.confidence / item.total * 100),
  }))
})
const modelBreakdown = computed(() => { const map = new Map<string, { model: string; provider: string; tokens: number }>(); modelEvents.value.forEach(item => { const key = `${item.provider}/${item.modelName}`; const current = map.get(key) || { model: item.modelName, provider: item.provider, tokens: 0 }; current.tokens += item.totalTokens || 0; map.set(key, current) }); return [...map.values()].sort((a, b) => b.tokens - a.tokens).slice(0, 8) })
const tokenTrendOption = computed<EChartsOption>(() => { const rows = [...modelEvents.value].slice(0, 14).reverse(); return { tooltip: { trigger: 'axis' }, grid: { left: 42, right: 16, top: 18, bottom: 28 }, xAxis: { type: 'category', data: rows.map(item => formatTime(item.observedAt)), axisLabel: { color: '#7b8ba3' } }, yAxis: { type: 'value', axisLabel: { color: '#7b8ba3' }, splitLine: { lineStyle: { color: '#e8eef6' } } }, series: [{ type: 'bar', barMaxWidth: 22, data: rows.map(item => item.totalTokens || 0), itemStyle: { color: '#3267e8', borderRadius: [4, 4, 0, 0] } }] } })
const recentActivity = computed(() => [...modelEvents.value.slice(0, 6).map(item => ({ time: formatDate(item.observedAt), kind: '模型调用', title: `${item.provider} · ${item.modelName}`, detail: `${formatNumber(item.totalTokens)} Token · ${item.latencyMs} ms`, status: modelStatusLabel(item.status), tone: item.status === 'SUCCEEDED' ? 'success' : 'danger' })), ...tools.value.slice(0, 4).map(item => ({ time: formatDate(item.observedAt), kind: item.toolKind === 'MCP' ? 'MCP' : '工具', title: item.toolName, detail: `${item.durationMs} ms · ${item.traceId || '无 Trace'}`, status: toolStatusLabel(item.status), tone: item.status === 'SUCCEEDED' ? 'success' : 'warning' }))].slice(0, 8))

type HistoryKind = 'models' | 'spans' | 'tools' | 'content' | 'knowledge' | 'governance'
type HistoryRow = ModelInvocationEvent | TraceSpanObservation | ToolInvocationEvent | ContentObservation | KnowledgeInteraction | GovernanceDataEvent
const historyKinds: HistoryKind[] = ['models', 'spans', 'tools', 'content', 'knowledge', 'governance']
const historyRows: Record<HistoryKind, Ref<HistoryRow[]>> = {
  models: modelEvents, spans, tools, content, knowledge, governance,
}
const history = reactive(Object.fromEntries(historyKinds.map(kind => [kind, {
  page: 0, requestedPage: 0, loading: false, hasNext: false, error: '', requestId: 0,
}])) as Record<HistoryKind, { page: number; requestedPage: number; loading: boolean; hasNext: boolean; error: string; requestId: number }>)
const historyPageSize = 50

function resetHistory() {
  for (const kind of historyKinds) {
    const state = history[kind]
    state.requestId++
    state.page = 0
    state.requestedPage = 0
    state.loading = false
    state.hasNext = false
    state.error = ''
    historyRows[kind].value = []
  }
  contentAccessDenied.value = false
}

async function fetchHistory(kind: HistoryKind, id: number, page: number): Promise<HistoryRow[]> {
  const params = { agentId: id, limit: historyPageSize, page }
  switch (kind) {
    case 'models': return EapApi.listModelInvocationEvents(params)
    case 'spans': return EapApi.listTraceSpanObservations(params)
    case 'tools': return EapApi.listToolInvocationEvents(params)
    case 'content': return EapApi.listContentObservations(params)
    case 'knowledge': return EapApi.listKnowledgeInteractions(historyPageSize, { agentId: id, page })
    case 'governance': return EapApi.listGovernanceDataEvents(historyPageSize, { agentId: id, page })
  }
}

function clearContentHistory() {
  const state = history.content
  state.requestId++
  state.page = 0
  state.requestedPage = 0
  state.loading = false
  state.hasNext = false
  state.error = ''
  content.value = []
  contentAccessDenied.value = !permissions.can('viewContent')
}

async function loadHistory(kind: HistoryKind, page = 0) {
  if (page < 0) return
  if (kind === 'content' && !permissions.can('viewContent')) { clearContentHistory(); return }
  const user = permissions.state.user
  const id = agentId.value
  const state = history[kind]
  const requestId = ++state.requestId
  state.requestedPage = page
  state.loading = true
  state.error = ''
  try {
    const rows = await fetchHistory(kind, id, page)
    if (requestId !== state.requestId || id !== agentId.value) return
    if (kind === 'content' && (user !== permissions.state.user || !permissions.can('viewContent'))) { clearContentHistory(); return }
    if (kind === 'content') contentAccessDenied.value = false
    // An empty next page establishes the end without replacing the last nonempty page.
    if (page > state.page && rows.length === 0) {
      state.hasNext = false
      return
    }
    historyRows[kind].value = rows
    state.page = page
    // List APIs preserve their array contract; a next-page probe also works with server-side size caps.
    state.hasNext = rows.length > 0
  } catch (error) {
    if (requestId !== state.requestId || id !== agentId.value) return
    if (kind === 'content' && (user !== permissions.state.user || !permissions.can('viewContent'))) { clearContentHistory(); return }
    if (kind === 'content' && (error as { response?: { status?: number } }).response?.status === 403) {
      contentAccessDenied.value = true
      content.value = []
      state.hasNext = false
    } else {
      state.error = '该区块加载失败，已保留当前页，请重试。'
    }
  } finally {
    if (requestId === state.requestId) state.loading = false
  }
}

let loadRequestId = 0
async function load() {
  const requestId = ++loadRequestId
  const id = agentId.value
  loading.value = true
  loadError.value = ''
  agent.value = undefined
  capabilities.value = []; runtimes.value = []; tasks.value = []
  discoveryAssets.value = []; agentOpsState.value = undefined; workItems.value = []; evaluationResults.value = []
  resetHistory()
  if (!Number.isSafeInteger(id) || id <= 0) { loading.value = false; return }
  try {
    const [agents, nextCapabilities, nextRuntimes, nextTasks, nextDiscoveryAssets, nextAgentOpsState, nextWorkItems, nextEvaluationResults] = await Promise.all([
      EapApi.listAgents(),
      EapApi.listAgentCapabilities(id),
      EapApi.listAgentRuntimeInstances(id),
      EapApi.listTasks(),
      EapApi.listManagedAgentDiscoveryAssets(id),
      EapApi.getAgentOpsAgent(id, context.windowHours),
      EapApi.listAgentOpsWorkItems({ agentId: id, limit: 100 }),
      EapApi.listEvaluationResults({ agentId: id, limit: 100 }).catch(() => [] as EvaluationResult[]),
      ...historyKinds.map(kind => loadHistory(kind)),
    ])
    if (requestId !== loadRequestId || id !== agentId.value) return
    agent.value = agents.find(item => item.id === id)
    capabilities.value = nextCapabilities
    runtimes.value = nextRuntimes
    tasks.value = nextTasks.filter(item => item.agentId === id)
    discoveryAssets.value = nextDiscoveryAssets
    agentOpsState.value = nextAgentOpsState
    workItems.value = nextWorkItems.filter(item => !['RESOLVED', 'CLOSED'].includes(item.status))
    evaluationResults.value = nextEvaluationResults
  } catch {
    if (requestId === loadRequestId) loadError.value = '智能体详情加载失败，请刷新重试。'
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}
async function changeStatus(status: 'ACTIVE' | 'PAUSED') { if (!permissions.can('deployAgents') || !agent.value) return; await EapApi.updateAgentStatus(agent.value.id, status); ElMessage.success(status === 'ACTIVE' ? '智能体已启用' : '智能体已暂停'); await load() }
async function transitionWorkItem(item: AgentOpsWorkItem, status: WorkItemStatus) {
  let assignee = item.assignee
  let reason: string | undefined
  try {
    if (status === 'CLAIMED') {
      const result = await ElMessageBox.prompt('填写本次事项负责人', '认领运营事项', { inputValue: item.assignee || '当前操作员', confirmButtonText: '认领', cancelButtonText: '取消' })
      assignee = result.value
    } else if (status === 'RESOLVED') {
      const result = await ElMessageBox.prompt('说明处置结果和验证方式', '解决运营事项', { inputType: 'textarea', confirmButtonText: '提交', cancelButtonText: '取消' })
      reason = result.value
    }
  } catch {
    return
  }
  await EapApi.transitionAgentOpsWorkItem(item.id, { status, assignee, reason })
  ElMessage.success('运营事项状态已更新')
  await load()
}
function inspectWorkItem(item: AgentOpsWorkItem) { let evidence = item.evidenceJson || '无结构化证据'; try { evidence = JSON.stringify(JSON.parse(evidence), null, 2) } catch { /* 保留原始证据 */ } void ElMessageBox.alert(`${item.description}\n\n${evidence}`, item.title, { confirmButtonText: '关闭' }) }
function openAgent(id: number) { if (id !== agentId.value) router.push(`/agents/${id}`) }
function openEvaluationTrace(result: EvaluationResult) { if (result.traceId) router.push({ path: '/physical', query: { traceId: result.traceId, agentId: String(agentId.value) } }); else router.push('/evaluation') }
function initials(name: string) { return Array.from(name || 'A').slice(0, 2).join('').toUpperCase() }; function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN').format(value || 0) }; function formatMoney(value: number) { return `$${(value || 0).toFixed(4)}` }; function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—' }; function formatTime(value?: string) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '—' }
function statusText(status: string) { return ({ DRAFT: '草稿', REVIEWING: '审核中', ACTIVE: '运行中', PAUSED: '已暂停', RETIRED: '已下线', SUCCEEDED: '成功', FAILED: '失败', STARTED: '进行中', CANCELLED: '已取消', RUNNING: '运行中', REQUESTED: '已请求', UNKNOWN: '未知' } as Record<string, string>)[status] || status }; function modelStatusLabel(status: ModelInvocationStatus) { return statusText(status) }; function toolStatusLabel(status: string) { return statusText(status) }; function runtimeStatusLabel(status: RuntimeStatus) { return ({ ONLINE: '在线', DEGRADED: '降级', OFFLINE: '离线' } as Record<string, string>)[status] || status }; function riskText(risk: RiskLevel) { return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '关键' } as Record<string, string>)[risk] }; function riskType(risk: RiskLevel) { return risk === 'LOW' ? 'success' : risk === 'MEDIUM' ? 'warning' : 'danger' }; function statusType(status: string) { return status === 'ACTIVE' || status === 'SUCCEEDED' || status === 'ONLINE' ? 'success' : status === 'PAUSED' || status === 'DEGRADED' || status === 'STARTED' ? 'warning' : status === 'FAILED' || status === 'CRITICAL' ? 'danger' : 'info' }; function traceKindLabel(kind: TraceSpanKind) { return ({ MODEL: '模型', TOOL: '工具', MCP: 'MCP', RAG: '知识检索', A2A: '智能体协作', AGENT: '智能体' } as Record<string, string>)[kind] || kind }; function traceTone(kind: TraceSpanKind) { return kind === 'MODEL' ? 'primary' : kind === 'TOOL' || kind === 'MCP' ? 'warning' : kind === 'RAG' ? 'success' : 'info' }; function statusLabel(status: string) { return statusText(status) }; function directionLabel(direction: ContentDirection) { return ({ INPUT: '模型输入', OUTPUT: '模型输出', TOOL_INPUT: '工具输入', TOOL_OUTPUT: '工具输出', SYSTEM: '系统信息' } as Record<string, string>)[direction] || direction }; function directionGovernanceLabel(direction: string) { return ({ INPUT_TO_MODEL: '进入大模型', OUTPUT_FROM_MODEL: '模型输出', TOOL_INPUT: '工具输入', TOOL_OUTPUT: '工具输出' } as Record<string, string>)[direction] || direction }; function actionLabel(action: GovernanceDataAction) { return ({ ALLOW: '允许', WARN: '告警', REDACT: '脱敏', BLOCK: '阻断', APPROVAL_REQUIRED: '需审批' } as Record<string, string>)[action] || action }
function discoverySourceLabel(source: string) { return ({ PROCESS: '运行进程', MANIFEST: '项目清单', CONFIGURATION: '本地配置', SKILL: '智能体技能', PLUGIN: '智能体插件', EXTERNAL: '外部系统', DOCKER: 'Docker 容器', A2A: 'A2A 协议', MCP_REGISTRY: 'MCP 注册中心', KUBERNETES_WATCH: 'Kubernetes 监听', KUBERNETES_ADMISSION: 'Kubernetes 准入', EBPF: 'eBPF 网络观测', HOST_OBSERVER: '跨平台主机观测' } as Record<string, string>)[source] || source }
watch(() => route.query.tab, value => {
  if (typeof value === 'string' && ['overview','runtime','deploy','trace','quality','models','tools','governance','identity'].includes(value)) activeTab.value=value
}, { immediate:true })
watch([() => permissions.state.user, () => permissions.can('viewContent')], () => {
  clearContentHistory()
  if (permissions.can('viewContent') && Number.isSafeInteger(agentId.value) && agentId.value > 0) void loadHistory('content')
}, { flush: 'sync' })
onBeforeUnmount(() => { loadRequestId++; resetHistory() })
watch([agentId, () => context.windowHours], load, { immediate: true })
</script>
