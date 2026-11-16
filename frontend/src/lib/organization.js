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

export function positionTypeLabel(position) {
  return POSITION_TYPES.find((type) => type.value === position?.position_type)?.label || '综合岗'
}

export function positionLabel(position) {
  return position ? `${positionTypeLabel(position)} · ${position.name}` : '未设置岗位'
}
