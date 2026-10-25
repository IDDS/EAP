import {http,getData} from '@/api/http'
export type Sample={input:string;expected:string}
export type Trial={id:number;variant:string;sampleIndex:number;status:string;traceId:string;finishReason:string|null;inputTokens:number|null;outputTokens:number|null;usageSource:string;latencyMs:number|null;endToEndMs:number|null;queueMs:number|null;score:number|null;reasonCode:string|null}
export type Experiment={id:number;agentId:number;instanceKey:string;imageRef:string;deploymentVersion:number;status:string;profileDigest:string;datasetDigest:string;evaluatorVersion:string;maxTokens:number;requestedBy:string;createdAt:string;variants:{id:number;name:string;configDigest:string}[];trials:Trial[]}
export type Profile={profile:{agentId:string;model:string;systemPrompt:string;maxTokens:number;thinkingMode?:string;profileDigest:string};imageRef:string;deploymentVersion:number}
export type Content={profile:Profile['profile'];samples:Sample[];variants:{name:string;systemPrompt:string}[];outputs:{trialId:number;output:string|null}[]}
export const ExperimentApi={
  list:()=>getData<Experiment[]>('/agent-experiments'),
  get:(id:number)=>getData<Experiment>(`/agent-experiments/${id}`),
  content:(id:number)=>getData<Content>(`/agent-experiments/${id}/content`),
  targets:(agentId:number)=>getData<{enabled:boolean;instances:{instanceKey:string}[]}>(`/agent-experiments/targets?agentId=${agentId}`),
  profile:async(agentId:number,instanceKey:string)=>(await http.post('/agent-experiments/profile',{agentId,instanceKey},{timeout:30000})).data.data as Profile,
  submit:async(payload:{agentId:number;instanceKey:string;profileDigest:string;candidatePrompt:string;maxTokens:number;samples:Sample[];publicSamplesConfirmed:boolean;requestKey:string})=>(await http.post('/agent-experiments',payload,{timeout:30000})).data.data as Experiment,
}
