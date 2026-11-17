// Local demonstration rules. Formal credentials, file inspection and authorization live in SSM.
export function createDemoContent(context) {
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
    manage,
    enrolled,
    progress,
    notice,
    log,
  } = context
  const handled = (result) => ({ handled: true, result })
  const random = (length) => {
    const bytes = new Uint8Array(length)
    globalThis.crypto.getRandomValues(bytes)
    return bytes
  }
  const editable = (id) => {
    const row = manage(id)
    if (!['DRAFT', 'REJECTED'].includes(row.status)) fail('仅草稿或驳回课程可编辑', 409)
    return row
  }
  const materialView = (row, userId = me.id) => {
    const asset = s.files.find((file) => file.id === (row.asset_id ?? row.assetId))
    return {
      ...row,
      content: row.content || '',
      asset_id: row.asset_id ?? row.assetId ?? null,
      material_type: row.material_type || 'TEXT',
      external_url: row.external_url || null,
      required: !!row.required,
      file_name: asset?.name || null,
      content_type: asset?.content_type || null,
      acknowledged: s.materialAcknowledgements.some(
        (ack) => ack.user_id === userId && ack.material_id === row.id,
      ),
    }
  }
  const materialsFor = (id, userId = me.id) =>
    s.materials.filter((row) => row.course_id === id).map((row) => materialView(row, userId))
  const status = (id, userId = me.id, update = false) => {
    const videos = progress(userId, id)
    const materials = materialsFor(id, userId)
    const required = materials.filter((row) => row.required)
    const videoCompleted = videos.filter((row) => row.completed).length
    const materialCompleted = required.filter((row) => row.acknowledged).length
    const total = videos.length + required.length
    const completed = total > 0 && videoCompleted + materialCompleted === total
    if (update && enrolled(userId, id)) {
      const registration = enrolled(userId, id)
      registration.completed = completed
      registration.completed_at = completed ? registration.completed_at || iso() : null
    }
    return {
      completed,
      videoCompleted,
      videoTotal: videos.length,
      materialCompleted,
      materialTotal: required.length,
      materials,
      percent: total ? ((videoCompleted + materialCompleted) / total) * 100 : 0,
    }
  }
  const validateMaterial = (row) => {
    const type = row.material_type
    const asset = row.asset_id ? s.files.find((file) => file.id === row.asset_id) : null
    const mime = asset?.content_type || ''
    if (!['TEXT', 'PDF', 'PPT', 'LINK'].includes(type)) fail('资料类型不正确', 400)
    if (row.asset_id && !asset) fail('附件不存在，请重新上传', 404)
    if (asset && !isAdmin && asset.owner_id !== me.id) fail('附件不属于当前用户', 403)
    if (type === 'TEXT' && !row.content.trim() && mime !== 'text/plain') {
      fail('文本资料需要正文或文本附件', 400)
    }
    if (type === 'PDF' && mime !== 'application/pdf') fail('PDF资料需要上传PDF附件', 400)
    if (
      type === 'PPT' &&
      ![
        'application/vnd.ms-powerpoint',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      ].includes(mime)
    )
      fail('PPT资料需要上传PPT或PPTX附件', 400)
    if (type !== 'LINK' && row.external_url) fail('外链请使用LINK资料类型', 400)
    if (type === 'LINK') {
      let url
      try {
        url = new URL(row.external_url)
      } catch {
        fail('请填写有效的HTTP(S)链接', 400)
      }
      if (
        !['http:', 'https:'].includes(url.protocol) ||
        !url.hostname ||
        url.username ||
        url.password
      ) {
        fail('请填写有效的HTTP(S)链接', 400)
      }
    }
  }
  const canGrade = (attempt) => {
    const exam = s.exams.find((row) => row.id === attempt.exam_id) || fail('考试不存在', 404)
    const paper = s.papers.find((row) => row.id === exam.paper_id) || fail('试卷不存在', 404)
    return (
      (isAdmin || exam.owner_id === me.id || paper.owner_id === me.id) && attempt.user_id !== me.id
    )
  }
  const teachingAttempt = (attempt) => {
    if (!canGrade(attempt)) fail('无权查看或批改这份答卷', 403)
    if (attempt.status === 'IN_PROGRESS') fail('考试尚未交卷，不能阅卷', 409)
    const exam = s.exams.find((row) => row.id === attempt.exam_id)
    const paper = s.papers.find((row) => row.id === exam.paper_id)
    return {
      attempt: { ...attempt, answers: undefined },
      exam,
      questions: paper.items.map((question) => {
        const answer = attempt.answers.find((row) => row.paper_question_id === question.id)
        return {
          ...question,
          snapshot_json: JSON.stringify(question.snapshot),
          type: question.snapshot.type,
          stem: question.snapshot.stem,
          options: JSON.parse(question.snapshot.options_json || '{}'),
          max_score: question.score,
          reference_answer: question.snapshot.answer,
          student_answer: answer?.answer || '',
          earned_score: answer?.score ?? null,
          grading_feedback: answer?.grading_feedback || null,
          grader_id: answer?.grader_id || null,
        }
      }),
      answers: attempt.answers,
    }
  }
  function dispatch(path, method, body) {
    let match
    if (path === '/files' && method === 'POST') {
      if (!body.id || typeof body.name !== 'string') fail('附件元数据不完整', 400)
      const file = {
        id: body.id,
        name: body.name,
        content_type: body.content_type || 'application/octet-stream',
        size_bytes: body.size_bytes || 0,
        owner_id: me.id,
        created_at: iso(),
      }
      if (s.files.some((row) => row.id === file.id)) fail('附件编号已存在，请重新上传', 409)
      s.files.push(file)
      return handled(file)
    }
    if (path === '/admin/employees' && method === 'POST') {
      admin()
      if (
        typeof body.displayName !== 'string' ||
        !body.displayName.trim() ||
        body.displayName.length > 100
      ) {
        fail('请填写员工姓名（1至100字）', 400)
      }
      for (const [key, list] of [
        ['departmentId', s.departments],
        ['positionId', s.positions],
        ['gradeId', s.grades],
      ]) {
        if (body[key] != null && !list.some((row) => row.id === body[key]))
          fail('所选部门、岗位或职级不存在', 400)
      }
      let username
      do {
        username =
          'EMP' +
          [...random(12)]
            .map((value) => value.toString(16).padStart(2, '0'))
            .join('')
            .toUpperCase()
      } while (s.users.some((user) => user.username === username))
      const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
      const temporaryPassword =
        'Aa0' + [...random(17)].map((value) => alphabet[value % alphabet.length]).join('')
      const user = {
        id: next(s.users),
        username,
        display_name: body.displayName.trim(),
        department_id: body.departmentId ?? null,
        position_id: body.positionId ?? null,
        grade_id: body.gradeId ?? null,
        active: true,
        roles: ['EMPLOYEE'],
        must_change_password: true,
        credentials_simulated: true,
      }
      s.users.push(user)
      log('EMPLOYEE_CREATE', user.id, '创建员工并自动生成账号（离线演示凭据）')
      return handled({
        user,
        account: { username, temporaryPassword, requiresPasswordChange: true },
      })
    }
    if ((match = path.match(/^\/courses\/(\d+)\/materials$/)) && method === 'POST') {
      const id = Number(match[1])
      editable(id)
      if (typeof body.title !== 'string' || !body.title.trim() || body.title.length > 200)
        fail('请填写资料标题', 400)
      const asset = body.assetId ? s.files.find((row) => row.id === body.assetId) : null
      const inferred =
        asset?.content_type === 'application/pdf'
          ? 'PDF'
          : asset?.content_type?.includes('powerpoint') ||
              asset?.content_type?.includes('presentationml')
            ? 'PPT'
            : 'TEXT'
      const row = {
        id: next(s.materials),
        course_id: id,
        title: body.title.trim(),
        content: body.content ?? '',
        asset_id: body.assetId ?? null,
        material_type: body.materialType || inferred,
        external_url: body.externalUrl || null,
        required: body.required ?? false,
      }
      if (
        typeof row.content !== 'string' ||
        row.content.length > 200000 ||
        typeof row.required !== 'boolean'
      )
        fail('资料内容或必读标记不正确', 400)
      validateMaterial(row)
      s.materials.push(row)
      return handled(materialView(row))
    }
    if ((match = path.match(/^\/courses\/(\d+)\/submit-review$/)) && method === 'POST') {
      const id = Number(match[1])
      const row = editable(id)
      const videos = s.lessons.filter((lesson) => lesson.course_id === id)
      const materials = s.materials.filter(
        (material) => material.course_id === id && material.required,
      )
      if ((!videos.length && !materials.length) || videos.some((video) => !video.video_asset_id))
        fail('请配置视频章节或必读资料', 400)
      materials.forEach(validateMaterial)
      row.status = 'PENDING'
      return handled()
    }
    if ((match = path.match(/^\/materials\/(\d+)\/acknowledge$/)) && method === 'POST') {
      const material =
        s.materials.find((row) => row.id === Number(match[1])) || fail('资料不存在', 404)
      if (!enrolled(me.id, material.course_id) || course(material.course_id).status !== 'PUBLISHED')
        fail('课程未分配或尚未发布', 403)
      if (
        !s.materialAcknowledgements.some(
          (ack) => ack.user_id === me.id && ack.material_id === material.id,
        )
      ) {
        s.materialAcknowledgements.push({
          user_id: me.id,
          material_id: material.id,
          acknowledged_at: iso(),
        })
      }
      return handled(status(material.course_id, me.id, true))
    }
    if ((match = path.match(/^\/courses\/(\d+)\/learning-status$/)) && method === 'GET') {
      const id = Number(match[1])
      const row = course(id)
      if (
        !isAdmin &&
        row.owner_id !== me.id &&
        !(row.status === 'PUBLISHED' && enrolled(me.id, id))
      )
        fail('课程未分配或尚未发布', 403)
      return handled(status(id))
    }
    if (['/teaching/grading', '/teaching/pending'].includes(path) && method === 'GET') {
      return handled(
        s.attempts
          .filter((attempt) => attempt.status === 'PENDING_GRADE' && canGrade(attempt))
          .map((attempt) => ({
            ...attempt,
            answers: undefined,
            title: s.exams.find((exam) => exam.id === attempt.exam_id)?.title,
          })),
      )
    }
    if ((match = path.match(/^\/teaching\/attempts\/([^/]+)(\/grade)?$/))) {
      const attempt = s.attempts.find((row) => row.id === match[1]) || fail('答卷不存在', 404)
      const view = teachingAttempt(attempt)
      if (match[2] && method === 'PUT') {
        if (attempt.status !== 'PENDING_GRADE') fail('答卷已经完成阅卷', 409)
        const question = view.questions.find((row) => row.id === body.questionId)
        if (
          !question ||
          question.type !== 'TEXT' ||
          !Number.isInteger(body.score) ||
          body.score < 0 ||
          body.score > question.max_score ||
          (body.feedback != null &&
            (typeof body.feedback !== 'string' || body.feedback.length > 1000))
        )
          fail('评分或评语不合法', 400)
        let answer = attempt.answers.find((row) => row.paper_question_id === question.id)
        if (answer?.score != null) fail('本题已经评分，请刷新后查看', 409)
        if (!answer) {
          answer = { paper_question_id: question.id, answer: '' }
          attempt.answers.push(answer)
        }
        Object.assign(answer, {
          score: body.score,
          grading_feedback: body.feedback || null,
          grader_id: me.id,
        })
        const paper = s.papers.find((row) => row.id === view.exam.paper_id)
        const complete = paper.items.every(
          (item) => attempt.answers.find((row) => row.paper_question_id === item.id)?.score != null,
        )
        if (complete) {
          attempt.score = attempt.answers.reduce((total, row) => total + row.score, 0)
          attempt.status = 'GRADED'
          attempt.passed = attempt.score >= view.exam.pass_score
          notice(attempt.user_id, '考试阅卷已完成', view.exam.title + '；成绩：' + attempt.score)
        }
        log('EXAM_GRADE', attempt.id, '题目 #' + question.id + '；分数：' + body.score)
        return handled()
      }
      if (!match[2] && method === 'GET') return handled(view)
      fail('请求方法不支持', 405)
    }
    return { handled: false }
  }
  return { dispatch, status, materialsFor }
}
