<script setup>
import { ref, computed } from 'vue'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
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
  orgName = ref('')
const { loading, error, reload } = useLoad(
  async () =>
    ([rows.value, org.value] = await Promise.all([api('/admin/users'), api('/organization')])),
)
const filtered = computed(() =>
  rows.value.filter((u) => (u.display_name + ' ' + u.username).includes(search.value)),
)
function openUserDialog(user,type){selected.value=user;dialog.value=type}
function create() {
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
async function submit() {
  await perform(async () => {
    if (dialog.value === 'create') await api('/admin/users', 'POST', form.value)
    else if (dialog.value === 'role')
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
      <p>一个员工账号，多种工作身份；管理权限与数据范围分别配置。</p>
    </div>
    <div class="btn-row">
      <button class="btn white" @click="dialog = 'org'">
        <Icon name="building" :size="16" />新增组织字典</button
      ><button class="btn" @click="create"><Icon name="plus" :size="16" />添加员工</button>
    </div>
  </div>
  <div class="notice">
    所有员工默认可以学习和创作课程。发布课程后自动兼任讲师；部门主管还需配置授权部门，不能默认查看全公司数据。
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
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="table-wrap section-spacer" v-if="filtered.length">
      <table>
        <thead>
          <tr>
            <th>员工</th>
            <th>账号</th>
            <th>部门</th>
            <th>岗位 / 职级</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in filtered" :key="u.id">
            <td>
              <strong>{{ u.display_name }}</strong
              ><small class="muted"> #{{ u.id }}</small>
            </td>
            <td>{{ u.username }}</td>
            <td>{{ org.departments.find((d) => d.id === u.department_id)?.name || '未设置' }}</td>
            <td>
              {{ org.positions.find((d) => d.id === u.position_id)?.name || '—' }} /
              {{ org.grades.find((d) => d.id === u.grade_id)?.name || '—' }}
            </td>
            <td>
              <span class="status" :class="u.active ? 'completed' : 'archived'">{{
                u.active ? '启用' : '停用'
              }}</span>
            </td>
            <td>
              <div class="actions">
                <button
                  class="text-link"
                  :disabled="u.id === session.me?.user.id"
                  @click="openUserDialog(u,'role')"
                >
                  设置角色</button
                ><button
                  class="text-link"
                  @click="openUserDialog(u,'scope')"
                >
                  部门范围</button
                ><button
                  class="text-link"
                  :disabled="u.id === session.me?.user.id"
                  @click="
                    perform(async () => {
                      await api('/admin/users/' + u.id + '/active', 'PUT', { active: !u.active })
                      await reload()
                    }, '账号状态已更新')
                  "
                >
                  {{ u.active ? '停用' : '启用' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty v-else title="没有符合条件的员工" /></LoadState
  ><Modal
    v-if="dialog"
    :title="
      dialog === 'create'
        ? '添加员工'
        : dialog === 'role'
          ? '设置员工角色'
          : dialog === 'scope'
            ? '配置主管部门范围'
            : '新增组织字典'
    "
    @close="dialog = ''"
    ><form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'create'"
          ><label class="field"
            ><span>员工姓名</span
            ><input v-model="form.displayName" required maxlength="100" /></label
          ><label class="field"
            ><span>登录账号</span
            ><input
              v-model="form.username"
              required
              pattern="[A-Za-z0-9_.@\-]{3,80}"
              placeholder="字母、数字等，至少3位" /></label
          ><label class="field span-2"
            ><span>初始密码</span
            ><input
              v-model="form.password"
              type="password"
              required
              minlength="12"
              maxlength="72"
              autocomplete="new-password" /></label
          ><label
            v-for="[key, label, list] in [
              ['departmentId', '部门', org.departments],
              ['positionId', '岗位', org.positions],
              ['gradeId', '职级', org.grades],
            ]"
            :key="key"
            class="field"
            ><span>{{ label }}</span
            ><select v-model="form[key]">
              <option :value="null">暂不设置</option>
              <option v-for="x in list" :key="x.id" :value="x.id">{{ x.name }}</option>
            </select></label
          ></template
        ><template v-if="dialog === 'role'"
          ><p class="notice span-2">
            正在设置
            {{
              selected.display_name
            }}
            的角色。变更将使其现有登录失效，需要重新登录；普通员工身份始终保留。
          </p>
          <label class="field"
            ><span>角色</span
            ><select v-model="role">
              <option value="INSTRUCTOR">讲师</option>
              <option value="MANAGER">部门主管</option>
              <option value="ADMIN">管理员</option>
            </select></label
          ><label class="field"
            ><span>操作</span
            ><select v-model="enabled">
              <option :value="true">授予角色</option>
              <option :value="false">撤销角色</option>
            </select></label
          ></template
        ><template v-if="dialog === 'scope'"
          ><p class="notice span-2">
            先授予部门主管角色，再添加授权部门。此处不会改变员工本人的所属部门。
          </p>
          <label class="field span-2"
            ><span>可查看的部门</span
            ><select v-model="department" required>
              <option :value="null" disabled>选择部门</option>
              <option v-for="d in org.departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select></label
          ></template
        ><template v-if="dialog === 'org'"
          ><label class="field"
            ><span>字典类型</span
            ><select v-model="orgType">
              <option value="departments">部门</option>
              <option value="positions">岗位</option>
              <option value="grades">职级</option>
            </select></label
          ><label class="field"
            ><span>名称</span><input v-model="orgName" required maxlength="100" /></label
        ></template>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button
        ><button class="btn" :disabled="ui.busy > 0">确认保存</button>
      </footer>
    </form></Modal
  >
</template>
