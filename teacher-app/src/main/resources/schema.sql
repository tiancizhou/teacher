-- ============================================
-- 书法AI批改系统 - SQLite 建表脚本
-- Spring Data JDBC，无 Hibernate，纯 SQL
-- ============================================

-- -------------------------------------------
-- 用户表：绑定微信身份，积累用户画像
-- 核心价值：用户资产化，驱动续费转化
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    open_id         TEXT    NOT NULL UNIQUE,             -- 微信 OpenID（唯一标识，登录凭证）
    nickname        TEXT,                                -- 微信昵称
    avatar_url      TEXT,                                -- 头像 URL
    role            TEXT    NOT NULL DEFAULT 'PARENT',   -- 角色：PARENT=家长 / STUDENT=学生 / TEACHER=老师
    total_analyses  INTEGER NOT NULL DEFAULT 0,          -- 累计批改次数（用于活跃度统计）
    created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime')),  -- 注册时间
    last_active_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))   -- 最后活跃时间
);

-- -------------------------------------------
-- 作业表：每次上传的书法图片 = 一次作业
-- 核心价值：串联用户与批改结果，支撑历史回溯
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_homework (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    task_id             TEXT    NOT NULL UNIQUE,             -- 业务任务ID（如 task-a1b2c3d4）
    user_id             INTEGER,                            -- 关联用户ID（匿名用户为空）
    original_file_name  TEXT,                                -- 上传的原始文件名
    image_path          TEXT,                                -- 图片存储路径（本地路径或 OSS URL）
    copy_book_id        TEXT,                                -- 临摹字帖ID（用于缓存命中和成长对比）
    char_count          INTEGER,                            -- 本次检测到的字符数
    avg_score           REAL,                                -- 本次综合平均分
    status              TEXT    NOT NULL DEFAULT 'PENDING',  -- 状态：PENDING=待处理 / PROCESSING=处理中 / COMPLETED=完成 / FAILED=失败
    processing_time_ms  INTEGER,                            -- AI批改总耗时（毫秒）
    created_at          TEXT    NOT NULL DEFAULT (datetime('now','localtime'))  -- 提交时间
);

-- -------------------------------------------
-- 分析结果表：每个字一条记录
-- 核心价值：① 逐字点评详情  ② 成长曲线数据源  ③ 缓存命中省 API 调用
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_analysis (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    homework_id      INTEGER NOT NULL,                    -- 关联作业ID（t_homework.id）
    char_index       INTEGER,                            -- 字在整页中的序号（从0开始，按阅读顺序）
    recognized_char  TEXT,                                -- AI 识别出的汉字（如 "永"、"天"）
    structure_score  INTEGER,                            -- 结构评分 0-100（重心、间架、比例）
    stroke_score     INTEGER,                            -- 笔画评分 0-100（起笔、行笔、收笔）
    overall_score    INTEGER,                            -- 综合评分 0-100
    result_json      TEXT,                                -- AI 完整返回的 JSON 原始数据（备查扩展）
    overall_comment  TEXT,                                -- 综合评语（温情鼓励风格）
    suggestion       TEXT,                                -- 改进建议
    cache_key        TEXT,                                -- 缓存键（格式：字帖ID:汉字），用于跨作业复用高分点评
    created_at       TEXT    NOT NULL DEFAULT (datetime('now','localtime'))  -- 分析时间
);

-- -------------------------------------------
-- API Key 调用日志：监控算力消耗，防刷审计
-- 核心价值：① 成本可视化  ② 异常 Key 追踪  ③ 用户级限流依据
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_key_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    user_id       INTEGER,                            -- 关联用户ID（可为空）
    task_id       TEXT,                                -- 关联任务ID（t_homework.task_id）
    api_key_hash  TEXT,                                -- API Key 哈希（脱敏，不存明文）
    provider      TEXT,                                -- AI 提供商：openai / anthropic
    model         TEXT,                                -- 使用的模型：gpt-4o / claude-3-5-sonnet 等
    char_count    INTEGER,                            -- 本次分析的字符数
    tokens_used   INTEGER,                            -- 预估 Token 消耗
    latency_ms    INTEGER,                            -- 调用总耗时（毫秒）
    success       INTEGER NOT NULL DEFAULT 1,          -- 是否成功：1=成功 / 0=失败
    error_message TEXT,                                -- 失败原因（成功时为空）
    cache_hits    INTEGER NOT NULL DEFAULT 0,          -- 本次缓存命中数（省掉的 API 调用次数）
    created_at    TEXT    NOT NULL DEFAULT (datetime('now','localtime'))  -- 记录时间
);

