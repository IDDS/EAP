<template>
  <section class="page-grid registry-page">
    <div class="toolbar">
      <div class="toolbar-title">
        <span class="interface-eyebrow">发现 · 身份 · 运行时</span>
        <h2>注册与自主发现</h2>
        <span>发现企业智能体、技能、插件和运行实例，统一复核身份与风险</span>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="autonomousLoading" @click="loadAutonomousDiscovery">刷新发现数据</el-button>
    </div>

    <SectionNavigation v-model="activeSection" label="注册发现分区" :items="sectionItems" />


    <RuntimeInventoryPanel v-if="activeSection === 'environment'" />
    <div v-show="activeSection === 'discovery'" class="registry-section-stack">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="端点探针主动上报本机发现数据；托管运行实例可在“运行环境发现”中直接查询并复核纳管。"
    />

    <div v-if="drilldownLabel" class="drilldown-context">
        <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>发现端点列表已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>协议级智能体 / MCP 发现</span>
          <span class="muted">读取 A2A Agent Card、MCP Registry 或 server.json 元数据，不执行远程命令</span>
        </div>
      </template>
      <div class="protocol-probe-row">
        <div class="protocol-address-field"><label for="protocol-url">发现地址</label><el-input id="protocol-url" v-model="protocolForm.targetUrl" aria-label="发现地址" placeholder="https://agent.example.com 或 MCP Registry 地址" /></div>
        <div class="protocol-kind-field"><label for="protocol-type">发现协议</label><el-select id="protocol-type" v-model="protocolForm.discoveryType" aria-label="发现协议" class="protocol-type-select">
          <el-option label="自动探测" value="AUTO" />
          <el-option label="A2A Agent Card" value="A2A" />
          <el-option label="MCP Registry" value="MCP_REGISTRY" />
          <el-option label="MCP server.json" value="MCP_SERVER_JSON" />
        </el-select></div>
        <el-button type="primary" :loading="protocolLoading" @click="probeProtocol">探测并登记</el-button>
      </div>
      <el-alert
        v-if="protocolResult"
        class="protocol-result"
        type="success"
        :closable="false"
        :title="`已登记 ${protocolResult.endpointsRegistered} 个端点，读取 ${protocolResult.documentsFetched} 份文档，发现 A2A ${protocolResult.a2aAgentsDiscovered} 个、MCP ${protocolResult.mcpServersDiscovered} 个`"
      />
      <div v-if="protocolResult?.warnings?.length" class="protocol-warnings">
        <span>部分探测未命中：</span>
        <el-tag v-for="warning in protocolResult.warnings.slice(0, 3)" :key="warning" type="warning" size="small">{{ warning }}</el-tag>
      </div>
    </el-card>

    <div class="discovery-overview">
      <div class="summary-metric">
        <span>智能体候选</span>
        <strong>{{ autonomousSummary?.agentCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric mcp">
        <span>MCP 配置</span>
        <strong>{{ autonomousSummary?.mcpConfigurationCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric extension">
        <span>技能 / 插件</span>
        <strong>{{ autonomousSummary?.skillCount ?? 0 }} / {{ autonomousSummary?.pluginCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric risk">
        <span>高风险资产</span>
        <strong>{{ autonomousSummary?.highRiskCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric managed">
        <span>已纳管</span>
        <strong>{{ autonomousSummary?.managedCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric last-run">
        <span>最近扫描</span>
        <strong>{{ autonomousSummary?.lastRun ? formatTime(autonomousSummary.lastRun.startedAt) : '尚未执行' }}</strong>
        <small v-if="autonomousSummary?.lastRun">
          {{ scanTriggerLabel(autonomousSummary.lastRun.triggerType) }} · {{ scanStatusLabel(autonomousSummary.lastRun.status) }} ·
          {{ autonomousSummary.lastRun.observationsCount }} 条证据
        </small>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>已接入发现端点</span>
          <span class="muted">端点采集器注册后，定期向中央 EAP 上报观察结果</span>
        </div>
      </template>
      <el-table :data="pagedDiscoveryEndpoints" v-loading="endpointLoading" row-key="id">
        <el-table-column prop="hostName" label="主机" min-width="180" />
        <el-table-column prop="endpointKey" label="端点标识" min-width="220" show-overflow-tooltip />
        <el-table-column prop="accountName" label="账户" width="140" />
        <el-table-column prop="operatingSystem" label="操作系统" width="140" />
        <el-table-column prop="collectorVersion" label="采集器版本" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag type="success" effect="plain">{{ runtimeStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后上报" width="180">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="visibleDiscoveryEndpoints.length" class="pagination-row">
        <PageControls
          v-model:current-page="endpointPage"
          v-model:page-size="endpointPageSize"
          :page-sizes="pageSizes"
          :total="visibleDiscoveryEndpoints.length"
          show-total show-jumper
        />
      </div>
    </el-card>
    </div>

    <div v-show="activeSection === 'probes'" class="registry-section-stack">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>发现探针控制面</span>
            <div class="button-row">
              <el-button :icon="Refresh" @click="loadProbeAgents">刷新</el-button>
              <el-button type="primary" :loading="tokenCreating" @click="createEnrollmentToken">创建 15 分钟安装令牌</el-button>
            </div>
          </div>
        </template>
        <el-alert
          v-if="installCommand"
          type="success"
          :closable="false"
          show-icon
          title="令牌仅显示本次，请在目标 Linux 机器上执行安装命令"
        >
          <template #default>
            <div class="probe-install-command mono">{{ installCommand }}</div>
          </template>
        </el-alert>
        <div class="discovery-overview probe-overview">
          <div class="summary-metric"><span>探针总数</span><strong>{{ probeAgents.length }}</strong></div>
          <div class="summary-metric managed"><span>在线</span><strong>{{ onlineProbeCount }}</strong></div>
          <div class="summary-metric extension"><span>内容采集已启用</span><strong>{{ contentEnabledProbeCount }}</strong></div>
          <div class="summary-metric risk"><span>指纹待复核</span><strong>{{ fingerprintReviewCount }}</strong></div>
        </div>
        <el-table :data="probeAgents" v-loading="probeLoading" row-key="id">
          <el-table-column label="机器 / 探针" min-width="220">
            <template #default="{ row }">
              <strong>{{ row.hostName }}</strong>
              <div class="muted mono">{{ row.endpointKey }}</div>
              <div class="muted">{{ row.operatingSystem || '未知系统' }} · {{ row.architecture || '未知架构' }} · v{{ row.agentVersion || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="组织上下文" min-width="170">
            <template #default="{ row }">
              <div>{{ row.department || '未分配部门' }}</div>
              <div class="muted">{{ row.siteName || '默认站点' }} · {{ row.environmentName || '未分类环境' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="运行状态" width="145">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ONLINE' ? 'success' : row.status === 'PENDING_REVIEW' ? 'warning' : 'info'">{{ runtimeStatusLabel(row.status) }}</el-tag>
              <div class="muted">{{ formatOptionalTime(row.lastHeartbeatAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="扫描 / 离线队列" width="170">
            <template #default="{ row }">
              <div>{{ formatOptionalTime(row.lastScanAt) }}</div>
              <div class="muted">{{ row.queueDepth }} 批 · {{ formatBytes(row.queueBytes) }}</div>
            </template>
          </el-table-column>
        <el-table-column label="业务内容通道" min-width="190">
            <template #default="{ row }">
              <el-tag :type="row.contentCaptureEnabled ? 'success' : row.contentCaptureStatus === 'PENDING_APPROVAL' ? 'warning' : 'info'">{{ contentStatusLabel(row.contentCaptureStatus) }}</el-tag>
              <div v-if="row.contentApprovalMode === 'NONE'" class="muted">当前版本免审核 · 可随时停用</div>
              <div v-else class="muted">平台：{{ row.contentPlatformApprovedBy || '待审批' }} · 数据：{{ row.contentDataApprovedBy || '待审批' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="采集范围 / Token" min-width="270">
            <template #default="{ row }">
              <div class="source-tags">
                <el-tag v-for="scope in row.collectionScopes" :key="scope" size="small" effect="plain">{{ collectionScopeLabel(scope) }}</el-tag>
              </div>
              <el-tag :type="row.tokenUsageStatus === 'AVAILABLE_WHEN_REPORTED' ? 'warning' : 'info'" size="small">
                {{ tokenUsageStatusLabel(row.tokenUsageStatus) }}
              </el-tag>
              <div class="muted collection-note">{{ row.tokenUsageNote || '暂无说明' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="审批与处置" width="255" fixed="right">
            <template #default="{ row }">
              <div class="asset-management-actions">
                <el-button v-if="row.fingerprintReviewRequired" size="small" type="warning" @click="approveFingerprint(row)">复核指纹</el-button>
                <el-button v-if="row.contentApprovalMode === 'DUAL' && !row.contentPlatformApprovedBy" size="small" @click="approveProbeContent(row, 'PLATFORM_ADMIN')">平台审批</el-button>
                <el-button v-if="row.contentApprovalMode === 'DUAL' && !row.contentDataApprovedBy" size="small" @click="approveProbeContent(row, 'DATA_OWNER')">数据审批</el-button>
                <el-button v-if="row.contentApprovalMode === 'NONE' && !row.contentCaptureEnabled" size="small" type="primary" @click="enableProbeContent(row)">启用内容采集</el-button>
                <el-button v-if="row.contentCaptureEnabled" link type="danger" @click="revokeProbeContent(row)">停用内容采集</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!probeLoading && !probeAgents.length" description="尚未安装 Discovery Agent" />
      </el-card>
    </div>

    <div v-show="activeSection === 'identity'" class="registry-section-stack">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>跨来源身份关联</span>
          <span class="muted">把本地、协议、Kubernetes 与 eBPF 证据归并到稳定身份键</span>
        </div>
      </template>
      <el-table :data="pagedIdentityLinks" v-loading="endpointLoading" row-key="id">
        <el-table-column prop="discoveredAgentId" label="候选资产" width="100" />
        <el-table-column label="身份键" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ row.identityKey }}</template>
        </el-table-column>
        <el-table-column label="关联方式" width="110">
          <template #default="{ row }">
            <el-tag :type="row.relationType === 'EXACT' ? 'success' : 'warning'" size="small">{{ identityRelationLabel(row.relationType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="190">
          <template #default="{ row }">
            <el-tag v-for="source in row.sourceTypes" :key="source" size="small" effect="plain">{{ discoverySourceLabel(source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="120">
          <template #default="{ row }"><el-progress :percentage="row.confidence" :stroke-width="8" /></template>
        </el-table-column>
        <el-table-column label="最后观测" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="identityLinks.length" class="pagination-row">
        <PageControls
          v-model:current-page="identityPage"
          v-model:page-size="identityPageSize"
          :page-sizes="pageSizes"
          :total="identityLinks.length"
          show-total show-jumper
        />
      </div>
    </el-card>
    </div>

    <div v-show="activeSection === 'assets'" class="registry-section-stack">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>自动发现资产清单</span>
          <span class="muted">共 {{ discoveredAssets.length }} 条；候选资产需完成复核与认领</span>
        </div>
      </template>
      <el-table :data="pagedDiscoveredAssets" v-loading="autonomousLoading" row-key="id">
        <el-table-column label="智能体 / 产品" min-width="210">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div class="muted">
              <el-tag size="small" effect="plain">{{ assetTypeLabel(row.assetType) }}</el-tag>
              {{ row.vendor }} · {{ row.product }}<span v-if="row.version"> · {{ row.version }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="设备与账户" min-width="170">
          <template #default="{ row }">
            <div>{{ row.hostName || '未知设备' }}</div>
            <div class="muted">{{ row.accountName || '未关联账户' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="发现证据" min-width="170">
          <template #default="{ row }">
            <div class="source-tags">
              <el-tag v-for="source in row.sourceTypes" :key="source" size="small" effect="plain">{{ discoverySourceLabel(source) }}</el-tag>
            </div>
            <div class="muted">{{ row.evidenceCount }} 条 · MCP {{ row.mcpServerCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="130">
          <template #default="{ row }">
            <el-progress :percentage="row.confidence" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column label="风险" width="105">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)">{{ riskLevelLabel(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="运行特征" min-width="150">
          <template #default="{ row }">
            <div>{{ row.processName || '未发现运行进程' }}</div>
            <div class="muted">
              自动批准 {{ row.autoApprove ? '是' : '否' }} ·
              进程信任 {{ row.trustedProcess === undefined || row.trustedProcess === null ? '未知' : row.trustedProcess ? '是' : '否' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最后发现" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="处置与纳管" width="245" fixed="right">
          <template #default="{ row }">
            <div v-if="row.managedAgentId" class="asset-management-actions">
              <el-tag type="success" effect="light">已纳入目录</el-tag>
              <el-button link type="primary" @click="openManagedAgent(row.managedAgentId)">查看智能体</el-button>
            </div>
            <div v-else class="asset-management-actions">
              <el-select :model-value="row.status" size="small" @change="handleAssetStatusChange(row.id, $event)">
                <el-option label="新发现" value="DISCOVERED" />
                <el-option label="复核中" value="REVIEWING" />
                <el-option label="已确认" value="CONFIRMED" />
                <el-option label="忽略" value="IGNORED" />
              </el-select>
              <el-button
                v-if="canManageAgentAsset(row)"
                type="primary"
                size="small"
                :loading="managingAssetId === row.id"
                @click="manageAsset(row)"
              >纳入目录</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="discoveredAssets.length" class="pagination-row">
        <PageControls
          v-model:current-page="assetPage"
          v-model:page-size="assetPageSize"
          :page-sizes="pageSizes"
          :total="discoveredAssets.length"
          show-total show-jumper
        />
      </div>
    </el-card>
    </div>

    <div v-show="activeSection === 'runtime'" class="registry-section-stack">
    <div class="toolbar registry-toolbar">
      <div class="toolbar-title">
        <strong>智能体运行时注册</strong>
        <span>维护能力、运行实例和心跳，并按任务能力选择可用节点</span>
      </div>
      <el-select v-model="selectedAgentId" class="agent-select" @change="loadAgentRegistry">
        <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} · ${agent.code}`" :value="agent.id" />
      </el-select>
    </div>

    <div class="registry-layout runtime-discovery-layout">
      <div class="registry-column">
        <el-card shadow="never" class="panel">
          <template #header>能力注册</template>
          <el-form label-position="top">
            <el-form-item label="能力标识">
              <el-input v-model="capabilityForm.capability" placeholder="ops.incident.triage" />
            </el-form-item>
            <el-form-item label="能力名称">
              <el-input v-model="capabilityForm.name" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="capabilityForm.description" type="textarea" :rows="2" />
            </el-form-item>
            <el-button type="primary" :icon="Plus" @click="registerCapability">注册能力</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>运行实例</template>
          <el-form label-position="top">
            <el-form-item label="实例标识">
              <el-input v-model="runtimeForm.instanceKey" placeholder="ops-incident-prod-1" />
            </el-form-item>
            <el-form-item label="服务端点">
              <el-input v-model="runtimeForm.endpoint" placeholder="http://agent-service:9000" />
            </el-form-item>
            <div class="form-row">
              <el-form-item label="协议">
                <el-select v-model="runtimeForm.protocol">
                  <el-option label="HTTP" value="HTTP" />
                  <el-option label="MCP" value="MCP" />
                  <el-option label="A2A" value="A2A" />
                </el-select>
              </el-form-item>
              <el-form-item label="负载">
                <el-input-number v-model="heartbeatLoad" :min="0" :max="100" />
              </el-form-item>
            </div>
            <el-form-item v-if="runtimeForm.protocol === 'A2A'" label="调度认证">
              <el-switch v-model="runtimeForm.dispatchAuthenticated" active-text="已完成 A2A 认证配置" />
            </el-form-item>
            <div class="button-row">
              <el-button type="primary" :icon="Connection" @click="registerRuntime">注册实例</el-button>
              <el-button :icon="Refresh" @click="heartbeat">发送心跳</el-button>
            </div>
          </el-form>
        </el-card>
      </div>

      <div class="registry-column">
        <el-card shadow="never" class="panel">
          <template #header>能力发现搜索</template>
          <el-form label-position="top">
            <div class="form-row">
              <el-form-item label="能力">
                <el-input v-model="discoveryForm.capability" placeholder="ops.incident.triage" />
              </el-form-item>
              <el-form-item label="最大风险">
                <el-select v-model="discoveryForm.maxRiskLevel">
                  <el-option label="低风险" value="LOW" />
                  <el-option label="中风险" value="MEDIUM" />
                  <el-option label="高风险" value="HIGH" />
                  <el-option label="严重风险" value="CRITICAL" />
                </el-select>
              </el-form-item>
            </div>
            <div class="form-row">
              <el-form-item label="部门">
                <el-input v-model="discoveryForm.department" placeholder="可选" />
              </el-form-item>
              <el-form-item label="协议">
                <el-select v-model="discoveryForm.protocol" clearable>
                  <el-option label="HTTP" value="HTTP" />
                  <el-option label="MCP" value="MCP" />
                  <el-option label="A2A" value="A2A" />
                </el-select>
              </el-form-item>
            </div>
            <el-button type="primary" :icon="Search" @click="discover">发现智能体</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>候选智能体</template>
          <el-table :data="candidates" v-loading="loading">
            <el-table-column prop="name" label="智能体" min-width="170">
              <template #default="{ row }">
                <strong>{{ row.name }}</strong>
                <div class="muted">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="capability" label="能力" min-width="170" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag type="success">{{ runtimeStatusLabel(row.runtimeStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="loadScore" label="负载" width="90" />
            <el-table-column prop="score" label="评分" width="90" />
            <el-table-column prop="endpoint" label="服务端点" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-card>
      </div>
    </div>

    <div class="registry-layout registry-data-layout">
      <el-card shadow="never" class="panel">
        <template #header>已注册能力</template>
        <el-table :data="capabilities">
          <el-table-column prop="capability" label="能力标识" min-width="180" />
          <el-table-column prop="name" label="名称" width="160" />
          <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>运行实例</template>
        <el-table :data="runtimeInstances">
          <el-table-column prop="instanceKey" label="实例" min-width="180" />
          <el-table-column prop="protocol" label="协议" width="90" />
          <el-table-column label="可调度" width="100">
            <template #default="{ row }">
              <el-tag :type="row.dispatchAuthenticated && row.protocol === 'A2A' ? 'success' : 'info'">
                {{ row.dispatchAuthenticated && row.protocol === 'A2A' ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ONLINE' ? 'success' : 'warning'">{{ runtimeStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="loadScore" label="负载" width="90" />
          <el-table-column prop="endpoint" label="服务端点" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-card>
    </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import PageControls from '@/components/common/PageControls.vue'
import SectionNavigation from '@/components/common/SectionNavigation.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import RuntimeInventoryPanel from '@/components/RuntimeInventoryPanel.vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  Agent,
  AgentCapability,
  AgentDiscoverySummary,
  AgentRuntimeInstance,
  DiscoveredAgentAsset,
  DiscoveryEndpoint,
  DiscoveryProbeAgent,
  DiscoveryIdentityLink,
  DiscoveryAssetStatus,
  DiscoveryCandidate,
  RiskLevel,
  ProtocolDiscoveryType,
  ProtocolProbeResponse,
} from '@/types/domain'

const agents = ref<Agent[]>([])
const selectedAgentId = ref<number>()
const capabilities = ref<AgentCapability[]>([])
const runtimeInstances = ref<AgentRuntimeInstance[]>([])
const candidates = ref<DiscoveryCandidate[]>([])
const loading = ref(false)
const autonomousLoading = ref(false)
const autonomousSummary = ref<AgentDiscoverySummary>()
const discoveredAssets = ref<DiscoveredAgentAsset[]>([])
const discoveryEndpoints = ref<DiscoveryEndpoint[]>([])
const identityLinks = ref<DiscoveryIdentityLink[]>([])
const endpointLoading = ref(false)
const protocolLoading = ref(false)
const protocolResult = ref<ProtocolProbeResponse>()
const probeAgents = ref<DiscoveryProbeAgent[]>([])
const probeLoading = ref(false)
const tokenCreating = ref(false)
const installCommand = ref('')
const managingAssetId = ref<number>()
const heartbeatLoad = ref(22)
const pageSizes = [10, 20, 50, 100]
const endpointPage = ref(1)
const endpointPageSize = ref(20)
const identityPage = ref(1)
const identityPageSize = ref(20)
const assetPage = ref(1)
const assetPageSize = ref(20)
const sectionItems = [
  { value: 'discovery', label: '发现总览' },
  { value: 'probes', label: '发现探针' },
  { value: 'identity', label: '身份关联' },
  { value: 'assets', label: '资产清单' },
  { value: 'runtime', label: '运行时注册' },
  { value: 'environment', label: '运行环境发现' },
] as const
const activeSection = ref<'discovery' | 'probes' | 'identity' | 'assets' | 'runtime' | 'environment'>('discovery')
const onlineProbeCount = computed(() => probeAgents.value.filter(item => item.status === 'ONLINE').length)
const contentEnabledProbeCount = computed(() => probeAgents.value.filter(item => item.contentCaptureEnabled).length)
const fingerprintReviewCount = computed(() => probeAgents.value.filter(item => item.fingerprintReviewRequired).length)

const capabilityForm = reactive({
  capability: 'ops.incident.triage',
  name: '运维事件分诊',
  description: '识别告警影响面并生成处置建议',
})

const runtimeForm = reactive({
  instanceKey: 'ops-incident-local-2',
  endpoint: 'http://ops-agent.local:9003',
  protocol: 'HTTP',
  dispatchAuthenticated: false,
})

const discoveryForm = reactive({
  capability: 'finance.analysis',
  maxRiskLevel: 'HIGH' as RiskLevel,
  department: '',
  protocol: 'HTTP',
})

const protocolForm = reactive({
  targetUrl: '',
  discoveryType: 'AUTO' as ProtocolDiscoveryType,
})
const route = useRoute()
const router = useRouter()
watch(() => route.query.section, section => {
  if (section === 'environment') activeSection.value='environment'
}, { immediate:true })
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const drilldownLabel = computed(() => contextEndpointKey.value ? `端点 ${contextEndpointKey.value}` : '')
const visibleDiscoveryEndpoints = computed(() => contextEndpointKey.value
  ? discoveryEndpoints.value.filter(endpoint => endpoint.endpointKey === contextEndpointKey.value)
  : discoveryEndpoints.value)
const pagedDiscoveryEndpoints = computed(() => pageItems(visibleDiscoveryEndpoints.value, endpointPage.value, endpointPageSize.value))
const pagedIdentityLinks = computed(() => pageItems(identityLinks.value, identityPage.value, identityPageSize.value))
const pagedDiscoveredAssets = computed(() => pageItems(discoveredAssets.value, assetPage.value, assetPageSize.value))

watch(contextEndpointKey, () => {
  endpointPage.value = 1
})

watch([visibleDiscoveryEndpoints, endpointPageSize], () => {
  endpointPage.value = clampPage(endpointPage.value, visibleDiscoveryEndpoints.value.length, endpointPageSize.value)
})

watch([identityLinks, identityPageSize], () => {
  identityPage.value = clampPage(identityPage.value, identityLinks.value.length, identityPageSize.value)
})

watch([discoveredAssets, assetPageSize], () => {
  assetPage.value = clampPage(assetPage.value, discoveredAssets.value.length, assetPageSize.value)
})

function clearDrilldown() {
  router.replace({ path: '/registry', query: {} })
}

function pageItems<T>(items: T[], page: number, pageSize: number) {
  const start = Math.max(0, (page - 1) * pageSize)
  return items.slice(start, start + pageSize)
}

function clampPage(page: number, total: number, pageSize: number) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  return Math.min(Math.max(1, page), pageCount)
}

async function load() {
  const [nextAgents] = await Promise.all([EapApi.listAgents(), loadAutonomousDiscovery()])
  agents.value = nextAgents
  selectedAgentId.value = agents.value[0]?.id
  await loadAgentRegistry()
  await discover()
}

async function loadAutonomousDiscovery() {
  autonomousLoading.value = true
  try {
    endpointLoading.value = true
    const [summary, assets, endpoints, links] = await Promise.all([
      EapApi.getAgentDiscoverySummary(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listDiscoveryEndpoints(),
      EapApi.listDiscoveryIdentityLinks(),
    ])
    autonomousSummary.value = summary
    discoveredAssets.value = assets
    discoveryEndpoints.value = endpoints
    identityLinks.value = links
    await loadProbeAgents()
  } finally {
    autonomousLoading.value = false
    endpointLoading.value = false
  }
}

async function loadProbeAgents() {
  probeLoading.value = true
  try {
    probeAgents.value = await EapApi.listDiscoveryProbeAgents()
  } finally {
    probeLoading.value = false
  }
}

async function createEnrollmentToken() {
  tokenCreating.value = true
  try {
    const token = await EapApi.createDiscoveryEnrollmentToken({
      tenantId: 'default', ttlMinutes: 15, maxUses: 1, createdBy: 'platform-admin',
    })
    const origin = window.location.origin.replace(/\/$/, '')
    const insecure = origin.startsWith('http://') ? ' --allow-insecure-http' : ''
    installCommand.value = `curl -fsSL ${origin}/downloads/install.sh | sudo bash -s -- --server ${origin} --token '${token.token}'${insecure}`
    ElMessage.success('已创建单次安装令牌，15 分钟后过期')
  } finally {
    tokenCreating.value = false
  }
}

async function approveProbeContent(agent: DiscoveryProbeAgent, role: 'PLATFORM_ADMIN' | 'DATA_OWNER') {
  const label = role === 'PLATFORM_ADMIN' ? '平台管理员' : '数据负责人'
  const { value } = await ElMessageBox.prompt(`请输入${label}审批人姓名，两个角色必须由不同人员完成。`, `业务内容采集 · ${label}`, {
    confirmButtonText: '确认审批', cancelButtonText: '取消', inputPattern: /\S{2,}/, inputErrorMessage: '请输入审批人',
  })
  await EapApi.approveDiscoveryContent(agent.id, { approvalRole: role, approvedBy: value.trim() })
  ElMessage.success(`${label}审批已记录`)
  await loadProbeAgents()
}

async function revokeProbeContent(agent: DiscoveryProbeAgent) {
  await ElMessageBox.confirm('停用后内容代理将停止采集，历史记录保留。', '停用业务内容采集', { type: 'warning' })
  await EapApi.revokeDiscoveryContent(agent.id, 'platform-admin')
  ElMessage.success('业务内容采集已停用')
  await loadProbeAgents()
}

async function enableProbeContent(agent: DiscoveryProbeAgent) {
  await EapApi.enableDiscoveryContent(agent.id, 'platform-admin')
  ElMessage.success('业务内容采集已启用')
  await loadProbeAgents()
}

async function approveFingerprint(agent: DiscoveryProbeAgent) {
  await ElMessageBox.confirm(`确认接受 ${agent.hostName} 当前机器指纹？`, '复核机器指纹', { type: 'warning' })
  await EapApi.approveDiscoveryFingerprint(agent.id, 'platform-admin')
  ElMessage.success('机器指纹已复核')
  await loadProbeAgents()
}

async function probeProtocol() {
  if (!protocolForm.targetUrl.trim()) {
    ElMessage.warning('请输入 A2A Agent 或 MCP Registry 地址')
    return
  }
  protocolLoading.value = true
  try {
    protocolResult.value = await EapApi.probeProtocolDiscovery({
      targetUrl: protocolForm.targetUrl.trim(),
      discoveryType: protocolForm.discoveryType,
    })
    ElMessage.success('协议发现完成，资产已进入候选清单')
    await loadAutonomousDiscovery()
  } finally {
    protocolLoading.value = false
  }
}

async function updateAssetStatus(id: number, status: DiscoveryAssetStatus) {
  await EapApi.updateDiscoveredAgentStatus(id, status)
  ElMessage.success('资产处置状态已更新')
  await loadAutonomousDiscovery()
}

function canManageAgentAsset(asset: DiscoveredAgentAsset) {
  return asset.assetType === 'AGENT' || asset.assetType === 'AGENT_PROJECT'
}

async function manageAsset(asset: DiscoveredAgentAsset) {
  managingAssetId.value = asset.id
  try {
    const managed = await EapApi.manageDiscoveredAgent(asset.id)
    ElMessage.success(`${asset.product || asset.name} 已纳入智能体目录`)
    await loadAutonomousDiscovery()
    agents.value = await EapApi.listAgents()
    if (managed.managedAgentId) await router.push(`/agents/${managed.managedAgentId}`)
  } finally {
    managingAssetId.value = undefined
  }
}

function openManagedAgent(agentId: number) {
  router.push(`/agents/${agentId}`)
}

function handleAssetStatusChange(id: number, value: unknown) {
  return updateAssetStatus(id, value as DiscoveryAssetStatus)
}

function riskTagType(riskLevel: RiskLevel) {
  if (riskLevel === 'CRITICAL' || riskLevel === 'HIGH') return 'danger'
  if (riskLevel === 'MEDIUM') return 'warning'
  return 'success'
}

function riskLevelLabel(value: string) {
  return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' } as Record<string, string>)[value] || value
}

function runtimeStatusLabel(value: string) {
  return ({ ONLINE: '在线', OFFLINE: '离线', DEGRADED: '降级', UNKNOWN: '未知', PENDING_REVIEW: '待复核', REVOKED: '已吊销', UNINSTALLED: '已卸载' } as Record<string, string>)[value] || value
}

function contentStatusLabel(value: string) {
  return ({ ENABLED: '已授权', NOT_REQUIRED: '已启用（免审核）', PENDING_APPROVAL: '待双人审批', DISABLED: '未启用' } as Record<string, string>)[value] || value
}

function collectionScopeLabel(value: string) {
  return ({
    PROCESS: '进程', DOCKER: 'Docker', PORTS: '端口', FILES: '文件元数据', SKILLS: '技能', PLUGINS: '插件',
    MCP: 'MCP', A2A: 'A2A', HOST_OBSERVER: '主机观测', EBPF_METADATA: 'eBPF 元数据',
    BUSINESS_CONTENT: '业务内容',
  } as Record<string, string>)[value] || value
}

function tokenUsageStatusLabel(value: string) {
  return ({ AVAILABLE_WHEN_REPORTED: 'Token 待链路上报', NOT_AVAILABLE: 'Token 未采集' } as Record<string, string>)[value] || value
}

function identityRelationLabel(value: string) {
  return ({ EXACT: '精确关联', HEURISTIC: '推断关联' } as Record<string, string>)[value] || value
}

function scanStatusLabel(value: string) {
  return ({ SUCCEEDED: '成功', FAILED: '失败', RUNNING: '进行中' } as Record<string, string>)[value] || value
}

function scanTriggerLabel(value: string) {
  return ({ SCHEDULED: '定时扫描', MANUAL: '手动扫描', ENDPOINT: '端点上报' } as Record<string, string>)[value] || value
}

function discoverySourceLabel(value: string) {
  return ({
    PROCESS: '运行进程',
    MANIFEST: '项目清单',
    CONFIGURATION: '本地配置',
    SKILL: '智能体技能',
    PLUGIN: '智能体插件',
    EXTERNAL: '外部系统',
    DOCKER: 'Docker 容器',
    A2A: 'A2A 协议',
    MCP_REGISTRY: 'MCP 注册中心',
    KUBERNETES_WATCH: 'Kubernetes 监听',
    KUBERNETES_ADMISSION: 'Kubernetes 准入',
    EBPF: 'eBPF 网络观测',
    HOST_OBSERVER: '跨平台主机观测',
  } as Record<string, string>)[value] || value
}

function assetTypeLabel(assetType: DiscoveredAgentAsset['assetType']) {
  if (assetType === 'MCP_CONFIGURATION') return 'MCP 配置'
  if (assetType === 'AGENT_PROJECT') return '智能体项目'
  if (assetType === 'AGENT_SKILL') return '智能体技能'
  if (assetType === 'AGENT_PLUGIN') return '智能体插件'
  return '运行智能体'
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatOptionalTime(value?: string) {
  return value ? formatTime(value) : '尚无上报'
}

function formatBytes(value: number) {
  if (!value) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

async function loadAgentRegistry() {
  if (!selectedAgentId.value) return
  const [nextCapabilities, nextInstances] = await Promise.all([
    EapApi.listAgentCapabilities(selectedAgentId.value),
    EapApi.listAgentRuntimeInstances(selectedAgentId.value),
  ])
  capabilities.value = nextCapabilities
  runtimeInstances.value = nextInstances
}

async function registerCapability() {
  if (!selectedAgentId.value) return
  await EapApi.registerAgentCapability(selectedAgentId.value, { ...capabilityForm })
  ElMessage.success('能力已注册')
  await loadAgentRegistry()
}

async function registerRuntime() {
  if (!selectedAgentId.value) return
  await EapApi.registerAgentRuntimeInstance(selectedAgentId.value, { ...runtimeForm })
  ElMessage.success('运行实例已注册')
  await loadAgentRegistry()
}

async function heartbeat() {
  await EapApi.sendAgentHeartbeat(runtimeForm.instanceKey, { status: 'ONLINE', loadScore: heartbeatLoad.value })
  ElMessage.success('心跳已更新')
  await loadAgentRegistry()
}

async function discover() {
  loading.value = true
  try {
    const response = await EapApi.discoverAgents({
      capability: discoveryForm.capability,
      maxRiskLevel: discoveryForm.maxRiskLevel,
      department: discoveryForm.department || undefined,
      protocol: discoveryForm.protocol || undefined,
    })
    candidates.value = response.candidates
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
