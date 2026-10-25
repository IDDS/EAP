import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import {evaluationHelpers,evaluationState} from './evaluation-test-support.mjs'

function functionsFrom(names, state) {
  names=[...names,...evaluationHelpers]
  state={...evaluationState(),...state}
  const script = readFileSync(new URL('../src/views/EvaluationView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('EvaluationView.vue', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const source = ast.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name.text)).map(node => node.getText(ast)).join('\n')
  const context = vm.createContext(state)
  vm.runInContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), context)
  return context
}
const ref = value => ({ value })
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
const calibrationState = () => ({
  calibrationRequestId: 0, selectedDatasetKey: ref('DATASET'), selectedCalibrationRunId: ref(''),
  calibration: ref({}), calibrationDetail: ref(), emptyCalibration: { datasetKey: '', evaluators: [] },
})

test('late current-label preview cannot overwrite a selected historical run', async () => {
  const preview = deferred(), history = deferred()
  const state = functionsFrom(['loadDatasetCalibration', 'selectCalibrationRun'], {
    ...calibrationState(),
    EapApi: { getEvaluationDatasetCalibration: () => preview.promise, getEvaluationCalibrationRun: () => history.promise },
  })
  const current = state.loadDatasetCalibration()
  const selected = state.selectCalibrationRun('run-old')
  history.resolve({ calibration: { runId: 'run-old', truePositive: 1 }, samples: [{ groundTruthLabel: 'PASS' }] }); await selected
  preview.resolve({ truePositive: 0 }); await current
  assert.equal(state.calibration.value.runId, 'run-old')
  assert.equal(state.calibration.value.truePositive, 1)
  assert.equal(state.calibrationDetail.value.samples[0].groundTruthLabel, 'PASS')
})

test('changing an SME label preserves the selected historical calibration and its evidence', async () => {
  const item = { itemKey: 'case-1' }
  let annotations = 0
  const state = functionsFrom(['annotateItem', 'loadDatasetItems', 'loadDatasetCalibration'], {
    ...calibrationState(), selectedCalibrationRunId: ref('run-old'), datasetItemsRequestId: 0,
    datasetItems: ref([item]), datasetItemsLoading: ref(false), annotationActor: ref('SME'),
    ElMessage: { success() {} }, annotationLabel: value => value,
    EapApi: {
      annotateEvaluationDatasetItem: async () => { annotations++ },
      listEvaluationDatasetItems: async () => [{ ...item, groundTruthLabel: 'FAIL' }],
      getEvaluationCalibrationRun: async (_dataset, runId) => ({ calibration: { runId, truePositive: 1 }, samples: [{ groundTruthLabel: 'PASS' }] }),
      getEvaluationDatasetCalibration: () => { throw new Error('must not replace history with current labels') },
    },
  })
  await state.annotateItem(item, 'FAIL')
  assert.equal(annotations, 1)
  assert.equal(state.datasetItems.value[0].groundTruthLabel, 'FAIL')
  assert.equal(state.calibration.value.runId, 'run-old')
  assert.equal(state.calibrationDetail.value.samples[0].groundTruthLabel, 'PASS')
})

test('new run selects its immutable history without overriding a later user selection', async () => {
  const first = deferred()
  const state = functionsFrom(['runDatasetCalibration'], {
    ...calibrationState(), calibrating: ref(false),
    EapApi: { runEvaluationDataset: () => first.promise },
    ElMessage: { success() {} }, percent: value => value, load: async () => {},
  })
  const running = state.runDatasetCalibration()
  state.selectedCalibrationRunId.value = 'user-selected-history'
  state.calibrationRequestId++
  first.resolve({ runId: 'new-run', evaluatedItems: 1, f1: 1 }); await running
  assert.equal(state.selectedCalibrationRunId.value, 'user-selected-history')
  assert.equal(state.calibrating.value, false)
})
