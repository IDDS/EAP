<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { EapApi } from '@/api/eap'
import { useOrchestrationMutation } from '@/api/orchestrationEditing'
import { usePermissions } from '@/stores/permissions'
import type { Agent, AgentTeam, AgentTeamMember } from '@/types/domain'

const props = defineProps<{ team?: AgentTeam; members: AgentTeamMember[]; hasPlans: boolean; disabled?: boolean }>()
const emit = defineEmits<{ changed: [teamId?: number]; working: [value: boolean] }>()
const permissions = usePermissions()
const scope = () => [props.team?.id, permissions.state.user]
const allowed = () => permissions.can('runWorkflows')
const { busy, error, run } = useOrchestrationMutation(scope, allowed, value => emit('working', value))
const teamOpen = ref(false), memberOpen = ref(false), creating = ref(false)
const teamDraft = ref({ code: '', name: '', objective: '', coordinationMode: 'SEQUENTIAL', workspacePolicy: '' })
const memberDraft = ref({ agentId: undefined as number | undefined, role: '', responsibility: '' })
const editingMember = ref<number>()
const agents = ref<Agent[]>([])
const availableAgents = computed(() => agents.value.filter(a => !props.members.some(m => m.agentId === a.id)))
let expectedRevision = 0
watch(scope, () => { teamOpen.value = false; memberOpen.value = false; agents.value = [] }, { flush: 'sync' })
watch(allowed, value => { if (!value) { teamOpen.value = false; memberOpen.value = false } })
function openTeam(create: boolean) {
  if (!allowed() || busy.value || props.disabled || !create && !props.team) return
  creating.value = create; expectedRevision = props.team?.revision ?? 0; error.value = ''
  teamDraft.value = create ? { code: '', name: '', objective: '', coordinationMode: 'SEQUENTIAL', workspacePolicy: '' }
    : { code: props.team!.code, name: props.team!.name, objective: props.team!.objective ?? '', coordinationMode: props.team!.coordinationMode, workspacePolicy: props.team!.workspacePolicy }
  teamOpen.value = true
}
async function saveTeam() {
  if (!teamOpen.value) return
  const body = Object.fromEntries(Object.entries(teamDraft.value).map(([key,value]) => [key,value.trim()])) as typeof teamDraft.value
  if (!body.code || !body.name || !body.coordinationMode || !body.workspacePolicy) { error.value = '请填写团队编码、名称、协作约定和工作区说明。'; return }
  const teamId = props.team?.id, create = creating.value, revision = expectedRevision
  await run(() => create ? EapApi.createTeam(body) : EapApi.updateTeam(teamId!, { ...body, expectedRevision: revision }), saved => {
    teamOpen.value = false; emit('changed', saved.id)
  })
}
async function openMember(member?: AgentTeamMember) {
  if (!props.team || !allowed() || busy.value || props.disabled) return
  expectedRevision = props.team.revision; editingMember.value = member?.id; error.value = ''
  memberDraft.value = { agentId: member?.agentId, role: member?.role ?? '', responsibility: member?.responsibility ?? '' }
  if (member) { memberOpen.value = true; return }
  await run(() => EapApi.listAgents(), result => { agents.value = result; memberOpen.value = true })
}
async function saveMember() {
  if (!memberOpen.value || !props.team) return
  const { agentId } = memberDraft.value
  const role = memberDraft.value.role.trim(), responsibility = memberDraft.value.responsibility.trim()
  if (!agentId || !role || !responsibility) { error.value = '请选择智能体并填写角色与职责。'; return }
  const teamId = props.team.id, memberId = editingMember.value, revision = expectedRevision
  await run(() => memberId ? EapApi.updateTeamMember(teamId, memberId, { role, responsibility, expectedRevision: revision })
    : EapApi.addTeamMember(teamId, { agentId, role, responsibility }), () => { memberOpen.value = false; emit('changed', teamId) })
}
async function removeMember(member: AgentTeamMember) {
  if (!props.team || props.disabled) return
  const teamId = props.team.id, revision = props.team.revision
  await run(async current => {
    await ElMessageBox.confirm(`将 Agent ${member.agentId} 移出当前团队？未提交计划仍分配给该成员时，需要先调整分配。`, '移除成员', { type: 'warning', confirmButtonText: '移除成员', cancelButtonText: '取消' })
    if (!current()) throw 'cancel'
    await EapApi.removeTeamMember(teamId, member.id, revision)
  }, () => emit('changed', teamId))
}
async function deleteTeam() {
  if (!props.team || props.disabled || props.hasPlans || props.members.length) return
  const teamId = props.team.id, revision = props.team.revision
  await run(async current => {
    await ElMessageBox.confirm(`删除空团队“${props.team!.name}”？`, '删除团队', { type: 'warning', confirmButtonText: '删除团队', cancelButtonText: '取消' })
    if (!current()) throw 'cancel'
    await EapApi.deleteTeam(teamId, revision)
  }, () => emit('changed'))
}
</script>

