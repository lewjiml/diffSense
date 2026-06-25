package com.diffsense.core

import com.intellij.openapi.project.Project
import git4idea.GitUtil
import git4idea.repo.GitRepository
import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Git Diff 收集器
 *
 * 负责从当前项目收集代码改动，供 CoverageScanner 使用。
 *
 * v3 改进：
 *   - 问题 4：多 Git 仓库聚合 diff，遍历所有仓库并标注来源
 *   - 问题 5a：移除基线分支，改用 `git diff HEAD`（只看未提交改动）
 *   - 问题 5b：支持 pathspec 排除路径（排除文件/目录噪声）
 */
object DiffCollector {

    private val log = logger<DiffCollector>()

    /**
     * 收集当前项目所有 Git 仓库的未提交改动（聚合）
     *
     * 问题 4：遍历 [GitUtil.getRepositories] 返回的全部仓库，每个仓库执行
     *   `git diff HEAD`，并把 diff 文本按仓库分段标注来源。
     * 问题 5a：不再与 origin/develop 等基线分支比较，只看工作区相对 HEAD 的改动。
     * 问题 5b：支持通过 [excludePaths] 排除指定路径（git pathspec `:(exclude)` 语法）。
     *
     * @param project       当前项目
     * @param excludePaths  需要从 diff 中排除的路径列表（相对各仓库根；支持目录/文件/glob）
     * @param onProgress    进度日志回调（可选）
     * @return 聚合后的 diff 文本；若无改动或出错返回空串
     */
    fun collectDiff(
        project: Project,
        excludePaths: List<String> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
    ): String {
        val repos = GitUtil.getRepositories(project)
        if (repos.isEmpty()) {
            log.warn("未找到任何 Git 仓库")
            onProgress?.invoke("⚠ 未找到任何 Git 仓库")
            return ""
        }
        onProgress?.invoke("发现 ${repos.size} 个 Git 仓库，开始聚合 diff")

        val sb = StringBuilder()
        var totalFiles = 0
        var includedRepos = 0
        for ((idx, repo) in repos.withIndex()) {
            val repoName = repoName(repo)
            onProgress?.invoke("→ [${idx + 1}/${repos.size}] 扫描仓库：$repoName")
            val diff = try {
                collectDiffFromRepo(repo, excludePaths)
            } catch (e: Exception) {
                log.warn("收集仓库 $repoName diff 失败: ${e.message}")
                onProgress?.invoke("⚠ 仓库 $repoName diff 失败：${e.message}")
                ""
            }
            if (diff.isBlank()) continue
            includedRepos++
            val files = countChangedFiles(diff)
            totalFiles += files
            sb.append("\n")
            sb.append("diff --repo $repoName (${files} files)\n")
            sb.append(diff)
            if (!diff.endsWith("\n")) sb.append("\n")
        }

        // 问题 5c：日志反馈
        if (excludePaths.isNotEmpty()) {
            onProgress?.invoke("已排除路径：${excludePaths.joinToString(", ")}")
        }
        if (sb.isEmpty()) {
            onProgress?.invoke("ℹ 所有仓库均无未提交改动（git diff HEAD 为空）")
            return ""
        }
        onProgress?.invoke("✓ 聚合完成：$includedRepos 个仓库，共 $totalFiles 个文件改动")
        return sb.toString()
    }

    /**
     * 收集暂存区 diff（pre-commit 场景）
     *
     * v0.9.0 修复（2.txt 问题 3）：改为遍历所有 Git 仓库聚合 staged diff，
     * 与 [collectDiff] 保持一致，避免多模块项目只取第一个仓库导致遗漏。
     */
    fun collectStagedDiff(
        project: Project,
        onProgress: ((String) -> Unit)? = null,
    ): String {
        val repos = GitUtil.getRepositories(project)
        if (repos.isEmpty()) {
            log.warn("[staged] 未找到任何 Git 仓库")
            onProgress?.invoke("⚠ [pre-commit] 未找到任何 Git 仓库")
            return ""
        }
        onProgress?.invoke("[pre-commit] 发现 ${repos.size} 个 Git 仓库，开始聚合 staged diff")

        val sb = StringBuilder()
        var totalFiles = 0
        var includedRepos = 0
        for ((idx, repo) in repos.withIndex()) {
            val repoName = repoName(repo)
            onProgress?.invoke("→ [${idx + 1}/${repos.size}] 扫描暂存区：$repoName")
            val diff = try {
                runGit(repo, "diff", "--cached", "--stat") +
                    "\n" + runGit(repo, "diff", "--cached")
            } catch (e: Exception) {
                log.warn("[staged] 收集仓库 $repoName staged diff 失败: ${e.message}")
                onProgress?.invoke("⚠ [pre-commit] 仓库 $repoName staged diff 失败：${e.message}")
                ""
            }
            if (diff.isBlank()) continue
            includedRepos++
            val files = countChangedFiles(diff)
            totalFiles += files
            sb.append("\n")
            sb.append("diff --repo $repoName (${files} files, staged)\n")
            sb.append(diff)
            if (!diff.endsWith("\n")) sb.append("\n")
        }

        if (sb.isEmpty()) {
            onProgress?.invoke("ℹ [pre-commit] 所有仓库暂存区均为空")
            return ""
        }
        onProgress?.invoke("✓ [pre-commit] 聚合完成：$includedRepos 个仓库，共 $totalFiles 个文件改动")
        return sb.toString()
    }

    /**
     * 单仓库的 diff（问题 5a：git diff HEAD；问题 5b：pathspec 排除）
     */
    private fun collectDiffFromRepo(repo: GitRepository, excludePaths: List<String>): String {
        val args = mutableListOf("diff", "HEAD")
        if (excludePaths.isNotEmpty()) {
            // 先加全量范围，再逐条加 :(exclude)<path>
            args += "--"
            args += "."
            excludePaths.forEach { p ->
                val trimmed = p.trim()
                if (trimmed.isNotEmpty()) args += ":(exclude)$trimmed"
            }
        }
        return runGit(repo, *args.toTypedArray())
    }

    /** 从 diff 文本中粗略统计改动文件数（以 `diff --git` 开头的行数为准） */
    private fun countChangedFiles(diff: String): Int =
        diff.lineSequence().count { it.startsWith("diff --git") }

    /** 仓库名称：取根目录最后一级路径，便于在聚合 diff 中识别来源 */
    private fun repoName(repo: GitRepository): String {
        val path = repo.root.path
        return path.substringAfterLast('/').ifBlank { path }
    }

    /**
     * 在仓库根目录执行 git 命令
     *
     * 使用 ProcessBuilder 而非 Git4Idea 的 service，
     * 因为我们需要原始 diff 文本，service 返回的是解析后的对象。
     */
    private fun runGit(repo: GitRepository, vararg args: String): String {
        val cmd = mutableListOf("git")
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(File(repo.root.path))
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = ByteArrayOutputStream()
        proc.inputStream.use { it.copyTo(out) }
        proc.waitFor()
        return out.toString("UTF-8")
    }
}
