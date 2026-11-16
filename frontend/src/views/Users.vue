<script setup>
import { ref, computed } from 'vue'
import { api, perform, ui, session, loadMe } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { POSITION_TYPES, ROLE_LABELS, positionLabel } from '../lib/organization.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const rows = ref([]),
  org = ref({ departments: [], positions: [], grades: [] }),
  search = ref(''),
  tab = ref('users'),
  dialog = ref(''),
  selected = ref(null),
  form = ref({}),
  role = ref('INSTRUCTOR'),
  enabled = ref(true),
  department = ref(null),
  orgType = ref('departments'),
  orgName = ref(''),
  positionType = ref('GENERAL')
const { loading, error, reload } = useLoad(
  async () =>
    ([rows.value, org.value] = await Promise.all([api('/admin/users'), api('/organization')])),
)
const filtered = computed(() =>
  rows.value.filter((user) => (user.display_name + ' ' + user.username).includes(search.value)),
)
const positionGroups = computed(() =>
  POSITION_TYPES.map((type) => ({
    ...type,
    positions: org.value.positions.filter(
      (position) => (position.position_type || 'GENERAL') === type.value,
    ),
  })),
)
const managedDepartments = computed(() => {
  const ids = selected.value?.manager_department_ids || selected.value?.scopes || []
  return org.value.departments.filter((item) => ids.includes(item.id))
})
const title = computed(() => {
  if (dialog.value === 'create') return '添加员工'
  if (dialog.value === 'profile') return '编辑员工资料'
  if (dialog.value === 'role') return '设置员工角色'
  if (dialog.value === 'scope') return '配置负责人部门范围'
  if (dialog.value === 'position') return '编辑具体岗位'
  return '新增组织字典'
})
function openUserDialog(user, type) {
  selected.value = user
  if (type === 'profile')
    form.value = {
      displayName: user.display_name,
      departmentId: user.department_id ?? null,
      positionId: user.position_id ?? null,
      gradeId: user.grade_id ?? null,
    }
  if (type === 'role') {
    role.value = 'INSTRUCTOR'
    enabled.value = true
  }
  if (type === 'scope') department.value = null
  dialog.value = type
}
function create() {
  selected.value = null
  form.value = {
    username: '',
    password: '',
    displayName: '',
    departmentId: null,
    positionId: null,
    gradeId: null,
  }
  dialog.value = 'create'
}
function openOrganization(type = 'departments') {
  selected.value = null
  orgType.value = type
  orgName.value = ''
  positionType.value = 'GENERAL'
  dialog.value = 'org'
}
function editPosition(position) {
  selected.value = position
  orgName.value = position.name
  positionType.value = position.position_type || 'GENERAL'
  dialog.value = 'position'
}
function userRoles(user) {
  return [...new Set(['EMPLOYEE', ...(user.roles || [])])]
}
async function revokeDepartment(id) {
  await perform(async () => {
    const userId = selected.value.id
    await api('/admin/users/' + userId + '/manager-scope/' + id, 'DELETE')
    await reload()
    selected.value = rows.value.find((user) => user.id === userId)
  }, '已撤销该部门的管理范围')
}
async function submit() {
  await perform(async () => {
    if (dialog.value === 'create') await api('/admin/users', 'POST', form.value)
    else if (dialog.value === 'profile') {
      await api('/admin/users/' + selected.value.id, 'PUT', form.value)
      if (selected.value.id === session.me?.user.id) await loadMe()
    }
    else if (dialog.value === 'role')
      await api('/admin/users/' + selected.value.id + '/roles', 'PUT', {
        role: role.value,
        enabled: enabled.value,
      })
    else if (dialog.value === 'scope')
      await api('/admin/users/' + selected.value.id + '/manager-scope', 'POST', {
        departmentId: department.value,
      })
    else if (dialog.value === 'position')
      await api('/admin/positions/' + selected.value.id, 'PUT', {
        name: orgName.value,
        positionType: positionType.value,
      })
    else
      await api('/admin/' + orgType.value, 'POST', {
        name: orgName.value,
        parentId: null,
        sortOrder: 0,
        ...(orgType.value === 'positions' ? { positionType: positionType.value } : {}),
      })
    dialog.value = ''
    await reload()
  }, '已保存')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">PEOPLE & ORGANIZATION</p>
      <h1>组织与人员</h1>
      <p>保留具体岗位，按三种岗位类型管理；一个员工账号可以兼任多种角色。</p>
    </div>
    <div class="btn-row">
      <button class="btn white" @click="openOrganization()">
        <Icon name="building" :size="16" />新增组织字典</button
      ><button class="btn" @click="create"><Icon name="plus" :size="16" />添加员工</button>
    </div>
  </div>
  <div class="notice">
    普通员工可以学习和创作课程，课程审核发布后兼任内训师；部门负责人按授权部门管理；组织发展部负责培训管理、审批与课程授权。
  </div>
  <div class="panel filter-bar">
    <div class="tabs">
      <button :class="{ active: tab === 'users' }" @click="tab = 'users'">员工列表</button>
      <button :class="{ active: tab === 'positions' }" @click="tab = 'positions'">岗位与类型</button>
    </div>
    <div v-if="tab === 'users'" class="search-input">
      <Icon name="search" :size="16" /><input
        v-model="search"
        placeholder="筛选本页姓名 / 账号"
        aria-label="筛选员工"
      />
    </div>
    <button v-else class="btn white" @click="openOrganization('positions')">
      <Icon name="plus" :size="16" />新增具体岗位
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload">
    <template v-if="tab === 'users'">
      <div class="table-wrap section-spacer" v-if="filtered.length">
        <table>
          <thead>
            <tr>
              <th>员工</th>
              <th>账号</th>
              <th>部门</th>
              <th>岗位类型 / 具体岗位 / 职级</th>
              <th>角色</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in filtered" :key="user.id">
              <td>
                <strong>{{ user.display_name }}</strong>
                <small class="muted"> #{{ user.id }}</small>
              </td>
              <td>{{ user.username }}</td>
              <td>
                {{ org.departments.find((item) => item.id === user.department_id)?.name || '未设置' }}
              </td>
              <td>
                {{ positionLabel(org.positions.find((item) => item.id === user.position_id)) }}
                <small class="muted job-grade">
                  {{ org.grades.find((item) => item.id === user.grade_id)?.name || '职级未设置' }}
                </small>
              </td>
              <td>
                <div class="role-tags">
                  <span v-for="item in userRoles(user)" :key="item" class="tag">{{
                    ROLE_LABELS[item] || item
                  }}</span>
                </div>
              </td>
              <td>
                <span class="status" :class="user.active ? 'completed' : 'archived'">{{
                  user.active ? '启用' : '停用'
                }}</span>
              </td>
              <td>
                <div class="actions user-actions">
                  <button class="text-link" @click="openUserDialog(user, 'profile')">编辑资料</button>
                  <button
                    class="text-link"
                    :disabled="user.id === session.me?.user.id"
                    @click="openUserDialog(user, 'role')"
                  >设置角色</button>
                  <button
                    class="text-link"
                    :disabled="!user.roles?.includes('MANAGER')"
                    @click="openUserDialog(user, 'scope')"
                  >部门范围</button>
                  <button
                    class="text-link"
                    :disabled="user.id === session.me?.user.id"
                    @click="
                      perform(async () => {
                        await api('/admin/users/' + user.id + '/active', 'PUT', { active: !user.active })
                        await reload()
                      }, '账号状态已更新')
                    "
                  >{{ user.active ? '停用' : '启用' }}</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="没有符合条件的员工" />
    </template>
    <div v-else class="position-groups section-spacer">
      <section v-for="group in positionGroups" :key="group.value" class="panel position-group">
        <header class="panel-head">
          <h2>{{ group.label }}</h2>
          <span class="tag">{{ group.positions.length }} 个具体岗位</span>
        </header>
        <div v-if="group.positions.length" class="position-list">
          <div v-for="position in group.positions" :key="position.id" class="position-row">
            <span>{{ position.name }}</span>
            <button class="text-link" @click="editPosition(position)">编辑岗位</button>
          </div>
        </div>
        <p v-else class="muted position-empty">暂未设置具体岗位，可新增后归入此类型。</p>
      </section>
    </div>
  </LoadState>
  <Modal v-if="dialog" :title="title" @close="dialog = ''">
    <form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'create' || dialog === 'profile'">
          <label class="field">
            <span>员工姓名</span>
            <input v-model="form.displayName" required maxlength="100" />
          </label>
          <template v-if="dialog === 'create'">
            <label class="field">
              <span>登录账号</span>
              <input
                v-model="form.username"
                required
                pattern="[A-Za-z0-9_.@\-]{3,80}"
                placeholder="字母、数字等，至少3位"
              />
            </label>
            <label class="field span-2">
              <span>初始密码</span>
              <input
                v-model="form.password"
                type="password"
                required
                minlength="12"
                maxlength="72"
                autocomplete="new-password"
              />
            </label>
          </template>
          <label class="field">
            <span>部门</span>
            <select v-model="form.departmentId">
              <option :value="null">暂不设置</option>
              <option v-for="item in org.departments" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
          <label class="field">
            <span>具体岗位</span>
            <select v-model="form.positionId">
              <option :value="null">暂不设置</option>
              <optgroup v-for="group in positionGroups" :key="group.value" :label="group.label">
                <option v-for="item in group.positions" :key="item.id" :value="item.id">{{ item.name }}</option>
              </optgroup>
            </select>
          </label>
          <label class="field">
            <span>职级</span>
            <select v-model="form.gradeId">
              <option :value="null">暂不设置</option>
              <option v-for="item in org.grades" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
        </template>
        <template v-else-if="dialog === 'role'">
          <p class="notice span-2">
            正在设置 {{ selected.display_name }} 的角色。普通员工身份始终保留，可以兼任其他角色。变更后需要重新登录。
          </p>
          <label class="field">
            <span>角色</span>
            <select v-model="role">
              <option value="EMPLOYEE" disabled>普通员工（默认保留）</option>
              <option value="INSTRUCTOR">内训师</option>
              <option value="MANAGER">部门负责人</option>
              <option value="ADMIN">组织发展部</option>
            </select>
          </label>
          <label class="field">
            <span>操作</span>
            <select v-model="enabled">
              <option :value="true">授予角色</option>
              <option :value="false">撤销角色</option>
            </select>
          </label>
        </template>
        <template v-else-if="dialog === 'scope'">
          <p class="notice span-2">
            为 {{ selected.display_name }} 添加可管理的部门。此范围用于部门看板和加课需求，不改变其本人所属部门。
          </p>
          <div class="field span-2">
            <span>当前授权部门</span>
            <div v-if="managedDepartments.length" class="managed-departments">
              <div v-for="item in managedDepartments" :key="item.id" class="managed-department">
                <span>{{ item.name }}</span>
                <button
                  type="button"
                  class="text-link"
                  :disabled="ui.busy > 0"
                  :aria-label="'撤销' + item.name + '管理范围'"
                  @click="revokeDepartment(item.id)"
                >撤销</button>
              </div>
            </div>
            <p v-else class="muted position-empty">尚未授权管理部门。</p>
          </div>
          <label class="field span-2">
            <span>授权管理部门</span>
            <select v-model="department" required>
              <option :value="null" disabled>选择部门</option>
              <option v-for="item in org.departments" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
          </label>
        </template>
        <template v-else>
          <label v-if="dialog === 'org'" class="field">
            <span>字典类型</span>
            <select v-model="orgType">
              <option value="departments">部门</option>
              <option value="positions">具体岗位</option>
              <option value="grades">职级</option>
            </select>
          </label>
          <label class="field">
            <span>{{ dialog === 'position' || orgType === 'positions' ? '具体岗位名称' : '名称' }}</span>
            <input v-model="orgName" required maxlength="100" />
          </label>
          <label v-if="dialog === 'position' || orgType === 'positions'" class="field">
            <span>岗位类型</span>
            <select v-model="positionType" required>
              <option v-for="item in POSITION_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <p v-if="dialog === 'position'" class="notice span-2">
            修改岗位类型会更新该岗位的分类，已关联的员工、成长路线和学习记录继续保留。
          </p>
        </template>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button>
        <button class="btn" :disabled="ui.busy > 0">确认保存</button>
      </footer>
    </form>
  </Modal>
</template>
<style scoped>
.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  min-width: 100px;
  max-width: 190px;
}
.job-grade {
  display: block;
  margin-top: 5px;
}
.user-actions {
  flex-wrap: wrap;
  max-width: 190px;
}
.position-groups {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}
.position-group { padding: 22px; }
.position-list { margin-top: 16px; }
.position-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  font-size: 12px;
  border-top: 1px solid #edf1f7;
}
.position-row > span { overflow-wrap: anywhere; }
.position-row button { flex-shrink: 0; }
.position-empty { margin-top: 16px; font-size: 12px; line-height: 1.8; }
.managed-departments { display: flex; flex-wrap: wrap; gap: 8px; }
.managed-department {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid #e2eaf5;
  border-radius: 6px;
  font-size: 12px;
}
@media (max-width: 900px) {
  .position-groups { grid-template-columns: 1fr; }
}
</style>
