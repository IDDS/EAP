import {getData,http} from '@/api/http'
export type OptimizationSample={id:string;groupId:string;split:'TRAIN'|'VALIDATION'|'HOLDOUT';input:string;expected:string}
export type OptimizationMetrics={samples:number;passed:number;succeeded:number;unknown:number;usageKnown:number;inputTokens:number|null;outputTokens:number|null;latencyMs:number|null;endToEndMs:number|null}
export type OptimizationEvaluation={experimentId:number;operationKey:string;candidateDigest:string;split:string;source:string;sampleCount:number;status:string;baseline:OptimizationMetrics;candidate:OptimizationMetrics}
export type HoldoutArm={source:string;registeredSamples:number;independentGroups:number;plannedBatches:number;completedBatches:number;baseline:OptimizationMetrics;candidate:OptimizationMetrics;baselineFailed:number;candidateFailed:number;baselineNotRun:number;candidateNotRun:number}
export type PhaseCost={phase:string;admittedCalls:number;usageKnownCalls:number;inputTokens:number|null;outputTokens:number|null;latencyP50Ms:number|null;latencyP95Ms:number|null;latencySamples:number}
export type OptimizationEvidence={protocolVersion:number;trainSamples:number;validationSamples:number;holdoutSamples:number;independentHoldoutGroups:number;holdout:HoldoutArm[];cost:{plannedUpperCalls:number;plannedUpperOutputTokens:number;admittedCalls:number;settledCalls:number;unknownCalls:number;usageKnownCalls:number;inputTokens:number|null;outputTokens:number|null;elapsedMs:number|null;phases:PhaseCost[];monetaryStatus:string}}
export type Optimization={id:number;agentId:number;instanceKey:string;status:string;engine:string;engineVersion:string;profileDigest:string;datasetDigest:string;imageRef:string;deploymentVersion:number;maxCalls:number;maxOutputTokens:number;maxMetricCalls:number;recommendation:string|null;reasonCode:string|null;createdAt:string;updatedAt:string;evaluations:OptimizationEvaluation[];reservedOutputTokens:number;usedOutputTokens:number;chargedCalls:number;pendingReservations:number;evidence?:OptimizationEvidence}
export type OptimizationSubmit={agentId:number;instanceKey:string;profileDigest:string;manualPrompt:string;engine:string;maxTokens:number;maxMetricCalls:number;maxCalls:number;maxOutputTokens:number;samples:OptimizationSample[];publicSamplesConfirmed:boolean;reflectionContentUseConfirmed:boolean;requestKey:string}
export type OptimizationContent={profile:{systemPrompt:string;model:string};manualPrompt:string;samples:OptimizationSample[];candidates:{candidate_digest:string;prompt_text:string;split:string;source:string;experiment_id:number}[]}
export type UnknownReview={runId:number;status:string;reasonCode:string|null;instanceKey:string;actorUid:string|null;experimentIds:number[];unknownTrials:number;notRunTrials:number;modelCalls:number;settledCalls:number;usageKnownCalls:number;inputTokens:number|null;outputTokens:number|null;blockers:string[];evidenceDigest:string;decision:{decision:string;reasonCode:string;evidenceDigest:string;principalDigest:string;createdAt:string}|null;decisionCurrent:boolean}
export type UnknownDecision={evidenceDigest:string;requestKey:string;acceptUnknownAndNoReplay:boolean}
export const OptimizationApi={
 status:()=>getData<{enabled:boolean}>('/agent-optimizations/status'),
 list:()=>getData<Optimization[]>('/agent-optimizations'),
 get:(id:number)=>getData<Optimization>(`/agent-optimizations/${id}`),
 content:(id:number)=>getData<OptimizationContent>(`/agent-optimizations/${id}/content`),
 review:(id:number)=>getData<UnknownReview>(`/agent-optimizations/${id}/unknown-review`),
 decide:async(id:number,body:UnknownDecision)=>(await http.post(`/agent-optimizations/${id}/unknown-review`,body)).data.data as UnknownReview,
 submit:async(body:OptimizationSubmit)=>(await http.post('/agent-optimizations',body,{timeout:30000})).data.data as Optimization,
}
