#!/usr/bin/env node
// AI 需求扫描工具 —— 从需求文档拆解需求条目，并扫描代码改动是否覆盖需求
// 三个子命令：parse / scan / run
// 零第三方依赖，只用 Node.js 内置模块
//
// 用法：
//   node ai-req.js parse <需求文档.md> --section "板块关键词" [--pick | --sub-section "子板块"]
//   node ai-req.js scan --req <requirements.json> --module <模块名> --base develop [需求过滤参数]
//   node ai-req.js run <需求文档.md> --section "LargeCNV" --module LargeCNV --base develop

// 解决 Windows 终端中文乱码
if (process.stdout.setDefaultEncoding) {
  process.stdout.setDefaultEncoding("utf-8");
}

const fs = require("fs");
const path = require("path");
const https = require("https");
const http = require("http");
const { URL } = require("url");
const { execSync } = require("child_process");
const crypto = require("crypto");
const readline = require("readline");

// ==================== 配置加载 ====================

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

// ==================== Token 统计 ====================

const tokenStats = {
  parse: { calls: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 },
  scan:  { calls: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 },
};
let currentStage = "parse";

function recordUsage(usage) {
  if (!usage) return;
  const s = tokenStats[currentStage];
  s.calls += 1;
  s.promptTokens += usage.prompt_tokens || 0;
  s.completionTokens += usage.completion_tokens || 0;
  s.totalTokens += usage.total_tokens || 0;
}

function printTokenReport() {
  const bar = "─".repeat(56);
  console.log(`\n${bar}`);
  console.log("  💰 Token 消耗统计");
  console.log(bar);
  const rows = [
    ["阶段", "调用次数", "输入token", "输出token", "合计token"],
    ["parse(需求拆解)", tokenStats.parse.calls, tokenStats.parse.promptTokens, tokenStats.parse.completionTokens, tokenStats.parse.totalTokens],
    ["scan(覆盖扫描)", tokenStats.scan.calls, tokenStats.scan.promptTokens, tokenStats.scan.completionTokens, tokenStats.scan.totalTokens],
  ];
  const totalCalls = tokenStats.parse.calls + tokenStats.scan.calls;
  const totalPrompt = tokenStats.parse.promptTokens + tokenStats.scan.promptTokens;
  const totalCompletion = tokenStats.parse.completionTokens + tokenStats.scan.completionTokens;
  const totalAll = tokenStats.parse.totalTokens + tokenStats.scan.totalTokens;
  rows.push(["合计", totalCalls, totalPrompt, totalCompletion, totalAll]);
  for (const r of rows) {
    console.log(`  ${r[0].padEnd(16)}${String(r[1]).padStart(8)}${String(r[2]).padStart(11)}${String(r[3]).padStart(11)}${String(r[4]).padStart(11)}`);
  }
  console.log(bar);
}

// ==================== 调用大模型 ====================

// 默认 180 秒超时（思考模型耗时长）
function callLLM(systemPrompt, userMessage, timeoutMs = 180000) {
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
          recordUsage(json.usage);
          resolve(json.choices[0].message.content);
        } catch (e) {
          reject(new Error(`解析响应失败: ${e.message}`));
        }
      });
    });
    req.on("error", reject);
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error(`请求超时（${timeoutMs / 1000}s）`));
    });
    req.write(body);
    req.end();
  });
}

// ==================== 代码文件扩展名 & 跳过目录 ====================

const CODE_EXTENSIONS = new Set([
  ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
  ".jsx", ".tsx", ".kt", ".scala", ".rb", ".php", ".cs", ".vue",
]);

const SKIP_DIRS = new Set([
  "node_modules", ".git", "target", "build", "dist",
  "__pycache__", ".idea", "venv",
]);

// ==================== 工具函数 ====================

