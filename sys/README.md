# 旭日企业培训系统 · SSM 后端

传统 Spring + Spring MVC + MyBatis，SQL Server 数据库，WAR 部署到 Tomcat。未使用 Spring Boot。Vue 3前端位于仓库的 [frontend](../frontend/README.md)，离线体验位于 [demo](../demo/使用说明.txt)。

2026-10-27：为前端考试管理增加 `GET /api/teaching/exams`。讲师仅查询本人安排的考试，管理员可查询全部；使用旧版后端时请重新构建并部署。

## 已实现的后端范围

- 一名员工多个角色；普通员工可创作课程，独立管理员审核发布后成为讲师；员工、讲师、主管和管理员具有不同工作区及数据权限。
- 课程、章节、文字资料、视频与文件上传、课程审核历史、分配学习、个人学习档案。
- 岗位/职级成长路线、顺序阶段、课程与考试通关、培训计划、固定参训名单、到期站内提醒、路线证书及失效标记。
- 播放心跳、有效时长、去重覆盖区间、单账号单有效会话、普通拖动事件、频繁跳播和多开预警。
- 预警证据、措施、复核、关闭、申诉、有效学习区间调整前后记录。
- 题库、固定组卷、试卷快照、xlsx导入、课程前置条件、在线答题、自动保存、到时交卷、客观判分、主观阅卷、补考、考后错题。
- 个人、讲师、部门/公司统计接口，受权限限制的CSV导出；作业、评分、固定课程满意度评价、站内通知、审计。
- 课程资料问答和AI出题草稿的真实模型接口接入；未配置服务时明确返回503，不生成假答案。

请先阅读 [需求与设计](docs/需求与设计V1.1.md)、[API与联调](docs/API.md) 和 [验证记录](docs/验证记录.md)。

## 环境

- 完整 JDK 17 或 21（只有 JRE 无法正常编译），Maven 3.9+
- SQL Server 2019+，建议 SQL Server 2022；开启 TCP/IP，准备应用数据库用户
- Tomcat 10.1（Jakarta Servlet 6；不能部署到 Tomcat 9）

## Windows 安装

1. 将源码包中的 `sys` 文件夹放到 `C:\Users\anzhipeng\Documents\AI大赛\sys`。该目录原有内容请先合并检查。
2. 在 SSMS 的 master 执行 `database/00-create-database.sql`；切换到 `XuriTraining`，执行 `database/01-schema.sql`。建表脚本用于全新数据库，不能在有数据的数据库重复运行。
3. 为应用配置数据库账号。初始化建表使用 DBA 账号，运行账号仅需要应用表的 SELECT/INSERT/UPDATE/DELETE 权限；无需建库或服务器管理权限。
4. 复制 `deploy/setenv.example.bat` 到 Tomcat 的 `bin/setenv.bat`，填写数据库连接、文件目录和初始管理员环境变量。密码自行设置，不要提交到代码仓库。
5. 在项目根目录执行 `mvn clean verify`，将 `target/training.war` 放入 Tomcat 的 `webapps`。
6. 启动 Tomcat，访问 `http://localhost:8080/training/api/health`。用配置的管理员账号调用登录接口，然后创建员工及第二个管理员。

课程审核和预警复核要求不同人员：管理员不能审核自己上传的课程，预警处理人不能自行复核关闭。首个管理员可以审核员工提交的课程；第二个管理员用于预警独立复核。

管理员初始化成功后可移除 `TRAINING_ADMIN_PASSWORD` 环境变量。相同用户名存在时不会覆盖密码。

## 配置项

| 环境变量 | 用途 |
| --- | --- |
| TRAINING_DB_URL | 完整SQL Server JDBC URL，必填 |
| TRAINING_DB_USER / TRAINING_DB_PASSWORD | 数据库账号，必填 |
| TRAINING_STORAGE | 文件存储绝对目录，建议置于Tomcat外并定期备份 |
| TRAINING_ADMIN_USER / TRAINING_ADMIN_PASSWORD | 首次启动创建管理员，密码至少12字符且UTF-8不超过72字节 |
| TRAINING_AI_ENDPOINT | 完整HTTPS聊天接口地址，包含提供商要求的完整路径 |
| TRAINING_AI_KEY / TRAINING_AI_MODEL | 自行提供的模型密钥和模型名 |

正式数据库连接使用 `encrypt=true;trustServerCertificate=false` 并正确配置信任证书。示例中的 `trustServerCertificate=true` 仅供本机开发。

AI接口采用兼容 `messages` / `choices[0].message.content` 的聊天协议。启用前确认所用服务支持该协议，允许向其发送课程资料，并配置好网络访问。视频须另外录入文字讲稿/课程资料才能用于答疑，本版不自动转写视频。

## 构建与验证

```text
mvn clean verify
```

单元与HTTP层测试不需要数据库，覆盖权限、跳播、多开、去重、考试门槛、答案隔离、复核和导入规则。Mapper解析测试不等于SQL Server执行验证。当前环境的实测情况见验证记录。

SQL Server连通后的数据库映射冒烟检查：

```powershell
$env:TRAINING_TEST_DB_URL=$env:TRAINING_DB_URL
$env:TRAINING_TEST_DB_USER=$env:TRAINING_DB_USER
$env:TRAINING_TEST_DB_PASSWORD=$env:TRAINING_DB_PASSWORD
mvn -Dtest=SqlServerSmokeTest test
```

该测试只读探测已初始化数据库，不创建/删除数据库、不插入演示数据；后续仍需按验证记录执行完整业务验收。

## 部署边界

本版面向单个后端实例，数据库锁防止并行学习和重复交卷；定时通知按单实例部署。多实例上线前应补充分布式任务调度和通知去重约束。视频以受控文件流提供，支持Range；播放地址使用15分钟、限定单文件的随机凭证，不把登录令牌放进URL。Vue与后端建议同域代理。

运行日志不应记录Authorization头、模型密钥、数据库密码和播放凭证。数据库和文件目录需要一并备份，并在上线前实际做一次恢复演练。
