#!/usr/bin/env node
// AI 初审工具 —— 在测试验收前拦截代码缺陷
// 核心审查脚本（Node.js 版，零第三方依赖）
// 用法：
//   node ai-review.js <文件或目录>              # 审查代码文件
//   node ai-review.js --diff <diff文件>         # 审查 git diff
//   node ai-review.js --stdin                   # 从标准输入读取
//   git diff HEAD~1 | node ai-review.js --stdin # 审查最近一次提交

// 解决 Windows 终端中文乱码
if (process.stdout.setDefaultEncoding) {
  process.stdout.setDefaultEncoding("utf-8");
}

const fs = require("fs");
const path = require("path");
const https = require("https");
const http = require("http");
const { URL } = require("url");

// ==================== 配置加载 ====================

// 读取脚本同目录的 .env 文件，解析 KEY=VALUE
function loadEnv() {
  const envPath = path.join(__dirname, ".env");
  if (!fs.existsSync(envPath)) return;
  const content = fs.readFileSync(envPath, "utf-8");
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq === -1) continue;
    const key = trimmed.slice(0, eq).trim();
    let val = trimmed.slice(eq + 1).trim();
    // 去掉两端引号
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (!process.env[key]) process.env[key] = val;
  }
}

loadEnv();

const BASE_URL = process.env.AI_BASE_URL || "https://api.openai.com/v1";
const API_KEY = process.env.AI_API_KEY || "";
const MODEL = process.env.AI_MODEL || "claude-sonnet-4-20250514";
const SEVERITY = (process.env.REVIEW_SEVERITY || "normal").toLowerCase();

// ==================== 支持的代码扩展名 & 跳过目录 ====================

const CODE_EXTENSIONS = new Set([
  ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
  ".jsx", ".tsx", ".kt", ".scala", ".rb", ".php", ".cs", ".vue",
]);

const SKIP_DIRS = new Set([
  "node_modules", ".git", "target", "build", "dist",
  "__pycache__", ".idea", "venv",
]);

// ==================== Prompt ====================

// 审查普通代码用
const SYSTEM_PROMPT = `你是资深代码审查专家，严格按照企业级开发规范审查代码。
你的目标是：在代码进入测试验收之前，提前发现并拦截缺陷。

审查维度（逐项检查）：
1. 语法错误 —— 编译能过但逻辑有误（如赋值写成比较）
2. 空指针/空值风险 —— 未判空就调用方法、集合可能为空
3. 未捕获异常 —— try-catch 缺失、catch了不处理直接吞掉
4. 入参校验缺失 —— 外部输入未校验（类型、长度、范围、格式）
5. 命名规范 —— 变量名/方法名是否清晰、符合团队规范
6. 代码重复 —— 复制粘贴的逻辑、可抽取的公共方法
7. 性能隐患 —— 循环内查数据库、N+1查询、大对象未释放、资源泄漏
8. 安全风险 —— SQL拼接注入、敏感信息打印日志、硬编码密码/token
9. 业务逻辑风险 —— if/else分支遗漏、边界条件未处理、并发问题

输出要求（严格遵守）：
- 只报告有问题的项，不要输出"没有问题"之类的废话
- 每个问题按以下格式输出：

【级别】致命 / 严重 / 一般 / 建议
【位置】行号或代码片段
【维度】属于上面9项中的哪一项
【原因】一句话说明问题
【修复】给出修复后的代码片段

级别说明：
- 致命：会导致崩溃、数据丢失、安全漏洞 —— 必须修复
- 严重：会导致功能错误、边界异常 —— 强烈建议修复
- 一般：代码质量、可维护性问题 —— 建议修复
- 建议：优化建议、风格改进 —— 可选`;

// 审查 diff 用
const DIFF_SYSTEM_PROMPT = `你是资深代码审查专家，正在审查一个 Pull Request / Merge Request 的代码变更。
你的目标是：在变更合并到主分支前，提前发现引入的缺陷。

审查重点：
1. 这次改动是否引入了新的 bug 或风险
2. 新增/修改代码是否有空指针、异常未捕获等问题
3. 是否破坏了已有逻辑（删错了、改错了）
4. 是否有安全风险（SQL注入、敏感信息泄露）
5. 边界条件和异常分支是否覆盖
6. 新增代码是否符合现有风格

输出要求同上。只报告有问题的项。
如果未发现阻断级问题，在开头明确输出：✅ 未发现阻断级问题`;

