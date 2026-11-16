-- 旭能学院：岗位固定为专业岗、管理岗、综合岗，不再保留具体岗位或子分类。
-- 已建库先执行 02、03，再执行本脚本，最后部署新版 WAR；新建库直接使用最新 01。
-- 由具备相应 DDL / UPDATE / DELETE 权限的数据库管理员执行，可重复执行。
-- 将旧岗位按 position_type 合并，更新员工与成长路线的岗位引用；不清空岗位外键。
-- 旧名称与映射保留在 position_migration_archive，不删除课程、学习记录、成绩或分配名单。
SET XACT_ABORT ON;

BEGIN TRY
    BEGIN TRANSACTION;

    IF OBJECT_ID(N'dbo.job_position', N'U') IS NULL
        OR OBJECT_ID(N'dbo.app_user', N'U') IS NULL
        OR OBJECT_ID(N'dbo.learning_path', N'U') IS NULL
        THROW 50003, N'请先初始化培训数据库，再执行固定岗位升级脚本。', 1;

    IF COL_LENGTH(N'dbo.job_position', N'position_type') IS NULL
        THROW 50004, N'请先执行 03-position-types-learning-requests.sql，再执行固定岗位升级脚本。', 1;

    IF OBJECT_ID(N'dbo.position_migration_archive', N'U') IS NULL
    BEGIN
        -- 旧岗位将被合并删除，因此存档不对旧岗位编号设置外键。
        CREATE TABLE dbo.position_migration_archive (
            old_position_id bigint PRIMARY KEY,
            old_name nvarchar(100) NOT NULL,
            old_position_type varchar(20) NOT NULL,
            canonical_position_id bigint NOT NULL,
            migrated_at datetime2 NOT NULL DEFAULT SYSUTCDATETIME()
        );
    END;

    -- 动态执行避免升级前旧元数据（缺少 position_type 或存档表）的批次预编译问题。
    EXEC sys.sp_executesql N'
        DECLARE @row_count bigint;
        SELECT @row_count = COUNT_BIG(*)
        FROM dbo.job_position WITH (TABLOCKX, HOLDLOCK);

        DECLARE @original TABLE (
            id bigint PRIMARY KEY,
            name nvarchar(100) NOT NULL,
            position_type varchar(20) NOT NULL
        );
        INSERT INTO @original(id, name, position_type)
        SELECT id, name, position_type FROM dbo.job_position;

        -- 已经使用标准岗位名称的记录优先按名称识别，避免旧默认 GENERAL 导致归错类。
        UPDATE p
        SET position_type = CASE p.name
            WHEN N''专业岗'' THEN ''PROFESSIONAL''
            WHEN N''管理岗'' THEN ''MANAGEMENT''
            WHEN N''综合岗'' THEN ''GENERAL''
        END
        FROM dbo.job_position p
        WHERE DATALENGTH(p.name) = 6 AND (
            (p.name = N''专业岗'' AND p.position_type <> ''PROFESSIONAL'')
            OR (p.name = N''管理岗'' AND p.position_type <> ''MANAGEMENT'')
            OR (p.name = N''综合岗'' AND p.position_type <> ''GENERAL''));

        IF EXISTS (
            SELECT 1 FROM dbo.job_position
            WHERE position_type IS NULL
                OR position_type NOT IN (''GENERAL'', ''PROFESSIONAL'', ''MANAGEMENT'')
        )
            THROW 50005, N''存在未识别的旧岗位分类，请先核对数据再升级。'', 1;

        DECLARE @canonical TABLE (
            position_type varchar(20) PRIMARY KEY,
            name nvarchar(100) NOT NULL,
            canonical_id bigint NULL
        );
        INSERT INTO @canonical(position_type, name)
        VALUES (''GENERAL'', N''综合岗''),
            (''PROFESSIONAL'', N''专业岗''),
            (''MANAGEMENT'', N''管理岗'');

        -- 同类中优先保留已有标准名称的编号，否则保留最小编号，尽量减少引用变更。
        UPDATE target
        SET canonical_id = COALESCE(
            (SELECT MIN(p.id) FROM dbo.job_position p
                WHERE p.position_type = target.position_type AND p.name = target.name
                    AND DATALENGTH(p.name) = DATALENGTH(target.name)),
            (SELECT MIN(p.id) FROM dbo.job_position p
                WHERE p.position_type = target.position_type)
        )
        FROM @canonical target;

        INSERT INTO dbo.job_position(name, position_type)
        SELECT name, position_type FROM @canonical WHERE canonical_id IS NULL;

        UPDATE target
        SET canonical_id = (
            SELECT MIN(p.id) FROM dbo.job_position p
            WHERE p.position_type = target.position_type
        )
        FROM @canonical target
        WHERE canonical_id IS NULL;

        DECLARE @mapping TABLE (
            old_id bigint PRIMARY KEY,
            canonical_id bigint NOT NULL
        );
        INSERT INTO @mapping(old_id, canonical_id)
        SELECT p.id, target.canonical_id
        FROM dbo.job_position p
        JOIN @canonical target ON target.position_type = p.position_type;

        -- 只存档被合并、改名或纠正分类的旧记录；重复执行不会生成多余存档。
        INSERT INTO dbo.position_migration_archive(
            old_position_id, old_name, old_position_type, canonical_position_id
        )
        SELECT original.id, original.name, original.position_type, map.canonical_id
        FROM @original original
        JOIN @mapping map ON map.old_id = original.id
        JOIN @canonical target ON target.canonical_id = map.canonical_id
        WHERE (original.id <> map.canonical_id OR original.name <> target.name
                OR DATALENGTH(original.name) <> DATALENGTH(target.name)
                OR original.position_type <> target.position_type)
            AND NOT EXISTS (
                SELECT 1 FROM dbo.position_migration_archive archived
                WHERE archived.old_position_id = original.id
            );

        UPDATE employee
        SET position_id = map.canonical_id
        FROM dbo.app_user employee
        JOIN @mapping map ON map.old_id = employee.position_id
        WHERE employee.position_id <> map.canonical_id;

        UPDATE route
        SET position_id = map.canonical_id
        FROM dbo.learning_path route
        JOIN @mapping map ON map.old_id = route.position_id
        WHERE route.position_id <> map.canonical_id;

        DELETE old_position
        FROM dbo.job_position old_position
        JOIN @mapping map ON map.old_id = old_position.id
        WHERE map.old_id <> map.canonical_id;

        UPDATE p
        SET name = target.name, position_type = target.position_type
        FROM dbo.job_position p
        JOIN @canonical target ON target.canonical_id = p.id;

        IF NOT EXISTS (
            SELECT 1 FROM sys.key_constraints
            WHERE parent_object_id = OBJECT_ID(N''dbo.job_position'')
                AND name = N''UQ_job_position_name''
        )
            ALTER TABLE dbo.job_position
                ADD CONSTRAINT UQ_job_position_name UNIQUE (name);

        IF NOT EXISTS (
            SELECT 1 FROM sys.key_constraints
            WHERE parent_object_id = OBJECT_ID(N''dbo.job_position'')
                AND name = N''UQ_job_position_type''
        )
            ALTER TABLE dbo.job_position
                ADD CONSTRAINT UQ_job_position_type UNIQUE (position_type);

        IF NOT EXISTS (
            SELECT 1 FROM sys.check_constraints
            WHERE parent_object_id = OBJECT_ID(N''dbo.job_position'')
                AND name = N''CK_job_position_fixed''
        )
            ALTER TABLE dbo.job_position WITH CHECK
                ADD CONSTRAINT CK_job_position_fixed CHECK (DATALENGTH(name) = 6 AND (
                    (position_type = ''GENERAL'' AND name = N''综合岗'')
                    OR (position_type = ''PROFESSIONAL'' AND name = N''专业岗'')
                    OR (position_type = ''MANAGEMENT'' AND name = N''管理岗'')
                ));
    ';

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
