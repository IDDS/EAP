<template>
  <Teleport to="body" :disabled="!expanded">
  <section ref="root" class="agent-network-graph" :class="{ expanded }" :aria-label="expanded ? '展开的智能体拓扑' : '智能体拓扑'">
    <div class="graph-toolbar">
      <el-select :model-value="selectedId" filterable placeholder="搜索并定位节点" aria-label="搜索并定位节点" @change="select">
        <el-option v-for="node in nodes" :key="node.id" :label="node.label + ' · ' + node.id" :value="node.id" />
      </el-select>
      <el-switch v-model="focus" active-text="聚焦关联" aria-label="聚焦关联" />
      <div class="graph-tools">
        <el-button :icon="ZoomOut" aria-label="缩小拓扑" title="缩小" @click="zoom(.8)" />
        <span class="graph-scale">{{ Math.round(camera.scale * 100) }}%</span>
        <el-button :icon="ZoomIn" aria-label="放大拓扑" title="放大" @click="zoom(1.25)" />
        <el-button @click="fit">适应画布</el-button>
        <el-button :icon="FullScreen" @click="toggleExpanded">{{ expanded ? '退出展开' : '展开画布' }}</el-button>
      </div>
    </div>
    <div ref="canvas" class="graph-viewport" tabindex="0" aria-label="拖动画布平移，滚轮缩放；通过搜索选择节点" @wheel.prevent="wheel" @pointerdown="panStart" @pointermove="panMove" @pointerup="panEnd" @pointercancel="panEnd" @keydown.esc="expanded = false">
      <div class="graph-scene" :style="{ width: scene.width + 'px', height: scene.height + 'px', transform: `translate(${camera.x}px, ${camera.y}px) scale(${camera.scale})` }">
        <svg class="graph-links" :style="{ '--control-dash': `${6 / camera.scale} ${4 / camera.scale}` }" :width="scene.width" :height="scene.height" aria-label="节点关联关系">
          <defs><marker :id="markerId" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="context-stroke" /></marker></defs>
          <path v-for="trunk in drawing.trunks" :key="trunk.id" :d="trunk.path" class="graph-edge control graph-trunk" />
          <path v-for="edge in drawing.paths" :key="edge.id" :d="edge.path" :class="['graph-edge', edge.plane.toLowerCase()]" :marker-end="`url(#${markerId})`"><title>{{ edge.relations.map(e => e.label).join('、') }}</title></path>
        </svg>
        <button v-for="node in scene.nodes" :key="node.id" type="button" class="graph-node" :class="[node.plane.toLowerCase(), { selected: node.id === selectedId }]" :style="{ left: node.x + 'px', top: node.y + 'px' }" :aria-pressed="node.id === selectedId" @pointerdown.stop @click="select(node.id)">
          <small>{{ node.plane === 'CONTROL' ? '控制服务' : '智能体' }} · {{ node.id }}</small><strong :title="node.label">{{ node.label }}</strong><span :title="node.status + ' · ' + node.metric">{{ node.status }} · {{ node.metric }}</span>
        </button>
      </div>
      <el-empty v-if="!nodes.length" description="暂无拓扑数据" />
    </div>
    <div class="graph-caption"><span>{{ focus ? '聚焦所选节点及直接关联' : '全部节点' }} · 显示 {{ scene.nodes.length }} / {{ nodes.length }} 个节点 · {{ scene.edges.length }} 组关系</span><span>拖动画布平移 · 滚轮缩放{{ expanded ? ' · Esc 退出' : '' }}</span></div>
    <details class="graph-relations"><summary>查看当前关系明细（{{ scene.edges.reduce((n, e) => n + e.count, 0) }} 条，重复连线已合并）</summary><div class="graph-relation-list"><p v-for="edge in scene.edges" :key="edge.id"><b>{{ names.get(edge.source) }} → {{ names.get(edge.target) }}</b><span>{{ edge.plane === 'CONTROL' ? '控制面' : '数据面' }} · {{ edge.relations.map(e => e.label).join(' / ') }}</span></p></div></details>
  </section>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { FullScreen, ZoomIn, ZoomOut } from '@element-plus/icons-vue'
