import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemo, DEMO_KEY } from '../src/lib/demo.js'

function fixture() {
  const memory = new Map()
  const storage = {
    getItem: (key) => memory.get(key),
    setItem: (key, value) => memory.set(key, value),
  }
  const db = createDemo(storage)
  storage.setItem(DEMO_KEY, JSON.stringify(db.read()))
  const call = (path, method = 'GET', body = {}, userId = 11) =>
    db.dispatch(path, { method, body, userId })
  const mutate = (fn) => {
    const state = db.read()
    fn(state)
    storage.setItem(DEMO_KEY, JSON.stringify(state))
  }
  const submit = (body = {}) =>
    call('/learning-requests', 'POST', {
      courseIds: [7],
      userIds: [10],
      pathId: 1,
      reason: '岗位专项能力提升',
      ...body,
    })
  const review = (id, approve = true) =>
    call(`/learning-requests/${id}/review`, 'POST', { approve, note: '已核对学习需求' }, 1)
  return { db, storage, call, mutate, submit, review }
}

test('部门负责人选项仅含授权部门员工，普通员工不能读取或提交加课需求', () => {
  const { call } = fixture()
  const options = call('/learning-requests/options')
  assert.deepEqual(options.users.map((user) => user.id).sort(), [8, 10, 11].sort())
  assert.deepEqual(options.paths[0].user_ids, [10])
  assert(options.courses.some((course) => course.id === 7))
  assert(options.courses.every((course) => ![5, 6].includes(course.id)))
  for (const path of ['/learning-requests', '/learning-requests/options']) {
    assert.throws(() => call(path, 'GET', {}, 7), { status: 403 })
  }
  assert.throws(
    () => call('/learning-requests', 'POST', { courseIds: [7], userIds: [7], reason: '越权' }, 7),
    { status: 403 },
  )
})

test('申请不能伪造范围外员工，申请人所属部门不自动获得管理授权', () => {
  const { call, submit, mutate } = fixture()
  assert.throws(() => submit({ userIds: [10, 7] }), { status: 403 })
  assert.equal(call('/learning-requests').length, 0)
  mutate((state) => (state.users.find((user) => user.id === 11).scopes = []))
  assert.deepEqual(call('/learning-requests/options').users, [])
  assert.throws(() => submit(), { status: 403 })
})

test('待审核需求不会授予课程权限，只有审批通过的指定员工获得课程', () => {
  const { call, submit, review, db } = fixture()
  const request = submit({ courseIds: [7, 7], userIds: [10, 10] })
  assert.equal(request.status, 'PENDING')
  assert.equal(request.users.length, 1)
  assert.equal(request.courses.length, 1)
  assert.equal(request.path_title, '从新人成长为岗位骨干')
  assert.equal(request.requester_name, '赵负责人')
  assert.throws(() => call('/courses/7', 'GET', {}, 10), { status: 403 })
  assert(
    db
      .read()
      .notifications.some((notice) => notice.user_id === 1 && notice.title.includes('加课需求')),
  )
  const processed = review(request.id)
  assert.equal(processed.status, 'APPROVED')
  assert.equal(processed.reviewer_name, '林管理员')
  assert.equal(call('/courses/7', 'GET', {}, 10).course.id, 7)
  assert.throws(() => call('/courses/7', 'GET', {}, 7), { status: 403 })
  assert(!call('/courses', 'GET', {}, 7).some((course) => course.id === 7))
  assert(db.read().audit.some((entry) => entry.action === 'LEARNING_REQUEST_APPROVE'))
  assert(
    db
      .read()
      .notifications.some((notice) => notice.user_id === 10 && notice.title === '新增个人学习课程'),
  )
  assert.equal(
    db.read().paths[0].stages.some((stage) => stage.courseIds.includes(7)),
    false,
  )
})

test('驳回留下处理意见且不授权，需求不能重复审批或由部门负责人自行审批', () => {
  const { call, submit, review, db } = fixture()
  const request = submit()
  assert.throws(
    () =>
      call(`/learning-requests/${request.id}/review`, 'POST', { approve: true, note: '自行通过' }),
    { status: 403 },
  )
  const rejected = review(request.id, false)
  assert.equal(rejected.review_note, '已核对学习需求')
  assert.equal(rejected.status, 'REJECTED')
  assert.throws(() => review(request.id), { status: 409 })
  assert.equal(
    db.read().enrollments.some((row) => row.course_id === 7),
    false,
  )
})