-- -------------------------------------------
-- 字帖模板表：预定义的字帖网格布局
-- 核心价值：确定性网格裁切，匹配行列位置
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_copybook_template (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    name         TEXT    NOT NULL,                    -- 模板名称（如"田字格 10x10"）
    grid_type    TEXT    NOT NULL,                    -- 格线类型：TIAN=田字格 / MI=米字格 / HUI=回宫格 / PLAIN=无格线
    grid_rows    INTEGER NOT NULL,                    -- 网格行数
    grid_cols    INTEGER NOT NULL,                    -- 网格列数
    header_ratio REAL    NOT NULL DEFAULT 0.0,        -- 顶部非书写区占比（0~0.3）
    description  TEXT,                                -- 补充描述
    created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 预置常用字帖模板
INSERT OR IGNORE INTO t_copybook_template (id, name, grid_type, grid_rows, grid_cols, header_ratio, description)
VALUES
    (1,  '田字格 8x8',   'TIAN',  8,  8,  0.05, '标准田字格练习纸，8行8列'),
    (2,  '田字格 10x10', 'TIAN',  10, 10, 0.05, '标准田字格练习纸，10行10列'),
    (3,  '田字格 12x8',  'TIAN',  12, 8,  0.05, '标准田字格练习纸，12行8列'),
    (4,  '田字格 10x8',  'TIAN',  10, 8,  0.05, '标准田字格练习纸，10行8列'),
    (5,  '米字格 8x6',   'MI',    8,  6,  0.05, '米字格练习纸，8行6列'),
    (6,  '米字格 8x8',   'MI',    8,  8,  0.05, '米字格练习纸，8行8列'),
    (7,  '米字格 10x8',  'MI',    10, 8,  0.05, '米字格练习纸，10行8列'),
    (8,  '回宫格 8x6',   'HUI',   8,  6,  0.05, '回宫格练习纸，8行6列'),
    (9,  '回宫格 10x8',  'HUI',   10, 8,  0.05, '回宫格练习纸，10行8列'),
    (10, '无格线 8x8',   'PLAIN', 8,  8,  0.0,  '无格线练习纸，8行8列'),
    (11, '田字格 6x6',   'TIAN',  6,  6,  0.05, '大格田字格练习纸，6行6列'),
    (12, '田字格 14x10', 'TIAN',  14, 10, 0.05, '田字格练习纸，14行10列');

-- -------------------------------------------
-- 单字精批结果表：每次单字精批一条记录
-- 核心价值：五维度深度分析，支撑单字成长追踪
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS t_single_analysis (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,  -- 主键
    task_id           TEXT    NOT NULL UNIQUE,             -- 业务任务ID（如 single-a1b2c3d4）
    user_id           INTEGER,                            -- 关联用户ID（匿名为空）
    recognized_char   TEXT,                                -- AI 识别出的汉字
    structure_score   INTEGER,                            -- 结构评分 0-100
    structure_detail  TEXT,                                -- 结构分析详情
    stroke_score      INTEGER,                            -- 笔画评分 0-100
    stroke_detail     TEXT,                                -- 笔画分析详情
    balance_score     INTEGER,                            -- 重心评分 0-100
    balance_detail    TEXT,                                -- 重心分析详情
    spacing_score     INTEGER,                            -- 间架评分 0-100
    spacing_detail    TEXT,                                -- 间架分析详情
    overall_score     INTEGER,                            -- 综合评分 0-100
    overall_comment   TEXT,                                -- 总评
    suggestion        TEXT,                                -- 练习建议
    processing_time_ms INTEGER,                           -- 处理耗时（毫秒）
    created_at        TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);

-- -------------------------------------------
-- 索引：按查询场景优化
-- -------------------------------------------
CREATE INDEX IF NOT EXISTS idx_user_open_id       ON t_user(open_id);          -- 微信登录查找
CREATE INDEX IF NOT EXISTS idx_homework_user       ON t_homework(user_id);      -- 用户作业历史
CREATE INDEX IF NOT EXISTS idx_homework_task       ON t_homework(task_id);      -- taskId 精确查询
CREATE INDEX IF NOT EXISTS idx_analysis_homework   ON t_analysis(homework_id);  -- 某次作业的全部逐字结果
CREATE INDEX IF NOT EXISTS idx_analysis_char       ON t_analysis(recognized_char);  -- 成长曲线：按汉字查历史
CREATE INDEX IF NOT EXISTS idx_analysis_cache_key  ON t_analysis(cache_key);    -- 缓存命中：字帖+汉字 快速匹配
CREATE INDEX IF NOT EXISTS idx_keylog_time         ON t_key_log(created_at);    -- 按时间段统计算力消耗
CREATE INDEX IF NOT EXISTS idx_keylog_user         ON t_key_log(user_id);       -- 按用户统计调用频率（防刷）
CREATE INDEX IF NOT EXISTS idx_single_user         ON t_single_analysis(user_id);          -- 用户单字精批历史
CREATE INDEX IF NOT EXISTS idx_single_char         ON t_single_analysis(recognized_char);  -- 按汉字查单字历史
CREATE INDEX IF NOT EXISTS idx_single_task         ON t_single_analysis(task_id);           -- taskId 查询
