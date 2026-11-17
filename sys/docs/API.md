# 后端接口与Vue联调

## 2026-11-17：六项培训流程扩展

详细行为见 [培训流程V1.3](培训流程V1.3.md)。以下路径仍以 `/api` 为前缀；岗位固定三项、四类角色和原有权限代码不变。

| 接口 | 用途与权限 |
| --- | --- |
| `POST /admin/employees` | 组织发展部新建员工并自动生成关联账号 |
| `POST /courses/{id}/materials` | 课程作者/组织发展部添加文字、PDF、PPT/PPTX或HTTP(S)链接 |
| `GET /courses/{id}/learning-status` | 获准访问课程的本人综合进度；包含必读确认与视频完成情况 |
| `POST /materials/{id}/acknowledge` | 已分配且课程已发布的员工主动确认阅读，不生成视频时长 |
| `PUT /plans/{id}/reminder-rules` | 组织发展部/计划负责人设置提醒；仅组织发展部能更换负责人 |
| `POST /plans/{id}/remind` | 负责人/组织发展部手动检查到期提醒和结果推送，重复检查不会重复发同一通知 |
| `GET /teaching/plans`、`GET /teaching/plans/{id}` | 负责人看整计划、内训师看本人课程，组织发展部看全部 |
| `GET /offline-sessions`、`GET /offline-sessions/{id}` | 员工看本人参训，授课内训师/组织发展部看可管理场次 |
| `GET /offline-sessions/options`、`POST /offline-sessions` | 内训师/组织发展部安排线下培训，人员与关联课程、计划范围独立校验 |
| `PUT /offline-sessions/{id}/attendance` | 授课内训师/组织发展部登记或更正出勤与成绩，原因必填 |
| `GET /me/offline-training` | 当前员工线下记录、已结束且出勤场次的独立学时 |
| `GET /teaching/attempts/{id}`、`PUT /teaching/attempts/{id}/grade` | 考试或试卷负责人/组织发展部人工阅卷，禁止自评和重复评分 |

新建员工不再要求填写账号密码：

```json
{"displayName":"新员工","departmentId":1,"positionId":1,"gradeId":1}
```

`positionId` 必须从组织接口取得。响应为 `{user:{...},account:{username,temporaryPassword,requiresPasswordChange:true}}`。临时明文只在本次返回，数据库只存密码散列；用户列表不返回初密。真实首次登录用户的 `user.must_change_password=true`，令牌仅可访问 `GET /me`、`PUT /auth/password`、`POST /auth/logout`；改密成功后需重新登录。旧 `POST /admin/users` 保留兼容，前端默认使用自动员工入口。

资料请求示例：

```json
{"title":"安全培训PDF","materialType":"PDF","assetId":"上传接口返回的文件UUID","content":"","required":true}
```

`materialType` 为 `TEXT/PDF/PPT/LINK`，LINK使用 `externalUrl`，TEXT可输入正文或上传TXT。省略类型按附件MIME推断，省略`required`按旧版选读处理；前端新增资料默认必读。PPT/PPTX支持上传与下载，PDF可按浏览器能力预览，不提供PPT在线转换。非空课程的全部视频达标且全部必读确认才完成；仅打开附件或网页不算完成。综合状态返回 `{completed,videoCompleted,videoTotal,materialCompleted,materialTotal,materials,percent}`，资料计数仅包括必读项，`materials`包含全部资料与当前用户的确认状态。

`POST /plans` 保留原 `title/startAt/dueAt/courseIds/userIds/examIds`，新增 `responsibleUserId`、`remindBeforeHours`、`remindEveryHours`、`pushResults`，默认当前创建人、72小时、24小时、开启结果推送。提醒规则PUT提交后四项；提前时间0至8760小时，重复间隔1至720小时。真实服务每小时检查一次，按周期或结果摘要去重，使用站内消息，不发送邮件或外部聊天消息。

线下安排体为 `{title,planId?,courseId?,instructorId,startAt,endAt,location,userIds}`，时间需带时区，地点不超过200字符，单场不超过24小时。登记体例如：

```json
{"records":[{"userId":7,"status":"PRESENT","score":92,"note":"完成实操"}],"reason":"已核对现场签到表"}
```

