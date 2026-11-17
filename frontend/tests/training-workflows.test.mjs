import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemo, DEMO_KEY } from '../src/lib/demo.js'

function fixture() {
  const memory = new Map()
  let time = Date.now()
  const storage = {
    getItem: (key) => memory.get(key),
    setItem: (key, value) => memory.set(key, value),
  }
  const db = createDemo(storage, () => time)
  storage.setItem(DEMO_KEY, JSON.stringify(db.read()))
  const call = (path, method = 'GET', body = {}, userId = 7) =>
    db.dispatch(path, { method, body, userId })
  const mutate = (change) => {
    const state = db.read()
    change(state)
    storage.setItem(DEMO_KEY, JSON.stringify(state))
  }
  const advance = (milliseconds) => (time += milliseconds)
  const grant = (courseId, userId = 7) =>
    call(
      '/admin/course-grants',
      'POST',
      { courseIds: [courseId], userIds: [userId], reason: '专项培训' },
      1,
    )
  return { db, storage, call, mutate, advance, grant, now: () => time }
}

test('自动创建员工账号唯一、初密只响应一次且不存储，普通员工不能创建', () => {
  const { call, db } = fixture()
  const users = [1, 2, 3].map((index) =>
    call(
      '/admin/employees',
      'POST',
      { displayName: '新员工' + index, departmentId: 2, positionId: 2 },
      1,
    ),
  )
  assert.equal(new Set(users.map((result) => result.account.username)).size, 3)
  for (const result of users) {
    assert.match(result.account.username, /^EMP[0-9A-F]{24}$/)
    assert.equal(result.account.temporaryPassword.length, 20)
    assert.match(result.account.temporaryPassword, /[A-Z]/)
    assert.match(result.account.temporaryPassword, /[a-z]/)
    assert.match(result.account.temporaryPassword, /[0-9]/)
    assert.equal(result.user.must_change_password, true)
    assert.equal(result.user.credentials_simulated, true)
    assert.equal(result.account.requiresPasswordChange, true)
    assert.deepEqual(result.user.roles, ['EMPLOYEE'])
    assert(!JSON.stringify(db.read()).includes(result.account.temporaryPassword))
    assert(!JSON.stringify(call('/admin/users', 'GET', {}, 1)).includes('temporaryPassword'))
  }
  assert.throws(() => call('/admin/employees', 'POST', { displayName: '越权' }), { status: 403 })
  assert.throws(
    () => call('/admin/employees', 'POST', { displayName: '错误部门', departmentId: 999 }, 1),
    { status: 400 },
  )
  assert.equal(db.read().users.length, 10)
})

test('纯资料课程显式确认才完成，打开详情与重复确认不会刷视频时长', () => {
  const { call, db, grant, mutate } = fixture()
  grant(8)
  mutate((state) =>
    state.materials.push({
      id: 100,
      course_id: 8,
      title: '可选拓展',
      content: '参考资料',
      material_type: 'TEXT',
      required: false,
    }),
  )
  const before = db.read().segments
  const original = call('/courses/8/learning-status')
  assert.equal(original.videoTotal, 0)
  assert.equal(original.materialTotal, 1)
  assert.equal(original.materials.length, 2)
  assert.equal(original.completed, false)
  call('/courses/8')
  assert.equal(call('/courses/8/learning-status').materialCompleted, 0)
  const requiredId = original.materials.find((row) => row.required).id
  call(`/materials/${requiredId}/acknowledge`, 'POST')
  call(`/materials/${requiredId}/acknowledge`, 'POST')
  const complete = call('/courses/8/learning-status')
  assert.equal(complete.completed, true)
  assert.equal(complete.percent, 100)
  assert.equal(complete.materialCompleted, 1)
  assert.equal(db.read().materialAcknowledgements.length, 1)
  assert.deepEqual(db.read().segments, before)
  assert.equal(
    db.read().enrollments.find((row) => row.course_id === 8 && row.user_id === 7).completed,
    true,
  )
  assert.throws(() => call(`/materials/${requiredId}/acknowledge`, 'POST', {}, 9), { status: 403 })
})

