package com.diffsense.git

import com.diffsense.core.CoverageScanner
import com.diffsense.core.DiffCollector
import com.diffsense.core.RequirementParser
import com.diffsense.core.TokenStats
import com.diffsense.settings.DiffSenseSettings
import com.diffsense.ui.toolwindow.DiffSenseToolWindowService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.util.PairConsumer

/**
 * Pre-commit 检查：提交前自动扫描需求覆盖度
 *
 * 对应 hooks/pre-commit 脚本的功能。
 *
 * 注册于 plugin.xml：
 *   <checkinHandlerFactory implementation="com.diffsense.git.DiffSenseCheckinHandlerFactory"/>
 *
 * 触发条件：
 *   1. 用户在 Settings 中启用了 Pre-commit 拦截
 *   2. 项目根目录有 requirements.json（否则跳过）
 *
 * 行为：
 *   - 收集暂存区 diff
 *   - 调用 CoverageScanner 分析
 *   - 若未覆盖数 > 阈值，弹窗询问是否继续提交
 */
class DiffSenseCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return DiffSenseCheckinHandler(panel)
    }
}

class DiffSenseCheckinHandler(
    private val panel: CheckinProjectPanel,
) : CheckinHandler() {

    private val log = logger<DiffSenseCheckinHandler>()

    /**
     * 提交前检查
     *
     * 返回结果：
     *   - ReturnResult.COMMIT：继续提交
     *   - ReturnResult.CANCEL：取消提交
     *
     * v0.9.0 修复（2.txt）：
     *   1. 三条提前 return 分支增加 Tool Window 日志推送，让用户能看到跳过原因
     *   2. findRequirementsJson 优先读 Tool Window 中用户选择的 JSON 路径
     *   3. collectStagedDiff 前后增加诊断日志
     *   4. collectStagedDiff 已改为多仓库聚合
     */
    override fun beforeCheckin(
        executor: com.intellij.openapi.vcs.changes.CommitExecutor?,
        additionalDataConsumer: PairConsumer<Any, Any>,
    ): CheckinHandler.ReturnResult {

        val project = panel.project
        val settings = DiffSenseSettings.getInstance()
        val service = DiffSenseToolWindowService.getInstance(project)

        // 未启用 Pre-commit 检查，直接放行
        if (!settings.state.preCommitEnabled) {
            service.appendLog("[pre-commit] ⏭ Pre-commit 拦截未启用，放行提交")
            return CheckinHandler.ReturnResult.COMMIT
        }

        service.appendLog("[pre-commit] ▶ Pre-commit 检查启动")

        // v0.9.0：优先读 Tool Window 中用户选择的 JSON，再回退到项目根目录
        val reqFile = findRequirementsJson(project, service.getReqJsonPath())
        if (reqFile == null || !reqFile.exists()) {
            val msg = if (reqFile == null) {
                "[pre-commit] ⏭ 未找到 requirements.json（项目根目录、docs/、Tool Window 选择均无效），跳过检查"
            } else {
                "[pre-commit] ⏭ 需求文件 ${reqFile.absolutePath} 不存在，跳过检查"
            }
            service.appendLog(msg)
            return CheckinHandler.ReturnResult.COMMIT
        }
        service.appendLog("[pre-commit] • 使用需求文件：${reqFile.absolutePath}")

        // 同步执行扫描（在 EDT 上阻塞等待用户决策）
        var result = CheckinHandler.ReturnResult.COMMIT
        var error: String? = null
        var uncoveredCount = 0

        // 注意：beforeCheckin 是在 EDT 调用，需要用 ProgressManager 同步执行
        try {
            val config = settings.toConfig()

            service.appendLog("[pre-commit] • 正在收集暂存区 diff...")
            val diff = DiffCollector.collectStagedDiff(project) { line ->
                service.appendLog(line)
            }
            if (diff.isBlank()) {
                service.appendLog("[pre-commit] ⏭ 暂存区无改动，放行提交")
                return CheckinHandler.ReturnResult.COMMIT
            }
            service.appendLog("[pre-commit] • 已收集 staged diff：${diff.length} 字符")

            val json = reqFile.readText(Charsets.UTF_8)
            val parser = RequirementParser(config)
            val doc = parser.fromJson(json)
            service.appendLog("[pre-commit] • 载入 ${doc.requirements.size} 条需求（启用 ${doc.requirements.count { it.enabled }} 条）")

            TokenStats.reset()
            val scanner = CoverageScanner(config)
            service.appendLog("[pre-commit] • 开始扫描覆盖度...")
            val report = scanner.scan(
                requirements = doc.requirements.filter { it.enabled },
                diff = diff,
                module = "pre-commit",
            )

            uncoveredCount = report.summary.uncovered + report.summary.partial

            // 推送结果到 Tool Window 的扫描 Tab 与日志
            service.appendLog("[pre-commit] ✓ 扫描完成：覆盖 ${report.summary.covered}/${report.summary.total}，未覆盖 $uncoveredCount 条")
            service.showReport(report, doc.requirements)
        } catch (e: Exception) {
            error = e.message
            log.warn("[pre-commit] 扫描出错: ${e.message}")
            service.appendLog("[pre-commit] ✗ 扫描出错：${e.message}")
        }

        // 决策：是否拦截
        val maxAllowed = settings.state.preCommitMaxUncovered
        when {
            error != null -> {
                // 扫描出错，询问是否继续
                val choice = Messages.showYesNoDialog(
                    project,
                    "DiffSense 扫描出错：$error\n是否仍然提交？",
                    "DiffSense Pre-commit 出错",
                    Messages.getWarningIcon()
                )
                if (choice != Messages.YES) {
                    result = CheckinHandler.ReturnResult.CANCEL
                }
            }
            uncoveredCount > maxAllowed -> {
                // 超过阈值，询问是否继续
                val choice = Messages.showYesNoDialog(
                    project,
                    """DiffSense 检测到 ${uncoveredCount} 条需求未被覆盖（阈值：${maxAllowed}）
                      |
                      |建议先补充实现或确认需求是否必要。
                      |
                      |是否仍然继续提交？""".trimMargin(),
                    "DiffSense Pre-commit 拦截",
                    Messages.getWarningIcon()
                )
                if (choice != Messages.YES) {
                    result = CheckinHandler.ReturnResult.CANCEL
                }
            }
        }

        return result
    }

    /**
     * 查找 requirements.json
     *
     * v0.9.0：优先级
     *   1. Tool Window 中用户手动选择的 JSON 路径（uiPath）
     *   2. 项目根目录 requirements.json
     *   3. docs/requirements.json
     */
    private fun findRequirementsJson(project: Project, uiPath: String): java.io.File? {
        val basePath = project.basePath ?: return null
        val candidates = mutableListOf<java.io.File>()
        if (uiPath.isNotBlank()) {
            candidates.add(java.io.File(uiPath))
        }
        candidates.add(java.io.File(basePath, "requirements.json"))
        candidates.add(java.io.File(basePath, "docs/requirements.json"))
        return candidates.firstOrNull { it.exists() }
    }
}
