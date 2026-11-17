export function createDemoPlans(context) {
  const {
    s,
    me,
    isAdmin,
    now,
    iso,
    next,
    fail,
    admin,
    course,
    enrolled,
    done,
    grant,
    notice,
    log,
    idsFor,
    requiredText,
    userSummary,
    content,
  } = context
  const handled = (result) => ({ handled: true, result })
  const isTeacher = me.roles.includes('INSTRUCTOR')
  const plan = (id) => s.plans.find((row) => row.id === id) || fail('培训计划不存在', 404)
  const responsible = (row) => row.responsible_user_id ?? row.owner_id
  const canManagePlan = (row) => isAdmin || responsible(row) === me.id
  const canViewPlan = (row) =>
    canManagePlan(row) ||
    (isTeacher &&
      row.status === 'PUBLISHED' &&
      row.courseIds.some((id) => course(id).owner_id === me.id))
  const rules = (body, row = null) => {
    const responsibleId = body.responsibleUserId ?? (row ? responsible(row) : me.id)
    if (!isAdmin && row && responsibleId !== responsible(row))
      fail('仅组织发展部可以变更计划负责人', 403)
    if (!s.users.some((user) => user.id === responsibleId && user.active))
      fail('请选择启用的计划负责人', 400)
    const before = body.remindBeforeHours ?? 72
    const every = body.remindEveryHours ?? 24
    const push = body.pushResults ?? true
    if (
      !Number.isInteger(before) ||
      before < 0 ||
      before > 8760 ||
      !Number.isInteger(every) ||
      every < 1 ||
      every > 720 ||
      typeof push !== 'boolean'
    )
      fail('提醒规则不合法', 400)
    return {
      responsible_user_id: responsibleId,
      remind_before_hours: before,
      remind_every_hours: every,
      push_results: push,
    }
  }
  const examResults = (row, courseId, userId) =>
    row.examIds
      .map((id) => s.exams.find((exam) => exam.id === id))
      .filter((exam) => courseId == null || exam.courseIds.includes(courseId))
      .map((exam) => {
        const attempt = s.attempts
          .filter((value) => value.exam_id === exam.id && value.user_id === userId)
          .sort((a, b) => b.attempt_no - a.attempt_no)[0]
        return {
          exam_id: exam.id,
          title: exam.title,
          attempt_id: attempt?.id || null,
          status: attempt?.status || 'NOT_STARTED',
          score: attempt?.score ?? null,
          passed: !!attempt?.passed,
          attempt_no: attempt?.attempt_no ?? null,
        }
      })
  const assignmentResults = (courseId, userId) =>
    s.assignments
      .filter((row) => row.course_id === courseId)
      .map((assignment) => {
        const submission = s.submissions.find(
          (row) => row.assignment_id === assignment.id && row.user_id === userId,
        )
        return {
          assignment_id: assignment.id,
          title: assignment.title,
          submission_id: submission?.id || null,
          score: submission?.score ?? null,
          feedback: submission?.feedback || null,
          status: !submission ? 'NOT_SUBMITTED' : submission.score == null ? 'SUBMITTED' : 'GRADED',
        }
      })
  const pending = (row) =>
    row.userIds.filter((userId) => {
      const user = s.users.find((value) => value.id === userId)
      if (!user?.active) return false
      return (
        row.courseIds.some((id) => !done(userId, id)) ||
        examResults(row, null, userId).some(
          (exam) => !exam.passed && exam.status !== 'PENDING_GRADE',
        ) ||
        row.courseIds.some((id) =>
          assignmentResults(id, userId).some((assignment) => assignment.status === 'NOT_SUBMITTED'),
        )
      )
    })
  const pendingGrade = (row) =>
    row.userIds.reduce(
      (sum, userId) =>
        sum +
        examResults(row, null, userId).filter((exam) => exam.status === 'PENDING_GRADE').length +
        row.courseIds.reduce(
          (value, id) =>
            value +
            assignmentResults(id, userId).filter((assignment) => assignment.status === 'SUBMITTED')
              .length,
          0,
        ),
      0,
    )
  const notifyOnce = (row, recipient, kind, state, title, text) => {
    if (
      s.planNotices.some(
        (entry) =>
          entry.plan_id === row.id &&
          entry.recipient_id === recipient &&
          entry.kind === kind &&
          entry.state_key === state,
      )
    )
      return 0
    s.planNotices.push({
      plan_id: row.id,
      recipient_id: recipient,
      kind,
      state_key: state,
      sent_at: iso(),
    })
    notice(recipient, title, text)
    return 1
  }
  const remind = (row) => {
    if (row.status !== 'PUBLISHED') fail('仅已发布的计划可发送提醒', 409)
    const users = pending(row)
    const grade = pendingGrade(row)
    const bucket = Math.floor(now() / ((row.remind_every_hours ?? 24) * 3600000))
    let sent = 0
    const activeResponsible = s.users.some((user) => user.id === responsible(row) && user.active)
    if (
      now() >= Date.parse(row.start_at) &&
      Date.parse(row.due_at) - now() <= (row.remind_before_hours ?? 72) * 3600000
    ) {
      const kind = now() >= Date.parse(row.due_at) ? 'OVERDUE' : 'DUE'
      const title = (kind === 'OVERDUE' ? '培训已逾期提醒 #' : '培训到期提醒 #') + row.id
      for (const user of users)
        sent += notifyOnce(
          row,
          user,
          'LEARNER_' + kind,
          String(bucket),
          title,
          row.title + '；截止：' + row.due_at + '。请完成课程、考试和作业。',
        )
      if (activeResponsible && (users.length || grade))
        sent += notifyOnce(
          row,
          responsible(row),
          'RESPONSIBLE_' + kind,
          String(bucket),
          title,
          row.title + '；尚有 ' + users.length + ' 人待完成，另有 ' + grade + ' 项考核待评阅。',
        )
    }
    if (activeResponsible && row.push_results !== false) {
      const states = row.userIds.map((userId) => ({
        user_id: userId,
        exams: examResults(row, null, userId),
        courses: row.courseIds.map((id) => ({
          course_id: id,
          completed: done(userId, id),
          completed_at: enrolled(userId, id)?.completed_at || null,
          exams: examResults(row, id, userId),
          assignments: assignmentResults(id, userId),
        })),
      }))
      const completed = states.reduce(
        (sum, user) => sum + user.courses.filter((value) => value.completed).length,
        0,
      )
      const scores = states.flatMap((user) =>
        user.exams
          .filter((exam) => exam.status !== 'NOT_STARTED')
          .map(
            (exam) =>
              s.users.find((value) => value.id === user.user_id)?.display_name +
              ' / ' +
              exam.title +
              '：' +
              (exam.status === 'PENDING_GRADE'
                ? '待评阅，尚未最终出分'
                : exam.status === 'GRADED'
                  ? '成绩 ' + exam.score + (exam.passed ? '，通过' : '，未通过')
                  : '作答中'),
          ),
      )
      sent += notifyOnce(
        row,
        responsible(row),
        'RESULT_DIGEST',
        JSON.stringify(states),
        '培训学习与考核结果更新 #' + row.id,
        (
          row.title +
          '；课程完成 ' +
          completed +
          '/' +
          row.userIds.length * row.courseIds.length +
          '；待评分 ' +
          grade +
          ' 项；尚有 ' +
          users.length +
          ' 人待完成。' +
          scores.join('；')
        ).slice(0, 1900),
      )
    }
    log('PLAN_REMIND', row.id, '发送站内消息：' + sent)
    return { sent, pending_learners: users.length, pending_grade: grade }
  }
  const session = (id) =>
    s.offlineSessions.find((row) => row.id === id) || fail('线下培训不存在', 404)
  const canManageSession = (row) => isAdmin || (isTeacher && row.instructor_id === me.id)
  const attendance = (id) => s.offlineAttendance.filter((row) => row.session_id === id)
  const sessionView = (row) => ({
    ...row,
    instructor_name: s.users.find((user) => user.id === row.instructor_id)?.display_name,
    course_title: row.course_id ? course(row.course_id).title : null,
    plan_title: row.plan_id ? plan(row.plan_id).title : null,
  })
  const credited = (record, row) => {
    const status = record?.status ?? null
    const seconds =
      status === 'PRESENT' && now() >= Date.parse(row.end_at)
        ? (Date.parse(row.end_at) - Date.parse(row.start_at)) / 1000
        : 0
    return { ...record, status, credited_seconds: seconds, credited_hours: seconds / 3600 }
  }
  const sessionList = (personal = false) =>
    s.offlineSessions
      .filter(
        (row) =>
          (!personal && canManageSession(row)) ||
          attendance(row.id).some((record) => record.user_id === me.id),
      )
      .sort((a, b) => Date.parse(b.start_at) - Date.parse(a.start_at))
      .map((row) => ({
        ...sessionView(row),
        ...credited(
          attendance(row.id).find((record) => record.user_id === me.id),
          row,
        ),
        member_count: attendance(row.id).length,
        attendance_status:
          attendance(row.id).find((record) => record.user_id === me.id)?.status || null,
        can_manage: !personal && canManageSession(row),
      }))
  const requireTeacher = () => {
    if (!isAdmin && !isTeacher) fail('需要内训师或组织发展部权限', 403)
  }
  const ownedLearner = (userId) =>
    s.enrollments.some(
      (registration) =>
        registration.user_id === userId &&
        course(registration.course_id).owner_id === me.id &&
        course(registration.course_id).status === 'PUBLISHED',
    )
  const teachingDetail = (row) => {
    if (!canViewPlan(row)) fail('无权查看此计划的教学数据', 403)
    const full = canManagePlan(row)
    const members = row.userIds.map((id) => userSummary(s.users.find((user) => user.id === id)))
    const courses = row.courseIds
      .map(course)
      .filter((value) => full || value.owner_id === me.id)
      .map((value) => ({
        course: value,
        materials: content.materialsFor(value.id),
        exams: row.examIds
          .map((id) => s.exams.find((exam) => exam.id === id))
          .filter((exam) => exam.courseIds.includes(value.id))
          .map(examMeta),
        assignments: s.assignments.filter((assignment) => assignment.course_id === value.id),
        learners: members.map((user) => ({
          ...user,
          completed: done(user.id, value.id),
          completed_at: enrolled(user.id, value.id)?.completed_at || null,
          exam_results: examResults(row, value.id, user.id),
          assignment_results: assignmentResults(value.id, user.id),
        })),
      }))
    return {
      plan: {
        ...row,
        responsible_name: s.users.find((user) => user.id === responsible(row))?.display_name,
      },
      members,
      courses,
      plan_exams: row.examIds
        .map((id) => s.exams.find((exam) => exam.id === id))
        .filter(
          (exam) =>
            full ||
            exam.owner_id === me.id ||
            exam.courseIds.some((id) => course(id).owner_id === me.id),
        )
        .map((exam) => ({
          ...examMeta(exam),
          results: row.userIds.map((userId) => ({
            user_id: userId,
            ...examResults(row, null, userId).find((result) => result.exam_id === exam.id),
          })),
        })),
      offline_sessions: s.offlineSessions
        .filter(
          (value) =>
            value.plan_id === row.id &&
            (full ||
              value.instructor_id === me.id ||
              (value.course_id && course(value.course_id).owner_id === me.id)),
        )
        .map(sessionView),
      can_manage_reminders: full,
    }
  }
  function examMeta(exam) {
    return {
      id: exam.id,
      title: exam.title,
      owner_id: exam.owner_id,
      start_at: exam.start_at,
      end_at: exam.end_at,
      duration_minutes: exam.duration_minutes,
      pass_score: exam.pass_score,
    }
  }
  function dispatch(path, method, body) {
    let match
    if (path === '/plans' && method === 'POST') {
      admin()
      const courseIds = idsFor(body.courseIds, '课程', 100)
      const userIds = idsFor(body.userIds, '员工', 1000)
      if (
        !Array.isArray(body.examIds) ||
        body.examIds.length > 100 ||
        body.examIds.some((id) => !Number.isSafeInteger(id) || id <= 0)
      )
        fail('考试列表不正确', 400)
      if (
        !Number.isFinite(Date.parse(body.startAt)) ||
        !Number.isFinite(Date.parse(body.dueAt)) ||
        Date.parse(body.dueAt) <= Date.parse(body.startAt)
      )
        fail('计划时间不合法', 400)
      if (courseIds.some((id) => course(id).status !== 'PUBLISHED')) fail('仅可分配已发布课程', 409)
      if (userIds.some((id) => !s.users.some((user) => user.id === id && user.active)))
        fail('所选员工不存在或已停用', 400)
      for (const id of body.examIds) {
        const exam = s.exams.find((value) => value.id === id) || fail('考试不存在', 404)
        if (!exam.courseIds.every((courseId) => courseIds.includes(courseId)))
          fail('计划应包含关联考试的全部前置课程', 400)
      }
      const row = {
        id: next(s.plans),
        title: requiredText(body.title, '培训计划名称'),
        owner_id: me.id,
        ...rules(body),
        start_at: body.startAt,
        due_at: body.dueAt,
        courseIds,
        userIds,
        examIds: [...new Set(body.examIds)],
        status: 'DRAFT',
      }
      s.plans.push(row)
      log('PLAN_CREATE', row.id, row.title)
      return handled(row)
    }
    if ((match = path.match(/^\/plans\/(\d+)\/reminder-rules$/)) && method === 'PUT') {
      const row = plan(Number(match[1]))
      if (!canManagePlan(row)) fail('仅组织发展部或指定负责人可设置提醒规则', 403)
      if (!['DRAFT', 'PUBLISHED'].includes(row.status)) fail('已关闭计划不能调整提醒', 409)
      const oldResponsible = responsible(row)
      Object.assign(row, rules(body, row))
      log('PLAN_REMINDER_RULES', row.id, JSON.stringify(body))
      if (oldResponsible !== responsible(row))
        notice(responsible(row), '已指定为培训计划负责人', row.title)
      return handled()
    }
    if ((match = path.match(/^\/plans\/(\d+)\/remind$/)) && method === 'POST') {
      const row = plan(Number(match[1]))
      if (!canManagePlan(row)) fail('仅组织发展部或指定负责人可发送提醒', 403)
      return handled(remind(row))
    }
    if (path === '/teaching/plans' && method === 'GET') {
      return handled(
        s.plans.filter(canViewPlan).map((row) => {
          const detail = teachingDetail(row)
          const learners = detail.courses.flatMap((value) => value.learners)
          const pendingItems = new Set()
          for (const learner of learners) {
            learner.exam_results
              .filter((result) => result.status === 'PENDING_GRADE')
              .forEach((result) => pendingItems.add('exam:' + learner.id + ':' + result.exam_id))
            learner.assignment_results
              .filter((result) => result.status === 'SUBMITTED')
              .forEach((result) =>
                pendingItems.add('assignment:' + learner.id + ':' + result.assignment_id),
              )
          }
          detail.plan_exams
            .flatMap((exam) => exam.results)
            .filter((result) => result.status === 'PENDING_GRADE')
            .forEach((result) => pendingItems.add('exam:' + result.user_id + ':' + result.exam_id))
          return {
            ...row,
            responsible_name: detail.plan.responsible_name,
            course_count: detail.courses.length,
            member_count: detail.members.length,
            assigned_tasks: learners.length,
            completed_tasks: learners.filter((value) => value.completed).length,
            pending_grade: pendingItems.size,
            can_manage_reminders: canManagePlan(row),
          }
        }),
      )
    }
    if ((match = path.match(/^\/teaching\/plans\/(\d+)$/)) && method === 'GET')
      return handled(teachingDetail(plan(Number(match[1]))))
    if (path === '/offline-sessions/options' && method === 'GET') {
      requireTeacher()
      const eligibleCourses = s.courses.filter(
        (row) => row.status === 'PUBLISHED' && (isAdmin || row.owner_id === me.id),
      )
      const users = s.users
        .filter((user) => user.active && (isAdmin || ownedLearner(user.id)))
        .map((user) => ({
          ...userSummary(user),
          course_ids: eligibleCourses
            .filter((row) => enrolled(user.id, row.id))
            .map((row) => row.id),
        }))
      return handled({
        users,
        instructors: s.users
          .filter(
            (user) =>
              user.active && user.roles.includes('INSTRUCTOR') && (isAdmin || user.id === me.id),
          )
          .map((user) => ({ id: user.id, display_name: user.display_name })),
        courses: eligibleCourses.map((row) => ({ id: row.id, title: row.title })),
        plans: s.plans
          .filter(
            (row) =>
              row.status === 'PUBLISHED' &&
              (isAdmin ||
                responsible(row) === me.id ||
                row.courseIds.some((id) => course(id).owner_id === me.id)),
          )
          .map((row) => ({
            id: row.id,
            title: row.title,
            user_ids: row.userIds.filter((id) => users.some((user) => user.id === id)),
            course_ids: row.courseIds.filter((id) =>
              eligibleCourses.some((value) => value.id === id),
            ),
          })),
      })
    }
    if (path === '/offline-sessions' && method === 'GET') return handled(sessionList())
    if (path === '/me/offline-training' && method === 'GET') {
      const records = sessionList(true)
      return handled({
        records,
        credited_hours: records.reduce((sum, record) => sum + record.credited_hours, 0),
        attended_sessions: records.filter((record) => record.credited_seconds > 0).length,
      })
    }
    if (path === '/offline-sessions' && method === 'POST') {
      requireTeacher()
      const userIds = idsFor(body.userIds, '员工', 1000)
      const start = Date.parse(body.startAt),
        end = Date.parse(body.endAt)
      if (
        !Number.isFinite(start) ||
        !Number.isFinite(end) ||
        end <= start ||
        end - start > 86400000
      )
        fail('单场培训须大于0且不超过24小时', 400)
      if (!isAdmin && body.instructorId !== me.id) fail('内训师只能安排本人授课', 403)
      const teacher = s.users.find(
        (user) => user.id === body.instructorId && user.active && user.roles.includes('INSTRUCTOR'),
      )
      if (!teacher) fail('请选择启用的内训师', 400)
      const linkedCourse = body.courseId == null ? null : course(body.courseId)
      if (linkedCourse && linkedCourse.status !== 'PUBLISHED') fail('关联课程尚未发布', 409)
      if (linkedCourse && !isAdmin && linkedCourse.owner_id !== me.id)
        fail('内训师只能关联自己的课程', 403)
      const linkedPlan = body.planId == null ? null : plan(body.planId)
      if (linkedPlan && linkedPlan.status !== 'PUBLISHED') fail('关联计划尚未发布', 409)
      if (linkedPlan && linkedCourse && !linkedPlan.courseIds.includes(linkedCourse.id))
        fail('关联课程不属于所选培训计划', 400)
      if (
        linkedPlan &&
        !linkedCourse &&
        !isAdmin &&
        responsible(linkedPlan) !== me.id &&
        !linkedPlan.courseIds.some((id) => course(id).owner_id === me.id)
      )
        fail('无权关联此培训计划', 403)
      for (const id of userIds) {
        const user = s.users.find((value) => value.id === id) || fail('员工不存在', 404)
        if (!user.active) fail('不能分配停用员工', 409)
        if (!isAdmin && !(linkedCourse ? enrolled(id, linkedCourse.id) : ownedLearner(id)))
          fail('只能为自己的课程学员安排线下培训', 403)
        if (linkedPlan && !linkedPlan.userIds.includes(id)) fail('所选员工不属于关联计划', 400)
      }
      const row = {
        id: next(s.offlineSessions),
        title: requiredText(body.title, '培训名称'),
        location: requiredText(body.location, '培训地点'),
        plan_id: linkedPlan?.id || null,
        course_id: linkedCourse?.id || null,
        instructor_id: teacher.id,
        start_at: body.startAt,
        end_at: body.endAt,
        created_by: me.id,
        created_at: iso(),
      }
      if (row.title.length > 200 || row.location.length > 200) fail('培训名称或地点过长', 400)
      s.offlineSessions.push(row)
      for (const id of userIds) {
        s.offlineAttendance.push({
          session_id: row.id,
          user_id: id,
          status: 'REGISTERED',
          score: null,
          note: null,
          recorded_at: null,
          recorded_by: null,
        })
        notice(id, '线下培训安排 #' + row.id, row.title + '；地点：' + row.location)
      }
      log('OFFLINE_CREATE', row.id, row.title)
      return handled(sessionView(row))
    }
    if ((match = path.match(/^\/offline-sessions\/(\d+)$/)) && method === 'GET') {
      const row = session(Number(match[1]))
      const all = canManageSession(row)
      if (!all && !attendance(row.id).some((record) => record.user_id === me.id))
        fail('未报名此线下培训', 403)
      return handled({
        session: sessionView(row),
        can_manage: all,
        attendance: attendance(row.id)
          .filter((record) => all || record.user_id === me.id)
          .map((record) => ({
            ...userSummary(s.users.find((user) => user.id === record.user_id)),
            ...credited(record, row),
          })),
      })
    }
    if ((match = path.match(/^\/offline-sessions\/(\d+)\/attendance$/)) && method === 'PUT') {
      const row = session(Number(match[1]))
      if (!canManageSession(row)) fail('仅授课内训师或组织发展部可登记出勤', 403)
      const reason = requiredText(body.reason, '登记或更正原因')
      if (now() < Date.parse(row.start_at)) fail('培训尚未开始，不能提前登记出勤', 409)
      if (!Array.isArray(body.records) || !body.records.length || body.records.length > 1000)
        fail('请提供出勤记录', 400)
      const selected = new Set()
      for (const record of body.records) {
        if (
          !record ||
          !Number.isSafeInteger(record.userId) ||
          record.userId <= 0 ||
          !['PRESENT', 'ABSENT', 'EXCUSED'].includes(record.status) ||
          (record.score != null &&
            (!Number.isInteger(record.score) || record.score < 0 || record.score > 100)) ||
          (record.note != null && (typeof record.note !== 'string' || record.note.length > 1000)) ||
          selected.has(record.userId)
        )
          fail('出勤记录不合法或员工重复', 400)
        selected.add(record.userId)
        if (!s.users.find((user) => user.id === record.userId)?.active)
          fail('员工不存在或已停用', 409)
        if (!attendance(row.id).some((value) => value.user_id === record.userId))
          fail('所选员工未报名此培训', 404)
      }
      for (const record of body.records) {
        const before = attendance(row.id).find((value) => value.user_id === record.userId)
        log(
          'OFFLINE_ATTENDANCE',
          'session=' + row.id + ';user=' + record.userId,
          JSON.stringify({
            before: { status: before.status, score: before.score, note: before.note },
            after: record,
            reason,
          }),
        )
        Object.assign(before, {
          status: record.status,
          score: record.score ?? null,
          note: record.note ?? null,
          recorded_by: me.id,
          recorded_at: iso(),
        })
        notice(
          record.userId,
          '线下培训出勤记录已更新 #' + row.id,
          row.title + '；状态：' + record.status + '；成绩：' + (record.score ?? '未评分'),
        )
      }
      return handled()
    }
    return { handled: false }
  }
  return { dispatch }
}