test('PDF、PPT附件与链接资料验证类型和归属，必读PDF可独立发布且文件正文不入演示数据', () => {
  const { call, db, grant } = fixture()
  const draft = call('/courses', 'POST', { title: '文档课程', description: '', category: '制度' })
  const file = call('/files', 'POST', {
    id: 'pdf1',
    name: '制度.pdf',
    content_type: 'application/pdf',
    size_bytes: 123,
  })
  const material = call(`/courses/${draft.id}/materials`, 'POST', {
    title: '制度PDF',
    assetId: file.id,
    materialType: 'PDF',
    required: true,
  })
  assert.equal(material.asset_id, 'pdf1')
  assert.equal(material.file_name, '制度.pdf')
  assert.equal(material.content_type, 'application/pdf')
  assert.equal(material.content, '')
  call(`/courses/${draft.id}/submit-review`, 'POST')
  call(`/admin/courses/${draft.id}/review`, 'POST', { approve: true, note: '文档审核通过' }, 1)
  grant(draft.id)
  call(`/materials/${material.id}/acknowledge`, 'POST')
  assert.equal(call(`/courses/${draft.id}/learning-status`).completed, true)
  const other = call(
    '/courses',
    'POST',
    { title: '另一文档', description: '', category: '制度' },
    8,
  )
  assert.throws(
    () =>
      call(
        `/courses/${other.id}/materials`,
        'POST',
        { title: '盗用附件', assetId: file.id, materialType: 'PDF' },
        8,
      ),
    { status: 403 },
  )
  for (const externalUrl of [
    'javascript:alert(1)',
    'file:///C:/a.pdf',
    'https://user:pass@example.com/doc',
  ]) {
    assert.throws(
      () =>
        call(
          `/courses/${other.id}/materials`,
          'POST',
          { title: '错误链接', materialType: 'LINK', externalUrl },
          8,
        ),
      { status: 400 },
    )
  }
  const link = call(
    `/courses/${other.id}/materials`,
    'POST',
    { title: '官方文档', materialType: 'LINK', externalUrl: 'https://example.com/training' },
    8,
  )
  assert.equal(link.external_url, 'https://example.com/training')
  call(
    '/files',
    'POST',
    {
      id: 'slides',
      name: '培训.pptx',
      content_type: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      size_bytes: 100,
    },
    8,
  )
  assert.equal(
    call(
      `/courses/${other.id}/materials`,
      'POST',
      { title: '培训PPT', materialType: 'PPT', assetId: 'slides', required: true },
      8,
    ).material_type,
    'PPT',
  )
  assert(!db.read().files.some((row) => 'content' in row || 'data' in row))
})

test('视频和必读资料必须同时完成，已读不会代替真实视频播放', () => {
  const { call, db, grant, advance } = fixture()
  const draft = call('/courses', 'POST', { title: '混合课程', description: '', category: '通用' })
  const lesson = call(`/courses/${draft.id}/lessons`, 'POST', {
    title: '视频',
    sortOrder: 1,
    durationSeconds: 30,
    videoAssetId: 'demo-video',
    coverageThreshold: 0.95,
    timeThreshold: 0.95,
  })
  const material = call(`/courses/${draft.id}/materials`, 'POST', {
    title: '必读说明',
    content: '阅读并理解操作要求。',
    materialType: 'TEXT',
    required: true,
  })
  call(`/courses/${draft.id}/submit-review`, 'POST')
  call(`/admin/courses/${draft.id}/review`, 'POST', { approve: true, note: '通过' }, 1)
  grant(draft.id)
  call(`/materials/${material.id}/acknowledge`, 'POST')
  assert.equal(call(`/courses/${draft.id}/learning-status`).completed, false)
  assert.equal(call(`/courses/${draft.id}/learning-status`).percent, 50)
  const session = call(`/lessons/${lesson.id}/sessions`, 'POST')
  call(`/learning/sessions/${session.sessionId}/events`, 'POST', {
    seq: 1,
    position: 0,
    event: 'PLAY',
    visible: true,
    rate: 1,
  })
  for (let step = 1; step <= 3; step++) {
    advance(10000)
    call(`/learning/sessions/${session.sessionId}/events`, 'POST', {
      seq: step + 1,
      position: step * 10,
      event: 'HEARTBEAT',
      visible: true,
      rate: 1,
    })
  }
  assert.equal(call(`/courses/${draft.id}/learning-status`).completed, true)
  assert.equal(
    db.read().enrollments.find((row) => row.course_id === draft.id && row.user_id === 7).completed,
    true,
  )
})

