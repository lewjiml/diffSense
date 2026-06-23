# DiffSense · AI 需求覆盖度审查插件

> **让每一行代码改动，都有需求可循。**

一个面向研发团队的 IntelliJ IDEA 插件，把「需求文档 → 结构化需求 → 代码覆盖度」这条链路做成可视化、可交互、可推广的工作流。

## 🆕 0.2.0 重大更新

| 改进 | 说明 |
|------|------|
| 🪟 **全交互改为边栏 Tool Window** | 移除模态弹窗（向导弹窗），所有操作集中在右侧 `DiffSense` 工具窗，**不阻塞 IDEA 使用** |
| 📑 **三 Tab 结构** | `需求 / 扫描 / 日志` 分工清晰，需求和扫描结果都在同一窗口 |
| ✏️ **需求可编辑** | 拆解出的需求以表格呈现，标题 / 描述 / 关联词 / 验收标准**全部可直接双击编辑** |
| ⚡ **并行拆解需求** | 需求文档按 `##` / `###` 切片后**并行**送 LLM，默认并发 3，可在 Settings 调整 |
| � **实时扫描日志** | 解析与扫描过程**实时输出**到日志 Tab，全程可见 |
| 🎯 **字段精简** | 需求只保留 `id / title / description / keywords / acceptanceCriteria`，移除模块 / 分支 / 优先级 / 分类 |

---

## �🚀 快速上手（3 分钟）

### 第 1 步：安装插件

