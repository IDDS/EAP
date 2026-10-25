# 工作流结构化结果契约

EAP 在匹配任务标识的成功回执上验收结果结构。契约随提交快照保存；更改当前节点不会影响已提交的执行。结构不合格会保留原结果、停止下游交接，并将工作流标为 `FAILED`。原步骤的 Runtime 回执仍为 `SUCCEEDED`，两者分别展示。

这是 EAP 原生的有界 JSON 结构验收，不是完整 JSON Schema、模型强制生成、业务正确性评估或 Kagent 运行时集成。首版适用于 `RUNTIME` 的 `AGENT` / `ARTIFACT` 节点，包括工具任务；AX、独立聊天和其他独立任务入口未接入。团队计划支持在新增步骤时配置、提交前修改、提交后只读查看；未配置的历史计划保持原结果交接行为。

## 配置

在“工作流自动化 → 节点配置 → 结果契约”选择结果来源并编辑结构，再保存节点。也可在节点 `configJson` 中指定：

```json
{
  "eapOutput": {
    "version": "EAP_JSON_V1",
    "source": "JSON_TEXT",
    "schema": {
      "type": "object",
      "properties": {
        "summary": { "type": "string" },
        "scores": { "type": "array", "items": { "type": "integer" } }
      },
      "required": ["summary"],
      "additionalProperties": false
    }
  }
}
```

| 来源 | 校验对象 | 典型用途 |
| --- | --- | --- |
| `JSON_TEXT` | `output` 字符串解析得到的完整 JSON 对象 | 当前配置式 Runner 的模型回答 |
| `OUTPUT` | 回执 `output` 中的 JSON 对象 | 工具、自定义运行程序 |
| 不配置 `eapOutput` | 保持原结果交接行为 | 既有工作流 |

`JSON_TEXT` 不剥离代码围栏、不提取文本片段、不修复 JSON、不做类型转换。空文本、重复键、尾随内容均拒绝。整个 Runtime 回执出现重复键或尾随 JSON 时，任务结果保持 `EXECUTION_UNKNOWN`，因为原回执本身有歧义。

模型任务仍需由任务目标或原智能体提示词约定输出内容。此功能不会修改提示词、添加供应商生成参数，或为纠正输出再次调用模型。

## 支持范围

- 根结构必须是 `object`。每个结构节点必须显式提供字符串 `type`。
- 对象必须且只能提供 `type`、`properties`、`required`、`additionalProperties`。`required` 是不重复、已在 `properties` 声明的字段名数组；`additionalProperties` 必须是布尔值。缺省可选字段允许不存在，出现时仍需符合类型。
- 数组必须且只能提供 `type`、`items`，逐项使用同一个结构校验。
- 标量只能提供 `type`：`string`、`number`、`integer`、`boolean`、`null`。`integer` 要求整数表示，`1.0` 不满足；`number` 要求转换为 double 时不会溢出为无穷的数值。字符串不转成数字或布尔值。
- 最多 8 层、128 个结构节点、每对象 64 个属性、字段名 1–128 个 UTF-16 单元。契约 JSON 紧凑编码最多 8192 字节。JSON 文本解析深度最多 32 层。
- `$ref`、`$schema`、正则、枚举、长度/数值范围、组合规则和其他关键字不支持，保存或提交时明确拒绝，不静默忽略。
- 既有输出 16KB、节点及执行快照 60KB、合并交接请求 60KB 限制保持。超限 Runtime 输出仍为原来的 `EXECUTION_UNKNOWN`，不会当作已接收且校验失败的结果。

## 执行语义

1. 保存节点和提交执行时检查契约；非法契约不会进入调度。幂等提交原键返回原执行。
2. 按原权限、策略、预算和调度规则派发，等待匹配任务标识的明确回执。
3. 仅 `SUCCEEDED` 回执进行结构验收；`FAILED`、`EXECUTION_UNKNOWN` 不会因输出看起来有效而升级为成功。
4. 将验收元数据与原结果、摘要、派发配置一起持久化。结构拒绝不会抹去已完成的模型/工具调用，也不退还、重领或重置预算。
5. 实例占用和回收仍按原回执处理。即使结构不合格，已知终态仍可进入原回收流程；休眠未确认时保留独立回收状态。未知回执不自动释放或休眠。
6. 合格结果交接时，再按原快照契约核对已保存结论。`JSON_TEXT` 向 `eapPreviousResults` 交付解析后的对象；`OUTPUT` 交付原对象。`outputSha256` 对应实际交接对象的紧凑 JSON，`originalOutputSha256` 对应保留的原 `output`。未配置契约时结果内容不变。
7. 不合格结果停止整个串行路径，包括没有显式 `inputFrom` 的后续节点。没有自动重试、修复、回滚、续跑或新增费用。