状态为`PRESENT/ABSENT/EXCUSED`，可选成绩0至100；培训开始前不能登记，只有结束且出勤才计线下学时，重复登记不累加学时。更正保存审计与通知，不修改视频学习数据。详情返回 `{session,attendance,can_manage}`，个人接口返回 `{records,credited_hours,attended_sessions}`。

简答题交卷后进入`PENDING_GRADE`，总分为空。阅卷详情含考生答案、参考答案、满分、已得分与评语，只对有权限的阅卷人开放且不得用于未交卷答卷。逐题评分体 `{questionId,score,feedback?}`，评语最多1000字；未作答的简答题也需人工确认0分。全部简答题评分后才汇总总分、计算通过结果并通知员工。已评题重复提交409，越权或自评403。

已有数据库先完成02、03、04，再执行 `05-training-workflows.sql` 后更新WAR；尚未执行真实SQL Server迁移的环境不能将自动测试通过视作完成实库联调。

## 2026-11-16：固定三岗位、路线分配与加课审批

权限代码沿用 `EMPLOYEE` / `INSTRUCTOR` / `MANAGER` / `ADMIN`，界面名称分别为普通员工、内训师、部门负责人、组织发展部。同一账号可兼任，普通员工身份保留。部门负责人必须配置 `manager-scope`，所属部门不等于授权部门。

`GET /organization` 的 `positions` 只返回专业岗、管理岗、综合岗三条真实数据库记录，沿用各自的 `id`。`position_type` 和 `positionTypes` 保留为兼容内部代码，不表示另有子岗位或分类管理。`GET /admin/users` 各行包含 `roles`、`manager_department_ids`。

岗位不可新增、改名或重新分类。兼容旧客户端的 `POST /admin/positions`、`PUT /admin/positions/{id}` 对组织发展部返回409及固定岗位说明，其他角色403，均不写入数据库。`PUT /admin/users/{id}` 可更新姓名、部门、岗位和职级；岗位必须引用这三个固定选项之一，不根据岗位自动授予角色。

`POST /paths/{id}/assign` 须提交所选部门、岗位及人员，替代下方旧的仅人员示例。岗位编号从 `GET /organization` 取得，不固定为示例中的2：

```json
{"departmentId":2,"positionId":2,"userIds":[8,10]}
```

整批核验人员启用状态、所选部门与岗位、路线适用部门和岗位约束后才分配；任一不符返回400，不产生部分分配。

| 接口 | 权限与用途 |
| --- | --- |
| `GET /learning-requests/options` | 负责人/组织发展部；返回可管理的启用员工、已发布课程及有关成长路线 |
| `GET /learning-requests` | 负责人只能查本人申请，组织发展部查全部；最近200条 |
| `POST /learning-requests` | 负责人/组织发展部提交需求，仅为待审批，不开通学习 |
| `POST /learning-requests/{id}/review` | 组织发展部审批，必须填写结果与意见 |
| `POST /admin/course-grants` | 组织发展部直接为指定员工开通已发布课程 |

选项响应为 `{users:[],courses:[],paths:[]}`，每条路线增加 `user_ids`，仅含调用者可管理且已参加路线的启用员工。负责人只获得已发布课程的目录元数据，此接口不开放视频、讲义或学习权限。

提交需求示例：

```json
{"pathId":null,"courseIds":[3],"userIds":[10],"reason":"岗位任务需要补充学习"}
```

可选关联 `pathId` 要求目标员工已参加该路线；课程是个人补充任务，不修改共享路线阶段。需求列表各条包含申请人、可选路线、状态、原因、课程数组、员工数组、审批人、审批意见与时间。

审批体为 `{"approve":true,"note":"同意补充学习"}` 或 `{"approve":false,"note":"请补充需求依据"}`。`approve` 与非空意见均必填。审批前重验课程发布状态、员工和申请人启用状态、申请人当前角色、当前授权部门及路线参加关系。已审批需求返回409；驳回不授权。

直接授权体为 `{"courseIds":[3],"userIds":[10],"reason":"指定个人专项学习"}`，成功返回 `user_count`、`course_count`。重复授权不重置已完成记录。两类批量操作均先验证整批再写入，通知和审计同事务提交。

