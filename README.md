# DiffSense · AI 需求覆盖度审查插件

> **让每一行代码改动，都有需求可循。**

一个面向研发团队的 IntelliJ IDEA 插件，把「需求文档 → 结构化需求 → 代码覆盖度」这条链路做成可视化、可交互、可推广的工作流。

---

## 🚀 快速上手（3 分钟）

### 第 1 步：安装插件

1. 打开 IDEA → `File` → `Settings`（Mac：`IntelliJ IDEA` → `Preferences`）
2. 左侧选 `Plugins` → 顶部点齿轮 ⚙️ → `Install Plugin from Disk...`
3. 选择 [`build/distributions/diffsense-idea-0.1.0.zip`](file:///e:/all-project/ai-shenhe/ai-initial-review-demo/build/distributions/diffsense-idea-0.1.0.zip)
4. 重启 IDEA

### 第 2 步：配置 LLM

1. `File` → `Settings` → 左侧 `Tools` → `DiffSense`
2. 填写：
   - **API Base URL**：OpenAI 兼容地址（如 `https://api.openai.com/v1`）
   - **Model**：模型名（推荐 `claude-sonnet-4-20250514`）
   - **API Key**：直接填，或留空从环境变量 `AI_API_KEY` 读取
3. 点 `Apply` / `OK`

> 也支持环境变量：`AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`

### 第 3 步：使用

按 `Ctrl+Shift+D` 打开向导 → 选模式 → 开始分析。

---

## 📍 所有功能入口（重要！）

插件安装后，以下 4 个入口会出现在 IDEA 里：

| # | 功能 | 位置 | 触发方式 |
|---|------|------|----------|
| 1 | **向导弹窗** | 顶部菜单 `Tools` → 最底部 `打开 DiffSense 向导` | 菜单点击 或 `Ctrl+Shift+D` |
| 2 | **Tool Window** | 右侧工具栏图标 / `View` → `Tool Windows` → `DiffSense` | 点击图标打开侧栏 |
| 3 | **审查选中代码** | 编辑器内右键 → 最底部 `审查选中代码覆盖的需求` | 选中代码后右键 |
| 4 | **Pre-commit 拦截** | Commit 面板（`Ctrl+K`）提交时自动触发 | 点提交按钮时 |
| 5 | **Settings** | `File` → `Settings` → `Tools` → `DiffSense` | 菜单进入 |

### 找不到入口？排查清单

如果按钮没出现，按顺序检查：

1. **确认插件已启用**：`Settings` → `Plugins` → 已安装 → 找到 `DiffSense` → 确认勾选 ✅
2. **确认重启过 IDE**：装完插件必须重启
3. **确认版本兼容**：本插件支持 IDEA 2023.1 ~ 2025.2（build 231 ~ 252.*）。看你的 IDE 版本：`Help` → `About`
4. **Tool Window 手动激活**：如果右侧没图标，点 `View` → `Tool Windows` → `DiffSense`
5. **菜单被折叠**：`Tools` 菜单里的 `打开 DiffSense 向导` 在最底部，可能需要点底部的 `>>` 展开二级菜单

---

## 🎯 四大功能详解

### 功能 1：向导弹窗（主力入口）

**打开方式**：`Tools` → `打开 DiffSense 向导`，或按 `Ctrl+Shift+D`

向导顶部有三个模式单选：

| 模式 | 用途 | 需要什么输入 |
|------|------|--------------|
| **分割需求** | 把 Markdown 需求文档拆解成结构化 JSON | 需求文档 `.md` |
| **扫描代码** | 基于已有需求 JSON 判断 Git Diff 覆盖度 | 需求 `requirements.json` |
| **一键 Run** | 分割 + 扫描一条龙 | `.md` 文档 |

**操作步骤（以「一键 Run」为例）**：
1. 选模式：`一键 Run`
2. 「需求文档（.md）」一栏选你的需求文档
3. 「模块名」「基线分支」按需修改（默认 `default` / `develop`）
4. 点右下角 `▶ 开始分析`
5. 等待 AI 分析（底部进度条）
6. 结果表格展示每条需求的覆盖状态：
   - ✅ 已覆盖（高置信度）
   - ⚠️ 部分覆盖（有缺口）
   - ❌ 未覆盖
7. 点 `导出报告` 生成 Markdown 报告到项目根目录

### 功能 2：Tool Window（历史报告）

**打开方式**：右侧工具栏点 DiffSense 图标，或 `View` → `Tool Windows` → `DiffSense`

- 查看历次扫描的覆盖度报告
- 每次 Pre-commit 拦截或向导扫描的结果都会出现在这里

### 功能 3：审查选中代码

**打开方式**：编辑器里选中一段代码 → 右键 → `审查选中代码覆盖的需求`

- 弹窗输入 `requirements.json` 路径
- AI 判断选中的代码覆盖了哪些需求
- 扫描完成弹窗显示覆盖率摘要

### 功能 4：Pre-commit 拦截

**打开方式**：`Ctrl+K` 提交代码时自动触发（需先在 Settings 启用）

- 提交前自动扫描暂存区 diff
- 若未覆盖需求数超过阈值，弹窗询问是否继续
- 可在 Settings 中配置：开关 / 最大允许未覆盖数

**Settings 配置项**：
- `启用 Pre-commit 检查`：开关
- `最大允许未覆盖数`：超过此值会拦截提交

> ⚠️ Pre-commit 需要项目根目录有 `requirements.json` 才会生效

---

## 🔧 配置项详解

`Settings` → `Tools` → `DiffSense`：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| API Base URL | OpenAI 兼容 API 地址 | `https://api.openai.com/v1` |
| Model | 模型名 | `claude-sonnet-4-20250514` |
| API Key | API 密钥（留空读环境变量 `AI_API_KEY`） | 空 |
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

产物：`build/distributions/diffsense-idea-0.1.0.zip`

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
┌─────────────────────────────────────┐
│  UI Layer (Kotlin / Swing)          │
│  ├─ WizardDialog（三步向导）         │
│  ├─ ToolWindow（结果展示）           │
│  └─ SettingsPanel（配置）            │
├─────────────────────────────────────┤
│  Core Layer (纯 Kotlin)             │
│  ├─ RequirementParser  ← parse 阶段 │
│  ├─ CoverageScanner    ← scan 阶段  │
│  ├─ LLMClient          ← HTTP 调用  │
│  └─ DiffCollector      ← Git4Idea   │
├─────────────────────────────────────┤
│  Infra Layer                        │
│  ├─ Config (.aireview.yml)          │
│  ├─ TokenStats                      │
│  └─ ReportExporter                  │
└─────────────────────────────────────┘
```

## 📁 项目结构

```
.
├── build.gradle.kts              # Gradle 构建
├── settings.gradle.kts
├── gradle.properties             # 兼容范围等配置
├── .aireview.yml.example         # 配置示例
└── src/main/
    ├── kotlin/com/diffsense/
    │   ├── actions/              # Action 入口
    │   │   ├── OpenWizardAction.kt       ← Ctrl+Shift+D
    │   │   └── ScanSelectionAction.kt    ← 编辑器右键
    │   ├── core/                 # 核心业务逻辑
    │   │   ├── CoverageScanner.kt
    │   │   ├── DiffCollector.kt
    │   │   ├── DiffSenseConfig.kt
    │   │   ├── LLMClient.kt
    │   │   ├── MarkdownSplitter.kt
    │   │   ├── Models.kt
    │   │   ├── Prompts.kt
    │   │   ├── RequirementParser.kt
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
    │       ├── WizardDialog.kt
    │       └── toolwindow/
    │           ├── DiffSenseToolWindowFactory.kt
    │           ├── DiffSenseToolWindowPanel.kt
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