// 读取 .aireview.yml（简单解析，不依赖 yaml 库）
function loadModuleConfig(moduleName) {
  // 向上查找 .aireview.yml
  let dir = process.cwd();
  for (let i = 0; i < 6; i++) {
    const cfgPath = path.join(dir, ".aireview.yml");
    if (fs.existsSync(cfgPath)) {
      const text = fs.readFileSync(cfgPath, "utf-8");
      const paths = parseModulePathsFromYaml(text, moduleName);
      if (paths.length) return paths;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return [];
}

// 简单解析 .aireview.yml 中某模块下的 paths
function parseModulePathsFromYaml(text, moduleName) {
  const paths = [];
  const lines = text.split(/\r?\n/);
  let inModule = false;
  let inPaths = false;
  for (const line of lines) {
    const m = line.match(/^(\s*)modules:\s*$/);
    if (m) { inModule = false; inPaths = false; continue; }
    const modMatch = line.match(/^\s{2}(\S+):\s*$/);
    if (modMatch) {
      inModule = (modMatch[1] === moduleName);
      inPaths = false;
      continue;
    }
    if (!inModule) continue;
    const pathMatch = line.match(/^\s*-\s+(.+?)\s*$/);
    if (line.match(/^\s+-\s+/) && line.includes("/")) {
      // paths 下的条目
      const v = line.replace(/^\s*-\s+/, "").trim();
      if (v && !v.includes(":") && (v.endsWith("/") || v.match(/\.(java|py|js|ts|go|kt)$/))) {
        paths.push(v);
      }
    }
  }
  return paths;
}

// 收集目录下的代码文件
function collectCodeFiles(targetPath, result = []) {
  if (!fs.existsSync(targetPath)) return result;
  const stat = fs.statSync(targetPath);
  if (stat.isFile()) {
    const ext = path.extname(targetPath).toLowerCase();
    if (CODE_EXTENSIONS.has(ext)) result.push(targetPath);
    return result;
  }
  if (stat.isDirectory()) {
    for (const name of fs.readdirSync(targetPath)) {
      if (SKIP_DIRS.has(name)) continue;
      collectCodeFiles(path.join(targetPath, name), result);
    }
  }
  return result;
}

// ==================== parse 命令 ====================

const PARSE_SYSTEM_PROMPT = `你是资深需求分析师。请把用户给你的需求文档片段，拆解成结构化的需求条目。

要求：
1. 每条需求要原子化、可测试、可验收
2. 提取明确的验收标准
3. 标注优先级：P0（必须）/ P1（重要）/ P2（可选）
4. 标注分类：功能 / UI / 接口 / 数据 / 安全 / 性能

输出 JSON 数组，每个元素格式：
{
  "title": "需求标题（简短）",
  "description": "详细描述",
  "priority": "P0",
  "category": "功能",
  "acceptance": ["验收标准1", "验收标准2"]
}

只输出 JSON 数组，不要输出任何其他文字。`;

// 按 ## 二级标题切片，用关键词模糊匹配筛选目标板块
function splitByHeading(text, sectionKeyword) {
  const lines = text.split(/\r?\n/);
  const sections = [];
  let currentTitle = null;
  let currentLines = [];

  for (const line of lines) {
    if (/^##\s+/.test(line) && !/^###\s+/.test(line)) {
      if (currentTitle !== null) {
        sections.push({ title: currentTitle, text: currentLines.join("\n") });
      }
      currentTitle = line.replace(/^##\s+/, "").trim();
      currentLines = [];
    } else {
      currentLines.push(line);
    }
  }
  if (currentTitle !== null) {
    sections.push({ title: currentTitle, text: currentLines.join("\n") });
  }

  // 关键词模糊匹配
  if (!sectionKeyword) return sections;
  const kw = sectionKeyword.toLowerCase();
  return sections.filter((s) => s.title.toLowerCase().includes(kw));
}

// 按 ### 三级标题分块（没有 ### 则整块返回）
function splitBySubHeading(section, maxChars = 25000) {
  const lines = section.text.split(/\r?\n/);
  const subs = [];
  let currentSub = null;
  let currentLines = [];

  for (const line of lines) {
    if (/^###\s+/.test(line)) {
      if (currentSub !== null) {
        const text = currentLines.join("\n").trim();
        if (text) subs.push({ subTitle: currentSub, text });
      }
      currentSub = line.replace(/^###\s+/, "").trim();
      currentLines = [];
    } else {
      currentLines.push(line);
    }
  }
  if (currentSub !== null) {
    const text = currentLines.join("\n").trim();
    if (text) subs.push({ subTitle: currentSub, text });
  } else if (currentLines.length) {
    // 没有 ### 子标题
    const text = currentLines.join("\n").trim();
    if (text) subs.push({ subTitle: section.title, text });
  }

  // 超过 maxChars 按行硬切
  const result = [];
  for (const sub of subs) {
    if (sub.text.length <= maxChars) {
      result.push(sub);
      continue;
    }
    const allLines = sub.text.split(/\r?\n/);
    const parts = Math.ceil(sub.text.length / maxChars);
    const linesPerPart = Math.ceil(allLines.length / parts);
    for (let i = 0; i < parts; i++) {
      const chunk = allLines.slice(i * linesPerPart, (i + 1) * linesPerPart).join("\n");
      result.push({
        subTitle: `${sub.subTitle} (part ${i + 1}/${parts})`,
        text: chunk,
      });
    }
  }
  return result;
}

// 交互式或关键词选择子板块
async function pickSubSections(sections, mode, keywords) {
  // 把每个 section 按 ### 拆成子块
  const allSubs = [];
  for (const sec of sections) {
    const subs = splitBySubHeading(sec);
    for (const sub of subs) {
      allSubs.push({ parent: sec.title, ...sub });
    }
  }

  if (allSubs.length === 0) return sections;

  if (mode === "keyword") {
    const kws = keywords.split(",").map((k) => k.trim().toLowerCase()).filter(Boolean);
    const filtered = allSubs.filter((sub) =>
      kws.some((k) => sub.subTitle.toLowerCase().includes(k))
    );
    return mergeSubsToSections(filtered);
  }

  if (mode === "pick") {
    // 列出所有子板块
    console.log(`\n找到 ${allSubs.length} 个子板块：`);
    allSubs.forEach((sub, i) => {
      console.log(`  [${i + 1}] (${sub.parent}) ${sub.subTitle}  (${sub.text.length} 字)`);
    });
    console.log(`\n输入序号选择：1,3,5 多选 | 1-3 范围 | 回车全选 | q 取消`);

    const answer = await askQuestion("请输入: ");
    if (answer.trim().toLowerCase() === "q") {
      console.log("已取消");
      process.exit(0);
    }
    if (!answer.trim()) {
      return mergeSubsToSections(allSubs);
    }

    const selectedIdx = parseSelection(answer.trim(), allSubs.length);
    const picked = selectedIdx.map((i) => allSubs[i]);
    return mergeSubsToSections(picked);
  }

  // 默认：返回原始 sections（不按子板块过滤）
  return sections;
}

// 把选中的子块按父标题分组，合并回 section 结构
function mergeSubsToSections(subs) {
  const map = new Map();
  for (const sub of subs) {
    if (!map.has(sub.parent)) map.set(sub.parent, []);
    map.get(sub.parent).push(`### ${sub.subTitle}\n${sub.text}`);
  }
  return [...map.entries()].map(([title, parts]) => ({
    title,
    text: parts.join("\n\n"),
  }));
}

// 解析用户输入的序号
function parseSelection(input, max) {
  const result = new Set();
  for (const part of input.split(",")) {
    const trimmed = part.trim();
    const range = trimmed.match(/^(\d+)-(\d+)$/);
    if (range) {
      const start = parseInt(range[1], 10);
      const end = parseInt(range[2], 10);
      for (let i = start; i <= end; i++) {
        if (i >= 1 && i <= max) result.add(i - 1);
      }
    } else {
      const n = parseInt(trimmed, 10);
      if (n >= 1 && n <= max) result.add(n - 1);
    }
  }
  return [...result].sort((a, b) => a - b);
}

// 从 stdin 读取一行
function askQuestion(prompt) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    rl.question(prompt, (answer) => {
      rl.close();
      resolve(answer);
    });
  });
}

// 让 AI 拆解一个文本块，返回需求条目数组
async function parseChunkWithAI(chunkText, chunkLabel) {
  const userMsg = `请拆解以下需求文档片段（${chunkLabel}）为结构化需求条目：\n\n${chunkText}`;
  const raw = await callLLM(PARSE_SYSTEM_PROMPT, userMsg);
  // 提取 JSON
  const jsonStr = extractJsonArray(raw);
  if (!jsonStr) return [];
  try {
    return JSON.parse(jsonStr);
  } catch (e) {
    console.error(`  ⚠️ 解析 JSON 失败：${e.message}`);
    return [];
  }
}

// 从可能带 markdown 的文本中提取 JSON 数组
function extractJsonArray(text) {
  // 先找 ```json ... ``` 或 ``` ... ```
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) return fence[1].trim();
  // 直接找 [ ... ]
  const start = text.indexOf("[");
  const end = text.lastIndexOf("]");
  if (start !== -1 && end !== -1 && end > start) {
    return text.slice(start, end + 1).trim();
  }
  return null;
}