已有数据库依次执行02、03、04增量脚本后部署新版；04归并旧岗位并保留映射存档。详细规则见 [岗位角色与加课流程V1.2](岗位角色与加课流程V1.2.md)。

开发访问前缀：`http://localhost:8080/training/api`。除登录、健康检查、限定文件凭证的media入口外，均发送`Authorization: Bearer <token>`。推荐Vue开发服务器将`/api`代理到Tomcat `/training/api`，上线使用同域反向代理。

成功返回业务对象/列表，无统一外层包装；无返回值操作通常返回200空响应。错误为`{"message":"说明"}`，服务器异常另有reference。401重新登录，403权限不足，404不存在，409业务条件不满足，429频率限制，503外部服务未配置。

## 首次登录与身份导航

`POST /auth/login`

```json
{"username":"admin","password":"自行设置的初始管理员密码"}
```

返回token、expiresIn、user、roles。之后调用`GET /me`，示例：

```json
{
  "user":{"id":7,"display_name":"张三"},
  "roles":["EMPLOYEE","INSTRUCTOR"],
  "workspaces":["LEARNING","CONTENT_CREATION","TEACHING"],
  "defaultWorkspace":"LEARNING",
  "theme":{"primary":"#003f98","navy":"#00274c","accent":"#ffcb0b"}
}
```

workspaces控制页面入口；数据接口仍独立校验。普通员工可进入内容创作，不需要先申请一个新的讲师账号。课程审核通过自动授予原账号INSTRUCTOR，再次调用/me即可获取新工作区。

接口请求字段采用camelCase，数据库映射结果中的业务实体字段采用snake_case；组合字段如sessionId/nextSeq采用camelCase。前端应根据实际接口映射，不能假定所有响应都是同一种命名。

## 课程发布流程

1. 管理员创建员工：`POST /admin/users`，字段username/password/displayName/departmentId/positionId/gradeId。员工密码至少12字符，UTF-8最多72字节。
2. 员工创建自己的课程：`POST /courses`，字段title/description/category。
3. 员工上传视频：`POST /files`，multipart字段file，返回id。文件不超过500MB。
4. 增加章节：`POST /courses/{id}/lessons`。

```json
{"title":"安全操作第一讲","sortOrder":1,"durationSeconds":1800,"videoAssetId":"视频文件UUID","coverageThreshold":0.95,"timeThreshold":0.95}
```

5. 增加AI可用文字资料：`POST /courses/{id}/materials`。

```json
{"title":"安全操作讲稿","content":"经审核的课程文字资料或视频讲稿……","assetId":null}
```

6. 员工提交：`POST /courses/{id}/submit-review`。
7. 管理员查看`GET /admin/courses/pending`，审核`POST /admin/courses/{id}/review`。

```json
{"approve":true,"note":"已核验内容、视频和标准时长，同意发布"}
```

8. 管理员通过路线或计划分配学习任务。普通员工`GET /courses`仅得到已分配的已发布课程；`GET /teaching/courses`返回自己创作的课程，不混入他人草稿。

## 培训计划和岗位路线

路线：POST /paths → POST /paths/{id}/stages → 配置阶段考试 → POST /paths/{id}/publish → POST /paths/{id}/assign。

```json
{"title":"初级操作员成长路线","positionId":1,"gradeId":1}
```

```json
{"title":"岗位基础","sortOrder":1,"courseIds":[1,2]}
```

```json
{"userIds":[7,8]}
```

路线按ID关联已存在岗位/职级，不会自动遍历所有匹配人员；分配名单显式指定。

2026-11-16：新建路线增加适用部门范围，支持所有部门或多个指定部门：

```json
{"title":"跨部门通用培训","positionId":null,"gradeId":null,"allDepartments":true,"departmentIds":[]}
```

```json
{"title":"部门专项学习路线","positionId":null,"gradeId":null,"allDepartments":false,"departmentIds":[1,2]}
```

部门选项来自 `GET /organization` 的 `departments`。选择指定部门时至少提供一个有效部门ID，重复ID去重；`allDepartments=true` 与非空ID列表不能同时提交。旧请求同时省略这两个字段时默认所有部门；仅提供非空ID列表时推断为指定部门。

