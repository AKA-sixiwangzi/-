function invalid(message) {
  const error = new Error(message)
  error.status = 400
  throw error
}

// Older routes without department metadata remain applicable to every department.
export function isAllDepartments(path) {
  return path?.all_departments !== false && path?.all_departments !== 0
}

export function pathDepartmentIds(path) {
  return [...new Set(path?.department_ids || [])]
}

export function departmentScopeLabel(path, catalog = []) {
  if (isAllDepartments(path)) return '所有部门'
  const departments = path.departments?.length ? path.departments : catalog
  const names = pathDepartmentIds(path).map(
    (id) => departments.find((d) => d.id === id)?.name || `部门 #${id}`,
  )
  return names.length ? names.join('、') : '尚未设置适用部门'
}

export function userMatchesDepartmentScope(user, path) {
  return isAllDepartments(path) || pathDepartmentIds(path).includes(user.department_id)
}

export function normalizeDepartmentScope(allDepartments, departmentIds, catalog) {
  if (departmentIds != null && !Array.isArray(departmentIds)) {
    invalid('适用部门格式不正确')
  }
  const ids = [...new Set(departmentIds || [])].sort((a, b) => a - b)
  const all = allDepartments == null ? ids.length === 0 : allDepartments
  if (typeof all !== 'boolean') invalid('适用部门范围不正确')
  if (all && ids.length) invalid('所有部门不能同时指定部门')
  if (!all && !ids.length) invalid('请至少选择一个适用部门')
  if (ids.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    invalid('适用部门编号不正确')
  }
  if (ids.some((id) => !catalog.some((department) => department.id === id))) {
    invalid('所选部门不存在，请刷新后重试')
  }
  return {
    all_departments: all,
    department_ids: ids,
    departments: catalog.filter((department) => ids.includes(department.id)),
  }
}

export function departmentScopeView(path, catalog) {
  const all = isAllDepartments(path)
  const ids = all ? [] : pathDepartmentIds(path)
  return {
    ...path,
    all_departments: all,
    department_ids: ids,
    departments: catalog.filter((department) => ids.includes(department.id)),
  }
}
