import test from 'node:test'
import assert from 'node:assert/strict'
import { createDemo } from '../src/lib/demo.js'
import { coverage, credit, allowed, sameChoices } from '../src/lib/rules.js'
function fixture() {
  const memory = new Map()
  let time = Date.now()
  const db = createDemo(
    { getItem: (k) => memory.get(k), setItem: (k, v) => memory.set(k, v) },
    () => time,
  )
  const call = (path, method = 'GET', body = {}, userId = 7) =>
    db.dispatch(path, { method, body, userId })
  return { db, call, advance: (ms) => (time += ms) }
}
test('普通员工只能获取自己的课程和个人数据', () => {
  const { call } = fixture()
  assert.equal(call('/me').roles.includes('ADMIN'), false)
  assert.throws(() => call('/dashboards/company'), /管理员/)
  assert.throws(() => call('/admin/users'), /管理员/)
  assert.throws(() => call('/courses/1/learners'), /自己的课程/)
  assert.equal(call('/courses').length, 4)
})
test('员工创建课程后通过独立管理员审核，同账号兼任讲师', () => {
  const { call } = fixture()
  const c = call('/courses', 'POST', {
    title: '员工经验',
    description: '流程',
    category: '经验分享',
  })
  call('/courses/' + c.id + '/lessons', 'POST', {
    title: '第一讲',
    sortOrder: 1,
    durationSeconds: 30,
    videoAssetId: 'demo-video',
    coverageThreshold: 0.95,
    timeThreshold: 0.95,
  })
  call('/courses/' + c.id + '/submit-review', 'POST')
  assert.throws(
    () =>
      call('/admin/courses/' + c.id + '/review', 'POST', { approve: true, note: '审核通过' }, 7),
    /管理员/,
  )
  call('/admin/courses/' + c.id + '/review', 'POST', { approve: true, note: '已核验' }, 1)
  assert.ok(call('/me').roles.includes('INSTRUCTOR'))
  assert.ok(call('/teaching/courses').some((x) => x.id === c.id))
  assert.ok(call('/notifications').some((x) => x.title.includes('通过')))
})
test('讲师不能读取其他讲师的课程学员', () => {
  const { call } = fixture()
  assert.throws(() => call('/courses/5/learners', 'GET', {}, 8), /自己的课程/)
  assert.ok(call('/courses/1/learners', 'GET', {}, 8).length)
})
test('未完成前置课程不能绕过入口开考', () => {
  const { call } = fixture()
  assert.throws(() => call('/exams/2/start', 'POST'), /前置课程/)
  assert.equal(call('/exams').find((x) => x.exam.id === 2).zone, 'LEARNING_REQUIRED')
})
test('有效课程完成后可考试，题目响应不暴露正确答案和解析', () => {
  const { call } = fixture()
  const exam = call('/exams/1/start', 'POST')
  assert.equal(exam.questions.length, 2)
  assert.ok(exam.questions.every((q) => !('answer' in q) && !('explanation' in q)))
  assert.throws(() => call('/attempts/' + exam.attempt.id, 'GET', {}, 9), /他人/)
  call('/attempts/' + exam.attempt.id + '/answers', 'PUT', { questionId: 101, answer: 'B' })
  call('/attempts/' + exam.attempt.id + '/answers', 'PUT', { questionId: 102, answer: 'C,A,B' })
  const result = call('/attempts/' + exam.attempt.id + '/submit', 'POST')
  assert.equal(result.attempt.score, 100)
  assert.equal(result.attempt.passed, true)
  assert.equal(call('/attempts/' + exam.attempt.id + '/submit', 'POST').attempt.score, 100)
})
test('服务端式截止时间约束和考试期间答疑禁用', () => {
  const { call, advance } = fixture()
  const { attempt } = call('/exams/1/start', 'POST')
  assert.throws(() => call('/courses/2/ai/ask', 'POST', { question: '答案' }), /考试期间/)
  advance(16 * 60000)
  assert.throws(
    () => call('/attempts/' + attempt.id + '/answers', 'PUT', { questionId: 101, answer: 'B' }),
    /已结束/,
  )
  assert.equal(call('/attempts/' + attempt.id).attempt.status, 'GRADED')
})
test('单次拖动只记事件，频繁向前跳播才形成预警', () => {
  const { call, advance } = fixture()
  const { sessionId } = call('/lessons/12/sessions', 'POST')
  const event = (seq, pos, type) =>
    call('/learning/sessions/' + sessionId + '/events', 'POST', {
      seq,
      position: pos,
      event: type,
      visible: true,
      rate: 1,
    })
  event(1, 0, 'PLAY')
  event(2, 10, 'SEEK')
  assert.equal(call('/warnings').length, 0)
  event(3, 20, 'SEEK')
  event(4, 29, 'SEEK')
  assert.equal(call('/warnings').length, 1)
  assert.equal(call('/courses/1/progress').find((x) => x.id === 12).effective_seconds, 0)
})
test('多开播放被拒绝且提醒管理员，重复心跳不重复计时', () => {
  const { call, advance } = fixture()
  const { sessionId } = call('/lessons/12/sessions', 'POST')
  assert.equal(call('/lessons/12/sessions', 'POST').conflict, true)
  assert.ok(call('/warnings', 'GET', {}, 1).some((w) => w.type === 'MULTI_PLAY'))
  call('/learning/sessions/' + sessionId + '/events', 'POST', {
    seq: 1,
    position: 0,
    event: 'PLAY',
    visible: true,
    rate: 1,
  })
  advance(10000)
  const body = { seq: 2, position: 10, event: 'HEARTBEAT', visible: true, rate: 1 }
  call('/learning/sessions/' + sessionId + '/events', 'POST', body)
  call('/learning/sessions/' + sessionId + '/events', 'POST', body)
  assert.equal(call('/courses/1/progress').find((l) => l.id === 12).effective_seconds, 10)
})
test('预警措施可追溯，同一管理员不得自行复核关闭', () => {
  const { call } = fixture()
  call('/warnings/1/actions', 'POST', { action: 'INVESTIGATE', note: '核对播放事件' }, 1)
  call('/warnings/1/actions', 'POST', { action: 'WARN', note: '已联系员工说明规则' }, 1)
  assert.throws(
    () => call('/warnings/1/actions', 'POST', { action: 'CLOSE', note: '本人确认' }, 1),
    /另一名/,
  )
  call('/warnings/1/actions', 'POST', { action: 'CLOSE', note: '独立核验完成' }, 2)
  const detail = call('/warnings/1', 'GET', {}, 1)
  assert.equal(detail.actions.length, 3)
  assert.equal(detail.warning.status, 'CLOSED')
})
test('AI演示明确标记固定示例，草稿必须审核后组卷', () => {
  const { call } = fixture()
  assert.match(
    call('/courses/1/ai/ask', 'POST', { question: '要点' }).answer,
    /演示回答，未调用大模型/,
  )
  const q = call('/courses/1/ai/question-drafts', 'POST', { count: 1 }, 8)[0]
  assert.equal(q.status, 'DRAFT')
  assert.throws(
    () => call('/papers', 'POST', { title: '试卷', questionIds: [q.id], scores: [100] }, 8),
    /审核/,
  )
  call('/questions/' + q.id + '/approve', 'POST', {}, 8)
  assert.ok(call('/papers', 'POST', { title: '试卷', questionIds: [q.id], scores: [100] }, 8).id)
})
test('纯规则：片段去重、暂停后台不计时、多选完全匹配、角色范围', () => {
  assert.equal(
    coverage([
      { start: 0, end: 60 },
      { start: 0, end: 60 },
    ]),
    60,
  )
  assert.equal(
    credit({
      playing: true,
      previous: 0,
      position: 29,
      elapsed: 10000,
      visible: true,
      rate: 1,
      event: 'SEEK',
    }),
    0,
  )
  assert.equal(
    credit({
      playing: true,
      previous: 0,
      position: 10,
      elapsed: 10000,
      visible: false,
      rate: 1,
      event: 'HEARTBEAT',
    }),
    0,
  )
  assert.equal(sameChoices('A,C', 'C,A'), true)
  assert.equal(sameChoices('A,C', 'A'), false)
  assert.equal(allowed(['EMPLOYEE'], ['ADMIN']), false)
})
