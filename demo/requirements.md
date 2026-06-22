# 案例展示网站需求文档（V1.0）

> 专业小程序 & 网站开发案例展示平台。
> 工具按 `##` 二级标题切板块，按 `###` 三级标题切子板块。
> 适用范围：C 端访客浏览 + B 端管理员后台维护，前后端分离架构。

---

## 项目架构

### 技术栈选型

后端采用 Spring Boot 3.2 + MyBatis-Plus + H2 文件数据库 + JWT 鉴权；
前端采用 Vue3 + Vite + Element Plus + Vue Router + Axios；
前后端通过 RESTful API 通信，开发期前端 Vite 代理 API 到后端 8081 端口。

### 工程结构约定

后端按 `controller / service / mapper / entity / dto / config / interceptor / util` 分层分包；
统一包名 `com.demo.showcase`，启动类 `ShowcaseApplication`；
前端按 `views / api / router / assets` 组织，API 调用统一封装在 `src/api/index.js`。

### 运行与配置

后端启动后监听 8081 端口，首次启动自动执行 `schema.sql` 初始化表结构并插入示例数据；
前端开发服务监听 5173 端口；数据库文件持久化到 `./data/showcase`，上传文件持久化到 `./uploads`。

---

## 管理员鉴权

### 管理员账号初始化

系统启动时通过 `schema.sql` 预置一个默认管理员账户（用户名 `admin`，密码 `admin123`）；
管理员表 `admin` 至少包含 id、username、password、create_time 字段，用户名需唯一。

### 管理员登录

提供 `POST /api/admin/login` 接口，请求体为 `{username, password}`；
后端根据用户名 + 密码明文比对查询 admin 表，匹配成功则生成 JWT Token 返回，失败返回错误提示；
登录响应体结构为 `{token, username}`，前端登录成功后保存 Token 用于后续请求。

### JWT Token 签发

使用 `io.jsonwebtoken`（jjwt）库签发 Token；
Token 中 subject 存放用户名，自定义 claim `userId` 存放管理员 ID；
签发时间 `issuedAt` 为当前时间，过期时间 `expiration` 由配置 `jwt.expiration` 控制（默认 24 小时）；
签名密钥从配置 `jwt.secret` 读取，使用 HMAC-SHA 算法。

### JWT 拦截与校验

对所有 `/api/admin/**` 路径（除 `/api/admin/login` 外）注册 `JwtInterceptor` 拦截器；
拦截器从请求头 `Authorization: Bearer <token>` 提取 Token，调用 JwtUtil 校验签名与有效期；
OPTIONS 预检请求直接放行，校验失败的请求返回 HTTP 401 与 JSON 错误体 `{code:401, message:"未登录或Token已过期"}`。

---

## 作品（Work）数据模型

### 作品表结构

作品表 `work` 至少包含以下字段：id（主键自增）、title（作品名称）、category（分类）、description（详细描述）、tech_stack（技术栈）、price（报价）、demo_url（在线体验地址）、cover_url（封面图 URL）、images（截图 URL，逗号分隔）、status（0 下架/1 上架）、sort（排序权重，越大越靠前）、create_time、update_time。

### 管理员表结构

管理员表 `admin` 至少包含 id、username（唯一）、password、create_time。

### 字段自动填充

create_time 在插入时自动填充，update_time 在插入和更新时自动填充（通过 MyBatis-Plus 的 `@TableField(fill = FieldFill.INSERT / INSERT_UPDATE)` 实现）。

### 实体类映射

`Work` 与 `Admin` 实体类使用 `@TableName`、`@TableId(type = IdType.AUTO)` 注解；
字段采用驼峰命名，MyBatis-Plus 全局开启 `map-underscore-to-camel-case`，自动映射数据库下划线字段。

---

## C 端案例展示接口

### 案例列表分页查询

提供 `GET /api/works` 接口，支持 `category`、`keyword`、`page`（默认 1）、`size`（默认 10）四个查询参数；
仅返回 `status=1`（已上架）的作品；
结果按 sort 降序、create_time 降序排列；
返回结构为分页对象 `{records, total, page, size}`。

### 关键词搜索

当 `keyword` 非空时，在 title、description、tech_stack 三个字段上执行 OR 模糊查询（LIKE）。

### 分类筛选

当 `category` 非空时，按 category 精确匹配过滤。

### 分类列表聚合

提供 `GET /api/categories` 接口，返回当前所有已上架作品中出现过的分类去重列表；
实现方式：对已上架作品按 category 字段分组，前端可用于渲染分类导航。

### 案例详情查询

提供 `GET /api/works/{id}` 接口，根据作品 ID 返回完整作品信息；
作品不存在时返回业务错误信息 "案例不存在"，HTTP 状态仍为 200，通过响应体 code 区分成功失败。

### 统一响应格式

所有接口返回统一封装对象 `Result<T>`，结构为 `{code, message, data}`；
成功时 code=200，失败时 code 为业务错误码，data 为业务数据或 null。