1. 打开 IDEA → `File` → `Settings`（Mac：`IntelliJ IDEA` → `Preferences`）
2. 左侧选 `Plugins` → 顶部点齿轮 ⚙️ → `Install Plugin from Disk...`
3. 选择 [`build/distributions/diffsense-idea-0.2.0.zip`](file:///e:/all-project/ai-shenhe/ai-initial-review-demo/build/distributions/diffsense-idea-0.2.0.zip)
4. 重启 IDEA

### 第 2 步：配置 LLM

1. `File` → `Settings` → 左侧 `Tools` → `DiffSense`
2. 填写：
   - **API Base URL**：OpenAI 兼容地址（如 `https://api.openai.com/v1`）
   - **Model**：模型名（推荐 `claude-sonnet-4-20250514`）
   - **API Key**：直接填，或留空从环境变量 `AI_API_KEY` 读取
   - **需求解析并发度**：默认 `3`（越高越快，但更耗 API 配额）
3. 点 `Apply` / `OK`

> 也支持环境变量：`AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`

### 第 3 步：使用

1. 右侧工具栏点 DiffSense 图标（或 `View` → `Tool Windows` → `DiffSense`）打开工具窗
2. 在「需求」Tab 选需求文档 `.md` → 点 `拆解需求`
3. 拆解完成后**可直接编辑**表格里的需求
4. 切到「扫描」Tab → 选基线分支 → 点 `开始扫描`
5. 在「日志」Tab 实时查看解析 / 扫描过程

---

## 📍 所有功能入口

插件安装后，以下入口会出现在 IDEA 里：

| # | 功能 | 位置 | 触发方式 |
|---|------|------|----------|
| 1 | **Tool Window（主入口）** | 右侧工具栏图标 / `View` → `Tool Windows` → `DiffSense` | 点击图标打开侧栏 |
| 2 | **审查选中代码** | 编辑器内右键 → 最底部 `审查选中代码覆盖的需求` | 选中代码后右键 |
| 3 | **Pre-commit 拦截** | Commit 面板（`Ctrl+K`）提交时自动触发 | 点提交按钮时 |
| 4 | **Settings** | `File` → `Settings` → `Tools` → `DiffSense` | 菜单进入 |

### 找不到入口？排查清单

如果按钮没出现，按顺序检查：

1. **确认插件已启用**：`Settings` → `Plugins` → 已安装 → 找到 `DiffSense` → 确认勾选 ✅
2. **确认重启过 IDE**：装完插件必须重启
3. **确认版本兼容**：本插件支持 IDEA 2023.1 ~ 2025.2（build 231 ~ 252.*）。看你的 IDE 版本：`Help` → `About`
4. **Tool Window 手动激活**：如果右侧没图标，点 `View` → `Tool Windows` → `DiffSense`

---

## 🎯 三大功能详解

### 功能 1：Tool Window（主入口，三 Tab）

**打开方式**：右侧工具栏点 DiffSense 图标，或 `View` → `Tool Windows` → `DiffSense`

工具窗内含三个 Tab：

#### 📋 需求 Tab
- 选择 Markdown 需求文档 → 点 `拆解需求`
- **并行解析**：文档按 `##` / `###` 切片，多片同时送 LLM（并发度可在 Settings 配置）
- 拆解出的需求以**可编辑表格**呈现：

  | 启用 | ID | 标题 | 描述 | 关联词 | 验收标准 |
  |------|----|------|------|--------|----------|
  | ☑ | R001 | 用户登录 | ... | login, auth | ... |

- 「启用」列勾选框控制该需求是否参与扫描
- 标题 / 描述 / 关联词 / 验收标准**双击即可编辑**
- 编辑会自动同步回内存中的需求文档

#### 🔍 扫描 Tab
- 输入模块名、基线分支（默认 `develop`）
- 点 `开始扫描` → 自动收集 Git Diff → 送 LLM 判断覆盖度
- 结果表格展示每条需求的覆盖状态：
  - ✅ 已覆盖（高置信度）
  - ⚠️ 部分覆盖（有缺口）
  - ❌ 未覆盖
- 顶部摘要条显示覆盖率百分比，按阈值变色（绿 / 橙 / 红）
- 点 `导出报告` 生成 Markdown 报告到项目根目录

#### 📜 日志 Tab
- **实时输出**解析与扫描全过程
- 包括：切片数、并发执行、每片 LLM 调用、Token 统计
- 等宽字体，便于对齐

### 功能 2：审查选中代码

**打开方式**：编辑器里选中一段代码 → 右键 → `审查选中代码覆盖的需求`

- 自动打开 Tool Window（如未打开）
- 复用工具窗中已拆解的需求（需先在「需求」Tab 拆解）
- 后台扫描选中代码覆盖了哪些需求
- 结果直接展示在「扫描」Tab，过程写入「日志」Tab

### 功能 3：Pre-commit 拦截

**打开方式**：`Ctrl+K` 提交代码时自动触发（需先在 Settings 启用）

- 提交前自动扫描暂存区 diff
- 若未覆盖需求数超过阈值，弹窗询问是否继续
- 扫描结果和日志同步推送到 Tool Window

**Settings 配置项**：
- `启用 Pre-commit 检查`：开关
- `最大允许未覆盖数`：超过此值会拦截提交

> ⚠️ Pre-commit 需要先在 Tool Window 拆解过需求（或项目根目录有 `requirements.json`）才会生效

---

## 🔧 配置项详解

`Settings` → `Tools` → `DiffSense`：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| API Base URL | OpenAI 兼容 API 地址 | `https://api.openai.com/v1` |
| Model | 模型名 | `claude-sonnet-4-20250514` |
| API Key | API 密钥（留空读环境变量 `AI_API_KEY`） | 空 |
| **需求解析并发度** | 拆解需求时的并行 LLM 调用数 | `3` |
| 启用 Pre-commit 检查 | 提交前是否扫描 | 关 |
| 最大允许未覆盖数 | Pre-commit 拦截阈值 | `3` |

---

## 📦 从源码构建

### 前置要求

- JDK 17+
- IntelliJ IDEA（用于 `runIde` 调试）
- 网络可访问 Maven Central（拉 Gradle 插件和 Gson）

### 构建 zip

```powershell
.\gradlew.bat clean buildPlugin --rerun-tasks
```

产物：`build/distributions/diffsense-idea-0.2.0.zip`

### 调试运行

```powershell
.\gradlew.bat runIde
```

会启动一个带 DiffSense 插件的 IDEA 沙箱实例。

---

## 🎯 使用场景

| 场景 | 价值 |
|------|------|
| 提交前自检 | Pre-commit 拦截未覆盖需求，避免漏实现 |
| Code Review | 判断 PR 是否完整覆盖需求 |
| 需求评审 | 把长文档拆解成可验收的条目 |
| 迭代回顾 | 统计每个迭代的需求覆盖率 |

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────┐
│  UI Layer (Kotlin / Swing)                  │
│  ├─ DiffSenseToolWindowPanel（三 Tab 主面板）│
│  │   ├─ RequirementTable（可编辑需求表格）   │
│  │   ├─ CoverageResultTable（扫描结果）      │
│  │   └─ ScanLogPanel（实时日志）             │
│  └─ DiffSenseSettingsPanel（配置）           │
├─────────────────────────────────────────────┤
│  Core Layer (纯 Kotlin)                     │
│  ├─ RequirementParser  ← 并行 parse（协程）  │
│  ├─ CoverageScanner    ← scan 阶段           │
│  ├─ LLMClient          ← HTTP 调用           │
│  ├─ MarkdownSplitter   ← 文档切片            │
│  └─ DiffCollector      ← Git4Idea            │
├─────────────────────────────────────────────┤
│  Infra Layer                                │
│  ├─ DiffSenseSettings（持久化配置）          │
│  ├─ TokenStats                              │
│  └─ ReportExporter                          │
└─────────────────────────────────────────────┘
```

## 📁 项目结构

```
.
├── build.gradle.kts              # Gradle 构建
├── settings.gradle.kts
├── gradle.properties             # 兼容范围等配置
└── src/main/
    ├── kotlin/com/diffsense/
    │   ├── actions/              # Action 入口
    │   │   └── ScanSelectionAction.kt    ← 编辑器右键
    │   ├── core/                 # 核心业务逻辑
    │   │   ├── CoverageScanner.kt
    │   │   ├── DiffCollector.kt
    │   │   ├── DiffSenseConfig.kt
    │   │   ├── LLMClient.kt
    │   │   ├── MarkdownSplitter.kt
    │   │   ├── Models.kt
    │   │   ├── Prompts.kt
    │   │   ├── RequirementParser.kt       ← 并行版本
    │   │   └── TokenStats.kt
    │   ├── git/                  # Pre-commit 集成
    │   │   └── DiffSenseCheckinHandler.kt
    │   ├── icons/                # 图标
    │   │   └── DiffSenseIcons.kt
    │   ├── settings/             # 配置面板
    │   │   ├── DiffSenseSettings.kt
    │   │   ├── DiffSenseSettingsConfigurable.kt
    │   │   └── DiffSenseSettingsPanel.kt
    │   └── ui/                   # UI 组件
    │       ├── CoverageResultTable.kt
    │       ├── RequirementTable.kt        ← 可编辑需求表格
    │       ├── ScanLogPanel.kt            ← 实时日志面板
    │       └── toolwindow/
    │           ├── DiffSenseToolWindowFactory.kt
    │           ├── DiffSenseToolWindowPanel.kt  ← 三 Tab 主面板
    │           └── DiffSenseToolWindowService.kt
    └── resources/META-INF/
        └── plugin.xml            # 插件描述（注册所有入口）
```

## 🔄 兼容范围

- **IDEA 版本**：2023.1 (build 231) ~ 2025.2 (build 252.*)
- **依赖插件**：`Git4Idea`（Git 集成，IDEA 自带）
- **JDK**：17+（构建时）/ 运行时由 IDEA 内置 JBR 提供

如需修改兼容范围：编辑 [`gradle.properties`](file:///e:/all-project/ai-shenhe/ai-initial-review-demo/gradle.properties) 的 `pluginSinceBuild` / `pluginUntilBuild`，**同时**修改 [`build.gradle.kts`](file:///e:/all-project/ai-shenhe/ai-initial-review-demo/build.gradle.kts) 中 `patchPluginXml { sinceBuild.set(...) / untilBuild.set(...) }`，然后重新构建。

## 📄 License

MIT