// parse 主流程
async function cmdParse(args) {
  const docPath = args[0];
  if (!docPath) {
    console.error("❌ 请指定需求文档路径");
    process.exit(2);
  }
  if (!fs.existsSync(docPath)) {
    console.error(`❌ 文件不存在：${docPath}`);
    process.exit(2);
  }

  // 解析参数
  const sectionKeyword = getArg(args, "--section");
  const moduleFlag = getArg(args, "--module") || path.basename(docPath, path.extname(docPath));
  const subSection = getArg(args, "--sub-section");
  const pickMode = args.includes("--pick");

  currentStage = "parse";

  const text = fs.readFileSync(docPath, "utf-8");
  console.log(`📄 读取需求文档：${docPath} (${text.length} 字)`);

  // Step 1: 按 ## 切片筛选
  let sections = splitByHeading(text, sectionKeyword);
  if (sections.length === 0) {
    console.error(`❌ 未找到匹配 "${sectionKeyword}" 的二级标题（##）`);
    process.exit(2);
  }
  console.log(`✅ 匹配到 ${sections.length} 个板块：`);
  sections.forEach((s) => console.log(`   - ${s.title} (${s.text.length} 字)`));

  // Step 1.5: 子板块选择
  if (pickMode || subSection) {
    const mode = pickMode ? "pick" : "keyword";
    sections = await pickSubSections(sections, mode, subSection || "");
    console.log(`\n✅ 子板块筛选后：${sections.length} 个板块`);
    sections.forEach((s) => console.log(`   - ${s.title} (${s.text.length} 字)`));
  }

  // Step 2: 分块拆解
  const MAX_CHARS = 25000;
  const allRequirements = [];

  for (const sec of sections) {
    // 再按 ### 分块
    const subs = splitBySubHeading(sec, MAX_CHARS);
    for (const sub of subs) {
      const label = `${sec.title} > ${sub.subTitle}`;
      console.log(`\n🔍 拆解：${label} (${sub.text.length} 字)`);
      try {
        const items = await parseChunkWithAI(sub.text, label);
        console.log(`   → 提取 ${items.length} 条需求`);
        allRequirements.push(...items);
      } catch (e) {
        console.error(`   ⚠️ 拆解失败：${e.message}`);
      }
    }
  }

  // Step 3: 编号 + enabled 标记
  allRequirements.forEach((r, i) => {
    r.id = `REQ-${String(i + 1).padStart(3, "0")}`;
    if (r.enabled === undefined) r.enabled = true;
  });

  // 保存
  const outFile = `requirements-${moduleFlag}.json`;
  fs.writeFileSync(outFile, JSON.stringify({
    module: moduleFlag,
    source: docPath,
    section: sectionKeyword || "",
    total: allRequirements.length,
    requirements: allRequirements,
  }, null, 2), "utf-8");

  console.log(`\n${"═".repeat(60)}`);
  console.log(`  ✅ 共提取 ${allRequirements.length} 条需求，保存到 ${outFile}`);
  console.log(`${"═".repeat(60)}`);

  // 打印优先级分布
  const priCount = {};
  allRequirements.forEach((r) => { priCount[r.priority] = (priCount[r.priority] || 0) + 1; });
  console.log("  优先级分布：");
  for (const p of ["P0", "P1", "P2"]) {
    if (priCount[p]) console.log(`    ${p}: ${priCount[p]} 条`);
  }

  printTokenReport();
}

