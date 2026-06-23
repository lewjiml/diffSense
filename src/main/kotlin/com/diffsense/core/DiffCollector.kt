package com.diffsense.core

import com.intellij.openapi.project.Project
import git4idea.GitUtil
import git4idea.repo.GitRepository
import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream

/**
 * Git Diff 收集器
 *
 * 负责从当前项目收集代码改动，供 CoverageScanner 使用。
 * 对应 ai-req.js 中的 git diff 命令。
 *
 * 使用 Git4Idea 插件提供的 API，而非直接执行 git 命令。
 */
object DiffCollector {

    private val log = logger<DiffCollector>()

    /**
     * 获取当前项目相对于指定基线分支的 diff
     *
     * @param project    当前项目
     * @param baseBranch 基线分支（如 develop）
     * @return diff 文本；若无改动或出错返回空串
     */
    fun collectDiff(project: Project, baseBranch: String): String {
        val repo = findRepo(project) ?: run {
            log.warn("未找到 Git 仓库")
            return ""
        }
        return try {
            collectDiffFromRepo(repo, baseBranch)
        } catch (e: Exception) {
            log.warn("收集 diff 失败: ${e.message}")
            ""
        }
    }

    /** 收集暂存区 diff（pre-commit 场景） */
    fun collectStagedDiff(project: Project): String {
        val repo = findRepo(project) ?: return ""
        return try {
            runGit(repo, "diff", "--cached", "--stat") +
                "\n" + runGit(repo, "diff", "--cached")
        } catch (e: Exception) {
            log.warn("收集 staged diff 失败: ${e.message}")
            ""
        }
    }

    private fun findRepo(project: Project): GitRepository? {
        val repos = GitUtil.getRepositories(project)
        return repos.firstOrNull()
    }

    private fun collectDiffFromRepo(repo: GitRepository, baseBranch: String): String {
        // 优先用 origin/<baseBranch>，避免本地没有该分支
        val remoteRef = "origin/$baseBranch"
        val diff = runGit(repo, "diff", remoteRef + "...HEAD")
        if (diff.isNotBlank()) return diff
        // 退回到本地 baseBranch
        return runGit(repo, "diff", "$baseBranch...HEAD")
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
        pb.directory(java.io.File(repo.root.path))
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = ByteArrayOutputStream()
        proc.inputStream.use { it.copyTo(out) }
        proc.waitFor()
        return out.toString("UTF-8")
    }
}