`GET /paths` 每项与 `GET /paths/{id}` 的 `path` 返回 `all_departments`、`department_ids`、`departments`（`[{"id":1,"name":"总经办"}]`）。所有部门用 `all_departments=true`、两个空数组表示；它也适用于未来新增部门。该范围不自动生成参训名单：仍需发布后调用分配接口。分配指定部门路线时，后端先校验整批人员都属于该范围，再写入任务；越界员工请求返回400，整批不分配。已有分配和学习记录不会因升级而重写。

已初始化旧数据库时，升级新版后端前请由数据库管理员在应用库执行 `database/02-add-path-departments.sql`；不要重复执行全量建表脚本。新建库直接使用最新 `01-schema.sql`。

创建计划`POST /plans`，之后调用`POST /plans/{id}/publish`：

```json
{"title":"新员工入职培训","startAt":"2026-10-16T09:00:00+08:00","dueAt":"2026-11-16T18:00:00+08:00","courseIds":[1,2],"examIds":[1],"userIds":[7,8]}
```

时间必须带时区，数据库统一存UTC；Vue按北京时间显示。没有关联考试时examIds传空数组。

## 播放事件协议

先`POST /lessons/{id}/sessions`取得sessionId，事件从seq=1连续递增。固定约10秒上报，用户操作时立即上报并串行发送。网络失败重试原seq，不跳号；每个页面只使用自己的会话，不跨标签共享。

`POST /learning/sessions/{sessionId}/events`

```json
{"seq":1,"position":0,"event":"PLAY","visible":true,"rate":1}
```

```json
{"seq":2,"position":10,"event":"HEARTBEAT","visible":true,"rate":1}
```

事件值PLAY/HEARTBEAT/SEEK/PAUSE/END。SEEK上报拖动后的实际位置；前端保留拖动前的位置用于提示和按规则回退。拖动后重新PLAY建立基准，再开始连续计时。浏览器暂停/缓冲、document.visibilityState变化时及时上报并暂停有效播放。

普通拖动显示“跳过片段不计入学习进度”；频繁跳播按后端规则形成预警。多开返回409，前端提示关闭原会话。点击退出学习可`POST /learning/stop`；结束播放发送END。退出失败由心跳超时兜底，不给超时期间补时。

播放资源：`POST /files/{assetId}/ticket`取得url和expiresIn。url是相对于应用根目录的`/api/media/...`，部署在`/training`时需拼为`/training/api/media/...`，不能遗漏contextPath。设置video.src时使用该地址，支持浏览器Range读取。凭证15分钟有效，临近到期重新获取，保留播放位置和有效学习会话。不要把登录Bearer令牌放在视频URL中。

暂停、页面隐藏、跳播不一定是违规；前端提示、原始事件与正式预警应分开呈现。

## 题库、试卷、考试

创建题目`POST /questions`：

```json
{"courseId":1,"type":"SINGLE","stem":"以下哪项符合安全要求？","options":{"A":"按规程操作","B":"跳过检查"},"answer":"A","explanation":"依据安全操作规范"}
```

创建试卷`POST /papers`：

```json
{"title":"安全基础试卷","questionIds":[1,2],"scores":[50,50]}
```

发布`POST /papers/{id}/publish`。上传试卷：GET /papers/import-template下载xlsx标准模板；POST /papers/import以multipart传title/file。样例行必须替换成真实课程ID及题目，避免误导入演示题。题型SINGLE/MULTIPLE/TRUE_FALSE/TEXT，多选答案用A,C，判断题提供两个选项和一个选项答案，TEXT填写参考答案。

创建考试`POST /exams`：

```json
{"title":"安全基础考核","paperId":1,"stageId":null,"startAt":"2026-10-16T09:00:00+08:00","endAt":"2026-11-16T18:00:00+08:00","durationMinutes":30,"passScore":80,"maxAttempts":2,"prerequisiteCourseIds":[1],"userIds":[7,8]}
```

阶段考试在路线发布前创建，stageId填阶段ID。考试名单不自动代表已分配课程，管理员需同时安排培训计划/路线，以便员工先学习。

