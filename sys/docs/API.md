# 后端接口与Vue联调

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