// ==================== scan 命令 ====================

const SCAN_SYSTEM_PROMPT = `你是资深代码审查专家和需求覆盖度分析师。
用户会给你**一批需求**（每条含验收标准）和一段代码改动（git diff 或代码片段）。
请一次性判断这段代码对每条需求的覆盖情况。

输出 JSON 数组，每个元素对应一条需求（按输入顺序）：
[
  {
    "id": "REQ-001",
    "covered": true,
    "confidence": "high",
    "evidence": "代码中 xxx 实现了该需求",
    "gap": ""
  },
  ...
]

字段说明：
- id: 需求 ID（与输入一致）
- covered: true=已覆盖 / false=未覆盖 / partial=部分覆盖
- confidence: high / medium / low
- evidence: 代码中的证据（代码片段或文件名）。如果代码改动中完全没有涉及该需求对应模块的改动，填 "本次代码变动未涉及"
- gap: 未覆盖的部分（如果 fully covered 则为空字符串）

判断规则：
1. 优先从 diff/代码片段中找实现证据
2. 如果代码改动为空，或改动完全不涉及该需求相关的模块/文件，covered=false，gap="本次代码变动未实现该需求"
3. 不要凭空猜测代码库其他地方可能实现了，只根据本次提供的代码改动来判断

只输出 JSON 数组，不要输出其他文字。`;