// ==================== 调用大模型 ====================

function callLLM(systemPrompt, userMessage) {
  return new Promise((resolve, reject) => {
    const url = new URL(BASE_URL.replace(/\/$/, "") + "/chat/completions");
    const body = JSON.stringify({
      model: MODEL,
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: userMessage },
      ],
      temperature: 0.1,
      max_tokens: 4096,
    });
    const options = {
      method: "POST",
      hostname: url.hostname,
      port: url.port || (url.protocol === "https:" ? 443 : 80),
      path: url.pathname + url.search,
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${API_KEY}`,
        "Content-Length": Buffer.byteLength(body),
      },
    };
    const lib = url.protocol === "https:" ? https : http;
    const req = lib.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        if (res.statusCode !== 200) {
          reject(new Error(`API 返回 ${res.statusCode}: ${data.slice(0, 500)}`));
          return;
        }
        try {
          const json = JSON.parse(data);
          resolve(json.choices[0].message.content);
        } catch (e) {
          reject(new Error(`解析响应失败: ${e.message}`));
        }
      });
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

// ==================== 文件收集 ====================

// 递归收集目录下的代码文件
function collectFiles(targetPath, result = []) {
  if (fs.existsSync(targetPath)) {
    const stat = fs.statSync(targetPath);
    if (stat.isFile()) {
      const ext = path.extname(targetPath).toLowerCase();
      if (CODE_EXTENSIONS.has(ext)) result.push(targetPath);
      return result;
    }
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(targetPath)) {
        if (SKIP_DIRS.has(name)) continue;
        collectFiles(path.join(targetPath, name), result);
      }
    }
  }
  return result;
}

// ==================== 严重度过滤 ====================

const LEVEL_RANK = { "致命": 4, "严重": 3, "一般": 2, "建议": 1 };
const SEVERITY_THRESHOLD = { strict: 1, normal: 2, relaxed: 3 };

// 按级别分块过滤，返回 { content, hasBlocking }
function filterBySeverity(report) {
  const threshold = SEVERITY_THRESHOLD[SEVERITY] || 2;
  // 按 【级别】 行分块
  const blocks = [];
  const lines = report.split(/\r?\n/);
  let current = [];
  let header = [];
  let inHeader = true;

  for (const line of lines) {
    if (line.includes("【级别】")) {
      // 开始新块：先把之前的 header 或块保存
      if (inHeader && current.length) {
        header = current.slice();
        current = [];
      } else if (current.length) {
        blocks.push(current.join("\n"));
        current = [];
      }
      inHeader = false;
    }
    current.push(line);
  }
  if (current.length) {
    if (inHeader) header = current.slice();
    else blocks.push(current.join("\n"));
  }

  // 过滤块
  const kept = [];
  let hasBlocking = false;
  for (const block of blocks) {
    const m = block.match(/【级别】\s*([^\s/／]+)/);
    if (!m) {
      kept.push(block);
      continue;
    }
    const level = m[1].trim();
    const rank = LEVEL_RANK[level] || 0;
    if (rank >= threshold) {
      kept.push(block);
      if (rank >= 3) hasBlocking = true; // 致命/严重为阻断级
    }
  }

  const content = [...header, ...kept].filter(Boolean).join("\n").trim();
  return { content, hasBlocking };
}

// ==================== 审查单个文件 ====================

async function reviewFile(filePath, isDiff = false) {
  const code = fs.readFileSync(filePath, "utf-8");
  const fileName = path.basename(filePath);
  const userMessage = isDiff
    ? `请审查以下代码变更（git diff 格式）：\n\n文件：${fileName}\n\`\`\`diff\n${code}\n\`\`\``
    : `请审查以下代码：\n\n文件：${fileName}\n\`\`\`\n${code}\n\`\`\``;

  const systemPrompt = isDiff ? DIFF_SYSTEM_PROMPT : SYSTEM_PROMPT;
  const raw = await callLLM(systemPrompt, userMessage);
  const { content, hasBlocking } = filterBySeverity(raw);

  // 打印报告头
  const tag = hasBlocking ? "❌ 发现阻断级问题" : "✅ 未发现阻断级问题";
  console.log(`\n${"─".repeat(60)}`);
  console.log(`  审查文件：${fileName}    ${tag}`);
  console.log(`${"─".repeat(60)}`);
  if (content) console.log(content);
  else console.log("（过滤后无需要报告的问题）");

  return hasBlocking;
}

// 从字符串审查（用于 stdin / diff 内容）
async function reviewContent(content, label, isDiff = true) {
  const userMessage = isDiff
    ? `请审查以下代码变更（git diff 格式）：\n\n${label}\n\`\`\`diff\n${content}\n\`\`\``
    : `请审查以下代码：\n\n${label}\n\`\`\`\n${content}\n\`\`\``;

  const systemPrompt = isDiff ? DIFF_SYSTEM_PROMPT : SYSTEM_PROMPT;
  const raw = await callLLM(systemPrompt, userMessage);
  const { content: filtered, hasBlocking } = filterBySeverity(raw);

  const tag = hasBlocking ? "❌ 发现阻断级问题" : "✅ 未发现阻断级问题";
  console.log(`\n${"─".repeat(60)}`);
  console.log(`  审查内容：${label}    ${tag}`);
  console.log(`${"─".repeat(60)}`);
  if (filtered) console.log(filtered);
  else console.log("（过滤后无需要报告的问题）");

  return hasBlocking;
}

// ==================== main ====================

function printHelp() {
  console.log(`
AI 初审工具 —— 在测试验收前拦截代码缺陷

用法：
  node ai-review.js <文件或目录>              审查代码文件
  node ai-review.js --diff <diff文件>         审查 git diff
  node ai-review.js --stdin                   从标准输入读取
  git diff HEAD~1 | node ai-review.js --stdin 审查最近一次提交

配置：
  从脚本同目录的 .env 读取 AI_BASE_URL / AI_API_KEY / AI_MODEL / REVIEW_SEVERITY
  REVIEW_SEVERITY 可选：strict / normal / relaxed（默认 normal）

退出码：
  0 = 未发现阻断级问题
  1 = 发现阻断级问题（致命/严重）
`);
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 0 || args.includes("-h") || args.includes("--help")) {
    printHelp();
    process.exit(0);
  }

  if (!API_KEY) {
    console.error("❌ 未配置 AI_API_KEY，请在 .env 中设置。可参考 .env.example。");
    process.exit(2);
  }

  let hasBlocking = false;

  // --stdin 模式
  if (args.includes("--stdin")) {
    const chunks = [];
    for await (const chunk of process.stdin) chunks.push(chunk);
    const content = Buffer.concat(chunks).toString("utf-8");
    if (!content.trim()) {
      console.error("❌ 标准输入为空");
      process.exit(2);
    }
    hasBlocking = await reviewContent(content, "stdin", true);
  }
  // --diff 模式
  else if (args.includes("--diff")) {
    const idx = args.indexOf("--diff");
    const diffFile = args[idx + 1];
    if (!diffFile) {
      console.error("❌ --diff 需要指定 diff 文件路径");
      process.exit(2);
    }
    if (!fs.existsSync(diffFile)) {
      console.error(`❌ 文件不存在：${diffFile}`);
      process.exit(2);
    }
    hasBlocking = await reviewContent(
      fs.readFileSync(diffFile, "utf-8"),
      path.basename(diffFile),
      true
    );
  }
  // 文件/目录模式
  else {
    const target = args[0];
    if (!fs.existsSync(target)) {
      console.error(`❌ 路径不存在：${target}`);
      process.exit(2);
    }
    const files = collectFiles(target);
    if (files.length === 0) {
      console.error(`❌ 未找到支持的代码文件（${[...CODE_EXTENSIONS].join(" ")}）`);
      process.exit(2);
    }
    console.log(`🔍 共发现 ${files.length} 个代码文件，开始审查...\n`);
    for (const f of files) {
      try {
        if (await reviewFile(f, false)) hasBlocking = true;
      } catch (e) {
        console.error(`\n❌ 审查 ${path.basename(f)} 失败：${e.message}`);
      }
    }
  }

  console.log(`\n${"═".repeat(60)}`);
  if (hasBlocking) {
    console.log("  ❌ 审查完成，发现阻断级问题，请修复后再提交！");
    process.exit(1);
  } else {
    console.log("  ✅ 审查完成，未发现阻断级问题。");
    process.exit(0);
  }
}

main().catch((e) => {
  console.error(`\n💥 运行出错：${e.message}`);
  process.exit(2);
});
