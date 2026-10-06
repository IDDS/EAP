import {getData,putData} from './http'
export type BudgetUnit='MODEL_CALLS'|'OUTPUT_TOKENS'|'TOOL_CLAIMS'
export type BudgetScope='PLATFORM'|'AGENT'|'TASK'|'EXPERIMENT'
export type BudgetAccount={id:number;scopeType:BudgetScope;scopeKey:string;unit:BudgetUnit;limitAmount:number|null;marginAmount:number;usedAmount:number;reservedAmount:number;revision:number;violated:boolean;updatedBy:string|null;updatedAt:string}
export type BudgetEntry={id:number;agentId:number|null;taskKey:string|null;experimentId:number|null;traceId:string|null;operation:string;outputBound:number;state:string;inputTokens:number|null;outputTokens:number|null;reasonCode:string|null;createdAt:string;settledAt:string|null}
export type BudgetDashboard={enabled:boolean;accounts:BudgetAccount[];reservations:BudgetEntry[]}
export type BudgetLimit={scopeType:BudgetScope;scopeKey:string;unit:BudgetUnit;limitAmount:number|null;marginAmount:number;expectedRevision:number}
export const BudgetApi={get:()=>getData<BudgetDashboard>('/budgets'),set:(body:BudgetLimit)=>putData<BudgetAccount,BudgetLimit>('/budgets/accounts',body)}