// 获取代码改动
function getCodeChanges(args) {
  const base = getArg(args, "--base");
  const diffFile = getArg(args, "--diff");
  const dir = getArg(args, "--dir");

  if (base) {
    const repo = getArg(args, "--repo");
    const cwdOpt = repo ? { cwd: repo } : {};
    // 用 git diff <base> 而非 <base>...HEAD：
    //   - 包含工作区未提交的改动（开发者最关心的）
    //   - 工作区干净时，等同于 <base>...HEAD（看 commit 历史）
    try {
      console.log(`📋 获取代码改动：git diff ${base}${repo ? `（仓库：${repo}）` : ""}`);
      return execSync(`git diff ${base}`, { encoding: "utf-8", maxBuffer: 50 * 1024 * 1024, ...cwdOpt });
    } catch (e) {
      console.error(`❌ git diff 失败：${e.message}`);
      process.exit(2);
    }
  }

  if (diffFile) {
    if (!fs.existsSync(diffFile)) {
      console.error(`❌ diff 文件不存在：${diffFile}`);
      process.exit(2);
    }
    console.log(`📋 读取 diff 文件：${diffFile}`);
    return fs.readFileSync(diffFile, "utf-8");
  }

  if (dir) {
    // 读目录下所有代码文件，拼成代码片段
    const files = collectCodeFiles(dir);
    if (files.length === 0) {
      console.error(`❌ 目录下未找到代码文件：${dir}`);
      process.exit(2);
    }
    console.log(`📋 读取目录：${dir}（${files.length} 个文件）`);
    const parts = [];
    for (const f of files) {
      parts.push(`// === 文件：${path.relative(dir, f)} ===\n${fs.readFileSync(f, "utf-8")}`);
    }
    return parts.join("\n\n");
  }

  console.error("❌ 请指定代码来源：--base <分支> / --diff <文件> / --dir <目录>");
  process.exit(2);
}

// 需求过滤
function filterRequirements(reqs, args) {
  let filtered = reqs.slice();

  // --only-enabled
  if (args.includes("--only-enabled")) {
    const before = filtered.length;
    filtered = filtered.filter((r) => r.enabled !== false);
    console.log(`  --only-enabled：过滤掉 ${before - filtered.length} 条 disabled 需求`);
  }

  // --priority P0,P1
  const priority = getArg(args, "--priority");
  if (priority) {
    const set = new Set(priority.split(",").map((s) => s.trim().toUpperCase()));
    const before = filtered.length;
    filtered = filtered.filter((r) => set.has((r.priority || "").toUpperCase()));
    console.log(`  --priority ${priority}：过滤掉 ${before - filtered.length} 条`);
  }

  // --include-ids
  const includeIds = getArg(args, "--include-ids");
  if (includeIds) {
    const set = new Set(includeIds.split(",").map((s) => s.trim()));
    const before = filtered.length;
    filtered = filtered.filter((r) => set.has(r.id));
    console.log(`  --include-ids ${includeIds}：过滤掉 ${before - filtered.length} 条`);
  }

  // --exclude-ids
  const excludeIds = getArg(args, "--exclude-ids");
  if (excludeIds) {
    const set = new Set(excludeIds.split(",").map((s) => s.trim()));
    const before = filtered.length;
    const removed = filtered.filter((r) => set.has(r.id));
    filtered = filtered.filter((r) => !set.has(r.id));
    console.log(`  --exclude-ids ${excludeIds}：过滤掉 ${removed.length} 条`);
    if (removed.length > 0) {
      console.log(`    被排除的需求（最多显示10条）：`);
      removed.slice(0, 10).forEach((r) => console.log(`      - ${r.id} ${r.title}`));
    }
  }

  return filtered;
}