test('只有可选资料的无视频草稿不能发布，已下架课程不能确认已读', () => {
  const { call, mutate, grant } = fixture()
  const draft = call('/courses', 'POST', { title: '未完成草稿', description: '', category: '通用' })
  call(`/courses/${draft.id}/materials`, 'POST', {
    title: '参考',
    content: '可选内容',
    required: false,
  })
  assert.throws(() => call(`/courses/${draft.id}/submit-review`, 'POST'), { status: 400 })
  grant(8)
  mutate((state) => (state.courses.find((row) => row.id === 8).status = 'ARCHIVED'))
  assert.throws(() => call('/materials/8/acknowledge', 'POST'), { status: 403 })
})

test('计划提醒按周期去重，结果摘要按状态变化去重并送指定负责人', () => {
  const { call, db, mutate, advance, now } = fixture()
  mutate((state) => {
    state.plans[0].due_at = new Date(now() + 3600000).toISOString()
  })
  const first = call('/plans/1/remind', 'POST', {}, 8)
  assert.equal(first.pending_learners, 3)
  assert.equal(first.sent, 5)
  assert.equal(call('/plans/1/remind', 'POST', {}, 8).sent, 0)
  advance(24 * 3600000)
  assert.equal(call('/plans/1/remind', 'POST', {}, 8).sent, 4)
  mutate((state) => {
    state.enrollments.find((row) => row.user_id === 7 && row.course_id === 1).completed = true
  })
  assert.equal(call('/plans/1/remind', 'POST', {}, 8).sent, 1)
  const summaries = db
    .read()
    .notifications.filter((notice) => notice.title === '培训学习与考核结果更新 #1')
  assert.equal(summaries.length, 2)
  assert(summaries.every((notice) => notice.user_id === 8))
})

test('仅组织发展部可转交负责人，指定负责人可以配置提醒而普通员工不可访问', () => {
  const { call } = fixture()
  assert.throws(() => call('/plans/1/remind', 'POST'), { status: 403 })
  assert.throws(
    () =>
      call(
        '/plans/1/reminder-rules',
        'PUT',
        { responsibleUserId: 11, remindBeforeHours: 48, remindEveryHours: 12, pushResults: false },
        8,
      ),
    { status: 403 },
  )
  call(
    '/plans/1/reminder-rules',
    'PUT',
    { responsibleUserId: 11, remindBeforeHours: 48, remindEveryHours: 12, pushResults: false },
    1,
  )
  assert.equal(call('/teaching/plans/1', 'GET', {}, 11).can_manage_reminders, true)
  assert.throws(() => call('/plans/1/remind', 'POST', {}, 8), { status: 403 })
  call(
    '/plans/1/reminder-rules',
    'PUT',
    { remindBeforeHours: 24, remindEveryHours: 6, pushResults: true },
    11,
  )
})

