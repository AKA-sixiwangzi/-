<script setup>
import { ref, computed } from 'vue'
import { api, download, session, perform, toast } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const data = ref(null),
  page = ref(0),
  details = ref([]),
  search = ref('')
const { loading, error, reload } = useLoad(async () => {
  data.value = await api('/dashboards/company')
  details.value = data.value.details || []
  page.value = 0
})
const ratio = computed(() =>
    data.value?.summary.assigned_tasks
      ? Math.round((data.value.summary.completed_tasks / data.value.summary.assigned_tasks) * 100)
      : 0,
  ),
  filtered = computed(() =>
    details.value.filter((r) => (r.display_name + ' ' + r.title).includes(search.value)),
  )
async function paginate(delta) {
  await perform(async () => {
    const offset = Math.max(0, page.value + delta)
    const list = await api('/dashboards/company/details?offset=' + offset)
    details.value = list
    page.value = offset
  })
}
async function exportReport() {
  if (session.mode === 'api')
    return perform(() => download('/reports/training.csv', '培训任务明细.csv'))
  const rows = data.value.details
  const cell = (v) => '"' + String(v ?? '').replace(/"/g, '""') + '"'
  const csv =
      '\uFEFF员工,课程,完成状态\r\n' +
      rows
        .map((r) =>
          [r.display_name, r.title, r.completed ? '已完成' : '待完成'].map(cell).join(','),
        )
        .join('\r\n'),
    url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })),
    a = document.createElement('a')
  a.href = url
  a.download = '演示-培训明细.csv'
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">ORGANIZATION LEARNING INSIGHTS</p>
      <h1>{{ session.me?.roles.includes('ADMIN') ? '公司培训看板' : '部门培训看板' }}</h1>
      <p>从任务到结果，了解组织学习的每一份进展。</p>
    </div>
    <div class="btn-row">
      <button class="btn white" @click="reload"><Icon name="refresh" :size="16" />刷新</button
      ><button class="btn" @click="exportReport">
        <Icon name="download" :size="16" />导出任务明细
      </button>
    </div>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><template v-if="data"
      ><div class="kpi-grid">
        <article
          class="kpi"
          v-for="s in [
            {
              label: '参训员工',
              value: data.summary.learner_count,
              unit: '人',
              icon: 'users',
              color: 'blue',
            },
            {
              label: '已分配任务',
              value: data.summary.assigned_tasks,
              unit: '项',
              icon: 'list',
              color: 'violet',
            },
            {
              label: '已完成任务',
              value: data.summary.completed_tasks,
              unit: '项',
              icon: 'checks',
              color: 'green',
            },
            {
              label: '任务完成率',
              value: data.summary.assigned_tasks ? ratio : '—',
              unit: '%',
              icon: 'chart',
              color: 'gold',
            },
          ]"
          :key="s.label"
        >
          <div class="kpi-top">
            {{ s.label }}<i :class="s.color"><Icon :name="s.icon" /></i>
          </div>
          <div class="kpi-value">
            {{ s.value }}<small>{{ s.unit }}</small>
          </div>
          <p>口径：授权范围内已分配的人课任务</p>
        </article>
      </div>
      <div class="cards-2">
        <section class="panel">
          <header class="panel-head">
            <h2>部门学习进度</h2>
            <span class="caption">完成任务 / 分配任务</span>
          </header>
          <div v-for="d in data.departments" :key="d.id" class="chart-row">
            <span>{{ d.name }}</span>
            <div class="progress">
              <i
                :style="{
                  width:
                    (d.assigned_tasks ? (d.completed_tasks / d.assigned_tasks) * 100 : 0) + '%',
                }"
              />
            </div>
            <b>{{ d.completed_tasks }} / {{ d.assigned_tasks }}</b>
          </div>
          <Empty v-if="!data.departments.length" title="暂无部门统计" />
        </section>
        <section class="panel completion-panel">
          <header class="panel-head">
            <h2>整体任务完成情况</h2>
            <span class="tag">按任务计数</span>
          </header>
          <div class="donut-row">
            <div
              class="donut"
              :style="{ background: `conic-gradient(#003f98 ${ratio}%,#edf2f9 0)` }"
            >
              <div>
                <b>{{ data.summary.assigned_tasks ? ratio + '%' : '—' }}</b
                ><small>任务完成率</small>
              </div>
            </div>
            <div class="donut-legend">
              <p>
                <i />已完成 <b>{{ data.summary.completed_tasks }}</b>
              </p>
              <p>
                <i />待完成 <b>{{ data.summary.assigned_tasks - data.summary.completed_tasks }}</b>
              </p>
              <span>同一员工学习多门课程<br />按多个人课任务统计。</span>
            </div>
          </div>
        </section>
      </div>
      <section v-if="data.plans?.length" class="panel section-spacer">
        <header class="panel-head">
          <h2>培训计划达成</h2>
          <span class="caption">需同时完成计划课程与关联考试</span>
        </header>
        <div v-for="p in data.plans" :key="p.id" class="plan-progress">
          <div>
            <strong>{{ p.title }}</strong
            ><small>截止 {{ dateText(p.due_at) }}</small>
          </div>
          <div class="progress">
            <i
              :style="{
                width:
                  (p.expected_people ? (p.completed_people / p.expected_people) * 100 : 0) + '%',
              }"
            />
          </div>
          <span>{{ p.completed_people }} / {{ p.expected_people }} 人</span>
        </div>
      </section>
      <section class="section-spacer">
        <header class="section-title">
          <div>
            <h2>任务明细</h2>
            <p>当前第 {{ Math.floor(page / 200) + 1 }} 页 · 每页最多200条</p>
          </div>
          <div class="search-input">
            <Icon name="search" :size="16" /><input
              v-model="search"
              placeholder="筛选本页姓名 / 课程"
              aria-label="筛选任务明细"
            />
          </div>
        </header>
        <div class="table-wrap" v-if="filtered.length">
          <table>
            <thead>
              <tr>
                <th>员工</th>
                <th>部门 ID</th>
                <th>课程</th>
                <th>状态</th>
                <th>完成时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in filtered" :key="r.id + '-' + r.course_id + '-' + i">
                <td>{{ r.display_name }}</td>
                <td>{{ r.department_id || '—' }}</td>
                <td>{{ r.title }}</td>
                <td>
                  <span :class="['status', r.completed ? 'completed' : 'draft']">{{
                    r.completed ? '已完成' : '待完成'
                  }}</span>
                </td>
                <td>{{ dateText(r.completed_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <Empty v-else title="暂无符合条件的任务" />
        <div class="pagination">
          <button class="btn white small" :disabled="page === 0" @click="paginate(-200)">
            上一页</button
          ><button class="btn white small" :disabled="details.length < 200" @click="paginate(200)">
            下一页
          </button>
        </div>
      </section>
      <p class="caption section-spacer">
        数据更新时间：{{ dateText(data.updatedAt) }} · 部门主管只查看明确授权部门。
      </p></template
    ></LoadState
  >
</template>
<style scoped>
.donut-row {
  display: flex;
  align-items: center;
  gap: 35px;
  justify-content: center;
  padding: 12px 5px;
}
.donut {
  height: 145px;
  width: 145px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.donut > div {
  width: 117px;
  height: 117px;
  border-radius: 50%;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
}
.donut b {
  font-size: 30px;
  color: #204b83;
}
.donut small {
  font-size: 10px;
  color: #95a7bd;
  margin-top: 6px;
}
.donut-legend p {
  font-size: 11px;
  color: #8399b6;
  display: flex;
  gap: 9px;
  align-items: center;
  margin: 14px 0;
}
.donut-legend b {
  color: #355a8b;
  margin-left: 6px;
}
.donut-legend i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #003f98;
}
.donut-legend p:nth-child(2) i {
  background: #dce5f3;
}
.donut-legend > span {
  display: block;
  font-size: 9px;
  color: #a2b0c3;
  line-height: 1.9;
  margin-top: 20px;
}
.plan-progress {
  display: grid;
  grid-template-columns: 260px 1fr 85px;
  gap: 25px;
  align-items: center;
  border-top: 1px solid #edf1f7;
  padding: 17px 0;
}
.plan-progress strong {
  font-size: 12px;
  font-weight: 500;
  color: #617c9d;
}
.plan-progress small {
  display: block;
  font-size: 9px;
  color: #a6b3c5;
  margin-top: 7px;
}
.plan-progress > span {
  font-size: 11px;
  color: #849bb9;
}
.pagination {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 15px;
}
@media (max-width: 650px) {
  .donut-row {
    gap: 20px;
  }
  .plan-progress {
    grid-template-columns: 1fr 80px;
    gap: 12px;
  }
  .plan-progress .progress {
    grid-column: 1/2;
    grid-row: 2;
  }
  .plan-progress > span {
    grid-column: 2;
    grid-row: 1/3;
  }
  .donut {
    width: 130px;
    height: 130px;
  }
  .donut > div {
    width: 105px;
    height: 105px;
  }
}
</style>
