# EAP 控制台视觉约定

2026-09-29，依据用户要求增强科技感并参考 AdminLTE；默认深色导航、浅色工作区，运行大屏仍使用独立展示样式。

- 色彩：导航 `#0c1c2b`，工作台标题/容量概览深蓝，工作区 `#f0f5f8`，强调 `#087f9a`，正文 `#193246`，辅助文字 `#52697b`。危险、警告、成功保留各自状态含义，不能用强调色替代。
- 层级：导航和面包屑定位 → 页面标题和主要操作 → 指标/筛选 → 业务表格。工作台三个行动入口作为一组，避免重复阴影与装饰边框。
- 图标：Element Plus SVG，用于已有功能入口和可识别的指标；装饰图标 aria-hidden，含义同时以文字显示。不得用依赖字体的特殊字符绘制硬件图标。
- 数字：指标使用 tabular-nums，保留单位、数据来源、查询时间与缺失说明。无采样不得显示为 0，不制造增长百分比、随机波形或在线数量。
- 字体：优先 Aptos / PingFang SC / Noto Sans CJK SC / Microsoft YaHei，使用本机可用字体，不发起外部字体请求。保留原字号选项。
- 交互：保留菜单当前状态、键盘 focus-visible、44px 移动导航、窄屏文字抽屉；prefers-reduced-motion 下缩短动画。样式不能掩盖禁用、加载、错误及权限限制。
- 实现：`frontend/src/styles/console.css` 在旧样式之后导入，集中定义本轮主题；不要继续把主题散落追加到旧 styles.css。新增页面复用这些 token，逐步迁移旧硬编码颜色。
- AdminLTE：已安装 @adminlte/vue 0.7.0，运行大屏使用 LteCard/LteSmallBox/LteInfoBox；参考 AdminLTE 控制台结构和主题变量方式，保留 Element Plus 表单/弹窗，无新增依赖或全局 Bootstrap 覆盖。
- 范围：这一轮不是全站深色模式，不改变业务、权限或接口合同，也不代表生产集成验收完成。

参考：[AdminLTE Vue](https://docs.adminlte.io/vue/introduction)、[主题](https://docs.adminlte.io/vue/theming)，访问日期 2026-09-29。