test('简答空答也等待人工评分，部分阅卷不出总分，全部阅完才回写成绩且不可重评', () => {
  const { call } = fixture()
  const start = call('/exams/3/start', 'POST')
  const id = start.attempt.id
  assert(
    start.questions.every(
      (question) => !('answer' in question) && !('reference_answer' in question),
    ),
  )
  assert.throws(() => call(`/teaching/attempts/${id}`, 'GET', {}, 8), { status: 409 })
  call(`/attempts/${id}/answers`, 'PUT', { questionId: 301, answer: 'B' })
  call(`/attempts/${id}/answers`, 'PUT', {
    questionId: 302,
    answer: '先明确任务，再逐项核对事实。',
  })
  const submitted = call(`/attempts/${id}/submit`, 'POST')
  assert.equal(submitted.attempt.status, 'PENDING_GRADE')
  assert.equal(submitted.attempt.score, null)
  const view = call(`/teaching/attempts/${id}`, 'GET', {}, 8)
  assert(view.questions.find((question) => question.id === 302).reference_answer.length > 0)
  assert.equal(view.questions.find((question) => question.id === 303).student_answer, '')
  assert.equal(view.questions.find((question) => question.id === 303).earned_score, null)
  call(
    `/teaching/attempts/${id}/grade`,
    'PUT',
    { questionId: 302, score: 25, feedback: '方法清晰，可补充资料来源。' },
    8,
  )
  assert.equal(call(`/teaching/attempts/${id}`, 'GET', {}, 8).attempt.score, null)
  assert.throws(
    () => call(`/teaching/attempts/${id}/grade`, 'PUT', { questionId: 302, score: 20 }, 8),
    { status: 409 },
  )
  call(
    `/teaching/attempts/${id}/grade`,
    'PUT',
    { questionId: 303, score: 0, feedback: '本题未作答。' },
    8,
  )
  const graded = call(`/teaching/attempts/${id}`, 'GET', {}, 8)
  assert.equal(graded.attempt.status, 'GRADED')
  assert.equal(graded.attempt.score, 65)
  assert.equal(graded.attempt.passed, false)
  assert.equal(graded.questions.find((question) => question.id === 302).grader_id, 8)
  assert.equal(
    graded.questions.find((question) => question.id === 303).grading_feedback,
    '本题未作答。',
  )
  assert.throws(
    () => call(`/teaching/attempts/${id}/grade`, 'PUT', { questionId: 303, score: 30 }, 1),
    { status: 409 },
  )
})

test('阅卷权限包含试卷所有者，禁止其他老师和本人阅卷且分数必须为整数', () => {
  const { call, mutate } = fixture()
  const id = call('/exams/3/start', 'POST').attempt.id
  call(`/attempts/${id}/submit`, 'POST')
  mutate((state) => {
    state.users.find((row) => row.id === 9).roles.push('INSTRUCTOR')
    state.exams.find((row) => row.id === 3).owner_id = 1
  })
  assert.equal(call(`/teaching/attempts/${id}`, 'GET', {}, 8).attempt.id, id)
  assert.throws(() => call(`/teaching/attempts/${id}`, 'GET', {}, 9), { status: 403 })
  assert.throws(() => call(`/teaching/attempts/${id}`, 'GET'), { status: 403 })
  for (const score of [undefined, null, '20', 1.5, -1, 31]) {
    assert.throws(
      () => call(`/teaching/attempts/${id}/grade`, 'PUT', { questionId: 302, score }, 8),
      { status: 400 },
    )
  }
})

test('线下报名统一为待签到，兼容旧状态并与后端保持地点长度上限', () => {
  const { call, db, mutate, now } = fixture()
  assert.equal(call('/offline-sessions/1').attendance[0].status, 'REGISTERED')
  mutate(state => { state.offlineAttendance[0].status = 'PENDING' })
  assert.equal(call('/offline-sessions/1').attendance[0].status, 'REGISTERED')
  const body = { title: '下一场线下培训', instructorId: 8,
    startAt: new Date(now() + 3600000).toISOString(),
    endAt: new Date(now() + 7200000).toISOString(), location: '演示会议室', userIds: [7] }
  const created = call('/offline-sessions', 'POST', body, 1)
  assert.equal(call(`/offline-sessions/${created.id}`, 'GET', {}, 1).attendance[0].status, 'REGISTERED')
  const before = db.read().offlineSessions.length
  assert.throws(() => call('/offline-sessions', 'POST', { ...body, location: '室'.repeat(201) }, 1), { status: 400 })
  assert.equal(db.read().offlineSessions.length, before)
})

