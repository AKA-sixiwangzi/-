-- 适用于已执行旧版01-schema.sql的数据库；新建库使用最新01-schema.sql即可。
-- 由具有ALTER TABLE / CREATE TABLE权限的数据库管理员执行，可重复执行。
-- 不修改历史路线、阶段、学习记录或分配名单；旧路线默认适用所有部门。
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.learning_path', N'U') IS NULL
    THROW 50001, N'请先初始化培训数据库，再执行成长路线部门升级脚本。', 1;

IF COL_LENGTH(N'dbo.learning_path', N'all_departments') IS NULL
BEGIN
    ALTER TABLE dbo.learning_path
        ADD all_departments bit NOT NULL
            CONSTRAINT DF_learning_path_all_departments DEFAULT (1) WITH VALUES;
END;

IF OBJECT_ID(N'dbo.path_department', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.path_department (
        path_id bigint NOT NULL,
        department_id bigint NOT NULL,
        CONSTRAINT PK_path_department PRIMARY KEY (path_id, department_id),
        CONSTRAINT FK_path_department_path FOREIGN KEY (path_id)
            REFERENCES dbo.learning_path(id),
        CONSTRAINT FK_path_department_department FOREIGN KEY (department_id)
            REFERENCES dbo.department(id)
    );
END;

COMMIT;
