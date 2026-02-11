# AI书法批改系统 (Teacher - Calligraphy AI Grading System)

基于 Spring Boot 3 + JDK 21 的智能书法作业批改系统，利用 OpenCV 图像切分和 GPT-4o/Claude 3.5 Sonnet 视觉能力，实现对学生书法作业的自动化逐字点评。

## 系统架构

```
                    ┌──────────────┐
                    │   前端页面    │  上传照片 / 查看批改结果
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │  REST API    │  teacher-web
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
     ┌────────▼──────┐  ┌─▼──────────┐ │
     │ OpenCV 切分   │  │ AI 分析引擎 │ │
     │ teacher-image │  │ teacher-ai  │ │
     └───────────────┘  └─────┬──────┘ │
                              │        │
                    ┌─────────▼────────▼──┐
                    │   算力调度中心        │  teacher-dispatcher
                    │  Key池 + 限流 + 并发  │
                    └─────────┬───────────┘
                              │
                  ┌───────────┴───────────┐
                  │                       │
          ┌───────▼───────┐       ┌───────▼───────┐
          │  内存（默认）   │       │  Redis（可选）  │
          │  零外部依赖     │       │  分布式部署     │
          └───────────────┘       └───────────────┘
```

## 技术栈

- **JDK 21** - Virtual Threads 实现高并发调用
- **Spring Boot 3.2** - 应用框架
- **OpenCV 4.9** (JavaCPP Presets) - 图像预处理与字符切分
- **Redis**（可选） - 分布式模式下的 Key 池与限流
- **OkHttp** - AI API 调用
- **Thymeleaf** - 前端模板

## 模块说明

| 模块 | 说明 |
|------|------|
| `teacher-common` | 公共 DTO、异常体系、工具类 |
| `teacher-image` | 图像预处理引擎（矫正、二值化、切分） |
| `teacher-dispatcher` | 算力调度中心（Key池、限流、并发） |
| `teacher-ai` | AI 调用层（多 Provider 适配、Prompt 模板、Multi-Agent） |
| `teacher-web` | Web 接入层（REST API + 前端页面） |
| `teacher-app` | 启动模块（配置与集成） |

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.9+
- Redis（**仅分布式模式需要**，轻量模式无需安装）

### 1. 配置 API Keys

在 `teacher-app/src/main/resources/application.yml` 中添加：

```yaml
teacher:
  api-keys: sk-your-openai-key-1,sk-your-openai-key-2
```

或通过环境变量：

```bash
export TEACHER_API_KEYS=sk-key1,sk-key2,sk-key3
```

### 2. 构建并运行

**轻量模式（默认，无需 Redis）：**

```bash
mvn clean package -DskipTests
java -jar teacher-app/target/teacher-app-1.0.0-SNAPSHOT.jar
```

**分布式模式（需要 Redis）：**

```bash
# 先启动 Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 启动应用，激活 redis profile
java -jar teacher-app/target/teacher-app-1.0.0-SNAPSHOT.jar --spring.profiles.active=redis
```

### 3. 访问

打开浏览器访问 http://localhost:8080

## 部署模式对比

| 特性 | 轻量模式 (memory) | 分布式模式 (redis) |
|------|-------------------|-------------------|
| 外部依赖 | 无 | 需要 Redis |
| 部署复杂度 | 一个 JAR 包即可 | 需额外维护 Redis |
| 多实例 | 不支持 | Key 池共享 |
| 重启后 Key 池 | 从配置重新加载 | Redis 中持久化 |
| 适用场景 | 个人/小型工作室 | 大规模/多节点 |

## API 接口

### 上传并批改

```
POST /api/homework/analyze
Content-Type: multipart/form-data
参数: file (图片文件)
```

### 查询批改结果

```
GET /api/homework/{taskId}
```

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `teacher.dispatcher.storage-type` | memory | 存储类型 (memory/redis) |
| `teacher.ai.provider` | openai | AI 提供商 (openai/anthropic) |
| `teacher.ai.multi-agent-enabled` | false | 多 Agent 模式 |
| `teacher.dispatcher.max-concurrent` | 15 | 最大并发数 |
| `teacher.dispatcher.retry-count` | 3 | 失败重试次数 |
| `teacher.dispatcher.rate-limit-max-requests` | 50 | 每 Key 每分钟最大请求数 |
| `teacher.opencv.min-contour-area` | 500 | 最小轮廓面积阈值 |
| `teacher.opencv.perspective-correction-enabled` | true | 是否启用透视矫正 |