<template>
  <section class="team-manager" aria-label="团队管理">
    <div class="button-row">
      <el-button v-if="allowed()" :disabled="busy || disabled" @click="openTeam(true)">新建团队</el-button>
      <el-button v-if="allowed()" :disabled="!team || busy || disabled" @click="openTeam(false)">编辑团队</el-button>
      <el-button v-if="allowed()" :disabled="!team || busy || disabled" @click="openMember()">添加成员</el-button>
      <el-button v-if="allowed() && team && !hasPlans && !members.length" type="danger" plain :disabled="busy || disabled" @click="deleteTeam">删除空团队</el-button>
      <span v-if="team" class="muted">{{ members.length }} 位成员</span>
    </div>
    <el-alert v-if="error && !teamOpen && !memberOpen" :title="error" type="error" :closable="false" />
    <el-collapse v-if="team" class="team-reference">
      <el-collapse-item name="members" title="团队与成员资料">
        <p>{{ team.name }} · {{ team.code }}</p><p v-if="team.objective">{{ team.objective }}</p>
        <p class="muted">协作约定：{{ team.coordinationMode }} · 工作区说明：{{ team.workspacePolicy }}</p>
        <ul v-if="members.length" class="team-members">
          <li v-for="member in members" :key="member.id">
            <div><strong>{{ member.role }} · Agent {{ member.agentId }}</strong><p>{{ member.responsibility }}</p></div>
            <div v-if="allowed()" class="button-row"><el-button text :disabled="busy || disabled" @click="openMember(member)">编辑成员</el-button><el-button text type="danger" :disabled="busy || disabled" @click="removeMember(member)">移除成员</el-button></div>
          </li>
        </ul>
        <p v-else class="muted">暂无成员。添加智能体后，即可为计划步骤分配执行成员。</p>
      </el-collapse-item>
    </el-collapse>
    <el-dialog v-model="teamOpen" :title="creating ? '新建团队' : '编辑团队'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy">
      <el-form label-position="top" @submit.prevent="saveTeam">
        <el-form-item label="团队编码" required><el-input v-model="teamDraft.code" aria-label="团队编码" maxlength="64" :disabled="busy || !creating" /></el-form-item>
        <el-form-item label="团队名称" required><el-input v-model="teamDraft.name" aria-label="团队名称" maxlength="128" :disabled="busy" /></el-form-item>
        <el-form-item label="团队目标"><el-input v-model="teamDraft.objective" aria-label="团队目标" type="textarea" maxlength="512" :disabled="busy" /></el-form-item>
        <el-form-item label="协作约定" required><el-input v-model="teamDraft.coordinationMode" aria-label="协作约定" maxlength="32" :disabled="busy" /></el-form-item>
        <el-form-item label="工作区说明" required><el-input v-model="teamDraft.workspacePolicy" aria-label="工作区说明" maxlength="128" :disabled="busy" /></el-form-item>
        <p class="muted">协作约定和工作区说明用于任务上下文；当前计划按步骤串行执行。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="busy" @click="teamOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!allowed()">保存团队</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog v-model="memberOpen" :title="editingMember ? '编辑成员' : '添加成员'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy">
      <el-form label-position="top" @submit.prevent="saveMember">
        <el-form-item v-if="!editingMember" label="智能体" required><el-select v-model="memberDraft.agentId" aria-label="团队成员智能体" filterable :disabled="busy" popper-class="plan-select-options"><el-option v-for="agent in availableAgents" :key="agent.id" :value="agent.id" :label="`${agent.name} · #${agent.id}`" /></el-select></el-form-item>
        <p v-else>Agent {{ memberDraft.agentId }}</p>
        <el-form-item label="成员角色" required><el-input v-model="memberDraft.role" aria-label="成员角色" maxlength="64" :disabled="busy" /></el-form-item>
        <el-form-item label="成员职责" required><el-input v-model="memberDraft.responsibility" aria-label="成员职责" type="textarea" maxlength="128" :disabled="busy" /></el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="busy" @click="memberOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!allowed()">保存成员</el-button></div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.team-manager { min-width: 0; }
.button-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.button-row .el-button { margin-left: 0; }
.team-members { list-style: none; padding: 0; }
.team-members li { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 12px 0; border-top: 1px solid var(--console-line); }
.team-members li > div { min-width: 0; }
p, strong { overflow-wrap: anywhere; }
</style>