---

## B 端作品管理接口

### 管理端作品列表

提供 `GET /api/admin/works` 接口（需 JWT），返回所有作品（含已下架），按 sort 与 create_time 降序排列；
与 C 端列表区别：不按 status 过滤、不分页、需登录鉴权。

### 新增作品

提供 `POST /api/admin/works` 接口（需 JWT），请求体为 Work 对象；
新增时若 status 为空则默认 1（上架），若 sort 为空则默认 0；
插入成功返回统一成功响应。

### 编辑作品

提供 `PUT /api/admin/works/{id}` 接口（需 JWT），URL 中的 id 强制覆盖请求体 id，调用 MyBatis-Plus `updateById` 完成更新；
作品新增/编辑均需支持 **备注（remark）** 字段：类型为字符串，最大长度 200，允许为空；
备注用于管理员在后台对作品补充运营信息（如"618 活动"、"客户加急"等）；
前端表单中提供文本输入框，后端入库/更新时随其他字段一并写入 work 表的 remark 列。

### 删除作品

提供 `DELETE /api/admin/works/{id}` 接口（需 JWT），物理删除作品记录，调用 MyBatis-Plus `deleteById`。

### 上下架状态切换

提供 `PUT /api/admin/works/{id}/toggle` 接口（需 JWT）；
逻辑：先按 id 查出作品，将 status 在 0/1 之间翻转，再 `updateById` 写回；
前端常用于"上架/下架"按钮无刷新切换。

### 批量删除作品

提供 `POST /api/admin/works/batch-delete` 接口（需 JWT），请求体为 id 数组（如 `{ "ids": [1, 2, 3] }`）；
逻辑：调用 MyBatis-Plus `removeByIds` 批量物理删除，全部删除成功后返回统一成功响应；
若 id 数组为空，返回参数错误提示；
用于管理后台勾选多条作品后一次性删除，提升运营效率。

---

## 文件上传

### 图片上传接口

提供 `POST /api/upload/image` 接口，接收 `multipart/form-data`，文件参数名为 `file`；
空文件返回错误提示 "请选择文件"。

### 文件存储

上传目录由配置 `upload.dir`（默认 `./uploads`）控制，不存在时自动创建；
保存时使用 UUID（去除横线）+ 原扩展名生成唯一文件名，避免冲突；
返回访问路径 `/uploads/<fileName>`。

### 静态资源映射

通过 `WebMvcConfig.addResourceHandlers` 将 `/uploads/**` 映射到本地 `upload.dir` 目录；
前端通过该路径直接访问已上传的图片。

### 上传大小限制

通过 `spring.servlet.multipart` 配置 `max-file-size` 与 `max-request-size`（默认 10MB），超限请求会被拒绝。

---

## 前端 C 端页面

### 首页

展示平台定位、核心数据（作品数、分类数等）、精选案例入口，提供到作品列表与联系页的导航。

### 作品列表页

调用 `/api/works` 分页加载，支持分类筛选与关键词搜索；
渲染卡片网格：封面图、标题、分类标签、技术栈摘要、报价；
支持分页器与分类标签栏联动。

### 作品详情页

路由 `/works/:id`，调用 `/api/works/{id}` 获取详情；
展示封面大图、多图轮播（images 字段拆分）、完整描述、技术栈、报价、在线体验入口（demo_url）。

### 联系我们页

展示联系方式、微信/电话等，提供咨询表单入口（前端静态展示）。

---

## 前端 B 端管理后台

### 管理员登录页

路由 `/admin`，提供用户名/密码表单，调用 `/api/admin/login`；
登录成功后 Token 存入 localStorage，跳转到 Dashboard。

### 管理仪表盘

调用 `/api/admin/works` 加载作品列表，以表格展示；
支持新增、编辑、删除、上下架切换操作；
表格列：封面、标题、分类、状态、排序、操作按钮。

### 作品新增/编辑表单

表单字段对齐 Work 实体，支持封面图上传（调用 `/api/upload/image`，上传后回填 cover_url）；
支持多图上传回填 images（逗号拼接）；
分类字段提供下拉选择（小程序/网站/工具）。

### 请求鉴权与拦截

前端通过 Axios 拦截器在所有 `/api/admin/**` 请求头自动附加 `Authorization: Bearer <token>`；
后端返回 401 时前端清空 Token 并跳转回登录页。

---

## 跨域与网络配置

### CORS 全局放行

通过 `CorsConfig` 配置全局跨域，允许前端开发域（默认 `http://localhost:5173`）访问；
允许所有请求方法与请求头，允许携带凭证。

### Vite 开发代理

前端 `vite.config.js` 中将 `/api` 与 `/uploads` 代理到 `http://localhost:8081`，避免开发期跨域与端口暴露。

### 静态资源访问策略

上传文件通过后端 `/uploads/**` 静态资源映射提供访问；
C 端作品图片通过 cover_url、images 字段存储相对路径，前端拼接 baseUrl 渲染。