import type { NetworkEdge, NetworkNode } from '@/types/domain'
import { controlBusPaths, edgePath, fitCamera, graphScene, zoomCamera } from '@/utils/networkGraph'
const props = defineProps<{ nodes: NetworkNode[]; edges: NetworkEdge[]; selectedId?: string }>()
const emit = defineEmits<{ select: [node: NetworkNode] }>()
const root = ref<HTMLElement>(), canvas = ref<HTMLElement>()
const expanded = ref(false), focus = ref(true)
const markerId = 'network-arrow-' + useId().replace(/[^a-z0-9-]/gi, '')
const camera = reactive({ scale: 1, x: 0, y: 0 })
const scene = computed(() => graphScene(props.nodes, props.edges, props.selectedId || '', focus.value, expanded.value ? 6 : 4))
const names = computed(() => new Map(props.nodes.map(n => [n.id, n.label])))
const drawing = computed(() => {
  const map = new Map(scene.value.nodes.map(n => [n.id, n]))
  const controls = scene.value.nodes.filter(n => n.plane === 'CONTROL')
  const groups = new Map<string, NetworkNode[]>(), branches = new Map<string, string>()
  const trunks: { id: string; path: string }[] = []
  const isBus = (e: NetworkEdge) => !focus.value && e.plane === 'CONTROL'
    && map.get(e.source)?.plane === 'CONTROL' && map.get(e.target)?.plane === 'DATA'
  for (const edge of scene.value.edges.filter(isBus)) {
    if (!groups.has(edge.source)) groups.set(edge.source, [])
    groups.get(edge.source)!.push(map.get(edge.target)!)
  }
  for (const [id, targets] of groups) {
    const bus = controlBusPaths(map.get(id)!, targets, controls.findIndex(n => n.id === id))
    trunks.push({ id: 'trunk:' + id, path: bus.trunk })
    for (const [target, path] of bus.branches) branches.set(JSON.stringify([id, target]), path)
  }
  const paths = scene.value.edges.map(e => ({ ...e, path: isBus(e)
    ? branches.get(JSON.stringify([e.source, e.target]))!
    : edgePath(map.get(e.source)!, map.get(e.target)!, e.source > e.target) }))
  return { trunks, paths }
})
function select(id: string) { const node = props.nodes.find(n => n.id === id); if (node) emit('select', node) }
function fit() { if (canvas.value?.clientWidth) Object.assign(camera, fitCamera(canvas.value.clientWidth, canvas.value.clientHeight, scene.value.width, scene.value.height)) }
function zoom(factor: number) { if (canvas.value) Object.assign(camera, zoomCamera(camera, factor, canvas.value.clientWidth / 2, canvas.value.clientHeight / 2)) }
function wheel(e: WheelEvent) { const rect = canvas.value!.getBoundingClientRect(); Object.assign(camera, zoomCamera(camera, e.deltaY < 0 ? 1.1 : 1 / 1.1, e.clientX - rect.left, e.clientY - rect.top)) }
let drag: { id: number; x: number; y: number; cx: number; cy: number } | undefined
function panStart(e: PointerEvent) { if (e.button !== 0) return; drag = { id: e.pointerId, x: e.clientX, y: e.clientY, cx: camera.x, cy: camera.y }; canvas.value?.setPointerCapture(e.pointerId) }
function panMove(e: PointerEvent) { if (drag?.id === e.pointerId) { camera.x = drag.cx + e.clientX - drag.x; camera.y = drag.cy + e.clientY - drag.y } }
function panEnd(e: PointerEvent) { if (canvas.value?.hasPointerCapture(e.pointerId)) canvas.value.releasePointerCapture(e.pointerId); drag = undefined }
async function toggleExpanded() { expanded.value = !expanded.value; await nextTick(); fit(); canvas.value?.focus() }
function escape(e: KeyboardEvent) { if (e.key === 'Escape' && expanded.value) { expanded.value = false; void nextTick(fit) } }
let observer: ResizeObserver | undefined
watch([scene, expanded], () => nextTick(fit))
onMounted(() => { observer = new ResizeObserver(fit); if (canvas.value) observer.observe(canvas.value); document.addEventListener('keydown', escape); fit() })
onBeforeUnmount(() => { observer?.disconnect(); document.removeEventListener('keydown', escape) })
</script>

<style scoped>
.agent-network-graph{min-width:0;border:1px solid #ccdce8;border-radius:8px;background:#f8fbff;align-self:start;overflow:hidden}.agent-network-graph.expanded{position:fixed;inset:12px;height:calc(100dvh - 24px);align-self:auto;z-index:1900;display:flex;flex-direction:column;box-shadow:0 0 0 20px #102333c9}.graph-toolbar{display:flex;align-items:center;gap:10px;padding:12px;flex-wrap:wrap;background:#fff;border-bottom:1px solid #dce7ef}.graph-toolbar>.el-select{width:260px;max-width:100%}.graph-toolbar,.graph-caption,.graph-relations{flex-shrink:0}.graph-tools{display:flex;align-items:center;gap:5px;flex-wrap:wrap}.graph-tools .el-button{margin:0;padding:8px 10px}.graph-scale{width:42px;text-align:center;font-size:12px;color:#46607a}.graph-viewport{height:570px;position:relative;overflow:hidden;touch-action:none;cursor:grab;background-color:#f5f9fd;background-image:radial-gradient(#b7cbdc .7px,transparent .7px);background-size:20px 20px;isolation:isolate}.expanded .graph-viewport{flex:1;min-height:0;height:auto}.graph-viewport:active{cursor:grabbing}.graph-viewport:focus-visible{outline:2px solid #0c87bd;outline-offset:-3px}.graph-scene{position:absolute;transform-origin:0 0}.graph-links{position:absolute;inset:0;overflow:visible;pointer-events:none}.graph-edge{fill:none;stroke-linejoin:round;stroke-width:2;stroke:#377fc3;pointer-events:stroke}.graph-edge.control{stroke-dasharray:var(--control-dash,6 4)}.graph-edge.data{stroke:#079579;stroke-width:3}.graph-node{position:absolute;box-sizing:border-box;width:196px;height:92px;border:1px solid #b6cce0;border-left:4px solid #328ad0;border-radius:8px;background:white;display:flex;flex-direction:column;gap:7px;padding:12px;text-align:left;color:#203d56;cursor:pointer;box-shadow:0 3px 10px #12354a0b}.graph-node.data{border-left-color:#099c7c}.graph-node.selected{outline:3px solid #2498d4;outline-offset:4px;background:#edf8ff}.graph-node small,.graph-node span{font-size:11px;color:#536f86;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}.graph-node strong{font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}.graph-caption{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap;padding:12px;font-size:12px;color:#526d84;border-top:1px solid #dce7ef}.graph-relations{font-size:12px;background:white;padding:12px;color:#4b667d}.graph-relations summary{cursor:pointer}.graph-relation-list{max-height:160px;overflow:auto}.graph-relation-list p{display:grid;gap:4px;overflow-wrap:anywhere;border-bottom:1px solid #e5edf3;padding-bottom:8px}@media(max-width:700px){.graph-viewport{height:440px}.agent-network-graph.expanded{inset:4px;height:calc(100dvh - 8px)}.graph-toolbar{gap:6px;padding:8px}}
</style>