员工GET /exams取得分区及缺少的前置课程，POST /exams/{id}/start建立或恢复考试。用返回的试卷题目ID（paper_question.id），而不是原始题库ID，保存答案：

`PUT /attempts/{attemptId}/answers`

```json
{"questionId":11,"answer":"A"}
```

主观题answer直接传文字。POST /attempts/{id}/submit交卷。服务端截止后拒绝继续保存，定时任务约15秒内收卷。网络中断后GET /attempts/{id}恢复已保存答案与截止时间。

讲师GET /teaching/grading → GET /teaching/attempts/{id} → PUT /teaching/attempts/{id}/grade，提交questionId/score。成绩状态为PENDING_GRADE时不能直接认定通过。

切屏上报POST /attempts/{id}/events，body为`{"event":"BLUR"}`或FOCUS。该信号只用于风险核实，不作为自动扣分依据。

## 预警中心与措施

GET /warnings：管理员看全部，员工仅看自己。GET /warnings/{id}返回warning和actions时间线。

POST /warnings/{id}/actions：

```json
{"action":"INVESTIGATE","note":"已开始核对播放会话与异常证据"}
```

后续action为WARN/RELEARN/FALSE_POSITIVE，进入REVIEW；另一管理员CLOSE关闭。员工对自己的已关闭记录可APPEAL；管理员可REOPEN。

需要撤销已核实无效的会话区间时，在INVESTIGATING状态调用POST /warnings/{id}/invalidate-session，body为`{"action":"INVALIDATE_SESSION","note":"经核实，该会话的学习记录不应认定有效"}`。保留原区间并标记失效，重算学习和相关证书状态，记录前后数据。该操作不直接删除考试成绩。

## AI接口

- POST /courses/{id}/ai/ask，body `{"question":"本课程对操作前检查有什么要求？"}`；返回answer与资料id/title引用。仅已分配课程可用，正式考试中不可用。
- POST /courses/{id}/ai/question-drafts，body `{"count":5}`；仅课程作者/管理员可用，返回DRAFT题目。
- GET /courses/{id}/questions查看题库，人工校验后POST /questions/{id}/approve。审核通过的题目才允许组卷。

未配置服务返回503；课程无文字资料返回409；模型失败或格式错误返回502；调用过频返回429。不得把错误当作成功结果展示。

## 统计、作业、评价

GET /dashboards/personal只查本人；GET /dashboards/teaching只查自己课程；GET /dashboards/company按管理员或部门授权范围查询。公司看板detail是首200条预览，GET /dashboards/company/details?offset=200翻页。GET /reports/training.csv分批流式导出授权范围，不只导出首200条。

作业：POST /courses/{id}/assignments，body为title/instructions；POST /assignments/{id}/submissions，body为content/assetId；PUT /submissions/{id}/grade，body为score/feedback；GET /courses/{id}/coursework按身份返回自己或课程学员的提交。

完成课程后POST /courses/{id}/feedback提交rating(1至5)/comment。通知GET /notifications，POST /notifications/{id}/read标为已读。

## 完整接口索引

2026-10-27补充：`GET /api/teaching/exams` 返回当前用户安排的考试列表，管理员可查看全部，单次最多200条。此接口用于Vue“考试安排”和“培训计划关联考试”。

下表由本次控制器源码生成。参数详细字段以Requests.java记录类型及上述业务示例为准。