结构验收不能证明模型结论、权限、外部工具效果或资源回收正确；这些仍需各自的原证据。

## 观测与内容权限

步骤新增 `resultValidation`：`contract`、`source`、`contractSha256`、`status`、`code`。摘要绑定数据库保存后、原执行快照中契约的紧凑 JSON 表示（包含来源与版本），不是跨字段重排的语义规范化摘要。

| 验收状态 | 含义 |
| --- | --- |
| `PASSED` | 原回执成功，结果符合契约 |
| `REJECTED` | 原回执成功，结果未通过结构验收，下游被停止 |
| `NOT_EVALUATED` | 没有成功回执，不作结构结论 |
| 字段缺失 / null | 未记录验收；可能未配置、尚未完成或属于旧执行 |

失败代码仅返回固定类别：`OUTPUT_MISSING`、`TEXT_REQUIRED`、`INVALID_JSON`、`TYPE_MISMATCH`、`REQUIRED_MISSING`、`UNEXPECTED_PROPERTY`、`RECEIPT_NOT_SUCCEEDED`。普通执行元数据不包含 schema、结果正文、属性路径或被拒绝的值。

任务工作台和执行时间线分别呈现任务回执、结构结论、资源回收。“步骤结果”保持手动加载原文，仍由 `PlatformPermission.VIEW_CONTENT` 和 `/me` 的 `viewContent` 控制；结构失败的原文也仅从既有 `/outputs` 内容接口读取。表单沿用 `/me.runWorkflows`。计划表单沿用同一能力。团队计划新增的配置接口和 V89 迁移见下节；没有新增权限来源或 Runner 变更。

旧记录不回填、不重新验收、不替换原 UNKNOWN。Plan5、Plan6 和旧 28 次调用计划保持关闭。

## 团队计划配置（2026-10-22）

在“团队与计划”中选定计划，可在“新增步骤”设置契约，或通过步骤的“配置契约”按钮修改。尚未分配成员时可保存结构；实际提交仍要求每一步已分配给本团队成员且具备可用运行条件。只读用户可查看定义，正文内容权限保持独立。

- `POST /api/orchestration/plans/{planId}/steps` 可携带 `outputContractJson`，值为契约定义的 JSON 字符串，不是完整节点配置。
- `POST /api/orchestration/steps/{stepId}/output-contract` 接收 `{"outputContractJson":"..."}`；值为 `null` 时清除，`"null"`、空白、非法结构及 AX 契约均拒绝。
- 保存与提交共用同一校验器。契约原始 UTF-8 文本和紧凑编码均不得超过 8192 字节，采用上述结构子集。前端只提交定义；执行方式、交接与休眠配置由服务器生成。
- 提交前锁定计划，随后锁定步骤并读取当前值，避免契约编辑与成员分配互相覆盖。提交后契约随工作流快照冻结；保存契约或分配成员均返回冲突，不修改原快照。
- 合格结果按原规则交接；结构拒绝时计划步骤显示 `FAILED`，后续步骤 `SKIPPED`。原任务回执仍显示 `SUCCEEDED`，验收为 `REJECTED`，原结果保留；未知回执保持 `EXECUTION_UNKNOWN` 和 `NOT_EVALUATED`，不会重派。
- Flyway V89 只为 `agent_plan_steps` 添加可空的 `output_contract_json`，不回填历史契约或状态。升级前后历史计划、原回执和 UNKNOWN 的证据需分别核对。

配置只说明预期输出结构。它不会修改智能体提示词，不保证业务结论正确，也不会自动修复失败结果或增加调用。

## 验证记录

2026-10-20 已部署并收尾。远程完整后端 1244 项（r2 报告/JAR，生产源码一致）、r3 受影响 H2 32 项、隔离 MySQL 32 项、前端 471 项及类型/构建通过；四角色桌面/手机 8 组与 28 张最终截图通过。新契约执行使用隔离 Runtime 替身，现网仅只读及合成 GET 界面验收，没有追加真实模型/业务工具调用。失败修正、复用依据和发布证据见 [本轮记录](evidence/structured-results-20261019/README.md)。


## 计划契约部署记录（2026-10-22）

V89及计划契约已部署。后端1341项覆盖（完整运行后仅测试夹具修正、37+16项定向重跑），MySQL17、前端499及构建、四角色API/8组浏览器通过。仅新增未执行Plan7，模型/运行派发0；结构校验不代表业务正确。见[完整验收](evidence/plan-output-contract-20261022/README.md)。
