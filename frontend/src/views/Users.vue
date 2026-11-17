<script setup>
import { ref, computed } from 'vue'
import { api, perform, ui, session, loadMe } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { ROLE_LABELS, positionLabel } from '../lib/organization.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const rows = ref([]),
  org = ref({ departments: [], positions: [], grades: [] }),
  search = ref(''),
  dialog = ref(''),
  selected = ref(null),
  form = ref({}),
  role = ref('INSTRUCTOR'),
  enabled = ref(true),
  department = ref(null),
  orgType = ref('departments'),
  orgName = ref(''),
  credentials = ref(null)
const { loading, error, reload } = useLoad(
  async () =>
    ([rows.value, org.value] = await Promise.all([api('/admin/users'), api('/organization')])),
)
const filtered = computed(() =>
  rows.value.filter((user) => (user.display_name + ' ' + user.username).includes(search.value)),
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
  dialog.value = 'org'
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
    if (dialog.value === 'create') {
      const created = await api('/admin/employees', 'POST', form.value)
      credentials.value = { ...created.account, displayName: form.value.displayName }
    } else if (dialog.value === 'profile') {
      await api('/admin/users/' + selected.value.id, 'PUT', form.value)
      if (selected.value.id === session.me?.user.id) await loadMe()
    } else if (dialog.value === 'role')
      await api('/admin/users/' + selected.value.id + '/roles', 'PUT', {
        role: role.value,
        enabled: enabled.value,
      })
    else if (dialog.value === 'scope')
      await api('/admin/users/' + selected.value.id + '/manager-scope', 'POST', {
        departmentId: department.value,
      })
    else
      await api('/admin/' + orgType.value, 'POST', {
        name: orgName.value,
        parentId: null,
        sortOrder: 0,
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
      <p>岗位统一为专业岗、管理岗、综合岗；一个员工账号可以兼任多种角色。</p>
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
    <div class="tabs"><button class="active">员工列表</button></div>
    <div class="search-input">
      <Icon name="search" :size="16" /><input
        v-model="search"
        placeholder="筛选本页姓名 / 账号"
        aria-label="筛选员工"
      />
    </div>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload">
    <div class="table-wrap section-spacer" v-if="filtered.length">
      <table>
        <thead>
          <tr>
            <th>员工</th>
            <th>账号</th>
            <th>部门</th>
            <th>岗位 / 职级</th>
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
                >
                  设置角色
                </button>
                <button
                  class="text-link"
                  :disabled="!user.roles?.includes('MANAGER')"
                  @click="openUserDialog(user, 'scope')"
                >
                  部门范围
                </button>
                <button
                  class="text-link"
                  :disabled="user.id === session.me?.user.id"
                  @click="
                    perform(async () => {
                      await api('/admin/users/' + user.id + '/active', 'PUT', {
                        active: !user.active,
                      })
                      await reload()
                    }, '账号状态已更新')
                  "
                >
                  {{ user.active ? '停用' : '启用' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty v-else title="没有符合条件的员工" />
  </LoadState>
  <Modal v-if="dialog" :title="title" @close="dialog = ''">
    <form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'create' || dialog === 'profile'">
          <label class="field">
            <span>员工姓名</span>
            <input v-model="form.displayName" required maxlength="100" />
          </label>
          <p v-if="dialog === 'create'" class="notice span-2">
            保存员工后自动生成关联账号和一次性初始密码，无需另外建账号。首次登录须修改密码。
          </p>
          <label class="field">
            <span>部门</span>
            <select v-model="form.departmentId">
              <option :value="null">暂不设置</option>
              <option v-for="item in org.departments" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>岗位</span>
            <select v-model="form.positionId">
              <option :value="null">暂不设置</option>
              <option v-for="item in org.positions" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>职级</span>
            <select v-model="form.gradeId">
              <option :value="null">暂不设置</option>
              <option v-for="item in org.grades" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
        </template>
        <template v-else-if="dialog === 'role'">
          <p class="notice span-2">
            正在设置
            {{
              selected.display_name
            }}
            的角色。普通员工身份始终保留，可以兼任其他角色。变更后需要重新登录。
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
            为
            {{
              selected.display_name
            }}
            添加可管理的部门。此范围用于部门看板和加课需求，不改变其本人所属部门。
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
                >
                  撤销
                </button>
              </div>
            </div>
            <p v-else class="muted position-empty">尚未授权管理部门。</p>
          </div>
          <label class="field span-2">
            <span>授权管理部门</span>
            <select v-model="department" required>
              <option :value="null" disabled>选择部门</option>
              <option v-for="item in org.departments" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </label>
        </template>
        <template v-else>
          <label class="field">
            <span>字典类型</span>
            <select v-model="orgType">
              <option value="departments">部门</option>
              <option value="grades">职级</option>
            </select>
          </label>
          <label class="field">
            <span>名称</span>
            <input v-model="orgName" required maxlength="100" />
          </label>
          <p class="notice span-2">岗位固定为专业岗、管理岗、综合岗，无需新增或维护岗位名称。</p>
        </template>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button>
        <button class="btn" :disabled="ui.busy > 0">确认保存</button>
      </footer>
    </form>
  </Modal>
  <Modal v-if="credentials && !dialog" title="员工与账号已创建" @close="credentials = null">
    <p class="notice">
      {{
        credentials.displayName
      }}的账号已关联。初始密码仅在本次显示，请记录并交给员工本人；首次登录必须改密。
    </p>
    <label class="field section-spacer"
      ><span>登录账号</span
      ><input
        readonly
        :value="credentials.username"
        autocomplete="off"
        @focus="$event.target.select()"
    /></label>
    <label class="field section-spacer"
      ><span>一次性初始密码</span
      ><input
        readonly
        :value="credentials.temporaryPassword"
        autocomplete="off"
        @focus="$event.target.select()"
    /></label>
    <p v-if="session.mode === 'demo'" class="caption section-spacer">
      当前为演示账号，不用于公司正式登录。
    </p>
    <footer class="form-actions">
      <button class="btn" @click="credentials = null">已记录，关闭</button>
    </footer>
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
.position-empty {
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.8;
}
.managed-departments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.managed-department {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid #e2eaf5;
  border-radius: 6px;
  font-size: 12px;
}
</style>
