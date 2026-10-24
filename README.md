# 旅迹 AI · 智能旅行规划平台（Java 版）

**说出想去哪儿，它去找、去算、去排；每一处改动，都由你点头才生效。**

> 把原 Python（FastAPI + LangGraph + ChromaDB）后端完整改写为 **Java 17 + Spring Boot 3**，
> 前端 70 个文件一行未改。行为以"字节级契约 + 数值可复现"为准。
>
> Python 原版仓库：<https://github.com/Alexia-Hxz/travel-agent-platform>

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)
![Milvus](https://img.shields.io/badge/Milvus-2.4-00A1EA)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)

## 这是什么

一个本地部署的 AI 旅行规划平台：给出目的地、日期、同行人和预算，它会联网检索真实素材，
抽取成结构化推荐（含图片、价格、营业时间、地址），做预算与时间冲突校验，最后合成一份可编辑的
逐日行程。所有模型产出的改动都以**草案**形式给出，用户逐条勾选或修改后才写入行程。

## 功能

- **AI 发现**：自然语言提问 → 联网检索 → 候选卡片（图片/价格/营业时间/地址/理由）。
- **整趟规划**：`POST /trips/{id}/plan-jobs` 发起，`GET .../{jobId}` 轮询阶段与分类批次。
- **单日规划 / 对话式调整**：一句话改行程，先生成草案预览，确认后生效（两阶段写入）。
- **收藏与逐日编排**：景点/酒店/餐厅/机场/车站；自动排期会避开已有行程并尊重营业时间。
- **资料向量检索**：上传 PDF/图片 → 解析 → 按行切分 → text-embedding-v4 → 按 trip 过滤检索，
  命中片段带来源文件名与相似度。
- **多模态**：上传票据 → 视觉模型抽取 → 预览 → 用户确认后入库。
- **多源聚合**：Pexels 配图（含可达性探测与占位图降级）、Open-Meteo 天气与逐日建议、Markdown 导出（前端）。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| Web | Spring Boot 3.4（内嵌 Tomcat）、Spring MVC |
| 持久层 | MyBatis-Plus 3.5 |
| 数据库 / 迁移 | MySQL 8 + Flyway |
| 认证授权 | Spring Security（过滤器链）+ 手写 token 格式（兼容老 token） |
| 密码哈希 | Bouncy Castle `SCrypt`（`scrypt$n$r$p$salt$hash`） |
| 模型调用 | 自建 `WebClient` 客户端（保证 `enable_search` 等私有参数透传） |
| Embedding | Spring AI `EmbeddingModel`（DashScope 兼容模式） |
| 向量库 | Milvus 2.4（COSINE + HNSW）；无 Milvus 时可用 MySQL 精确余弦 |
| PDF / 图片 | Apache PDFBox / `qwen3-vl-plus` 视觉模型 |
| 流水线 | Service 顺序调用 + 共享状态对象（原图是纯线性 DAG，无需图引擎） |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus（**未改动**） |

## 快速开始

### 1. 本地（推荐开发）

前置：JDK 17+、Maven、MySQL 8、Node.js 20+。

```powershell
# 建库（只需一次）
mysql -u root -p -e "CREATE DATABASE travel_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

cd backend
Copy-Item .env.example .env      # 填 DASHSCOPE_API_KEY / DB_PASSWORD（PEXELS_API_KEY 可选）
mvn -DskipTests package
.\run.ps1                        # 读 .env → 起在 8001
```

接口：<http://127.0.0.1:8001/api/v1/health>

### 1b. Windows 一键启动 / 停止

| 文件 | 作用 |
|---|---|
| 桌面 `启动旅迹AI(JAVA).bat` | 开两个窗口（后端 8001、前端 5173），等就绪后自动打开浏览器 |
| 桌面 `停止旅迹AI(JAVA).bat` | 停掉 8001 / 5173 及残留的 Java、node 进程 |
| `start-all.bat` / `stop-all.bat` | 同上，项目目录内的版本 |
| `start-backend.bat` | 单独起后端：自动找 JDK 17+、读 `.env`、jar 缺失或源码更新时自动 `mvn package` |
| `start-frontend.bat` | 单独起前端 |

首次启动会自动打包（约 1 分钟），之后直接起 jar；改过 Java 源码后再点启动会自动重新打包。

### 2. Docker Compose（MySQL + Milvus + 后端）

```bash
docker compose up -d --build
```

### 3. 前端

```bash
cd frontend
npm install
npm run dev        # http://127.0.0.1:5173
```

前端默认请求 `http://127.0.0.1:8001/api/v1`（见 `src/api/http.ts`），所以后端必须监听 8001。

## 环境变量

| 变量 | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 模型服务 Key（必填） |
| `PEXELS_API_KEY` | 图片服务 Key（可选；不填只有内置占位图） |
| `AUTH_SECRET_KEY` | token 签名密钥（生产必须设置；不设则用内置开发密钥） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `VECTOR_BACKEND` | `auto`（默认，先试 Milvus 失败退 MySQL）/ `milvus` / `mysql` |
| `MILVUS_HOST` / `MILVUS_PORT` | Milvus 地址 |
| `PLANNING_MODEL_NAME` | 规划/抽取模型（默认 `qwen3.7-plus`） |
| `VISION_MODEL_NAME` | 视觉模型（默认 `qwen3-vl-plus`） |
| `EMBEDDING_MODEL_NAME` | 向量化模型（默认 `text-embedding-v4`） |
| `PLANNING_JOB_WORKERS` | 后台规划并发（默认 2） |

## 接口一览（39 条）

| 分组 | 数量 | 说明 |
|---|---|---|
| 认证 | 4 | 注册 / 登录 / me / 补邮箱手机号 |
| 旅行 | 4 | 增删查 |
| 收藏地点 | 5 | 增删改查 + 一次清空 |
| 行程项 | 4 | 增删查 + 自动排期 |
| 文件 | 10 | 上传/列表/下载/删除/解析/状态/结果/视觉预览/视觉确认 |
| 规划 | 3 | `/plan`、`/plan-jobs`、`/plan-jobs/{id}` |
| 资料检索 | 3 | status / search / rebuild |
| 行程调整 | 4 | 草案 / 列表 / apply / reject |
| 图片 | 1 | `/image-proxy`（公开，307 → 占位图） |
| 系统 | 1 | `/health` |

契约要点：JSON 字段 **snake_case**、错误体 **`{"detail": ...}`**、时间 **`HH:mm:ss`**、
越权与不存在统一 **404**、`DELETE /places` 返回 `{"removed": n}`。

## 测试

```powershell
# 单元测试（预算公式、最大余数法、冲突、切分、自动排期、天气建议、token、scrypt）
mvn test

# 端到端冒烟（需要后端已启动；124 项断言）
python tools/api_smoke.py
```

`tools/api_smoke.py` 会自己注册账号、建行程、传 PDF 与图片、跑解析与检索、发起规划并轮询，
最后验证越权隔离——它同时是一份可执行的验收清单。

辅助脚本：

| 脚本 | 用途 |
|---|---|
| `tools/api_smoke.py` | 端到端冒烟：126 项断言，覆盖全部 39 条路由 |
| `tools/make_test_files.py` | 生成测试用的酒店订单 PDF 与 PNG |
| `tools/migrate_sqlite.py` | 把 Python 版 SQLite 数据原样搬进 MySQL（保留 id 与密码哈希） |
| `tools/restore_place_images.py` | 复制旧图片缓存并改写图片 URL（迁移后必跑） |
| `tools/backfill_place_images.py` | 给仍缺图的餐厅/景点补图（模型出英文检索词 → Pexels → 存本地） |
| `tools/check_image_chain.py` | 回归：确认 API 给出的图片地址真的能取到 |
| `tools/verify_old_login.py` | 验证「Python 生成的密码哈希，Java 能校验通过」 |
| `tools/gen_java_data.py` | 从 Python 版数据表生成 Java 常量（23 国价格带 / 汇率 / 170 城市） |
| `tools/probe_model.py` | 探测 DashScope 上实际可用的模型名 |

## 从 Python 版迁移数据

```powershell
# 把 SQLite 里的 6 张表整表搬进 MySQL（保留原 id、保留密码哈希）
python tools/migrate_sqlite.py

# 把旧库里的图片缓存复制过来，并把绝对地址改写成相对路径
python tools/restore_place_images.py

# 验证老账号能用原密码登录（会临时建一个账号再删掉）
python tools/verify_old_login.py
```

迁移脚本会先清空这 6 张表再灌入。密码是 `scrypt$n$r$p$salt$hash`，Java 侧用同样的算法校验，
所以**老账号不需要改密码**。上传文件与向量索引没有迁移（原库本来就没有文件）；
如果以后有文件，重新上传或用 `POST /knowledge/rebuild` 重建即可。

图片要注意：旧库 `places.image_url` 存的是 `http://127.0.0.1:8001/static/place_images/cache/<sha>.jpg`
这种指向 Python 后端的地址，而文件在 Python 项目的 `app/static/place_images/cache/` 里。
`restore_place_images.py` 会把这些文件复制到 `backend/data/photo-cache/`（Java 的 `/static/place_images/cache/**`
映射的就是这里），并把 URL 改成相对路径；剩余的公网图片会尽量镜像到本地。

## 项目结构

```
travel-agent-platform-java/
├── backend/
│   ├── src/main/java/com/huxizhen/travel/
│   │   ├── agent/          # 规划流水线、调整器、工具（预算/冲突/图片/天气/汇率…）
│   │   ├── common/         # {"detail": ...} 错误体、异常
│   │   ├── config/         # Jackson / CORS / 安全 / 向量后端选择
│   │   ├── controller/     # 9 个 Controller，共 39 条路由
│   │   ├── dto/            # record DTO（snake_case 由 Jackson 统一处理）
│   │   ├── entity/ mapper/ # 6 张业务表 + 向量片段表
│   │   ├── integration/    # DashScope / 搜索 / 天气 / 地理编码 / Milvus
│   │   ├── security/       # token、SCrypt、JWT 过滤器、行程归属拦截器
│   │   └── service/        # 业务服务（认证/行程/文件/向量/规划/调整）
│   └── src/main/resources/db/migration/   # Flyway V1 / V2
├── frontend/               # 原样保留，一行未改
├── deploy/                 # Dockerfile、Milvus compose
├── docs/                   # 改写方案、验收报告
└── tools/                  # 冒烟测试与数据生成脚本
```

## 文档

- [改写方案](docs/改写方案.md)：端点对照、表结构、工具落点、风险决策（R1–R6）
- [验收报告](docs/验收报告.md)：已验证项、已知差异、如何复现

## 说明

- `.env` 与上传的数据不纳入版本控制。
- 项目为个人项目，代码用于学习与作品展示。
