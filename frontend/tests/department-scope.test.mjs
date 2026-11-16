import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemo, DEMO_KEY } from '../src/lib/demo.js'
import {
  departmentScopeLabel,
  normalizeDepartmentScope,
  userMatchesDepartmentScope,
} from '../src/lib/department-scope.js'

function fixture() {
  const memory = new Map()
  const storage = {
    getItem: (key) => memory.get(key),
    setItem: (key, value) => memory.set(key, value),
  }
  const db = createDemo(storage)
  const call = (path, method = 'GET', body = {}, userId = 1) =>
    db.dispatch(path, { method, body, userId })
  const mutate = (fn) => {
    const state = db.read()
    fn(state)
    storage.setItem(DEMO_KEY, JSON.stringify(state))
  }
  return { db, storage, call, mutate }
}

function publish(call, path) {
  call(`/paths/${path.id}/stages`, 'POST', { title: '基础阶段', sortOrder: 1, courseIds: [1] })
  call(`/paths/${path.id}/publish`, 'POST')
}

test('省略部门字段的旧创建请求默认所有部门', () => {
  const { call } = fixture()
  const created = call('/paths', 'POST', { title: '全员路线', positionId: null, gradeId: null })
  assert.equal(created.all_departments, true)
  assert.deepEqual(created.department_ids, [])
  assert.deepEqual(call(`/paths/${created.id}`).path.departments, [])
})

test('指定多个部门保存并去重，列表、详情和重新读取均保留部门名称', () => {
  const { call, storage } = fixture()
  const created = call('/paths', 'POST', {
    title: '跨部门协作路线',
    allDepartments: false,
    departmentIds: [2, 1, 2],
  })
  assert.equal(created.all_departments, false)
  assert.deepEqual(created.department_ids, [1, 2])
  assert.deepEqual(
    created.departments.map((department) => department.name),
    ['总经办', '运营管理部'],
  )
  assert.deepEqual(call('/paths').find((path) => path.id === created.id).department_ids, [1, 2])
  const reloaded = createDemo(storage).dispatch(`/paths/${created.id}`, { userId: 1 }).path
  assert.equal(departmentScopeLabel(reloaded), '总经办、运营管理部')
})

test('指定部门空选、无效部门或所有部门与指定ID矛盾时拒绝创建', () => {
  const { call } = fixture()
  for (const scope of [
    { allDepartments: false, departmentIds: [] },
    { allDepartments: false, departmentIds: [999] },
    { allDepartments: false, departmentIds: [-1] },
    { allDepartments: false, departmentIds: ['1'] },
    { allDepartments: true, departmentIds: [1] },
  ]) {
    assert.throws(() => call('/paths', 'POST', { title: '不合法范围', ...scope }), { status: 400 })
  }
  assert.equal(call('/paths').length, 1)
})

test('只提供非空部门列表时推断为指定部门', () => {
  const { call } = fixture()
  const created = call('/paths', 'POST', { title: '部门路线', departmentIds: [1] })
  assert.equal(created.all_departments, false)
  assert.deepEqual(created.department_ids, [1])
})

test('旧浏览器数据缺少部门元数据仍按所有部门读取和分配', () => {
  const { call, mutate } = fixture()
  mutate((state) => {
    delete state.paths[0].all_departments
    delete state.paths[0].department_ids
    state.paths[0].position_id = null
  })
  assert.equal(call('/paths')[0].all_departments, true)
  assert.deepEqual(call('/paths/1').path.department_ids, [])
  call('/paths/1/assign', 'POST', { departmentId: 2, positionId: 2, userIds: [8] })
  assert.ok(call('/paths/1').path.users.includes(8))
})

test('指定部门分配先整体校验，混入越界员工不能部分分配或新增任务', () => {
  const { call, db, mutate } = fixture()
  mutate((state) => (state.users.find((user) => user.id === 1).position_id = 1))
  const route = call('/paths', 'POST', {
    title: '总经办路线',
    allDepartments: false,
    departmentIds: [1],
  })
  publish(call, route)
  assert.throws(
    () =>
      call(`/paths/${route.id}/assign`, 'POST', {
        departmentId: 1,
        positionId: 1,
        userIds: [1, 8],
      }),
    /适用部门范围/,
  )
  assert.deepEqual(call(`/paths/${route.id}`).path.users, [])
  assert.equal(
    db.read().enrollments.some((row) => row.user_id === 1 && row.course_id === 1),
    false,
  )
  call(`/paths/${route.id}/assign`, 'POST', { departmentId: 1, positionId: 1, userIds: [1, 7, 7] })
  assert.deepEqual(call(`/paths/${route.id}`).path.users, [1, 7])
  assert.equal(
    db.read().enrollments.filter((row) => row.user_id === 1 && row.course_id === 1).length,
    1,
  )
})

test('所有部门动态覆盖新增部门，未设置部门员工可由管理员单独授权课程', () => {
  const { call, mutate, db } = fixture()
  const route = call('/paths', 'POST', {
    title: '全员共同学习',
    allDepartments: true,
    departmentIds: [],
  })
  publish(call, route)
  mutate((state) => {
    state.departments.push({ id: 3, name: '新部门' })
    state.users.find((user) => user.id === 7).department_id = 3
    state.users.find((user) => user.id === 8).department_id = null
  })
  assert.equal(
    userMatchesDepartmentScope(
      db.read().users.find((user) => user.id === 8),
      route,
    ),
    true,
  )
  call(`/paths/${route.id}/assign`, 'POST', { departmentId: 3, positionId: 1, userIds: [7] })
  assert.deepEqual(call(`/paths/${route.id}`).path.users, [7])
  assert.throws(
    () =>
      call(`/paths/${route.id}/assign`, 'POST', { departmentId: 3, positionId: 2, userIds: [8] }),
    { status: 400 },
  )
  call('/admin/course-grants', 'POST', { courseIds: [1], userIds: [8], reason: '未设部门个人授权' })
  assert.ok(db.read().enrollments.some((row) => row.user_id === 8 && row.course_id === 1))
})

test('部门范围辅助函数正确区分所有、指定与空选，并保留原有管理权限', () => {
  const catalog = [
    { id: 1, name: '总经办' },
    { id: 2, name: '运营管理部' },
  ]
  const all = normalizeDepartmentScope(true, [], catalog)
  const one = normalizeDepartmentScope(false, [1], catalog)
  assert.equal(departmentScopeLabel(all), '所有部门')
  assert.equal(userMatchesDepartmentScope({ department_id: null }, all), true)
  assert.equal(userMatchesDepartmentScope({ department_id: 2 }, one), false)
  assert.equal(userMatchesDepartmentScope({ department_id: 1 }, one), true)
  assert.throws(() => normalizeDepartmentScope(false, null, catalog), /至少选择/)
  assert.throws(
    () => fixture().call('/paths', 'POST', { title: '越权创建', allDepartments: true }, 7),
    { status: 403 },
  )
})
