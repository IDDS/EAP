<template>
  <div class="graph-viewport">
    <div class="viewport-controls" aria-label="拓扑视图控制">
      <button aria-label="缩小拓扑" :disabled="scale <= 0.5" @click="scaleBy(1 / 1.25)">−</button>
      <output aria-label="拓扑缩放比例">{{ Math.round(scale * 100) }}%</output>
      <button aria-label="放大拓扑" :disabled="scale >= 4" @click="scaleBy(1.25)">+</button>
      <button @click="reset">适应画布</button>
      <button :disabled="!selected" @click="locate">定位选中</button>
    </div>
    <svg ref="canvas" :viewBox="`0 0 1000 ${height}`" tabindex="0" role="group"
      aria-label="关系拓扑；方向键平移，加减键缩放，Home 适应画布" @keydown="onKeydown">
      <g :transform="transform"><slot /></g>
    </svg>
  </div>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { select } from 'd3-selection'
import { zoom, zoomIdentity, type ZoomBehavior } from 'd3-zoom'
const props = defineProps<{ height: number; selected?: { px: number; py: number } }>()
const canvas = ref<SVGSVGElement>()
const scale = ref(1)
const transform = ref('translate(0,0) scale(1)')
let behavior: ZoomBehavior<SVGSVGElement, unknown> | undefined
function scaleBy(factor: number) {
  if (canvas.value && behavior) select(canvas.value).call(behavior.scaleBy, factor)
}
function reset() {
  if (canvas.value && behavior) select(canvas.value).call(behavior.transform, zoomIdentity)
}
function locate() {
  if (!canvas.value || !behavior || !props.selected) return
  const k = Math.max(1.5, scale.value)
  select(canvas.value).call(behavior.transform, zoomIdentity.translate(500 - props.selected.px * k, props.height / 2 - props.selected.py * k).scale(k))
}
function onKeydown(event: KeyboardEvent) {
  // Node keyboard interaction belongs to the node, not the viewport.
  if (event.target !== canvas.value || !canvas.value || !behavior) return
  const directions: Record<string, [number, number]> = { ArrowLeft: [50, 0], ArrowRight: [-50, 0], ArrowUp: [0, 50], ArrowDown: [0, -50] }
  if (directions[event.key]) {
    event.preventDefault()
    const [x, y] = directions[event.key]
    select(canvas.value).call(behavior.translateBy, x / scale.value, y / scale.value)
  } else if (['+', '=', '-', 'Home'].includes(event.key)) {
    event.preventDefault()
    if (event.key === 'Home') reset()
    else scaleBy(event.key === '-' ? 1 / 1.25 : 1.25)
  }
}
onMounted(() => {
  if (!canvas.value) return
  behavior = zoom<SVGSVGElement, unknown>().scaleExtent([0.5, 4])
    .extent((): [[number, number], [number, number]] => [[0, 0], [1000, props.height]])
    .clickDistance(5)
    .filter(event => (!event.ctrlKey || event.type === 'wheel') && !event.button)
    .on('zoom', event => { scale.value = event.transform.k; transform.value = event.transform.toString() })
  select(canvas.value).call(behavior).on('dblclick.zoom', null)
})
onBeforeUnmount(() => {
  if (canvas.value) select(canvas.value).on('.zoom', null)
  behavior?.on('zoom', null)
})
</script>
<style scoped>
.graph-viewport{display:flex;flex-direction:column;min-width:0;min-height:250px;width:100%;height:100%;overflow:hidden}
.viewport-controls{display:flex;justify-content:flex-end;align-items:center;gap:6px;padding:6px 0;flex-shrink:0;flex-wrap:wrap}
button{border:1px solid #6590b1;background:#245578;color:#eff9ff;border-radius:5px;min-height:30px;padding:4px 9px;font:inherit;font-size:12px;cursor:pointer}
button:disabled{opacity:.4;cursor:default}button:focus-visible,svg:focus-visible{outline:2px solid #9ceaff;outline-offset:-2px}
output{color:#d4eafa;font-size:12px;min-width:42px;text-align:center;font-variant-numeric:tabular-nums}
svg{display:block;flex:1;width:100%;height:100%;min-height:180px;overflow:hidden;touch-action:none;cursor:grab}svg:active{cursor:grabbing}
@media(min-width:1360px) and (min-height:740px){.graph-viewport{min-height:0}svg{min-height:0}}
@media(max-width:800px){button{min-height:40px;min-width:40px}.graph-viewport{height:340px}}
</style>
