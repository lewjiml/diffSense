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
     */
    override fun beforeCheckin(
        executor: com.intellij.openapi.vcs.changes.CommitExecutor?,
        additionalDataConsumer: PairConsumer<Any, Any>,
    ): CheckinHandler.ReturnResult {

        val project = panel.project
        val settings = DiffSenseSettings.getInstance()

        // 未启用 Pre-commit 检查，直接放行
        if (!settings.state.preCommitEnabled) {
            return CheckinHandler.ReturnResult.COMMIT
        }

        // 检查是否有 requirements.json
        val reqFile = findRequirementsJson(project)
        if (reqFile == null || !reqFile.exists()) {
            log.info("[pre-commit] 未找到 requirements.json，跳过检查")
            return CheckinHandler.ReturnResult.COMMIT
        }

        // 同步执行扫描（在 EDT 上阻塞等待用户决策）
        var result = CheckinHandler.ReturnResult.COMMIT
        var error: String? = null
        var uncoveredCount = 0

        // 注意：beforeCheckin 是在 EDT 调用，需要用 ProgressManager 同步执行
        try {
            val config = settings.toConfig()
            val diff = DiffCollector.collectStagedDiff(project)
            if (diff.isBlank()) {
                log.info("[pre-commit] 暂存区无改动")
                return CheckinHandler.ReturnResult.COMMIT
            }

            val json = reqFile.readText(Charsets.UTF_8)
            val parser = RequirementParser(config)
            val doc = parser.fromJson(json)

            TokenStats.reset()
            val scanner = CoverageScanner(config)
            val report = scanner.scan(
                requirements = doc.requirements.filter { it.enabled },
                diff = diff,
                module = "pre-commit",
            )

            uncoveredCount = report.summary.uncovered + report.summary.partial

            // 推送结果到 Tool Window 的扫描 Tab 与日志
            val service = DiffSenseToolWindowService.getInstance(project)
            service.appendLog("[pre-commit] 扫描完成：覆盖 ${report.summary.covered}/${report.summary.total}，未覆盖 $uncoveredCount 条")
            service.showReport(report, doc.requirements)
        } catch (e: Exception) {
            error = e.message
            log.warn("[pre-commit] 扫描出错: ${e.message}")
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

    /** 在项目根目录查找 requirements.json */
    private fun findRequirementsJson(project: Project): java.io.File? {
        val basePath = project.basePath ?: return null
        val candidates = listOf(
            java.io.File(basePath, "requirements.json"),
            java.io.File(basePath, "docs/requirements.json"),
        )
        return candidates.firstOrNull { it.exists() }
    }
}