| 方法 | 路径（含/api） | JSON请求类型 |
| --- | --- | --- |
| GET | `/api/courses` | — |
| GET | `/api/courses/{id}` | — |
| GET | `/api/teaching/courses` | — |
| POST | `/api/courses` | Course |
| PUT | `/api/courses/{id}` | Course |
| POST | `/api/courses/{id}/lessons` | Lesson |
| POST | `/api/courses/{id}/materials` | Material |
| POST | `/api/courses/{id}/submit-review` | — |
| POST | `/api/courses/{id}/archive` | — |
| POST | `/api/admin/courses/{id}/restore` | — |
| GET | `/api/admin/courses/pending` | — |
| POST | `/api/admin/courses/{id}/review` | Review |
| GET | `/api/courses/{id}/reviews` | — |
| POST | `/api/courses/{id}/enroll` | — |
| GET | `/api/learning/courses` | — |
| GET | `/api/courses/{id}/progress` | — |
| GET | `/api/courses/{id}/learners` | — |
| POST | `/api/lessons/{id}/sessions` | — |
| POST | `/api/learning/sessions/{id}/events` | Heartbeat |
| POST | `/api/learning/stop` | — |
| GET | `/api/dashboards/personal` | — |
| GET | `/api/dashboards/teaching` | — |
| POST | `/api/questions` | Question |
| GET | `/api/courses/{id}/questions` | — |
| POST | `/api/questions/{id}/approve` | — |
| POST | `/api/papers` | Paper |
| GET | `/api/papers` | — |
| GET | `/api/papers/{id}` | — |
| POST | `/api/papers/{id}/publish` | — |
| GET | `/api/papers/import-template` | — |
| POST | `/api/papers/import` | — |
| POST | `/api/exams` | Exam |
| GET | `/api/exams` | — |
| POST | `/api/exams/{id}/start` | — |
| GET | `/api/attempts/{id}` | — |
| PUT | `/api/attempts/{id}/answers` | Answer |
| POST | `/api/attempts/{id}/submit` | — |
| POST | `/api/attempts/{id}/events` | ExamEvent |
| GET | `/api/teaching/grading` | — |
| GET | `/api/teaching/attempts/{id}` | — |
| PUT | `/api/teaching/attempts/{id}/grade` | Grade |
| GET | `/api/learning/wrong-answers` | — |
| POST | `/api/files` | — |
| POST | `/api/files/{id}/ticket` | — |
| GET | `/api/media/{id}` | — |
| GET | `/api/files/{id}` | — |
| GET | `/api/health` | — |
| POST | `/api/auth/login` | Login |
| POST | `/api/auth/logout` | — |
| PUT | `/api/auth/password` | Password |
| GET | `/api/me` | — |
| GET | `/api/organization` | — |
| GET | `/api/admin/users` | — |
| POST | `/api/admin/users` | User |
| PUT | `/api/admin/users/{id}/roles` | Role |
| PUT | `/api/admin/users/{id}` | UserProfile |
| PUT | `/api/admin/users/{id}/active` | Active |
| POST | `/api/admin/users/{id}/manager-scope` | Scope |
| DELETE | `/api/admin/users/{id}/manager-scope/{departmentId}` | — |
| POST | `/api/admin/departments` | Name |
| POST | `/api/admin/positions` | Name |
| POST | `/api/admin/grades` | Name |
| GET | `/api/admin/audits` | — |
| GET | `/api/warnings` | — |
| GET | `/api/warnings/{id}` | — |
| POST | `/api/warnings/{id}/actions` | WarningAction |
| POST | `/api/warnings/{id}/invalidate-session` | WarningAction |
| GET | `/api/notifications` | — |
| POST | `/api/notifications/{id}/read` | — |
| GET | `/api/dashboards/company` | — |
| GET | `/api/dashboards/company/details` | — |
| GET | `/api/reports/training.csv` | — |
| GET | `/api/courses/{id}/coursework` | — |
| POST | `/api/courses/{id}/assignments` | Assignment |
| POST | `/api/assignments/{id}/submissions` | Submission |
| PUT | `/api/submissions/{id}/grade` | AssignmentGrade |
| POST | `/api/courses/{id}/feedback` | Feedback |
| GET | `/api/courses/{id}/feedback` | — |
| POST | `/api/courses/{id}/ai/ask` | Ask |
| POST | `/api/courses/{id}/ai/question-drafts` | Draft |
| GET | `/api/paths` | — |
| POST | `/api/paths` | Path |
| GET | `/api/paths/{id}` | — |
| POST | `/api/paths/{id}/stages` | Stage |
| POST | `/api/paths/{id}/publish` | — |
| POST | `/api/paths/{id}/assign` | Members |
| POST | `/api/paths/{id}/certificate` | — |
| GET | `/api/certificates` | — |
| GET | `/api/plans` | — |
| POST | `/api/plans` | Plan |
| POST | `/api/plans/{id}/publish` | — |
| GET | `/api/plans/{id}` | — |
