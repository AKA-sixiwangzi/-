-- 旭能学院：岗位兼容字段、部门加课需求与个人课程授权（中间升级）。
-- 已建数据库依次执行02、本文件、04-fixed-positions.sql，再部署新版WAR。
-- 由有 ALTER TABLE / CREATE TABLE 权限的数据库管理员执行；可重复执行。
-- 本文件先保留旧岗位名称并补兼容字段；04再归并为专业岗、管理岗、综合岗三个固定岗位。
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.job_position', N'U') IS NULL
    OR OBJECT_ID(N'dbo.learning_path', N'U') IS NULL
    OR OBJECT_ID(N'dbo.course', N'U') IS NULL
    OR OBJECT_ID(N'dbo.app_user', N'U') IS NULL
    THROW 50002, N'请先初始化培训数据库，再执行岗位和加课需求升级脚本。', 1;

IF COL_LENGTH(N'dbo.job_position', N'position_type') IS NULL
BEGIN
    ALTER TABLE dbo.job_position
        ADD position_type varchar(20) NOT NULL
            CONSTRAINT DF_job_position_type DEFAULT ('GENERAL') WITH VALUES;
END;

IF OBJECT_ID(N'dbo.CK_job_position_type', N'C') IS NULL
BEGIN
    -- 动态执行，避免同一批次中新增列后约束引用在旧元数据上预编译。
    EXEC(N'ALTER TABLE dbo.job_position
        ADD CONSTRAINT CK_job_position_type
        CHECK (position_type IN (''PROFESSIONAL'', ''MANAGEMENT'', ''GENERAL''))');
END;

IF OBJECT_ID(N'dbo.learning_request', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.learning_request (
        id bigint IDENTITY PRIMARY KEY,
        requester_id bigint NOT NULL REFERENCES dbo.app_user(id),
        path_id bigint NULL REFERENCES dbo.learning_path(id),
        reason nvarchar(1000) NOT NULL,
        status varchar(20) NOT NULL DEFAULT 'PENDING'
            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
        reviewer_id bigint NULL REFERENCES dbo.app_user(id),
        review_note nvarchar(1000) NULL,
        created_at datetime2 NOT NULL DEFAULT SYSUTCDATETIME(),
        reviewed_at datetime2 NULL
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.learning_request') AND name = N'ix_learning_request_requester'
)
    CREATE INDEX ix_learning_request_requester ON dbo.learning_request(requester_id, id);

IF OBJECT_ID(N'dbo.learning_request_course', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.learning_request_course (
        request_id bigint NOT NULL REFERENCES dbo.learning_request(id),
        course_id bigint NOT NULL REFERENCES dbo.course(id),
        PRIMARY KEY (request_id, course_id)
    );
END;

IF OBJECT_ID(N'dbo.learning_request_user', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.learning_request_user (
        request_id bigint NOT NULL REFERENCES dbo.learning_request(id),
        user_id bigint NOT NULL REFERENCES dbo.app_user(id),
        PRIMARY KEY (request_id, user_id)
    );
END;

COMMIT;
