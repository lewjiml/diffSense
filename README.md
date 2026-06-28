# AI DiffSense · AI Requirement Coverage & Code Quality Review Plugin

> **让每一行代码改动，都有需求可循、有质量可依。**
>
> **Make every line of code change traceable to a requirement and accountable to quality.**

An IntelliJ IDEA plugin for R&D teams that turns the workflow of *Requirement Document → Structured Requirements → Code Coverage + Code Quality* into a visual, interactive, and scalable process.

[English](#-english) · [中文](#-中文)

---

## 🇬🇧 English

A plugin for IntelliJ IDEA that connects requirement documents to code changes, helping you verify coverage and catch quality issues before they ship.

### ✨ v1.0.0 Features

| Capability | Description |
|------------|-------------|
| 📋 **Requirement Parsing** | Splits Markdown requirement docs by `##`/`###`, sends slices to an LLM in parallel, and breaks them down into verifiable structured items |
| 🎯 **Coverage Scan** | Compares Git Diff against requirements to determine coverage status (Covered / Partial / Uncovered) |
| 🛡️ **Code Quality Scan** | Independently scans the diff for potential bugs / security risks / performance issues / code smells, with severity + fix suggestions |
| 🚦 **Pre-commit Interception** | Auto-scans checked files on commit; blocks when uncovered requirements exceed the threshold |
| 🪟 **Tool Window** | Always-on right-side tool window (Requirements / Scan / Log tabs), non-blocking |
| ⚡ **Performance** | Coverage and quality scans run in parallel; batched concurrency; shared HttpClient singleton |
| ⚙️ **Configurable Prompts** | parse / scan / quality prompts are customizable in Settings, with one-click reset |

> 📌 **v1.0.0 key fix**: Pre-commit no longer blindly runs `git diff --cached` (IDEA changelist ≠ git staged, which scanned stale content). It now uses `panel.selectedChanges` to take diffs by file path, ensuring the currently committed version is scanned.

---

### 🚀 Quick Start (3 minutes)

#### Step 1: Install the plugin

1. Open IDEA → `File` → `Settings` (Mac: `IntelliJ IDEA` → `Preferences`)
2. Left panel: `Plugins` → top gear ⚙️ → `Install Plugin from Disk...`
3. Select `build/distributions/diffsense-idea-1.0.0.zip`
4. Restart IDEA

#### Step 2: Configure the LLM

1. `File` → `Settings` → left `Tools` → `AI DiffSense`
2. Fill in:
   - **API Base URL**: OpenAI-compatible endpoint (e.g. `https://api.openai.com/v1`)
   - **Model**: model name (recommended `claude-sonnet-4-20250514`)
   - **API Key**: fill directly, or leave empty to read from env var `AI_API_KEY`
   - **Parse Concurrency**: default `3` (higher is faster but uses more API quota)
3. Click `Apply` / `OK`

> Env vars are also supported: `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`

#### Step 3: Use it

1. Click the DiffSense icon in the right toolbar (or `View` → `Tool Windows` → `AI DiffSense`) to open the tool window
2. In the **Requirements** tab, select a `.md` requirement doc → click `Parse Requirements`
3. After parsing, you can **edit** the requirement table directly
4. Switch to the **Scan** tab → select baseline branch → click `Start Scan`
5. Watch the parse / scan progress in the **Log** tab in real time

---

### 📍 Feature Entry Points

After installation, these entries appear in IDEA:

| # | Feature | Location | Trigger |
|---|---------|----------|---------|
| 1 | **Tool Window (main)** | Right toolbar icon / `View` → `Tool Windows` → `AI DiffSense` | Click icon |
| 2 | **Scan Selection** | Right-click in editor → bottom item | Select code, then right-click |
| 3 | **Pre-commit** | Commit panel (`Ctrl+K`), triggered on commit | Click commit |
| 4 | **Settings** | `File` → `Settings` → `Tools` → `AI DiffSense` | Menu |

### Troubleshooting checklist

If buttons don't show up, check in order:

1. **Plugin enabled**: `Settings` → `Plugins` → Installed → find `AI DiffSense` → make sure it's checked ✅
2. **IDE restarted**: must restart after install
3. **Version compatible**: supports IDEA 2023.1 ~ 2025.2 (build 231 ~ 252.*). Check your IDE version: `Help` → `About`
4. **Tool Window activation**: if no icon on the right, click `View` → `Tool Windows` → `AI DiffSense`

---

### 🎯 Feature Details

#### Feature 1: Tool Window (main entry, 3 tabs)

Open via the right toolbar icon or `View` → `Tool Windows` → `DiffSense`.

##### 📋 Requirements tab
- Select a Markdown requirement doc → click `Parse Requirements`
- **Parallel parsing**: the doc is sliced by `##` / `###`, multiple slices sent to the LLM at once (concurrency configurable in Settings)
- Parsed requirements appear in an **editable table**:

  | Enabled | ID | Title | Description | Keywords | Acceptance |
  |---------|----|-------|-------------|----------|------------|
  | ☑ | R001 | User login | ... | login, auth | ... |

- The "Enabled" checkbox controls whether a requirement participates in the scan
- Title / Description / Keywords / Acceptance are **double-click editable**
- Edits sync back to the in-memory requirement doc

##### 🔍 Scan tab
- Enter module name, baseline branch (default `develop`)
- **Code quality scan toggle**: when checked, an extra quality pass runs (bug / security / performance / smell), sharing the same persisted value as in Settings
- Click `Start Scan` → auto-collect Git Diff → send to LLM to judge coverage (plus an extra quality pass if enabled)
- Coverage result table shows each requirement's status:
  - ✅ Covered (high confidence)
  - ⚠️ Partial (with gaps)
  - ❌ Uncovered
- Top summary bar shows coverage percentage, colored by threshold (green / orange / red)
- **Code quality issues area**: below the coverage table, a 4-column table:
  - Severity (🔴 High / 🟡 Medium / 🟢 Low, color rendered)
  - Category (bug / security / performance / smell)
  - File + line
  - Description
  - Click a row to see full description + fix suggestion in the bottom detail panel
- Click `Export Report` to generate a Markdown report (coverage + quality issues) in the project root

##### 📜 Log tab
- **Real-time output** of the parse and scan process
- Includes: slice count, concurrent execution, each LLM call, token stats
- Monospace font for alignment

#### Feature 2: Scan Selection

Open via: select code in editor → right-click → `Scan Requirements Covered by Selection`

- Auto-opens the Tool Window if not open
- Reuses the requirements parsed in the Tool Window (parse them first in the Requirements tab)
- Scans which requirements the selected code covers, in the background
- Results show in the Scan tab; process is written to the Log tab

#### Feature 3: Pre-commit Interception

Triggered automatically on `Ctrl+K` commit (must be enabled in Settings first)

- Before commit, auto-scans the **files actually checked by the user** (`panel.selectedChanges`), taking staged diffs by file path
- If uncovered requirements exceed the threshold, a dialog asks whether to continue
- Scan results and logs are pushed to the Tool Window

**Settings options**:
- `Enable Pre-commit Interception`: toggle
- `Max Uncovered`: blocks commit when exceeded

> ⚠️ Pre-commit requires that requirements have been parsed in the Tool Window (or a `requirements.json` exists in the project root)

---

### 🔧 Configuration Reference

`Settings` → `Tools` → `AI DiffSense`:

**Basic**

| Option | Description | Default |
|--------|-------------|---------|
| API Base URL | OpenAI-compatible API endpoint | `https://api.openai.com/v1` |
| Model | Model name | `claude-sonnet-4-20250514` |
| API Key | API key (empty → read env `AI_API_KEY`) | empty |
| **Parse Concurrency** | Parallel LLM calls during parsing | `3` |
| Enable Pre-commit | Whether to scan before commit | off |
| Max Uncovered | Pre-commit block threshold | `3` |

**System Prompts**

Each prompt textbox has a "Reset to Default" button that overwrites with the built-in constant from `Prompts.kt`.

| Option | Description | Default source |
|--------|-------------|----------------|
| Parse Prompt | System prompt for requirement parsing | `Prompts.parseSystemPrompt` |
| Coverage Scan Prompt | System prompt for coverage judgment | `Prompts.scanSystemPrompt` |
| Quality Scan Prompt | System prompt for code quality review | `Prompts.qualitySystemPrompt` |

**Code Quality Scan**

| Option | Description | Default |
|--------|-------------|---------|
| Enable Quality Scan | Whether to add a quality pass during scan (shares the same value with the scan window checkbox) | on |
| **Scan Concurrency** | Batched concurrency for coverage scan LLM calls | `3` |

> 💡 The quality scan toggle is available in both the Settings panel and the scan window, pointing to the same persisted value; changing one syncs the other.

---

### 📦 Build from Source

#### Prerequisites

- JDK 17+
- IntelliJ IDEA (for `runIde` debugging)
- Network access to Maven Central (for Gradle plugins and Gson)

#### Build the zip

```powershell
.\gradlew.bat clean buildPlugin --rerun-tasks
```

Output: `build/distributions/diffsense-idea-1.0.0.zip`

#### Debug run

```powershell
.\gradlew.bat runIde
```

Launches an IDEA sandbox instance with DiffSense loaded.

---

### 🎯 Use Cases

| Scenario | Value |
|----------|-------|
| Pre-commit self-check | Block uncovered requirements before they ship |
| Code Review | Judge whether a PR fully covers requirements |
| Requirement review | Break long docs into verifiable items |
| Iteration retrospective | Track requirement coverage per iteration |
| Quality gate | Scan diffs for bug / security / performance / smell issues early |

---

### 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│  UI Layer (Kotlin / Swing)                  │
│  ├─ DiffSenseToolWindowPanel (3-tab main)   │
│  │   ├─ RequirementTable (editable table)   │
│  │   ├─ CoverageResultTable                 │
│  │   ├─ QualityResultTable                  │
│  │   └─ ScanLogPanel (real-time log)        │
│  └─ DiffSenseSettingsPanel (config+prompts) │
├─────────────────────────────────────────────┤
│  Core Layer (pure Kotlin)                   │
│  ├─ RequirementParser  ← parallel parse     │
│  ├─ CoverageScanner    ← scan stage         │
│  ├─ QualityScanner     ← quality stage      │
│  ├─ LLMClient          ← HTTP calls         │
│  ├─ MarkdownSplitter   ← doc slicing        │
│  └─ DiffCollector      ← Git4Idea           │
├─────────────────────────────────────────────┤
│  Infra Layer                                │
│  ├─ DiffSenseSettings (persisted config)    │
│  ├─ TokenStats (parse / scan / quality)     │
│  └─ ReportExporter                          │
└─────────────────────────────────────────────┘
```

### 📁 Project Structure

```
.
├── build.gradle.kts              # Gradle build
├── settings.gradle.kts
├── gradle.properties             # compatibility range, etc.
└── src/main/
    ├── kotlin/com/diffsense/
    │   ├── actions/              # Action entries
    │   │   └── ScanSelectionAction.kt    ← editor right-click
    │   ├── core/                 # core business logic
    │   │   ├── CoverageScanner.kt
    │   │   ├── DiffCollector.kt
    │   │   ├── DiffSenseConfig.kt
    │   │   ├── LLMClient.kt
    │   │   ├── MarkdownSplitter.kt
    │   │   ├── Models.kt
    │   │   ├── Prompts.kt                ← three system prompt constants
    │   │   ├── QualityScanner.kt         ← code quality scanner
    │   │   ├── RequirementParser.kt       ← parallel version
    │   │   └── TokenStats.kt             ← parse/scan/quality stages
    │   ├── git/                  # Pre-commit integration
    │   │   └── DiffSenseCheckinHandler.kt
    │   ├── icons/                # icons
    │   │   └── DiffSenseIcons.kt
    │   ├── settings/             # config panel
    │   │   ├── DiffSenseSettings.kt
    │   │   ├── DiffSenseSettingsConfigurable.kt
    │   │   └── DiffSenseSettingsPanel.kt  ← prompt editor + quality toggle
    │   └── ui/                   # UI components
    │       ├── CoverageResultTable.kt
    │       ├── QualityResultTable.kt     ← quality issue table
    │       ├── RequirementTable.kt        ← editable requirement table
    │       ├── ScanLogPanel.kt            ← real-time log panel
    │       └── toolwindow/
    │           ├── DiffSenseToolWindowFactory.kt
    │           ├── DiffSenseToolWindowPanel.kt  ← 3-tab main panel
    │           └── DiffSenseToolWindowService.kt
    └── resources/META-INF/
        └── plugin.xml            # plugin descriptor (registers all entries)
```

### 🔄 Compatibility

- **IDEA version**: 2023.1 (build 231) ~ 2025.2 (build 252.*)
- **Required plugin**: `Git4Idea` (Git integration, bundled with IDEA)
- **JDK**: 17+ (build time) / runtime provided by IDEA's bundled JBR

To change the compatibility range: edit `pluginSinceBuild` / `pluginUntilBuild` in `gradle.properties`, **and also** update `patchPluginXml { sinceBuild.set(...) / untilBuild.set(...) }` in `build.gradle.kts`, then rebuild.

### 📄 License

MIT

---

## 🇨🇳 中文

一个面向研发团队的 IntelliJ IDEA 插件，把「需求文档 → 结构化需求 → 代码覆盖度 + 代码质量」这条链路做成可视化、可交互、可推广的工作流。

### ✨ v1.0.0 正式版

| 能力 | 说明 |
|------|------|
| 📋 **需求拆解** | 把 Markdown 需求文档按 `##`/`###` 切片并行送 LLM，拆解成可验收的结构化条目 |
| 🎯 **覆盖度扫描** | 对比 Git Diff 与需求，判断每条需求的覆盖状态（已覆盖 / 部分 / 未覆盖） |
| 🛡️ **代码质量扫描** | 独立扫描 diff 中的潜在 bug / 安全风险 / 性能问题 / 代码异味，带严重度 + 修复建议 |
| 🚦 **Pre-commit 拦截** | 提交时自动扫描勾选文件，未覆盖需求超阈值则拦截，从源头守住质量 |
| 🪟 **Tool Window** | 右侧常驻工具窗（需求 / 扫描 / 日志 三 Tab），不阻塞 IDEA |
| ⚡ **效率优化** | 覆盖度与质量扫描并行、分批并发、HttpClient 共享单例 |
| ⚙️ **提示词可配置** | parse / scan / quality 三套 Prompt 可在 Settings 自定义，一键重置 |

> 📌 **v1.0.0 关键修复**：Pre-commit 不再盲目执行 `git diff --cached`（IDEA changelist ≠ git staged，会扫到旧版本），改用 `panel.selectedChanges` 获取用户实际勾选的文件按路径取 diff，确保扫描的是当前要提交的版本。

---

### 🚀 快速上手（3 分钟）

#### 第 1 步：安装插件

1. 打开 IDEA → `File` → `Settings`（Mac：`IntelliJ IDEA` → `Preferences`）
2. 左侧选 `Plugins` → 顶部点齿轮 ⚙️ → `Install Plugin from Disk...`
3. 选择 `build/distributions/diffsense-idea-1.0.0.zip`
4. 重启 IDEA

#### 第 2 步：配置 LLM

1. `File` → `Settings` → 左侧 `Tools` → `AI DiffSense`
2. 填写：
   - **API Base URL**：OpenAI 兼容地址（如 `https://api.openai.com/v1`）
   - **Model**：模型名（推荐 `claude-sonnet-4-20250514`）
   - **API Key**：直接填，或留空从环境变量 `AI_API_KEY` 读取
   - **需求解析并发度**：默认 `3`（越高越快，但更耗 API 配额）
3. 点 `Apply` / `OK`

> 也支持环境变量：`AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`

#### 第 3 步：使用

1. 右侧工具栏点 DiffSense 图标（或 `View` → `Tool Windows` → `AI DiffSense`）打开工具窗
2. 在「需求」Tab 选需求文档 `.md` → 点 `拆解需求`
3. 拆解完成后**可直接编辑**表格里的需求
4. 切到「扫描」Tab → 选基线分支 → 点 `开始扫描`
5. 在「日志」Tab 实时查看解析 / 扫描过程

---

### 📍 所有功能入口

插件安装后，以下入口会出现在 IDEA 里：

| # | 功能 | 位置 | 触发方式 |
|---|------|------|----------|
| 1 | **Tool Window（主入口）** | 右侧工具栏图标 / `View` → `Tool Windows` → `AI DiffSense` | 点击图标打开侧栏 |
| 2 | **审查选中代码** | 编辑器内右键 → 最底部 `审查选中代码覆盖的需求` | 选中代码后右键 |
| 3 | **Pre-commit 拦截** | Commit 面板（`Ctrl+K`）提交时自动触发 | 点提交按钮时 |
| 4 | **Settings** | `File` → `Settings` → `Tools` → `AI DiffSense` | 菜单进入 |

#### 找不到入口？排查清单

如果按钮没出现，按顺序检查：

1. **确认插件已启用**：`Settings` → `Plugins` → 已安装 → 找到 `AI DiffSense` → 确认勾选 ✅
2. **确认重启过 IDE**：装完插件必须重启
3. **确认版本兼容**：本插件支持 IDEA 2023.1 ~ 2025.2（build 231 ~ 252.*）。看你的 IDE 版本：`Help` → `About`
4. **Tool Window 手动激活**：如果右侧没图标，点 `View` → `Tool Windows` → `AI DiffSense`

---

### 🎯 三大功能详解

#### 功能 1：Tool Window（主入口，三 Tab）

**打开方式**：右侧工具栏点 DiffSense 图标，或 `View` → `Tool Windows` → `DiffSense`

工具窗内含三个 Tab：

##### 📋 需求 Tab
- 选择 Markdown 需求文档 → 点 `拆解需求`
- **并行解析**：文档按 `##` / `###` 切片，多片同时送 LLM（并发度可在 Settings 配置）
- 拆解出的需求以**可编辑表格**呈现：

  | 启用 | ID | 标题 | 描述 | 关联词 | 验收标准 |
  |------|----|------|------|--------|----------|
  | ☑ | R001 | 用户登录 | ... | login, auth | ... |

- 「启用」列勾选框控制该需求是否参与扫描
- 标题 / 描述 / 关联词 / 验收标准**双击即可编辑**
- 编辑会自动同步回内存中的需求文档

##### 🔍 扫描 Tab
- 输入模块名、基线分支（默认 `develop`）
- **代码质量扫描开关**：勾选后扫描时会额外执行一次质量检查（bug / 安全 / 性能 / 异味），与 Settings 中的开关共享同一个持久化值
- 点 `开始扫描` → 自动收集 Git Diff → 送 LLM 判断覆盖度（开启质量扫描时再追加一次质量扫描）
- 覆盖度结果表格展示每条需求的覆盖状态：
  - ✅ 已覆盖（高置信度）
  - ⚠️ 部分覆盖（有缺口）
  - ❌ 未覆盖
- 顶部摘要条显示覆盖率百分比，按阈值变色（绿 / 橙 / 红）
- **代码质量问题区**：在覆盖度表格下方，4 列表格展示
  - 严重度（🔴 高 / 🟡 中 / 🟢 低，着色渲染）
  - 类别（bug / security / performance / smell）
  - 文件 + 行号
  - 描述
  - 点击行可在底部详情面板查看完整描述 + 修复建议
- 点 `导出报告` 生成 Markdown 报告（含覆盖度 + 质量问题清单）到项目根目录

##### 📜 日志 Tab
- **实时输出**解析与扫描全过程
- 包括：切片数、并发执行、每片 LLM 调用、Token 统计
- 等宽字体，便于对齐

#### 功能 2：审查选中代码

**打开方式**：编辑器里选中一段代码 → 右键 → `审查选中代码覆盖的需求`

- 自动打开 Tool Window（如未打开）
- 复用工具窗中已拆解的需求（需先在「需求」Tab 拆解）
- 后台扫描选中代码覆盖了哪些需求
- 结果直接展示在「扫描」Tab，过程写入「日志」Tab

#### 功能 3：Pre-commit 拦截

**打开方式**：`Ctrl+K` 提交代码时自动触发（需先在 Settings 启用）

- 提交前自动扫描**用户实际勾选的文件**（`panel.selectedChanges`），按文件路径取 staged diff
- 若未覆盖需求数超过阈值，弹窗询问是否继续
- 扫描结果和日志同步推送到 Tool Window

**Settings 配置项**：
- `启用 Pre-commit 检查`：开关
- `最大允许未覆盖数`：超过此值会拦截提交

> ⚠️ Pre-commit 需要先在 Tool Window 拆解过需求（或项目根目录有 `requirements.json`）才会生效

---

### 🔧 配置项详解

`Settings` → `Tools` → `AI DiffSense`：

**基础配置**

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| API Base URL | OpenAI 兼容 API 地址 | `https://api.openai.com/v1` |
| Model | 模型名 | `claude-sonnet-4-20250514` |
| API Key | API 密钥（留空读环境变量 `AI_API_KEY`） | 空 |
| **需求解析并发度** | 拆解需求时的并行 LLM 调用数 | `3` |
| 启用 Pre-commit 检查 | 提交前是否扫描 | 关 |
| 最大允许未覆盖数 | Pre-commit 拦截阈值 | `3` |

**系统提示词**

每个 prompt 文本框旁都有「重置为默认」按钮，点击后用 `Prompts.kt` 中的内置常量覆盖。

| 配置项 | 说明 | 默认值来源 |
|--------|------|-----------|
| 需求拆解 Prompt（parse） | 控制需求拆解的 system prompt | `Prompts.parseSystemPrompt` |
| 覆盖度扫描 Prompt（scan） | 控制覆盖度判断的 system prompt | `Prompts.scanSystemPrompt` |
| 代码质量扫描 Prompt（quality） | 控制代码质量审查的 system prompt | `Prompts.qualitySystemPrompt` |

**代码质量扫描**

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 启用代码质量扫描 | 扫描时是否追加质量检查（与扫描窗口的 checkbox 共享同一个值） | 开 |
| **覆盖度扫描并发度** | 覆盖度扫描分批并发的 LLM 调用数 | `3` |

> 💡 质量扫描开关在 Settings 面板和扫描窗口两处都能配置，指向同一个持久化值，任一处修改另一处同步。

---

### 📦 从源码构建

#### 前置要求

- JDK 17+
- IntelliJ IDEA（用于 `runIde` 调试）
- 网络可访问 Maven Central（拉 Gradle 插件和 Gson）

#### 构建 zip

```powershell
.\gradlew.bat clean buildPlugin --rerun-tasks
```

产物：`build/distributions/diffsense-idea-1.0.0.zip`

#### 调试运行

```powershell
.\gradlew.bat runIde
```

会启动一个带 DiffSense 插件的 IDEA 沙箱实例。

---

### 🎯 使用场景

| 场景 | 价值 |
|------|------|
| 提交前自检 | Pre-commit 拦截未覆盖需求，避免漏实现 |
| Code Review | 判断 PR 是否完整覆盖需求 |
| 需求评审 | 把长文档拆解成可验收的条目 |
| 迭代回顾 | 统计每个迭代的需求覆盖率 |
| 代码质量把关 | 扫描 diff 中的 bug / 安全 / 性能 / 异味，提前发现问题 |

---

### 🏗️ 架构

```
┌─────────────────────────────────────────────┐
│  UI Layer (Kotlin / Swing)                  │
│  ├─ DiffSenseToolWindowPanel（三 Tab 主面板）│
│  │   ├─ RequirementTable（可编辑需求表格）   │
│  │   ├─ CoverageResultTable（覆盖度结果）    │
│  │   ├─ QualityResultTable（质量问题结果）   │
│  │   └─ ScanLogPanel（实时日志）             │
│  └─ DiffSenseSettingsPanel（配置 + prompt）  │
├─────────────────────────────────────────────┤
│  Core Layer (纯 Kotlin)                     │
│  ├─ RequirementParser  ← 并行 parse（协程）  │
│  ├─ CoverageScanner    ← scan 阶段           │
│  ├─ QualityScanner     ← quality 阶段        │
│  ├─ LLMClient          ← HTTP 调用           │
│  ├─ MarkdownSplitter   ← 文档切片            │
│  └─ DiffCollector      ← Git4Idea            │
├─────────────────────────────────────────────┤
│  Infra Layer                                │
│  ├─ DiffSenseSettings（持久化配置 + prompt） │
│  ├─ TokenStats（parse / scan / quality）     │
│  └─ ReportExporter                          │
└─────────────────────────────────────────────┘
```

### 📁 项目结构

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
    │   │   ├── Prompts.kt                ← 三套系统提示词常量
    │   │   ├── QualityScanner.kt         ← 代码质量扫描器
    │   │   ├── RequirementParser.kt       ← 并行版本
    │   │   └── TokenStats.kt             ← parse/scan/quality 三阶段
    │   ├── git/                  # Pre-commit 集成
    │   │   └── DiffSenseCheckinHandler.kt
    │   ├── icons/                # 图标
    │   │   └── DiffSenseIcons.kt
    │   ├── settings/             # 配置面板
    │   │   ├── DiffSenseSettings.kt
    │   │   ├── DiffSenseSettingsConfigurable.kt
    │   │   └── DiffSenseSettingsPanel.kt  ← 含 prompt 编辑 + 质量开关
    │   └── ui/                   # UI 组件
    │       ├── CoverageResultTable.kt
    │       ├── QualityResultTable.kt     ← 质量问题表格
    │       ├── RequirementTable.kt        ← 可编辑需求表格
    │       ├── ScanLogPanel.kt            ← 实时日志面板
    │       └── toolwindow/
    │           ├── DiffSenseToolWindowFactory.kt
    │           ├── DiffSenseToolWindowPanel.kt  ← 三 Tab 主面板
    │           └── DiffSenseToolWindowService.kt
    └── resources/META-INF/
        └── plugin.xml            # 插件描述（注册所有入口）
```

### 🔄 兼容范围

- **IDEA 版本**：2023.1 (build 231) ~ 2025.2 (build 252.*)
- **依赖插件**：`Git4Idea`（Git 集成，IDEA 自带）
- **JDK**：17+（构建时）/ 运行时由 IDEA 内置 JBR 提供

如需修改兼容范围：编辑 `gradle.properties` 的 `pluginSinceBuild` / `pluginUntilBuild`，**同时**修改 `build.gradle.kts` 中 `patchPluginXml { sinceBuild.set(...) / untilBuild.set(...) }`，然后重新构建。

### 📄 License

MIT
