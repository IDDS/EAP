<template>
  <div ref="chartRef" class="trend-chart" :style="{ height }"></div>
</template>

<script setup lang="ts">
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import type { ECharts, EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

type EChartsOption = EChartsCoreOption

const props = withDefaults(defineProps<{
  option: EChartsOption
  height?: string
}>(), {
  height: '270px',
})

const chartRef = ref<HTMLElement>()
let chart: ECharts | undefined
let resizeObserver: ResizeObserver | undefined

function render() {
  if (!chart || !props.option) return
  chart.setOption(props.option, true)
  chart.resize()
}

onMounted(async () => {
  await nextTick()
  if (!chartRef.value) return
  chart = init(chartRef.value)
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartRef.value)
  render()
})

watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = undefined
})
</script>