test('线下签到只记个人参训学时，不修改线上课程完成或视频有效时长', () => {
  const { call, db } = fixture()
  const before = db.read()
  call(
    '/offline-sessions/1/attendance',
    'PUT',
    {
      records: [
        { userId: 7, status: 'PRESENT', score: 92, note: '完成现场实操' },
        { userId: 9, status: 'ABSENT', note: '未到场' },
      ],
      reason: '按现场签到表登记',
    },
    8,
  )
  const personal = call('/me/offline-training')
  assert.equal(personal.credited_hours, 2)
  assert.equal(personal.attended_sessions, 1)
  assert.equal(personal.records[0].score, 92)
  assert.equal(call('/me/offline-training', 'GET', {}, 9).credited_hours, 0)
  assert.deepEqual(db.read().segments, before.segments)
  assert.deepEqual(db.read().enrollments, before.enrollments)
  assert(db.read().audit.some((row) => row.action === 'OFFLINE_ATTENDANCE'))
})

test('线下培训普通员工仅看本人记录，只有授课老师或管理员能看全员和签到', () => {
  const { call } = fixture()
  assert.equal(call('/offline-sessions/1').attendance.length, 1)
  assert.equal(call('/offline-sessions/1').attendance[0].user_id, 7)
  assert.equal(call('/offline-sessions/1', 'GET', {}, 8).attendance.length, 2)
  assert.equal(call('/offline-sessions/1', 'GET', {}, 1).can_manage, true)
  assert.throws(() => call('/offline-sessions/1', 'GET', {}, 10), { status: 403 })
  assert.throws(
    () =>
      call('/offline-sessions/1/attendance', 'PUT', {
        records: [{ userId: 7, status: 'PRESENT' }],
        reason: '自行登记',
      }),
    { status: 403 },
  )
  assert.throws(() => call('/offline-sessions/options'), { status: 403 })
})

test('线下培训整批验证防止部分写入，进行中签到结束后才计学时', () => {
  const { call, db, now, advance } = fixture()
  const original = db.read().offlineAttendance
  assert.throws(
    () =>
      call(
        '/offline-sessions/1/attendance',
        'PUT',
        {
          records: [
            { userId: 7, status: 'PRESENT' },
            { userId: 10, status: 'PRESENT' },
          ],
          reason: '混入未报名员工',
        },
        8,
      ),
    { status: 404 },
  )
  assert.deepEqual(db.read().offlineAttendance, original)
  const ongoing = call(
    '/offline-sessions',
    'POST',
    {
      title: '进行中的培训',
      instructorId: 8,
      courseId: 1,
      planId: 1,
      startAt: new Date(now() - 3600000).toISOString(),
      endAt: new Date(now() + 3600000).toISOString(),
      location: '培训室',
      userIds: [7],
    },
    8,
  )
  call(
    `/offline-sessions/${ongoing.id}/attendance`,
    'PUT',
    { records: [{ userId: 7, status: 'PRESENT' }], reason: '现场签到' },
    8,
  )
  assert.equal(call('/me/offline-training').credited_hours, 0)
  advance(2 * 3600000)
  assert.equal(call('/me/offline-training').credited_hours, 2)
})

test('内训师不能代其他老师安排培训或添加非本人课程学员', () => {
  const { call, db, now, mutate } = fixture()
  const body = {
    title: '授权范围检查',
    instructorId: 8,
    courseId: 1,
    startAt: new Date(now() + 3600000).toISOString(),
    endAt: new Date(now() + 7200000).toISOString(),
    location: '培训室',
    userIds: [7],
  }
  assert.throws(() => call('/offline-sessions', 'POST', { ...body, instructorId: 7 }, 8), {
    status: 403,
  })
  assert.throws(() => call('/offline-sessions', 'POST', { ...body, userIds: [1] }, 8), {
    status: 403,
  })
  mutate((state) => (state.courses.find((row) => row.id === 1).owner_id = 9))
  assert.throws(() => call('/offline-sessions', 'POST', body, 8), { status: 403 })
  assert.equal(db.read().offlineSessions.length, 1)
})