test('审批前重新验证已撤销的管理范围、员工启用状态和课程发布状态', () => {
  for (const mutateBeforeReview of [
    (state) => (state.users.find((user) => user.id === 11).scopes = []),
    (state) => (state.users.find((user) => user.id === 11).roles = ['EMPLOYEE']),
    (state) => (state.users.find((user) => user.id === 10).active = false),
    (state) => (state.courses.find((course) => course.id === 7).status = 'ARCHIVED'),
    (state) => (state.paths[0].users = state.paths[0].users.filter((id) => id !== 10)),
  ]) {
    const { submit, mutate, review, db } = fixture()
    const request = submit()
    mutate(mutateBeforeReview)
    assert.throws(() => review(request.id))
    assert.equal(db.read().learningRequests[0].status, 'PENDING')
    assert.equal(
      db.read().enrollments.some((row) => row.course_id === 7),
      false,
    )
    assert(!db.read().notifications.some((row) => row.title === '新增个人学习课程'))
  }
})

test('部门负责人只看自己提交的申请，组织发展部可查看全部', () => {
  const { submit, call } = fixture()
  submit()
  call('/learning-requests', 'POST', { courseIds: [7], userIds: [7], reason: '另一项需求' }, 1)
  assert.equal(call('/learning-requests').length, 1)
  assert.equal(call('/learning-requests', 'GET', {}, 1).length, 2)
})

test('个人课程授权保留原有完成成绩，管理员可以直接授权具体某个人', () => {
  const { call, db } = fixture()
  assert.throws(
    () => call('/admin/course-grants', 'POST', { courseIds: [7], userIds: [10], reason: '越权' }),
    { status: 403 },
  )
  const completed = db.read().enrollments.find((row) => row.user_id === 10 && row.course_id === 2)
  const result = call(
    '/admin/course-grants',
    'POST',
    { courseIds: [2, 7], userIds: [10], reason: '定向培训' },
    1,
  )
  assert.deepEqual(result, { user_count: 1, course_count: 2 })
  assert.deepEqual(
    db.read().enrollments.find((row) => row.user_id === 10 && row.course_id === 2),
    completed,
  )
  assert.equal(
    db.read().enrollments.filter((row) => row.user_id === 10 && row.course_id === 7).length,
    1,
  )
  assert(!db.read().enrollments.some((row) => row.user_id === 7 && row.course_id === 7))
})

test('批量直接授权先全量验证，包含未发布课程或停用员工时不得部分授权', () => {
  for (const input of [
    { courseIds: [7, 5], userIds: [10] },
    { courseIds: [7], userIds: [10, 999] },
  ]) {
    const { call, db } = fixture()
    assert.throws(() => call('/admin/course-grants', 'POST', { ...input, reason: '批量操作' }, 1))
    assert.equal(
      db.read().enrollments.some((row) => row.course_id === 7),
      false,
    )
    assert(!db.read().audit.some((row) => row.action === 'COURSE_GRANT'))
  }
})

test('申请必须提供有效人员、课程与原因，选择路线时员工必须已经分配该路线', () => {
  const { submit, call } = fixture()
  for (const body of [
    { courseIds: [] },
    { userIds: [] },
    { userIds: [null] },
    { courseIds: ['7'] },
    { reason: '  ' },
    { reason: 'x'.repeat(1001) },
    { courseIds: [5] },
    { pathId: 1, userIds: [8] },
    { pathId: -1 },
  ])
    assert.throws(() => submit(body))
  assert.equal(call('/learning-requests').length, 0)
})

test('部门看板只返回授权部门的人员明细、计划人数和部门统计', () => {
  const { call } = fixture()
  const data = call('/dashboards/company')
  assert.deepEqual(
    data.departments.map((department) => department.id),
    [2],
  )
  assert(data.details.every((row) => row.department_id === 2))
  assert.equal(data.plans[0].expected_people, 1)
  assert.deepEqual(data.plans[0].userIds, [10])
})

