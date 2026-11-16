export const POSITION_TYPES = [
  { value: 'PROFESSIONAL', label: '专业岗' },
  { value: 'MANAGEMENT', label: '管理岗' },
  { value: 'GENERAL', label: '综合岗' },
]

export const ROLE_LABELS = {
  EMPLOYEE: '普通员工',
  INSTRUCTOR: '内训师',
  MANAGER: '部门负责人',
  ADMIN: '组织发展部',
}

export function positionLabel(position) {
  return position?.name || '未设置岗位'
}
