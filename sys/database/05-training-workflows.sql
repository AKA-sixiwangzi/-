-- 旭能学院：自动员工账号、多格式课件、计划提醒/负责人、人工阅卷、线下参训与讲师计划看板。
-- 现有数据库先确认02、03、04已执行，再运行本文件，之后部署新版training.war。
-- 新库直接使用最新01-schema.sql；无需新建数据库或重建旧表。
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.app_user', N'U') IS NULL
        OR OBJECT_ID(N'dbo.course_material', N'U') IS NULL
        OR OBJECT_ID(N'dbo.training_plan', N'U') IS NULL
        OR OBJECT_ID(N'dbo.exam_answer', N'U') IS NULL
        THROW 50006, N'请先初始化培训数据库，再执行培训流程升级脚本。', 1;

    IF COL_LENGTH(N'dbo.app_user', N'must_change_password') IS NULL
        EXEC(N'ALTER TABLE dbo.app_user ADD must_change_password bit NOT NULL CONSTRAINT DF_user_must_change_password DEFAULT(0) WITH VALUES');

    IF COL_LENGTH(N'dbo.course_material', N'material_type') IS NULL
    BEGIN
        EXEC(N'ALTER TABLE dbo.course_material ADD material_type varchar(12) NOT NULL CONSTRAINT DF_material_type DEFAULT(''TEXT'') WITH VALUES');
        -- 原有PDF附件仍可打开，且不会把旧选读资料改为必读、重置历史完成状态。
        EXEC(N'UPDATE m SET material_type=CASE WHEN f.content_type=''application/pdf'' THEN ''PDF'' ELSE ''PPT'' END
            FROM dbo.course_material m JOIN dbo.file_asset f ON f.id=m.asset_id
            WHERE f.content_type IN (''application/pdf'',''application/vnd.ms-powerpoint'',''application/vnd.openxmlformats-officedocument.presentationml.presentation'')');
    END;
    IF COL_LENGTH(N'dbo.course_material', N'external_url') IS NULL
        EXEC(N'ALTER TABLE dbo.course_material ADD external_url nvarchar(2048) NULL');
    IF COL_LENGTH(N'dbo.course_material', N'required') IS NULL
        EXEC(N'ALTER TABLE dbo.course_material ADD required bit NOT NULL CONSTRAINT DF_material_required DEFAULT(0) WITH VALUES');
    IF OBJECT_ID(N'dbo.CK_material_type', N'C') IS NULL
        EXEC(N'ALTER TABLE dbo.course_material WITH CHECK ADD CONSTRAINT CK_material_type CHECK(material_type IN(''TEXT'',''PDF'',''PPT'',''LINK''))');

    IF OBJECT_ID(N'dbo.material_ack', N'U') IS NULL
        CREATE TABLE dbo.material_ack (
            user_id bigint NOT NULL REFERENCES dbo.app_user(id),
            material_id bigint NOT NULL REFERENCES dbo.course_material(id),
            acknowledged_at datetime2 NOT NULL DEFAULT SYSUTCDATETIME(),
            PRIMARY KEY(user_id, material_id)
        );

    IF COL_LENGTH(N'dbo.exam_answer', N'grading_feedback') IS NULL
        EXEC(N'ALTER TABLE dbo.exam_answer ADD grading_feedback nvarchar(1000) NULL');

    IF COL_LENGTH(N'dbo.training_plan', N'responsible_user_id') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan ADD responsible_user_id bigint NULL CONSTRAINT FK_plan_responsible REFERENCES dbo.app_user(id)');
    IF COL_LENGTH(N'dbo.training_plan', N'remind_before_hours') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan ADD remind_before_hours int NOT NULL CONSTRAINT DF_plan_remind_before DEFAULT(72) WITH VALUES');
    IF COL_LENGTH(N'dbo.training_plan', N'remind_every_hours') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan ADD remind_every_hours int NOT NULL CONSTRAINT DF_plan_remind_every DEFAULT(24) WITH VALUES');
    IF COL_LENGTH(N'dbo.training_plan', N'push_results') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan ADD push_results bit NOT NULL CONSTRAINT DF_plan_push_results DEFAULT(1) WITH VALUES');
    IF OBJECT_ID(N'dbo.CK_plan_remind_before', N'C') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan WITH CHECK ADD CONSTRAINT CK_plan_remind_before CHECK(remind_before_hours BETWEEN 0 AND 8760)');
    IF OBJECT_ID(N'dbo.CK_plan_remind_every', N'C') IS NULL
        EXEC(N'ALTER TABLE dbo.training_plan WITH CHECK ADD CONSTRAINT CK_plan_remind_every CHECK(remind_every_hours BETWEEN 1 AND 720)');

    IF OBJECT_ID(N'dbo.plan_notice_log', N'U') IS NULL
        CREATE TABLE dbo.plan_notice_log (
            id bigint IDENTITY PRIMARY KEY,
            plan_id bigint NOT NULL REFERENCES dbo.training_plan(id),
            recipient_id bigint NOT NULL REFERENCES dbo.app_user(id),
            kind varchar(30) NOT NULL,
            state_key varchar(128) NOT NULL,
            created_at datetime2 NOT NULL DEFAULT SYSUTCDATETIME(),
            UNIQUE(plan_id, recipient_id, kind, state_key)
        );

    IF OBJECT_ID(N'dbo.offline_session', N'U') IS NULL
        CREATE TABLE dbo.offline_session (
            id bigint IDENTITY PRIMARY KEY,
            title nvarchar(200) NOT NULL,
            plan_id bigint NULL REFERENCES dbo.training_plan(id),
            course_id bigint NULL REFERENCES dbo.course(id),
            instructor_id bigint NOT NULL REFERENCES dbo.app_user(id),
            start_at datetime2 NOT NULL,
            end_at datetime2 NOT NULL,
            location nvarchar(200) NOT NULL,
            created_by bigint NOT NULL REFERENCES dbo.app_user(id),
            created_at datetime2 NOT NULL DEFAULT SYSUTCDATETIME(),
            CHECK(end_at > start_at)
        );
    IF OBJECT_ID(N'dbo.offline_attendance', N'U') IS NULL
        CREATE TABLE dbo.offline_attendance (
            session_id bigint NOT NULL REFERENCES dbo.offline_session(id),
            user_id bigint NOT NULL REFERENCES dbo.app_user(id),
            status varchar(20) NOT NULL DEFAULT 'REGISTERED'
                CHECK(status IN('REGISTERED','PRESENT','ABSENT','EXCUSED')),
            score int NULL CHECK(score BETWEEN 0 AND 100),
            note nvarchar(1000) NULL,
            recorded_by bigint NULL REFERENCES dbo.app_user(id),
            recorded_at datetime2 NULL,
            PRIMARY KEY(session_id, user_id)
        );
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