// scan 主流程
async function cmdScan(args) {
  const reqFile = getArg(args, "--req");
  if (!reqFile) {
    console.error("❌ 请指定 --req <requirements.json>");
    process.exit(2);
  }
  if (!fs.existsSync(reqFile)) {
    console.error(`❌ 文件不存在：${reqFile}`);
    process.exit(2);
  }

  currentStage = "scan";

  const reqData = JSON.parse(fs.readFileSync(reqFile, "utf-8"));
  let requirements = reqData.requirements || [];
  console.log(`📄 读取需求文件：${reqFile}（共 ${requirements.length} 条）`);

  // 需求过滤
  console.log(`\n🔍 需求过滤...`);
  requirements = filterRequirements(requirements, args);
  console.log(`✅ 实际扫描 ${requirements.length} 条需求\n`);

  if (requirements.length === 0) {
    console.log("没有需要扫描的需求，退出。");
    return;
  }

  // 获取代码改动（允许为空）
  const codeChanges = getCodeChanges(args);
  if (!codeChanges.trim()) {
    console.log(`⚠️ 本次代码无变动（git diff 为空）`);
    console.log(`📋 所有需求将被判定为"本次代码变动未实现该需求"\n`);
  } else {
    console.log(`📋 代码改动：${codeChanges.length} 字符\n`);
  }

  // 分批调用 AI（批量判定，非逐条）
  const BATCH_SIZE = 15; // 每批最多 15 条需求，避免 prompt 过长
  const MAX_CODE_CHARS = 50000;
  const batches = [];
  for (let i = 0; i < requirements.length; i += BATCH_SIZE) {
    batches.push(requirements.slice(i, i + BATCH_SIZE));
  }
  console.log(`📦 分 ${batches.length} 批扫描（每批最多 ${BATCH_SIZE} 条，并行调用）\n`);

  // 构造一批的 prompt
  function buildBatchUserMsg(batchReqs) {
    const reqsText = batchReqs.map((req) => `---
需求 ID：${req.id}
标题：${req.title}
描述：${req.description}
优先级：${req.priority}
分类：${req.category}
验收标准：
${(req.acceptance || []).map((a, i) => `${i + 1}. ${a}`).join("\n")}`).join("\n\n");

    const codeSection = codeChanges.trim()
      ? `\n\n=== 代码改动 ===\n${codeChanges.slice(0, MAX_CODE_CHARS)}`
      : "\n\n=== 代码改动 ===\n（本次无代码变动）";

    return `请判断以下代码改动对每条需求的覆盖情况（共 ${batchReqs.length} 条）：\n\n${reqsText}${codeSection}`;
  }

  // 解析批量返回
  function parseBatchResult(raw, batchReqs) {
    const jsonStr = extractJsonArray(raw);
    if (!jsonStr) {
      // 解析失败，全部标为 unknown
      return batchReqs.map((req) => ({
        ...req,
        verdict: { covered: "unknown", confidence: "low", evidence: "", gap: "AI 返回解析失败" },
      }));
    }
    let arr;
    try {
      arr = JSON.parse(jsonStr);
    } catch (e) {
      return batchReqs.map((req) => ({
        ...req,
        verdict: { covered: "unknown", confidence: "low", evidence: "", gap: `JSON 解析失败: ${e.message}` },
      }));
    }
    // 按 id 对齐
    const verdictMap = {};
    if (Array.isArray(arr)) {
      for (const item of arr) {
        if (item && item.id) verdictMap[item.id] = item;
      }
    }
    return batchReqs.map((req) => {
      const v = verdictMap[req.id];
      if (!v) {
        return { ...req, verdict: { covered: "unknown", confidence: "low", evidence: "", gap: "AI 未返回该条结果" } };
      }
      return { ...req, verdict: v };
    });
  }

  // 并行调用所有批次
  const batchPromises = batches.map((batch, idx) => {
    const batchIds = batch.map((r) => r.id).join(",");
    console.log(`🔍 批次 ${idx + 1}/${batches.length}（${batch.length} 条：${batchIds}）调用中...`);
    return callLLM(SCAN_SYSTEM_PROMPT, buildBatchUserMsg(batch))
      .then((raw) => {
        const batchResults = parseBatchResult(raw, batch);
        console.log(`✅ 批次 ${idx + 1} 完成`);
        return batchResults;
      })
      .catch((e) => {
        console.error(`⚠️ 批次 ${idx + 1} 失败：${e.message}`);
        return batch.map((req) => ({
          ...req,
          verdict: { covered: "unknown", confidence: "low", evidence: "", gap: e.message },
        }));
      });
  });

  const batchResults = await Promise.all(batchPromises);
  const results = batchResults.flat();

  // 逐条打印结果
  console.log();
  for (const r of results) {
    const v = r.verdict;
    const icon = v.covered === true ? "✅" : v.covered === "partial" ? "🟡" : v.covered === false ? "❌" : "❓";
    console.log(`  ${icon} ${r.id} ${r.title}  (${v.confidence})`);
  }

  // 生成报告
  const covered = results.filter((r) => r.verdict.covered === true).length;
  const partial = results.filter((r) => r.verdict.covered === "partial").length;
  const notCovered = results.filter((r) => r.verdict.covered === false).length;
  const unknown = results.filter((r) => r.verdict.covered === "unknown").length;
  const coverage = ((covered + partial * 0.5) / results.length * 100).toFixed(1);

  // Markdown 报告
  const md = [
    `# 需求覆盖度报告`,
    ``,
    `- 总需求数：${results.length}`,
    `- 已覆盖：${covered}`,
    `- 部分覆盖：${partial}`,
    `- 未覆盖：${notCovered}`,
    `- 未知：${unknown}`,
    `- 覆盖率：${coverage}%`,
    ``,
    `## 详细结果`,
    ``,
    `| ID | 标题 | 优先级 | 覆盖 | 置信度 | 说明 |`,
    `|----|------|--------|------|--------|------|`,
  ];
  for (const r of results) {
    const v = r.verdict;
    const icon = v.covered === true ? "✅" : v.covered === "partial" ? "🟡" : v.covered === false ? "❌" : "❓";
    const note = (v.gap || v.evidence || "").replace(/\|/g, "\\|").replace(/\n/g, " ");
    md.push(`| ${r.id} | ${r.title} | ${r.priority} | ${icon} ${v.covered} | ${v.confidence} | ${note} |`);
  }

  const reportFile = `coverage-report.md`;
  fs.writeFileSync(reportFile, md.join("\n"), "utf-8");

  // JSON 报告
  const jsonFile = `coverage-report.json`;
  fs.writeFileSync(jsonFile, JSON.stringify({
    total: results.length,
    covered,
    partial,
    notCovered,
    unknown,
    coverage: parseFloat(coverage),
    results,
  }, null, 2), "utf-8");

  console.log(`\n${"═".repeat(60)}`);
  console.log(`  📊 覆盖度报告`);
  console.log(`${"═".repeat(60)}`);
  console.log(`  总需求：${results.length}`);
  console.log(`  已覆盖：${covered}  部分覆盖：${partial}  未覆盖：${notCovered}  未知：${unknown}`);
  console.log(`  覆盖率：${coverage}%`);
  console.log(`\n  报告已保存：${reportFile} / ${jsonFile}`);
  console.log(`${"═".repeat(60)}`);

  printTokenReport();
}

