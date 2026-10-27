export function coverage(segments) {
  let total = 0,
    start = -1,
    end = -1
  for (const s of [...segments].sort((a, b) => a.start - b.start)) {
    if (start < 0) {
      start = s.start
      end = s.end
    } else if (s.start <= end) end = Math.max(end, s.end)
    else {
      total += end - start
      start = s.start
      end = s.end
    }
  }
  return total + (start < 0 ? 0 : end - start)
}
export function credit({ playing, previous, position, elapsed, visible, rate, event }) {
  if (event === 'SEEK' || !playing || !visible || rate !== 1 || elapsed <= 0 || elapsed > 30000)
    return 0
  const delta = position - previous,
    wall = elapsed / 1000
  return delta < 0 || delta > wall * 1.05 + 0.25 ? 0 : Math.min(delta, wall)
}
export function allowed(roles, required = []) {
  return !required.length || required.some((r) => roles.includes(r))
}
export function sameChoices(expected, actual) {
  const norm = (s) =>
    [
      ...new Set(
        String(s)
          .toUpperCase()
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean),
      ),
    ]
      .sort()
      .join(',')
  return norm(expected) === norm(actual)
}
export const statusText = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  ARCHIVED: '已下架',
  OPEN: '待处理',
  INVESTIGATING: '核实中',
  REVIEW: '待复核',
  CLOSED: '已关闭',
  READY: '待考试',
  LEARNING_REQUIRED: '待完成学习',
  IN_PROGRESS: '考试中',
  PENDING_GRADE: '待阅卷',
  GRADED: '已阅卷',
  RETAKE: '待补考',
  COMPLETED: '已通过',
  ATTEMPTS_EXHAUSTED: '次数已用完',
  VALID: '有效',
  REVOKED: '已撤销',
  APPROVED: '已审核',
}
export const dateText = (v) =>
  v
    ? new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(String(v)) ? v : String(v) + 'Z'))
    : '—'
export const minutes = (v) => Math.round((Number(v) || 0) / 60)
