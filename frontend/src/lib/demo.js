import { seed } from './seed.js'
import { coverage, credit, sameChoices } from './rules.js'
export const DEMO_KEY = 'xuri-demo-v1'
export function createDemo(storage, now = () => Date.now()) {
  const read = () => {
    try {
      return JSON.parse(storage.getItem(DEMO_KEY)) || seed()
    } catch {
      return seed()
    }
  }
  const save = (s) => storage.setItem(DEMO_KEY, JSON.stringify(s))
  const fail = (message, status = 409) => {
    const e = new Error(message)
    e.status = status
    throw e
  }
  function dispatch(path, { method = 'GET', body = {}, userId = 7 } = {}) {
    const s = read(),
      me = s.users.find((u) => u.id === Number(userId))
    if (!me || !me.active) fail('当前演示账号不可用', 401)
    const isAdmin = me.roles.includes('ADMIN'),
      id = Number(path.match(/\/(\d+)(?:\/|$)/)?.[1]),
      iso = () => new Date(now()).toISOString(),
      next = (list) => Math.max(0, ...list.map((x) => Number(x.id) || 0)) + 1
    const admin = () => {
      if (!isAdmin) fail('需要管理员权限', 403)
    }
    const course = (c) => s.courses.find((x) => x.id === Number(c)) || fail('课程不存在', 404)
    const manage = (c) => {
      const c0 = course(c)
      if (!isAdmin && c0.owner_id !== me.id) fail('只能管理自己的课程', 403)
      return c0
    }
    const notice = (user, title, text) =>
      s.notifications.push({
        id: next(s.notifications),
        user_id: user,
        title,
        body: text,
        created_at: iso(),
        read_at: null,
      })
    const log = (action, target, detail) =>
      s.audit.push({
        id: next(s.audit),
        actor_id: me.id,
        action,
        target,
        detail,
        created_at: iso(),
      })
    const progress = (user, c) =>
      s.lessons
        .filter((l) => l.course_id === c)
        .map((l) => {
          const seg = s.segments.filter(
              (t) => t.user_id === user && t.lesson_id === l.id && t.valid !== false,
            ),
            covered = coverage(seg),
            effective = seg.reduce((sum, x) => sum + x.end - x.start, 0)
          return {
            ...l,
            effective_seconds: effective,
            covered_seconds: covered,
            completed:
              covered >= l.duration_seconds * l.coverage_threshold &&
              effective >= l.duration_seconds * l.time_threshold
                ? 1
                : 0,
          }
        })
    const enrolled = (u, c) => s.enrollments.find((e) => e.user_id === u && e.course_id === c)
    const done = (u, c) => !!enrolled(u, c)?.completed
    const grant = (u, c) => {
      if (!enrolled(u, c))
        s.enrollments.push({ user_id: u, course_id: c, completed: false, enrolled_at: iso() })
    }
    const examsFor = (stage, u) =>
      s.exams
        .filter((e) => e.stage_id === stage)
        .map((e) => ({
          ...e,
          passed: s.attempts.some((a) => a.exam_id === e.id && a.user_id === u && a.passed) ? 1 : 0,
        }))
    const stagePassed = (stage, u) =>
      stage.courseIds.length > 0 &&
      stage.courseIds.every((c) => done(u, c)) &&
      examsFor(stage.id, u).every((e) => e.passed)
    const warning = (type, c, session, evidence) => {
      const old = s.warnings.find(
        (w) =>
          w.user_id === me.id &&
          w.type === type &&
          w.status !== 'CLOSED' &&
          now() - Date.parse(w.created_at) < 300000,
      )
      if (old) {
        old.occurrences++
        old.updated_at = iso()
        return
      }
      const w = {
        id: next(s.warnings),
        user_id: me.id,
        course_id: c,
        session_id: session,
        type,
        severity: 'HIGH',
        status: 'OPEN',
        evidence,
        occurrences: 1,
        created_at: iso(),
        updated_at: iso(),
        actions: [],
      }
      s.warnings.push(w)
      s.users
        .filter((u) => u.roles.includes('ADMIN'))
        .forEach((u) => notice(u.id, '学习异常预警 #' + w.id, type + '，请核实事件记录。'))
    }
    const ownAttempt = (a) => {
      const t = s.attempts.find((x) => x.id === a) || fail('考试记录不存在', 404)
      if (t.user_id !== me.id) fail('无权访问他人的考试', 403)
      return t
    }
    const finish = (a) => {
      if (a.status !== 'IN_PROGRESS') return
      const e = s.exams.find((x) => x.id === a.exam_id),
        p = s.papers.find((x) => x.id === e.paper_id)
      let score = 0,
        pending = false
      for (const q of p.items) {
        const ans = a.answers.find((x) => x.paper_question_id === q.id) || {
          paper_question_id: q.id,
          answer: '',
        }
        if (!a.answers.includes(ans)) a.answers.push(ans)
        if (q.snapshot.type === 'TEXT' && ans.answer) {
          pending = true
          ans.score = null
        } else {
          ans.score =
            q.snapshot.type !== 'TEXT' && sameChoices(q.snapshot.answer, ans.answer) ? q.score : 0
          score += ans.score
        }
      }
      a.status = pending ? 'PENDING_GRADE' : 'GRADED'
      a.score = pending ? null : score
      a.passed = !pending && score >= e.pass_score
      a.submitted_at_ms = now()
    }
    const attemptView = (a) => {
      const e = s.exams.find((x) => x.id === a.exam_id),
        p = s.papers.find((x) => x.id === e.paper_id)
      return {
        attempt: { ...a, answers: undefined },
        exam: e,
        questions: p.items.map((q) => ({
          id: q.id,
          score: q.score,
          type: q.snapshot.type,
          stem: q.snapshot.stem,
          options: JSON.parse(q.snapshot.options_json),
        })),
        answers: a.answers,
      }
    }
    let result
    if (path === '/me')
      result = {
        user: me,
        roles: me.roles,
        workspaces: [
          'LEARNING',
          'CONTENT_CREATION',
          ...(me.roles.includes('INSTRUCTOR') ? ['TEACHING'] : []),
          ...(isAdmin ? ['ADMINISTRATION'] : []),
        ],
      }
    else if (path === '/organization')
      result = { departments: s.departments, positions: s.positions, grades: s.grades }
    else if (path === '/courses' && method === 'GET')
      result = s.courses.filter(
        (c) => c.status === 'PUBLISHED' && (isAdmin || enrolled(me.id, c.id)),
      )
    else if (path === '/courses' && method === 'POST') {
      result = { ...body, id: next(s.courses), owner_id: me.id, status: 'DRAFT', tone: 'craft' }
      s.courses.push(result)
      log('COURSE_CREATE', result.id, body.title)
    } else if (path === '/teaching/courses') result = s.courses.filter((c) => c.owner_id === me.id)
    else if (path === '/admin/courses/pending') {
      admin()
      result = s.courses.filter((c) => c.status === 'PENDING')
    } else if (/^\/admin\/courses\/\d+\/review$/.test(path)) {
      admin()
      const c = course(id)
      if (c.owner_id === me.id) fail('不能审核自己的课程')
      if (c.status !== 'PENDING') fail('课程不处于待审核状态')
      c.status = body.approve ? 'PUBLISHED' : 'REJECTED'
      c.review_note = body.note
      s.reviews.push({
        id: next(s.reviews),
        course_id: id,
        actor_id: me.id,
        action: body.approve ? 'APPROVE' : 'REJECT',
        note: body.note,
        created_at: iso(),
      })
      if (body.approve) {
        const u = s.users.find((u) => u.id === c.owner_id)
        if (!u.roles.includes('INSTRUCTOR')) u.roles.push('INSTRUCTOR')
      }
      notice(c.owner_id, body.approve ? '课程审核通过' : '课程审核驳回', body.note)
      log('COURSE_REVIEW', id, body.note)
    } else if (/^\/courses\/\d+$/.test(path)) {
      const c = course(id)
      if (method === 'PUT') {
        manage(id)
        if (!['DRAFT', 'REJECTED'].includes(c.status)) fail('已发布课程不可编辑')
        Object.assign(c, body, { status: 'DRAFT' })
        result = c
      } else {
        if (!isAdmin && c.owner_id !== me.id && !(c.status === 'PUBLISHED' && enrolled(me.id, id)))
          fail('课程未分配或尚未发布', 403)
        result = {
          course: c,
          lessons: s.lessons.filter((l) => l.course_id === id),
          materials: s.materials.filter((m) => m.course_id === id),
        }
      }
    } else if (/^\/courses\/\d+\/lessons$/.test(path)) {
      const c = manage(id)
      if (!['DRAFT', 'REJECTED'].includes(c.status)) fail('仅草稿可编辑')
      result = {
        id: next(s.lessons),
        course_id: id,
        title: body.title,
        sort_order: body.sortOrder,
        duration_seconds: body.durationSeconds,
        video_asset_id: body.videoAssetId,
        coverage_threshold: body.coverageThreshold,
        time_threshold: body.timeThreshold,
      }
      s.lessons.push(result)
    } else if (/^\/courses\/\d+\/materials$/.test(path)) {
      manage(id)
      result = { ...body, id: next(s.materials), course_id: id }
      s.materials.push(result)
    } else if (/^\/courses\/\d+\/submit-review$/.test(path)) {
      const c = manage(id)
      if (!['DRAFT', 'REJECTED'].includes(c.status)) fail('请勿重复提交')
      if (!s.lessons.some((l) => l.course_id === id)) fail('请先增加视频章节')
      c.status = 'PENDING'
    } else if (/^\/courses\/\d+\/archive$/.test(path)) {
      manage(id).status = 'ARCHIVED'
    } else if (/^\/courses\/\d+\/reviews$/.test(path)) {
      manage(id)
      result = s.reviews.filter((r) => r.course_id === id)
    } else if (path === '/learning/courses')
      result = s.enrollments
        .filter((e) => e.user_id === me.id)
        .map((e) => ({ ...course(e.course_id), ...e, id: e.course_id }))
    else if (/^\/courses\/\d+\/progress$/.test(path)) {
      if (!enrolled(me.id, id)) fail('课程未分配', 403)
      result = progress(me.id, id)
    } else if (/^\/courses\/\d+\/learners$/.test(path)) {
      manage(id)
      result = s.enrollments
        .filter((e) => e.course_id === id)
        .map((e) => ({
          ...s.users.find((u) => u.id === e.user_id),
          password: undefined,
          ...e,
          effective_seconds: progress(e.user_id, id).reduce(
            (sum, p) => sum + p.effective_seconds,
            0,
          ),
        }))
    } else if (/^\/lessons\/\d+\/sessions$/.test(path)) {
      const l = s.lessons.find((l) => l.id === id) || fail('章节不存在', 404)
      if (!enrolled(me.id, l.course_id) || course(l.course_id).status !== 'PUBLISHED')
        fail('无权学习此课程', 403)
      const prior = s.sessions.find(
        (t) => t.user_id === me.id && t.active && now() - t.last < 30000,
      )
      if (prior) {
        warning('MULTI_PLAY', l.course_id, prior.id, '演示：尝试同时开启第二个播放会话')
        result = { conflict: true, message: '已有视频正在学习，请先关闭原播放会话' }
      } else {
        const session = {
          id: 's' + now() + Math.random().toString(36).slice(2),
          user_id: me.id,
          lesson_id: id,
          last: now(),
          seq: 0,
          position: 0,
          playing: false,
          active: true,
        }
        s.sessions.filter((t) => t.user_id === me.id).forEach((t) => (t.active = false))
        s.sessions.push(session)
        result = { sessionId: session.id, nextSeq: 1 }
      }
    } else if (/^\/learning\/sessions\/[^/]+\/events$/.test(path)) {
      const sid = path.split('/')[3],
        t = s.sessions.find((t) => t.id === sid && t.user_id === me.id) || fail('会话不存在', 404),
        l = s.lessons.find((l) => l.id === t.lesson_id),
        old = s.events.find((e) => e.session === sid && e.seq === body.seq)
      if (old) result = { duplicate: true, event: old }
      else {
        if (!t.active) fail('会话已失效')
        if (body.seq !== t.seq + 1) fail('事件序号不连续')
        const seconds = credit({
          playing: t.playing,
          previous: t.position,
          position: body.position,
          elapsed: now() - t.last,
          visible: body.visible,
          rate: body.rate,
          event: body.event,
        })
        if (seconds > 0)
          s.segments.push({
            user_id: me.id,
            lesson_id: l.id,
            session: sid,
            start: t.position,
            end: t.position + seconds,
            valid: true,
          })
        const type =
          body.event === 'SEEK' && body.position > t.position ? 'FORWARD_SEEK' : body.event
        s.events.push({
          session: sid,
          seq: body.seq,
          type,
          position: body.position,
          time: now(),
          accepted: seconds,
        })
        if (
          type === 'FORWARD_SEEK' &&
          s.events.filter((e) => e.session === sid && e.type === type && now() - e.time < 60000)
            .length >= 3
        )
          warning('FREQUENT_SEEK', l.course_id, sid, '演示：60秒内至少3次向前跳播，原始事件已保留')
        Object.assign(t, {
          seq: body.seq,
          position: body.position,
          last: now(),
          playing: body.visible && body.rate === 1 && ['PLAY', 'HEARTBEAT'].includes(body.event),
          active: body.event !== 'END',
        })
        const p = progress(me.id, l.course_id)
        enrolled(me.id, l.course_id).completed = p.length > 0 && p.every((p) => p.completed)
        result = {
          acceptedSeconds: seconds,
          nextSeq: body.seq + 1,
          lessons: p,
          message: seconds ? '有效播放' : '当前区间不计时',
        }
      }
    } else if (path === '/learning/stop')
      s.sessions.filter((t) => t.user_id === me.id).forEach((t) => (t.active = false))
    else if (path === '/plans' && method === 'GET')
      result = s.plans.filter((p) => isAdmin || p.userIds.includes(me.id))
    else if (path === '/plans' && method === 'POST') {
      admin()
      result = {
        ...body,
        id: next(s.plans),
        status: 'DRAFT',
        start_at: body.startAt,
        due_at: body.dueAt,
      }
      s.plans.push(result)
    } else if (/^\/plans\/\d+\/publish$/.test(path)) {
      admin()
      const p = s.plans.find((p) => p.id === id) || fail('计划不存在', 404)
      p.status = 'PUBLISHED'
      p.userIds.forEach((u) => {
        p.courseIds.forEach((c) => grant(u, c))
        p.examIds.forEach((eid) => {
          const e = s.exams.find((e) => e.id === eid)
          if (!e.userIds.includes(u)) e.userIds.push(u)
        })
        notice(u, '培训计划已发布', p.title)
      })
    } else if (/^\/plans\/\d+$/.test(path)) {
      const p = s.plans.find((p) => p.id === id) || fail('计划不存在', 404)
      if (!isAdmin && !p.userIds.includes(me.id)) fail('无权查看', 403)
      result = {
        plan: p,
        progress: p.courseIds.map((c) => ({ ...course(c), completed: done(me.id, c) ? 1 : 0 })),
        exams: p.examIds.map((e) => ({
          ...s.exams.find((x) => x.id === e),
          passed: s.attempts.some((a) => a.exam_id === e && a.user_id === me.id && a.passed)
            ? 1
            : 0,
        })),
      }
    } else if (path === '/paths' && method === 'GET')
      result = s.paths.filter((p) => isAdmin || p.users.includes(me.id))
    else if (path === '/paths' && method === 'POST') {
      admin()
      result = { ...body, id: next(s.paths), stages: [], users: [], published: false }
      s.paths.push(result)
    } else if (/^\/paths\/\d+\/stages$/.test(path)) {
      admin()
      const p = s.paths.find((p) => p.id === id)
      if (!p || p.published) fail('已发布路线不可编辑')
      p.stages.push({
        ...body,
        id: next(s.paths.flatMap((p) => p.stages)),
        sort_order: body.sortOrder,
      })
    } else if (/^\/paths\/\d+\/publish$/.test(path)) {
      admin()
      const p = s.paths.find((p) => p.id === id)
      if (!p?.stages.length) fail('请先配置阶段')
      p.published = true
    } else if (/^\/paths\/\d+\/assign$/.test(path)) {
      admin()
      const p = s.paths.find((p) => p.id === id)
      if (!p?.published) fail('路线未发布')
      p.users = [...new Set([...p.users, ...body.userIds])]
      body.userIds.forEach((u) => p.stages.forEach((t) => t.courseIds.forEach((c) => grant(u, c))))
    } else if (/^\/paths\/\d+$/.test(path)) {
      const p = s.paths.find((p) => p.id === id) || fail('路线不存在', 404)
      if (!isAdmin && !p.users.includes(me.id)) fail('无权访问', 403)
      let unlocked = true
      result = {
        path: p,
        stages: p.stages.map((t) => {
          const stage = {
            stage: t,
            unlocked,
            passed: stagePassed(t, me.id),
            courses: t.courseIds.map((c) => ({ ...course(c), completed: done(me.id, c) ? 1 : 0 })),
            exams: examsFor(t.id, me.id),
          }
          unlocked = unlocked && stage.passed
          return stage
        }),
      }
    } else if (/^\/paths\/\d+\/certificate$/.test(path)) {
      const p = s.paths.find((p) => p.id === id)
      if (!p?.users.includes(me.id) || !p.stages.every((t) => stagePassed(t, me.id)))
        fail('尚未满足全部通关要求')
      if (!s.certificates.some((c) => c.user_id === me.id && c.path_id === id))
        s.certificates.push({
          id: 'XR-' + now(),
          user_id: me.id,
          path_id: id,
          title: p.title,
          status: 'VALID',
          issued_at: iso(),
        })
    } else if (path === '/certificates') result = s.certificates.filter((c) => c.user_id === me.id)
    else if (path === '/exams' && method === 'GET')
      result = s.exams
        .filter((e) => e.userIds.includes(me.id))
        .map((e) => {
          const attempts = s.attempts.filter((a) => a.exam_id === e.id && a.user_id === me.id),
            prerequisites = e.courseIds.map((c) => ({
              ...course(c),
              completed: done(me.id, c) ? 1 : 0,
            }))
          let zone = prerequisites.every((c) => c.completed) ? 'READY' : 'LEARNING_REQUIRED'
          if (attempts.some((a) => a.status === 'IN_PROGRESS')) zone = 'IN_PROGRESS'
          else if (attempts.some((a) => a.passed)) zone = 'COMPLETED'
          else if (attempts.some((a) => a.status === 'PENDING_GRADE')) zone = 'PENDING_GRADE'
          else if (attempts.length)
            zone = attempts.length < e.max_attempts ? 'RETAKE' : 'ATTEMPTS_EXHAUSTED'
          return { exam: e, attempts, prerequisites, zone }
        })
    else if (path === '/exams' && method === 'POST') {
      const p = s.papers.find((p) => p.id === body.paperId) || fail('试卷不存在')
      if (!isAdmin && p.owner_id !== me.id) fail('无权管理此试卷', 403)
      if (!p.published) fail('请先发布试卷')
      result = {
        ...body,
        id: next(s.exams),
        paper_id: body.paperId,
        owner_id: me.id,
        stage_id: body.stageId,
        courseIds: body.prerequisiteCourseIds,
        userIds: body.userIds,
        duration_minutes: body.durationMinutes,
        pass_score: body.passScore,
        max_attempts: body.maxAttempts,
        start_at: body.startAt,
        end_at: body.endAt,
      }
      s.exams.push(result)
    } else if (/^\/exams\/\d+\/start$/.test(path)) {
      const e = s.exams.find((e) => e.id === id) || fail('考试不存在', 404)
      if (!e.userIds.includes(me.id)) fail('不在考试名单中', 403)
      if (!e.courseIds.every((c) => done(me.id, c))) fail('请先完成前置课程')
      if (now() < Date.parse(e.start_at) || now() >= Date.parse(e.end_at))
        fail('不在考试开放时间内')
      if (e.stage_id) {
        const p = s.paths.find((p) => p.stages.some((t) => t.id === e.stage_id))
        if (!p?.users.includes(me.id)) fail('未分配成长路线')
        for (const stage of p.stages) {
          if (stage.id === e.stage_id) break
          if (!stagePassed(stage, me.id)) fail('请先完成前序阶段')
        }
      }
      const as = s.attempts.filter((a) => a.user_id === me.id && a.exam_id === id)
      for (const a of as) if (a.status === 'IN_PROGRESS' && now() >= a.deadline_ms) finish(a)
      let a = as.find((a) => a.status === 'IN_PROGRESS')
      if (!a) {
        if (as.some((a) => a.passed)) fail('考试已经通过')
        if (as.some((a) => a.status === 'PENDING_GRADE')) fail('请等待阅卷')
        if (as.length >= e.max_attempts) fail('考试次数已用完')
        a = {
          id: 'a' + now(),
          exam_id: id,
          user_id: me.id,
          attempt_no: as.length + 1,
          status: 'IN_PROGRESS',
          started_at_ms: now(),
          deadline_ms: Math.min(now() + e.duration_minutes * 60000, Date.parse(e.end_at)),
          answers: [],
          passed: false,
        }
        s.attempts.push(a)
      }
      result = attemptView(a)
    } else if (/^\/attempts\/[^/]+(?:\/answers|\/submit|\/events)?$/.test(path)) {
      const a = ownAttempt(path.split('/')[2])
      if (path.endsWith('/answers')) {
        if (a.status !== 'IN_PROGRESS' || now() >= a.deadline_ms) fail('考试已结束')
        const e = s.exams.find((e) => e.id === a.exam_id),
          p = s.papers.find((p) => p.id === e.paper_id)
        if (!p.items.some((q) => q.id === body.questionId)) fail('题目不属于此试卷')
        let ans = a.answers.find((x) => x.paper_question_id === body.questionId)
        if (!ans) {
          ans = { paper_question_id: body.questionId }
          a.answers.push(ans)
        }
        ans.answer = body.answer
      } else if (path.endsWith('/events')) {
        a.events = a.events || []
        a.events.push({ type: body.event, time: now() })
      } else {
        if (path.endsWith('/submit') || now() >= a.deadline_ms) finish(a)
        result = attemptView(a)
      }
    } else if (/^\/courses\/\d+\/questions$/.test(path)) {
      manage(id)
      result = s.questions.filter((q) => q.course_id === id)
    } else if (path === '/questions') {
      manage(body.courseId)
      result = {
        ...body,
        id: next(s.questions),
        course_id: body.courseId,
        owner_id: me.id,
        options_json: JSON.stringify(body.options),
        status: 'APPROVED',
      }
      s.questions.push(result)
    } else if (/^\/questions\/\d+\/approve$/.test(path)) {
      const q = s.questions.find((q) => q.id === id) || fail('题目不存在')
      manage(q.course_id)
      q.status = 'APPROVED'
    } else if (path === '/papers' && method === 'GET')
      result = s.papers.filter((p) => isAdmin || p.owner_id === me.id)
    else if (path === '/papers' && method === 'POST') {
      const items = body.questionIds.map((qid, i) => {
        const q = s.questions.find((q) => q.id === qid) || fail('题目不存在')
        manage(q.course_id)
        if (q.status !== 'APPROVED') fail('题目需先审核')
        return {
          id: now() + i,
          question_id: qid,
          score: body.scores[i],
          snapshot: structuredClone(q),
        }
      })
      result = { id: next(s.papers), title: body.title, owner_id: me.id, published: false, items }
      s.papers.push(result)
    } else if (/^\/papers\/\d+\/publish$/.test(path)) {
      const p = s.papers.find((p) => p.id === id) || fail('试卷不存在')
      if (!isAdmin && p.owner_id !== me.id) fail('无权管理此试卷', 403)
      p.published = true
    } else if (path === '/teaching/exams')
      result = s.exams.filter((e) => isAdmin || e.owner_id === me.id)
    else if (path === '/teaching/grading')
      result = s.attempts.filter(
        (a) =>
          a.status === 'PENDING_GRADE' &&
          (isAdmin || s.exams.find((e) => e.id === a.exam_id).owner_id === me.id),
      )
    else if (/^\/teaching\/attempts\/[^/]+(?:\/grade)?$/.test(path)) {
      const a = s.attempts.find((a) => a.id === path.split('/')[3]) || fail('考试不存在'),
        e = s.exams.find((e) => e.id === a.exam_id),
        p = s.papers.find((p) => p.id === e.paper_id)
      if (!isAdmin && e.owner_id !== me.id) fail('无权阅卷', 403)
      if (a.user_id === me.id) fail('不能批改自己的考试', 403)
      if (path.endsWith('/grade')) {
        const q = p.items.find((q) => q.id === body.questionId)
        if (!q || q.snapshot.type !== 'TEXT' || body.score < 0 || body.score > q.score)
          fail('评分不合法')
        a.answers.find((x) => x.paper_question_id === body.questionId).score = body.score
        if (a.answers.every((x) => x.score != null)) {
          a.score = a.answers.reduce((sum, x) => sum + x.score, 0)
          a.passed = a.score >= e.pass_score
          a.status = 'GRADED'
        }
      } else
        result = {
          attempt: a,
          questions: p.items.map((q) => ({ ...q, snapshot_json: JSON.stringify(q.snapshot) })),
          answers: a.answers,
        }
    } else if (path === '/warnings')
      result = s.warnings.filter((w) => isAdmin || w.user_id === me.id).toReversed()
    else if (/^\/warnings\/\d+(?:\/actions|\/invalidate-session)?$/.test(path)) {
      const w = s.warnings.find((w) => w.id === id) || fail('预警不存在', 404)
      if (!isAdmin && w.user_id !== me.id) fail('无权查看', 403)
      if (method === 'GET') result = { warning: w, actions: w.actions }
      else if (path.endsWith('invalidate-session')) {
        admin()
        if (w.status !== 'INVESTIGATING' || !w.session_id) fail('仅核实中且具有关联会话时可调整')
        const before = s.segments.filter(
          (x) => x.session === w.session_id && x.valid !== false,
        ).length
        s.segments.filter((x) => x.session === w.session_id).forEach((x) => (x.valid = false))
        s.enrollments
          .filter((e) => e.user_id === w.user_id && e.course_id === w.course_id)
          .forEach(
            (e) => (e.completed = progress(w.user_id, w.course_id).every((p) => p.completed)),
          )
        w.actions.push({
          actor_id: me.id,
          action: 'INVALIDATE_SESSION',
          note: body.note + '；失效区间数：' + before,
          before_state: w.status,
          after_state: w.status,
          created_at: iso(),
        })
      } else {
        const before = w.status
        let after
        const action = body.action
        if (action === 'APPEAL') {
          if (w.user_id !== me.id || before !== 'CLOSED') fail('只能申诉本人的已关闭预警')
          after = 'INVESTIGATING'
        } else {
          admin()
          if (action === 'INVESTIGATE' && before === 'OPEN') after = 'INVESTIGATING'
          if (['WARN', 'RELEARN', 'FALSE_POSITIVE'].includes(action) && before === 'INVESTIGATING')
            after = 'REVIEW'
          if (action === 'CLOSE' && before === 'REVIEW') {
            if (w.handler_id === me.id) fail('须由另一名管理员复核关闭')
            after = 'CLOSED'
          }
          if (action === 'REOPEN' && before === 'CLOSED') after = 'INVESTIGATING'
        }
        if (!after) fail('当前状态不支持此操作')
        w.actions.push({
          actor_id: me.id,
          action,
          note: body.note,
          before_state: before,
          after_state: after,
          created_at: iso(),
        })
        Object.assign(w, { status: after, handler_id: me.id, updated_at: iso() })
        notice(w.user_id, '预警处理更新', body.note)
        log('WARNING_' + action, id, body.note)
      }
    } else if (path === '/notifications')
      result = s.notifications.filter((n) => n.user_id === me.id).toReversed()
    else if (/^\/notifications\/\d+\/read$/.test(path)) {
      const n =
        s.notifications.find((n) => n.id === id && n.user_id === me.id) || fail('消息不存在', 404)
      n.read_at = iso()
    } else if (/^\/admin\/(departments|positions|grades)$/.test(path) && method === 'POST') {
      admin()
      const list = s[path.split('/')[2]]
      result = { ...body, id: next(list) }
      list.push(result)
    } else if (/^\/admin\/users\/\d+\/manager-scope$/.test(path)) {
      admin()
      const u = s.users.find((u) => u.id === id) || fail('员工不存在')
      if (!u.roles.includes('MANAGER')) fail('请先授予部门主管角色')
      u.scopes = [...new Set([...(u.scopes || []), body.departmentId])]
    } else if (path === '/admin/users' && method === 'GET') {
      admin()
      result = s.users
    } else if (path === '/admin/users' && method === 'POST') {
      admin()
      if (s.users.some((u) => u.username === body.username)) fail('账号已存在')
      result = {
        ...body,
        id: next(s.users),
        display_name: body.displayName,
        department_id: body.departmentId,
        position_id: body.positionId,
        grade_id: body.gradeId,
        roles: ['EMPLOYEE'],
        active: true,
      }
      delete result.password
      s.users.push(result)
    } else if (/^\/admin\/users\/\d+\/(roles|active)$/.test(path)) {
      admin()
      if (id === me.id) fail('不能修改自己的管理权限或停用本人')
      const u = s.users.find((u) => u.id === id) || fail('员工不存在')
      if (path.endsWith('active')) u.active = body.active
      else if (body.enabled) u.roles = [...new Set([...u.roles, body.role])]
      else u.roles = u.roles.filter((r) => r !== body.role)
      log('USER_CHANGE', id, JSON.stringify(body))
    } else if (path === '/admin/audits') {
      admin()
      result = s.audit.toReversed()
    } else if (path === '/dashboards/personal') {
      const es = s.enrollments.filter((e) => e.user_id === me.id)
      result = {
        summary: {
          assigned_courses: es.length,
          completed_courses: es.filter((e) => e.completed).length,
          effective_seconds: s.segments
            .filter((x) => x.user_id === me.id && x.valid !== false)
            .reduce((sum, x) => sum + x.end - x.start, 0),
          passed_attempts: s.attempts.filter((a) => a.user_id === me.id && a.passed).length,
        },
        courses: es.map((e) => ({ ...course(e.course_id), ...e, id: e.course_id })),
      }
    } else if (path === '/dashboards/teaching')
      result = s.courses
        .filter((c) => c.owner_id === me.id)
        .map((c) => ({
          ...c,
          learner_count: s.enrollments.filter((e) => e.course_id === c.id).length,
          completed_count: s.enrollments.filter((e) => e.course_id === c.id && e.completed).length,
        }))
    else if (path === '/dashboards/company' || path.startsWith('/dashboards/company/details')) {
      admin()
      const details = s.enrollments.map((e) => ({
        ...s.users.find((u) => u.id === e.user_id),
        ...course(e.course_id),
        ...e,
        id: e.user_id,
      }))
      result = path.includes('details')
        ? details
        : {
            summary: {
              assigned_tasks: details.length,
              completed_tasks: details.filter((e) => e.completed).length,
              learner_count: new Set(details.map((e) => e.user_id)).size,
            },
            details,
            departments: s.departments.map((d) => ({
              ...d,
              assigned_tasks: details.filter((x) => x.department_id === d.id).length,
              completed_tasks: details.filter((x) => x.department_id === d.id && x.completed)
                .length,
            })),
            plans: s.plans
              .filter((p) => p.status === 'PUBLISHED')
              .map((p) => ({
                ...p,
                expected_people: p.userIds.length,
                completed_people: p.userIds.filter(
                  (u) =>
                    p.courseIds.every((c) => done(u, c)) &&
                    p.examIds.every((e) =>
                      s.attempts.some((a) => a.exam_id === e && a.user_id === u && a.passed),
                    ),
                ).length,
              })),
            updatedAt: iso(),
          }
    } else if (path === '/learning/wrong-answers')
      result = s.attempts
        .filter(
          (a) =>
            a.user_id === me.id &&
            a.status === 'GRADED' &&
            Date.parse(s.exams.find((e) => e.id === a.exam_id).end_at) < now(),
        )
        .flatMap((a) =>
          a.answers
            .filter((x) => x.score === 0)
            .map((x) => ({
              answer: x.answer,
              ...s.papers.flatMap((p) => p.items).find((q) => q.id === x.paper_question_id)
                ?.snapshot,
            })),
        )
    else if (/^\/courses\/\d+\/ai\/ask$/.test(path)) {
      if (!enrolled(me.id, id)) fail('课程未分配', 403)
      if (s.attempts.some((a) => a.user_id === me.id && a.status === 'IN_PROGRESS'))
        fail('正式考试期间暂停课程答疑')
      const m = s.materials.find((m) => m.course_id === id)
      result = {
        answer:
          '【演示回答，未调用大模型】\n' +
          m.content +
          '\n请结合实际岗位制度执行。接入后端并配置模型后，将根据你的问题生成回答。',
        citations: [{ id: m.id, title: m.title }],
      }
    } else if (/^\/courses\/\d+\/ai\/question-drafts$/.test(path)) {
      manage(id)
      result = Array.from({ length: body.count }, (_, i) => ({
        id: next(s.questions) + i,
        course_id: id,
        owner_id: me.id,
        type: 'SINGLE',
        stem: '【演示草稿】发现工作流程异常时应采取什么行动？',
        options_json: JSON.stringify({ A: '停止并报告', B: '忽略异常继续工作' }),
        answer: 'A',
        explanation: '示例题目仅演示审核流程，请根据正式课程核验。',
        status: 'DRAFT',
        source: 'AI',
      }))
      s.questions.push(...result)
    } else if (/^\/courses\/\d+\/coursework$/.test(path)) {
      const c = course(id),
        teacher = isAdmin || c.owner_id === me.id
      if (!teacher && !enrolled(me.id, id)) fail('课程未分配', 403)
      result = {
        assignments: s.assignments.filter((a) => a.course_id === id),
        submissions: s.submissions.filter(
          (a) =>
            s.assignments.some((t) => t.id === a.assignment_id && t.course_id === id) &&
            (teacher || a.user_id === me.id),
        ),
      }
    } else if (/^\/courses\/\d+\/assignments$/.test(path)) {
      manage(id)
      result = { id: next(s.assignments), course_id: id, ...body }
      s.assignments.push(result)
    } else if (/^\/assignments\/\d+\/submissions$/.test(path)) {
      const a = s.assignments.find((a) => a.id === id) || fail('作业不存在')
      if (!enrolled(me.id, a.course_id)) fail('课程未分配', 403)
      let sub = s.submissions.find((x) => x.assignment_id === id && x.user_id === me.id)
      if (!sub) {
        sub = { id: next(s.submissions), assignment_id: id, user_id: me.id }
        s.submissions.push(sub)
      }
      Object.assign(sub, body, { score: null, feedback: null })
    } else if (/^\/submissions\/\d+\/grade$/.test(path)) {
      const sub = s.submissions.find((x) => x.id === id) || fail('提交不存在')
      manage(s.assignments.find((a) => a.id === sub.assignment_id).course_id)
      if (sub.user_id === me.id) fail('不能给自己评分')
      Object.assign(sub, body)
    } else if (/^\/courses\/\d+\/feedback$/.test(path)) {
      if (method === 'GET') {
        manage(id)
        result = s.feedback.filter((f) => f.course_id === id)
      } else {
        if (!done(me.id, id)) fail('完成课程后可评价')
        s.feedback = s.feedback.filter((f) => !(f.course_id === id && f.user_id === me.id))
        s.feedback.push({ ...body, course_id: id, user_id: me.id })
      }
    } else fail('此操作请切换到后端模式并接入服务', 501)
    save(s)
    return structuredClone(result)
  }
  return {
    dispatch,
    reset() {
      save(seed())
    },
    read,
  }
}