// 从文本中提取 JSON 对象
function extractJsonObject(text) {
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) return fence[1].trim();
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start !== -1 && end !== -1 && end > start) {
    return text.slice(start, end + 1).trim();
  }
  return null;
}

// ==================== run 命令 ====================

async function cmdRun(args) {
  const docPath = args[0];
  if (!docPath) {
    console.error("❌ 请指定需求文档路径");
    process.exit(2);
  }

  const sectionKeyword = getArg(args, "--section");
  const moduleFlag = getArg(args, "--module") || path.basename(docPath, path.extname(docPath));

  // Step 1: parse
  console.log(`${"═".repeat(60)}`);
  console.log("  Step 1/2：需求拆解（parse）");
  console.log(`${"═".repeat(60)}`);
  await cmdParse(args);

  // Step 2: scan
  const reqFile = `requirements-${moduleFlag}.json`;
  console.log(`\n${"═".repeat(60)}`);
  console.log("  Step 2/2：覆盖扫描（scan）");
  console.log(`${"═".repeat(60)}`);
  const scanArgs = ["--req", reqFile, ...args.slice(1)];
  await cmdScan(scanArgs);
}

// ==================== 工具：从 args 取值 ====================

function getArg(args, name) {
  const idx = args.indexOf(name);
  if (idx === -1) return undefined;
  return args[idx + 1];
}