test('教学计划看板限制普通内训师只看本人课程，指定负责人和组织发展部看全计划', () => {
  const { call, mutate } = fixture()
  mutate((state) => {
    state.users.find((row) => row.id === 9).roles.push('INSTRUCTOR')
    state.users.find((row) => row.id === 10).roles.push('INSTRUCTOR')
    state.courses.find((row) => row.id === 7).owner_id = 9
    state.plans[0].courseIds.push(7)
  })
  const visible = call('/teaching/plans/1', 'GET', {}, 9)
  assert.deepEqual(
    visible.courses.map((row) => row.course.id),
    [7],
  )
  assert(visible.courses[0].learners.every((learner) => learner.exam_results.length === 0))
  assert.equal(visible.can_manage_reminders, false)
  assert.equal(call('/teaching/plans/1', 'GET', {}, 8).courses.length, 4)
  assert.equal(call('/teaching/plans/1', 'GET', {}, 1).courses.length, 4)
  assert.deepEqual(call('/teaching/plans', 'GET', {}, 10), [])
  assert.throws(() => call('/teaching/plans/1', 'GET', {}, 10), { status: 403 })
})

test('共享课程考试与无前置课程考试在教学计划中展示，待阅卷数量去重', () => {
  const { call, mutate } = fixture()
  mutate((state) => {
    const shared = state.exams.find((exam) => exam.id === 3)
    shared.courseIds = [1, 2]
    const general = { ...shared, id: 4, title: '计划综合简答考核', courseIds: [] }
    state.exams.push(general)
    state.plans[0].examIds.push(4)
    state.attempts.push(
      {
        id: 'shared-essay',
        exam_id: 3,
        user_id: 7,
        attempt_no: 1,
        status: 'PENDING_GRADE',
        score: null,
        passed: false,
        answers: [],
      },
      {
        id: 'general-essay',
        exam_id: 4,
        user_id: 7,
        attempt_no: 1,
        status: 'PENDING_GRADE',
        score: null,
        passed: false,
        answers: [],
      },
    )
  })
  const detail = call('/teaching/plans/1', 'GET', {}, 8)
  assert.equal(
    detail.plan_exams.find((exam) => exam.id === 4).results.find((result) => result.user_id === 7)
      .status,
    'PENDING_GRADE',
  )
  assert.equal(call('/teaching/plans', 'GET', {}, 8)[0].pending_grade, 2)
  assert(
    detail.courses
      .filter((row) => [1, 2].includes(row.course.id))
      .every((row) =>
        row.learners
          .find((learner) => learner.id === 7)
          .exam_results.some((exam) => exam.exam_id === 3),
      ),
  )
})

test('内训师不选关联课程也不能伪造到无授课或负责关系的培训计划', () => {
  const { call, mutate, now, db } = fixture()
  mutate((state) => {
    state.courses.find((row) => row.id === 7).owner_id = 9
    state.plans.push({
      id: 2,
      title: '另一位老师的计划',
      owner_id: 1,
      responsible_user_id: 9,
      status: 'PUBLISHED',
      courseIds: [7],
      examIds: [],
      userIds: [7],
      start_at: new Date(now() - 86400000).toISOString(),
      due_at: new Date(now() + 86400000).toISOString(),
    })
  })
  assert.throws(
    () =>
      call(
        '/offline-sessions',
        'POST',
        {
          title: '越权关联计划',
          planId: 2,
          instructorId: 8,
          startAt: new Date(now() + 3600000).toISOString(),
          endAt: new Date(now() + 7200000).toISOString(),
          location: '培训室',
          userIds: [7],
        },
        8,
      ),
    { status: 403 },
  )
  assert.equal(db.read().offlineSessions.length, 1)
})

test('旧浏览器数据补充新模块数组而不重置已有员工、学习和审批记录', () => {
  const { mutate, db, call } = fixture()
  mutate((state) => {
    delete state.materialAcknowledgements
    delete state.offlineSessions
    delete state.offlineAttendance
    delete state.planNotices
  })
  const before = db.read()
  call('/organization')
  const after = db.read()
  for (const key of ['users', 'enrollments', 'segments', 'learningRequests'])
    assert.deepEqual(after[key], before[key])
  assert.deepEqual(after.materialAcknowledgements, [])
  assert.deepEqual(after.offlineSessions, [])
})