test('部门加岗位分配拒绝筛选外员工、路线岗位不符和停用人员', () => {
  const { call, mutate, db } = fixture()
  const route = call('/paths', 'POST', { title: '专业专项路线', positionId: 2 }, 1)
  call(`/paths/${route.id}/stages`, 'POST', { title: '专业入门', sortOrder: 1, courseIds: [7] }, 1)
  call(`/paths/${route.id}/publish`, 'POST', {}, 1)
  for (const body of [
    { departmentId: 1, positionId: 2, userIds: [10] },
    { departmentId: 2, positionId: 1, userIds: [10] },
    { departmentId: 1, positionId: 1, userIds: [7] },
    { departmentId: 2, userIds: [10] },
    { userIds: [10] },
  ])
    assert.throws(() => call(`/paths/${route.id}/assign`, 'POST', body, 1))
  assert.equal(
    db.read().enrollments.some((row) => row.course_id === 7),
    false,
  )
  mutate((state) => (state.users.find((user) => user.id === 10).active = false))
  assert.throws(() =>
    call(`/paths/${route.id}/assign`, 'POST', { departmentId: 2, positionId: 2, userIds: [10] }, 1),
  )
  mutate((state) => (state.users.find((user) => user.id === 10).active = true))
  call(`/paths/${route.id}/assign`, 'POST', { departmentId: 2, positionId: 2, userIds: [10] }, 1)
  assert.deepEqual(call(`/paths/${route.id}`, 'GET', {}, 1).path.users, [10])
})

test('岗位分类与员工资料编辑保留身份和学习记录，并拒绝不存在的组织编号', () => {
  const { call, db } = fixture()
  const before = db.read().enrollments
  const updated = call(
    '/admin/positions/2',
    'PUT',
    { name: '培训规划岗', positionType: 'MANAGEMENT' },
    1,
  )
  assert.deepEqual(updated, { id: 2, name: '培训规划岗', position_type: 'MANAGEMENT' })
  call(
    '/admin/users/10',
    'PUT',
    { displayName: '李昂', departmentId: 2, positionId: 3, gradeId: null },
    1,
  )
  assert.equal(db.read().users.find((user) => user.id === 10).position_id, 3)
  assert.deepEqual(db.read().users.find((user) => user.id === 10).roles, ['EMPLOYEE'])
  assert.deepEqual(db.read().enrollments, before)
  assert.throws(() => call('/admin/users/10', 'PUT', { displayName: '李昂', positionId: 999 }, 1), {
    status: 400,
  })
  assert.throws(
    () => call('/admin/positions/2', 'PUT', { name: '岗位', positionType: 'UNKNOWN' }, 1),
    { status: 400 },
  )
})

test('授权范围可撤销且审批立即生效，角色撤销后刷新不会被演示升级恢复', () => {
  const { call, submit, review, db, storage } = fixture()
  const request = submit()
  call('/admin/users/11/manager-scope/2', 'DELETE', {}, 1)
  assert.deepEqual(
    call('/admin/users', 'GET', {}, 1).find((user) => user.id === 11).manager_department_ids,
    [],
  )
  assert.throws(() => review(request.id), { status: 403 })
  call('/admin/users/11/roles', 'PUT', { role: 'MANAGER', enabled: false }, 1)
  assert.deepEqual(
    createDemo(storage)
      .read()
      .users.find((user) => user.id === 11).roles,
    ['EMPLOYEE'],
  )
  assert.equal(db.read().users.filter((user) => user.username === 'zhao_manager').length, 1)
})

test('旧演示数据安全升级岗位类型和部门负责人，不覆盖已占用编号或重置学习记录', () => {
  const { db, mutate, storage } = fixture()
  mutate((state) => {
    delete state.learningRequests
    delete state.learningRequestVersion
    state.positions.forEach((position) => delete position.position_type)
    const user = state.users.find((row) => row.id === 11)
    user.username = 'existing_employee'
    user.roles = ['EMPLOYEE']
    user.display_name = '原有员工'
  })
  const previousEnrollments = db.read().enrollments
  const upgraded = createDemo(storage).read()
  assert.equal(upgraded.users.find((user) => user.id === 11).display_name, '原有员工')
  assert.notEqual(upgraded.users.find((user) => user.username === 'zhao_manager').id, 11)
  assert.equal(upgraded.positions.find((position) => position.id === 2).name, '培训讲师')
  assert.equal(upgraded.positions.find((position) => position.id === 2).position_type, 'GENERAL')
  assert.deepEqual(
    new Set(upgraded.positions.map((position) => position.position_type)),
    new Set(['PROFESSIONAL', 'MANAGEMENT', 'GENERAL']),
  )
  assert.deepEqual(upgraded.enrollments, previousEnrollments)
  assert.deepEqual(upgraded.learningRequests, [])
})