// ==================== 帮助 ====================

function printHelp() {
  console.log(`
AI 需求扫描工具 —— 拆解需求文档，扫描代码覆盖度

命令：
  parse  从需求文档拆解结构化需求条目
  scan   扫描代码改动是否覆盖需求
  run    顺序执行 parse + scan

【parse 命令】
  node ai-req.js parse <需求文档.md> --section "板块关键词" [选项]

  选项：
    --section "关键词"      按 ## 二级标题模糊匹配板块（必填）
    --module <模块名>        输出文件名后缀（默认取文档名）
    --pick                  交互式选择 ### 子板块
    --sub-section "关键词"   按关键词过滤 ### 子板块（非交互）

  示例：
    node ai-req.js parse req.md --section "LargeCNV" --module LargeCNV
    node ai-req.js parse req.md --section "LargeCNV" --pick
    node ai-req.js parse req.md --section "用户" --sub-section "登录,注册"

【scan 命令】（批量并行扫描，自动分批）
  node ai-req.js scan --req <requirements.json> [选项]

  选项（代码来源三选一，推荐 --base）：
    --base <分支>            git diff <base>...HEAD（推荐，MR 场景自动识别改动）
    --repo <仓库路径>        指定 git 仓库目录（--base 时使用，默认当前目录）
    --diff <diff文件>        从文件读取 diff
    --dir <目录>             读取目录下所有代码文件（全量，不推荐日常用）

  选项（需求过滤，可组合）：
    --only-enabled          只扫 enabled !== false 的需求（推荐）
    --priority P0,P1        只扫指定优先级
    --include-ids REQ-001   白名单
    --exclude-ids REQ-002   黑名单

  说明：
    - 代码无变动时（git diff 为空），所有需求判为"本次代码变动未实现该需求"
    - 需求 >15 条时自动分批并行调用（速度从 N×8s 降至 ceil(N/15)×8s）

  示例：
    node ai-req.js scan --req requirements-WorkMgmt.json --base develop --only-enabled
    node ai-req.js scan --req req.json --dir src/largecnv/ --priority P0

【run 命令】
  node ai-req.js run <需求文档.md> --section "LargeCNV" --module LargeCNV --base develop
`);
}

// ==================== main ====================

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 0 || args[0] === "-h" || args[0] === "--help") {
    printHelp();
    process.exit(0);
  }

  if (!API_KEY) {
    console.error("❌ 未配置 AI_API_KEY，请在 .env 中设置。");
    process.exit(2);
  }

  const command = args[0];
  const rest = args.slice(1);

  try {
    switch (command) {
      case "parse":
        await cmdParse(rest);
        break;
      case "scan":
        await cmdScan(rest);
        break;
      case "run":
        await cmdRun(rest);
        break;
      default:
        console.error(`❌ 未知命令：${command}`);
        printHelp();
        process.exit(2);
    }
  } catch (e) {
    console.error(`\n💥 运行出错：${e.message}`);
    process.exit(2);
  }
}

main();
