<template>
  <section class="workflow-designer" :aria-busy="loading">
    <header class="designer-command-bar">
      <div class="command-leading">
        <button class="icon-button back-button" type="button" aria-label="返回工作流编排" @click="backToCenter">
          <ArrowLeftIcon />
        </button>
        <div class="workflow-identity">
          <span>{{ isNew ? '新建工作流' : '工作流设计器' }}</span>
          <input v-model.trim="form.name" maxlength="80" aria-label="工作流名称" placeholder="输入工作流名称" :disabled="Boolean(loadError) || editingLocked">
        </div>
      </div>

      <div class="command-context">
        <label>
          <span>适用场景</span>
          <select v-model="form.scenarioCode" aria-label="适用场景" :disabled="Boolean(loadError) || editingLocked">
            <option v-for="item in scenarios" :key="item.code" :value="item.code">{{ item.name }}</option>
          </select>
        </label>
      </div>

      <div class="command-actions">
        <div class="save-state" :class="saveState" role="status" aria-live="polite">
          <span></span>{{ saveStateLabel }}
        </div>
        <button class="secondary-action run-action" type="button" :title="runButtonHint" :disabled="Boolean(loadError) || loading || saving" @click="openRunSetup">
          <PlayIcon />{{ editingLocked ? '查看运行' : '试运行' }}
        </button>
        <button class="primary-action" type="button" :disabled="Boolean(loadError) || saving || loading || editingLocked" @click="saveWorkflow">
          {{ saving ? '保存中…' : '保存工作流' }}
        </button>
      </div>
    </header>

    <div v-if="notice" class="designer-notice" :class="noticeType" role="status" aria-live="polite">
      <span>{{ notice }}</span>
      <button type="button" aria-label="关闭提示" @click="notice = ''"><XMarkIcon /></button>
    </div>

    <div v-if="loading" class="designer-loading" role="status">
      <span class="loading-mark"></span>
      <strong>正在加载工作流</strong>
      <small>正在准备节点和画布布局…</small>
    </div>

    <div v-else-if="loadError" class="designer-load-error" role="alert">
      <span><ExclamationTriangleIcon /></span>
      <strong>无法打开这条工作流</strong>
      <p>{{ loadError }}</p>
      <div>
        <button class="secondary-action" type="button" @click="backToCenter"><ArrowLeftIcon />返回工作流中心</button>
        <button class="primary-action" type="button" @click="loadWorkflow">重新加载</button>
      </div>
    </div>

    <div
      v-else
      class="designer-workspace"
      :class="{
        'palette-collapsed': leftCollapsed,
        'inspector-collapsed': rightCollapsed,
        'list-mode': editorMode === 'list',
        'testing-mode': testPanelOpen,
        compact: isCompact
      }"
    >
      <button
        v-if="isCompact && (!leftCollapsed || !rightCollapsed)"
        class="panel-backdrop"
        type="button"
        aria-label="关闭侧边面板"
        @click="closeOverlayPanels"
      ></button>

      <aside id="workflow-node-palette" class="node-palette" :aria-hidden="leftCollapsed && isCompact">
        <header>
          <div><strong>节点库</strong><span>选择节点加入流程</span></div>
          <button class="icon-button" type="button" :aria-label="leftCollapsed ? '展开节点库' : '收起节点库'" @click="toggleLeftPanel">
            <ChevronDoubleLeftIcon />
          </button>
        </header>
        <div class="palette-content">
          <button
            v-for="type in nodeTypes"
            :key="type.value"
            type="button"
            :aria-label="`添加${type.label}节点`"
            :disabled="editingLocked"
            @click="addNode(type.value)"
          >
            <span class="type-symbol" :class="type.value"><component :is="nodeIcon(type.value)" /></span>
            <span><strong>{{ type.label }}</strong><small>{{ type.hint }}</small></span>
            <PlusIcon />
          </button>
        </div>
      </aside>

      <main class="designer-stage">
        <header class="stage-toolbar">
          <div class="stage-toolbar-leading">
            <button
              class="icon-button panel-toggle"
              type="button"
              aria-label="打开节点库"
              :aria-expanded="!leftCollapsed"
              aria-controls="workflow-node-palette"
              @click="toggleLeftPanel"
            >
              <Squares2X2Icon />
            </button>
            <div class="mode-switch" aria-label="编排模式">
              <button type="button" :aria-label="isPhone ? '画布预览' : '画布编排'" :aria-pressed="editorMode === 'canvas'" :class="{ active: editorMode === 'canvas' }" @click="setEditorMode('canvas')">
                <Squares2X2Icon /><span>{{ isPhone ? '画布预览' : '画布编排' }}</span>
              </button>
              <button type="button" aria-label="列表编排" :aria-pressed="editorMode === 'list'" :class="{ active: editorMode === 'list' }" @click="setEditorMode('list')">
                <QueueListIcon /><span>列表编排</span>
              </button>
            </div>
          </div>

          <div v-if="editorMode === 'canvas'" class="canvas-tools" aria-label="画布工具">
            <output aria-live="polite" title="当前画布缩放比例">{{ Math.round(canvasScale * 100) }}%</output>
            <button class="tool-button primary-tool" type="button" aria-label="自动排列节点" :disabled="editingLocked" @click="autoLayout">
              <Squares2X2Icon /><span>自动布局</span>
            </button>
          </div>

          <div class="stage-run-tools">
            <button v-if="lastRun && !testPanelOpen" class="tool-button history-tool" type="button" aria-label="查看最近运行" @click="showLastRun"><ClockIcon /><span>最近运行</span></button>
            <button v-if="isPhone" class="tool-button mobile-run-tool" type="button" :title="runButtonHint" :disabled="saving" @click="openRunSetup"><PlayIcon />{{ running ? '运行中' : '试运行' }}</button>
          </div>

          <button
            class="icon-button panel-toggle"
            type="button"
            aria-label="打开属性面板"
            :aria-expanded="!rightCollapsed"
            aria-controls="workflow-node-inspector"
            @click="toggleRightPanel"
          >
            <Cog6ToothIcon />
          </button>
        </header>

        <div v-if="editorMode === 'canvas'" ref="canvasElement" class="canvas-shell">
          <div v-if="isPhone" class="mobile-preview-note">
            手机端为画布预览模式，请切换到列表编排修改节点顺序。
          </div>
          <VueFlow
            id="workflow-designer-flow"
            class="workflow-flow"
            :nodes="flowNodes"
            :edges="flowEdges"
            :default-edge-options="defaultEdgeOptions"
            :min-zoom=".35"
            :max-zoom="1.8"
            :nodes-connectable="!editingLocked"
            :edges-updatable="false"
            :zoom-on-double-click="false"
            :fit-view-on-init="true"
            :elevate-nodes-on-select="true"
            aria-label="工作流节点画布"
            @node-click="handleFlowNodeClick"
            @connect="handleFlowConnect"
            @node-drag-stop="handleFlowNodeDragStop"
            @pane-click="clearNodeSelection"
            @viewport-change="handleViewportChange"
          >
            <Background variant="dots" :gap="20" :size="1.25" pattern-color="var(--flow-grid-dot)" />

            <template #node-workflow="{ data, selected }">
              <div
                class="flow-node-card"
                :class="[data.node.type, { selected, 'has-run': runVisible && runPhase !== 'setup' }, `run-${nodeRunStatus(data.node.id)}`]"
                :aria-label="`第${data.index + 1}步，${nodeTypeLabel(data.node.type)}，${data.node.name || '未命名节点'}${runVisible ? `，${stepStatusLabel(nodeRunStatus(data.node.id))}` : ''}`"
                @keydown="handleNodeKeydown($event, data.node.id)"
              >
                <Handle
                  v-if="data.index > 0 || data.hasIncoming"
                  type="target"
                  :position="data.targetPosition"
                  :connectable="!editingLocked"
                  class="flow-handle"
                />
                <span class="node-sequence">{{ data.index + 1 }}</span>
                <span class="node-symbol"><component :is="nodeIcon(data.node.type)" /></span>
                <span class="node-copy">
                  <small>{{ nodeTypeLabel(data.node.type) }}</small>
                  <strong>{{ data.node.name || '未命名节点' }}</strong>
                  <em v-show="canvasScale >= .62">{{ data.node.description || '选择节点补充处理说明' }}</em>
                </span>
                <span v-if="runVisible && runPhase !== 'setup'" class="node-run-status" :class="nodeRunStatus(data.node.id)">
                  <span class="run-status-mark"></span>{{ stepStatusLabel(nodeRunStatus(data.node.id)) }}<small v-if="runSteps[data.node.id]?.durationMs != null"> · {{ formatDuration(runSteps[data.node.id].durationMs) }}</small>
                </span>
                <Handle
                  v-if="data.node.type !== 'output' && data.node.type !== 'condition'"
                  type="source"
                  :position="data.sourcePosition"
                  :connectable="!editingLocked"
                  class="flow-handle"
                />
                <template v-if="data.node.type === 'condition'">
                  <Handle id="true" type="source" :position="Position.Bottom" :connectable="!editingLocked" class="flow-handle branch-true" title="满足条件" />
                  <Handle id="false" type="source" :position="Position.Right" :connectable="!editingLocked" class="flow-handle branch-false" title="不满足条件" />
                </template>
              </div>
            </template>

            <Controls position="bottom-left" :show-interactive="false" :fit-view-params="fitViewOptions" />
            <MiniMap
              v-if="!runVisible"
              position="bottom-right"
              :pannable="true"
              :zoomable="true"
              :width="168"
              :height="104"
              :node-border-radius="8"
              node-color="var(--flow-minimap-node)"
              node-stroke-color="var(--flow-minimap-stroke)"
              mask-color="var(--flow-minimap-mask)"
              aria-label="工作流缩略图"
            />

            <div v-if="!form.nodes.length" class="canvas-empty-state">
              <Squares2X2Icon />
              <strong>从节点库建立流程</strong>
              <span>建议先添加“开始”节点，再配置处理步骤。</span>
              <button type="button" @click.stop="addNode('input')"><PlusIcon />添加开始节点</button>
            </div>
          </VueFlow>
        </div>

        <section v-else class="list-workspace" aria-label="工作流节点列表">
          <header>
            <div><strong>流程节点</strong><span>执行路径由画布连线决定</span></div>
            <button type="button" :disabled="editingLocked" @click="addNode('condition')"><PlusIcon />添加节点</button>
          </header>
          <div class="node-list">
            <article v-for="(node, index) in form.nodes" :key="node.id" :class="[{ selected: selectedNodeId === node.id }, `run-${nodeRunStatus(node.id)}`]" @click="highlightNode(node.id)">
              <span class="list-index">{{ index + 1 }}<small v-if="runVisible" :class="nodeRunStatus(node.id)">{{ stepStatusLabel(nodeRunStatus(node.id)) }}</small></span>
              <label><span>类型</span><select v-model="node.type" :disabled="editingLocked"><option v-for="type in nodeTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></label>
              <label><span>节点名称</span><input v-model.trim="node.name" maxlength="60" required placeholder="节点名称" :disabled="editingLocked"></label>
              <label class="description-field"><span>处理说明</span><input v-model.trim="node.description" maxlength="160" placeholder="说明该节点处理什么" :disabled="editingLocked"></label>
              <div class="list-actions">
                <button type="button" :disabled="editingLocked || index === 0" :aria-label="`上移${node.name || '当前节点'}`" @click.stop="moveNode(index, -1)"><ArrowUpIcon /></button>
                <button type="button" :disabled="editingLocked || index === form.nodes.length - 1" :aria-label="`下移${node.name || '当前节点'}`" @click.stop="moveNode(index, 1)"><ArrowDownIcon /></button>
                <button class="danger-button" type="button" :disabled="editingLocked || form.nodes.length <= 1" :aria-label="`删除${node.name || '当前节点'}`" @click.stop="removeNode(index)"><TrashIcon /></button>
              </div>
            </article>
            <div v-if="!form.nodes.length" class="list-empty-state">
              <QueueListIcon /><strong>还没有流程节点</strong><button type="button" @click="addNode('input')">添加开始节点</button>
            </div>
          </div>
        </section>

      </main>

      <aside v-show="!testPanelOpen" id="workflow-node-inspector" class="node-inspector" :aria-hidden="rightCollapsed && isCompact">
        <header>
          <div><strong>属性面板</strong><span>{{ inspectorView === 'run' ? '本次运行' : inspectorView === 'node' ? '节点配置' : '流程设置' }}</span></div>
          <button class="icon-button" type="button" :aria-label="rightCollapsed ? '展开属性面板' : '收起属性面板'" @click="toggleRightPanel">
            <ChevronDoubleRightIcon />
          </button>
        </header>
        <div class="inspector-tabs" :class="{ 'with-run-tab': runVisible }" role="tablist" aria-label="属性类型">
          <button type="button" role="tab" :aria-selected="inspectorView === 'node'" :disabled="!selectedCanvasNode" @click="openInspectorView('node')">节点配置</button>
          <button type="button" role="tab" :aria-selected="inspectorView === 'workflow'" @click="openInspectorView('workflow')">流程设置</button>
          <button v-if="runVisible" type="button" role="tab" :aria-selected="inspectorView === 'run'" :disabled="!selectedCanvasNode" @click="openInspectorView('run')">运行详情</button>
        </div>

        <div v-if="inspectorView === 'run' && selectedCanvasNode" class="inspector-content inspector-run-detail">
          <div class="inspector-title">
            <span class="type-symbol" :class="selectedCanvasNode.type"><component :is="nodeIcon(selectedCanvasNode.type)" /></span>
            <div><strong>{{ selectedCanvasNode.name || '未命名节点' }}</strong><small>第 {{ selectedNodeIndex + 1 }} 步 · {{ nodeTypeLabel(selectedCanvasNode.type) }}</small></div>
          </div>
          <div class="run-detail-state" :class="nodeRunStatus(selectedCanvasNode.id)"><span class="run-status-mark"></span><strong>{{ stepStatusLabel(nodeRunStatus(selectedCanvasNode.id)) }}</strong><time v-if="selectedRunStep?.durationMs != null">{{ formatDuration(selectedRunStep.durationMs) }}</time></div>
          <div class="run-detail-block"><span>执行记录</span><p>{{ selectedRunStep?.detail || (nodeRunStatus(selectedCanvasNode.id) === 'running' ? '正在执行节点…' : '尚无执行记录。') }}</p></div>
          <div v-if="selectedRunStep?.input !== undefined" class="run-detail-block"><span>节点输入</span><pre>{{ formatPayload(selectedRunStep.input) }}</pre></div>
          <div v-if="selectedRunStep?.output !== undefined" class="run-detail-block"><span>节点输出</span><pre>{{ formatPayload(selectedRunStep.output) }}</pre></div>
          <div v-if="selectedRunStep?.error" class="run-detail-block"><span>错误信息</span><pre class="run-error-output">{{ formatPayload(selectedRunStep.error) }}</pre></div>
          <button v-if="runPhase === 'running' && activeNodeId && selectedCanvasNode.id !== activeNodeId" class="follow-run-button" type="button" @click="followCurrentNode">定位当前节点</button>
        </div>

        <div v-else-if="inspectorView === 'node' && selectedCanvasNode" class="inspector-content">
          <div class="inspector-title">
            <span class="type-symbol" :class="selectedCanvasNode.type"><component :is="nodeIcon(selectedCanvasNode.type)" /></span>
            <div><strong>{{ selectedCanvasNode.name || '未命名节点' }}</strong><small>{{ nodeTypeLabel(selectedCanvasNode.type) }}</small></div>
          </div>
          <label><span>节点类型</span><select v-model="selectedCanvasNode.type" :disabled="editingLocked"><option v-for="type in nodeTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></label>
          <label><span>节点名称</span><input v-model.trim="selectedCanvasNode.name" maxlength="60" required placeholder="节点名称" :disabled="editingLocked"></label>
          <label><span>节点说明</span><textarea v-model.trim="selectedCanvasNode.description" rows="2" maxlength="160" placeholder="说明该节点处理什么" :disabled="editingLocked"></textarea></label>
          <WorkflowNodeConfig :node="selectedCanvasNode" :nodes="form.nodes" :edges="effectiveEdges" :disabled="editingLocked"
            :knowledge-bases="knowledgeBases" @update-config="selectedCanvasNode.config = $event" @branch="setNodeBranch" />
          <div class="inspector-order">
            <span>执行顺序</span>
            <div>
              <button type="button" :disabled="editingLocked || selectedNodeIndex <= 0" aria-label="上移当前节点" @click="moveNode(selectedNodeIndex, -1)"><ArrowUpIcon /></button>
              <strong>{{ selectedNodeIndex + 1 }} / {{ form.nodes.length }}</strong>
              <button type="button" :disabled="editingLocked || selectedNodeIndex >= form.nodes.length - 1" aria-label="下移当前节点" @click="moveNode(selectedNodeIndex, 1)"><ArrowDownIcon /></button>
            </div>
          </div>
          <button class="delete-node" type="button" :disabled="editingLocked || form.nodes.length <= 1" @click="removeSelectedNode"><TrashIcon />删除节点</button>
        </div>

        <div v-else class="inspector-content workflow-settings">
          <button v-if="scenarioTemplate" class="template-action" type="button" :disabled="editingLocked" @click="applyScenarioTemplate">使用本场景可运行模板</button>
          <label><span>工作流名称</span><input v-model.trim="form.name" maxlength="80" required placeholder="工作流名称" :disabled="editingLocked"></label>
          <label><span>适用场景</span><select v-model="form.scenarioCode" :disabled="editingLocked"><option v-for="item in scenarios" :key="item.code" :value="item.code">{{ item.name }}</option></select></label>
          <label><span>业务说明</span><textarea v-model.trim="form.description" rows="3" maxlength="500" placeholder="说明这条流程解决什么业务问题" :disabled="editingLocked"></textarea></label>
          <label class="enable-setting">
            <span><strong>启用工作流</strong><small>启用后可在对应业务场景中运行</small></span>
            <input v-model="form.enabled" type="checkbox" aria-label="启用工作流" :disabled="editingLocked">
          </label>
          <div class="setting-note">
            <strong>执行方式</strong>
            <p>运行从开始节点沿连线前进。旧版无显式连线时按节点顺序连接；编辑连线后按保存的路径和条件分支运行。</p>
          </div>
          <div class="edge-editor">
            <div class="edge-editor-heading"><strong>执行连线</strong><button type="button" :disabled="editingLocked" @click="addExplicitEdge">{{ hasExplicitEdges ? '添加连线' : '转为显式连线' }}</button></div>
            <p>{{ hasExplicitEdges ? '当前使用已保存的显式连线；条件节点请设置 true / false 分支。' : '当前为旧版默认顺序连线；添加或拖拽连线后转为显式路径。' }}</p>
            <div v-for="edge in effectiveEdges" :key="edge.id" class="edge-editor-row">
              <select :value="edge.source" :disabled="editingLocked" aria-label="连线起点" @change="updateEdge(edge.id, 'source', $event.target.value)"><option v-for="node in form.nodes" :key="node.id" :value="node.id">{{ node.name }}</option></select>
              <span>→</span>
              <select :value="edge.target" :disabled="editingLocked" aria-label="连线终点" @change="updateEdge(edge.id, 'target', $event.target.value)"><option v-for="node in form.nodes" :key="node.id" :value="node.id">{{ node.name }}</option></select>
              <select :value="edge.branch || ''" :disabled="editingLocked || nodeById(edge.source)?.type !== 'condition'" aria-label="连线分支" @change="updateEdge(edge.id, 'branch', $event.target.value)"><option value="">默认</option><option value="true">true</option><option value="false">false</option></select>
              <button type="button" :disabled="editingLocked" :aria-label="`删除连线 ${edge.source} 到 ${edge.target}`" @click="removeEdge(edge.id)"><TrashIcon /></button>
            </div>
          </div>
        </div>
      </aside>
      <WorkflowRunPanel v-show="testPanelOpen" :workflow="form" :edges="effectiveEdges" :template="scenarioTemplate"
        :phase="runPhase" :record="runRecord" :steps="runPanelSteps" :error="runError" :saving="saving"
        :dirty="isNew || ['dirty', 'error'].includes(saveState)" :running="running" :approval-busy="approvalBusy"
        :refreshing="runStatusRefreshing" :knowledge-bases="knowledgeBases" :ai-status="aiStatus" :resource-error="resourceError"
        @close="hideTestPanel" @run="runWorkflow" @retry="openRunSetup" @approve="resumeWorkflow" @refresh="refreshCurrentRun"
        @select-node="highlightRunNode" @fix-node="fixRunIssue" @reload-resources="loadTestResources" />
    </div>

    <footer class="designer-status-bar">
      <template v-if="loadError">
        <span class="status-copy error"><i></i>工作流加载失败</span>
        <span class="local-layout-note">请返回工作流中心重新选择</span>
      </template>
      <template v-else>
        <span class="status-copy" :class="saveState"><i></i>{{ saveStateLabel }}</span>
        <span>{{ form.nodes.length }} 个节点</span>
        <span v-if="editorMode === 'canvas'">画布 {{ Math.round(canvasScale * 100) }}%</span>
        <span class="keyboard-tip">选中节点后可用 Alt + 方向键移动</span>
        <span class="local-layout-note">节点布局随工作流保存</span>
      </template>
    </footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Handle, MarkerType, Position, VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import {
  ArrowDownIcon, ArrowLeftIcon, ArrowPathIcon, ArrowUpIcon, CheckCircleIcon, ClockIcon,
  ChevronDoubleLeftIcon, ChevronDoubleRightIcon, ChevronDownIcon, CircleStackIcon, Cog6ToothIcon,
  CpuChipIcon, CursorArrowRaysIcon, ExclamationTriangleIcon, FunnelIcon, HandRaisedIcon,
  PlayIcon, PlusIcon, QueueListIcon, Squares2X2Icon, TrashIcon, WrenchScrewdriverIcon, XMarkIcon
} from '@heroicons/vue/24/outline'
import { scenarios } from '../data/scenarios'
import { workflowAPI, knowledgeAPI, systemAPI } from '../services/api'
import WorkflowRunPanel from '../components/WorkflowRunPanel.vue'
import WorkflowNodeConfig from '../components/WorkflowNodeConfig.vue'
import { authState, ensureAuth } from '../services/auth'

const route = useRoute()
const router = useRouter()
const initialPhone = typeof window !== 'undefined' && window.matchMedia('(max-width: 760px)').matches

const nodeTypes = [
  { value: 'input', label: '开始', hint: '接收用户请求' },
  { value: 'condition', label: '条件判断', hint: '按规则选择路径' },
  { value: 'knowledge', label: '知识检索', hint: '召回知识片段' },
  { value: 'tool', label: '业务工具', hint: '查询或执行业务' },
  { value: 'model', label: '模型处理', hint: '理解并生成内容' },
  { value: 'approval', label: '人工处理', hint: '复核高风险动作' },
  { value: 'output', label: '结束', hint: '返回处理结果' }
]

const flowApi = useVueFlow('workflow-designer-flow')
const fitViewOptions = Object.freeze({ padding: .2, minZoom: .35, maxZoom: 1 })
const defaultEdgeOptions = Object.freeze({
  type: 'smoothstep',
  animated: false,
  selectable: false,
  focusable: false,
  markerEnd: { type: MarkerType.ArrowClosed, color: 'var(--primary)' },
  style: { stroke: 'var(--primary)', strokeWidth: 1.8 }
})
const routeId = computed(() => String(route.params.id || ''))
const isNew = computed(() => route.name === 'workflow-designer-new' || !routeId.value)
const loading = ref(true)
const saving = ref(false)
const running = ref(false)
const notice = ref('')
const noticeType = ref('info')
const lastRun = ref(null)
const runRecord = ref(null)
const runPhase = ref('idle')
const runSteps = ref({})
const activeNodeId = ref('')
const activeEdgeKey = ref('')
const traversedEdges = ref([])
const recentEdgeKeys = ref([])
const runElapsedMs = ref(0)
const runError = ref('')
const approvalBusy = ref('')
const runStatusRefreshing = ref(false)
const testPanelOpen = ref(false)
const templates = ref([])
const knowledgeBases = ref([])
const aiStatus = ref(null)
const resourceError = ref('')
const scenarioTemplate = computed(() => templates.value.find(item => item.scenarioCode === form.value.scenarioCode))
const runPanelSteps = computed(() => runDisplayNodes.value.map(node => ({ nodeId: node.id, nodeName: node.name, nodeType: node.type, ...runSteps.value[node.id], status: nodeRunStatus(node.id) })))
const followActiveNode = ref(true)
let runController = null
let noticeTimer = null
let runRequestToken = 0
const edgeAnimationTimers = new Set()
const loadError = ref('')
const saveState = ref('idle')
const lastSavedAt = ref('')
const readyForChanges = ref(false)
const form = ref(createEmptyWorkflow())
const savedEditorMode = localStorage.getItem('ai-service:workflow-editor-mode')
const editorMode = ref(initialPhone ? 'list' : (savedEditorMode === 'list' ? 'list' : 'canvas'))
const leftCollapsed = ref(initialPhone || localStorage.getItem('ai-service:workflow-palette-collapsed') === 'true')
const rightCollapsed = ref(initialPhone || localStorage.getItem('ai-service:workflow-inspector-collapsed') === 'true')
const isCompact = ref(typeof window !== 'undefined' && window.innerWidth < 1200)
const isPhone = ref(initialPhone)
const inspectorView = ref('workflow')
const selectedNodeId = ref('')
const nodePositions = ref({})
const canvasScale = ref(1)
const canvasElement = ref(null)
let paletteBeforeTest = null

const saveStateLabel = computed(() => ({
  idle: '准备就绪', new: '尚未保存', dirty: '有未保存更改', saving: '正在保存', saved: lastSavedAt.value ? `已保存 ${lastSavedAt.value}` : '已保存', error: '保存失败'
})[saveState.value] || '准备就绪')
const runVisible = computed(() => runPhase.value !== 'idle')
const editingLocked = computed(() => running.value || runPhase.value === 'waiting' || (runPhase.value === 'interrupted' && runRecord.value?.status === 'running'))
const canApprove = computed(() => authState.user?.role === 'ADMIN')
const runButtonHint = computed(() => editingLocked.value ? '查看当前运行' : '选择用例或填写数据，验证当前流程')
const runDisplayNodes = computed(() => {
  const nodes = [...form.value.nodes]
  const ids = new Set(nodes.map(node => node.id))
  Object.values(runSteps.value).forEach(step => {
    if (step?.nodeId && !ids.has(step.nodeId)) {
      nodes.push({ id: step.nodeId, name: step.nodeName || '运行快照节点', type: step.nodeType || 'output' })
      ids.add(step.nodeId)
    }
  })
  return nodes
})
const activeNodeName = computed(() => form.value.nodes.find(node => node.id === activeNodeId.value)?.name || runSteps.value[activeNodeId.value]?.nodeName || '准备开始')
const hasExplicitEdges = computed(() => Array.isArray(form.value.edges))
const effectiveEdges = computed(() => hasExplicitEdges.value ? form.value.edges : form.value.nodes.slice(0, -1).map((node, index) => ({
  id: `edge-${node.id}-${form.value.nodes[index + 1].id}`,
  source: node.id,
  target: form.value.nodes[index + 1].id
})))

const selectedCanvasNode = computed(() => form.value.nodes.find(item => item.id === selectedNodeId.value)
  || (inspectorView.value === 'run' ? runDisplayNodes.value.find(item => item.id === selectedNodeId.value) : null)
  || null)
const selectedNodeIndex = computed(() => (inspectorView.value === 'run' ? runDisplayNodes.value : form.value.nodes).findIndex(item => item.id === selectedNodeId.value))
const selectedRunStep = computed(() => runSteps.value[selectedNodeId.value] || null)
const flowNodes = computed(() => form.value.nodes.map((node, index) => {
  const position = positionForNode(node.id, index)
  const incoming = effectiveEdges.value.find(edge => edge.target === node.id)
  const outgoing = effectiveEdges.value.find(edge => edge.source === node.id)
  const previousIndex = form.value.nodes.findIndex(item => item.id === incoming?.source)
  const nextIndex = form.value.nodes.findIndex(item => item.id === outgoing?.target)
  const previousPosition = previousIndex >= 0 ? positionForNode(incoming.source, previousIndex) : null
  const nextPosition = nextIndex >= 0 ? positionForNode(outgoing.target, nextIndex) : null
  return {
    id: node.id,
    type: 'workflow',
    position: { x: position.x, y: position.y },
    selected: selectedNodeId.value === node.id,
    draggable: !isPhone.value && !editingLocked.value,
    connectable: !editingLocked.value,
    deletable: false,
    focusable: true,
    ariaLabel: `第${index + 1}步，${nodeTypeLabel(node.type)}，${node.name || '未命名节点'}`,
    data: {
      node,
      index,
      hasIncoming: Boolean(incoming),
      targetPosition: connectionPosition(previousPosition, position, true),
      sourcePosition: connectionPosition(position, nextPosition, false)
    }
  }
}))
const flowEdges = computed(() => effectiveEdges.value.filter(edge => nodeById(edge.source) && nodeById(edge.target)).map(edge => ({
  id: edge.id,
  source: edge.source,
  target: edge.target,
  sourceHandle: nodeById(edge.source)?.type === 'condition' ? edge.branch || 'true' : undefined,
  type: 'smoothstep',
  label: edge.branch === 'true' ? '满足' : edge.branch === 'false' ? '不满足' : undefined,
  animated: false,
  class: runVisible.value && runPhase.value !== 'setup' ? `run-edge-${edgeRunStatus(edge)}` : '',
  selectable: false,
  focusable: false,
  markerEnd: runVisible.value && runPhase.value !== 'setup' ? { type: MarkerType.ArrowClosed, color: edgeRunColor(edgeRunStatus(edge)) } : defaultEdgeOptions.markerEnd,
  style: runVisible.value && runPhase.value !== 'setup' ? { stroke: edgeRunColor(edgeRunStatus(edge)), strokeWidth: edgeRunStatus(edge) === 'active' ? 2.7 : 1.8 } : undefined,
  ariaLabel: `${nodeById(edge.source)?.name || '节点'} 到 ${nodeById(edge.target)?.name || '节点'}${edge.branch ? `，分支 ${edge.branch}` : ''}`
})))

watch(form, () => {
  if (!readyForChanges.value || saving.value) return
  saveState.value = 'dirty'
  if (runVisible.value && runPhase.value !== 'setup' && !editingLocked.value) closeRunDock()
  lastRun.value = null
}, { deep: true })
watch(nodePositions, () => {
  if (readyForChanges.value && !saving.value) saveState.value = 'dirty'
}, { deep: true })

let skipNextRouteLoad = false
watch(() => route.params.id, () => {
  if (skipNextRouteLoad) { skipNextRouteLoad = false; return }
  loadWorkflow()
})
watch(leftCollapsed, value => localStorage.setItem('ai-service:workflow-palette-collapsed', String(value)))
watch(rightCollapsed, value => localStorage.setItem('ai-service:workflow-inspector-collapsed', String(value)))
watch([testPanelOpen, leftCollapsed, rightCollapsed], () => { if (!loading.value) void fitCanvas(false) })

onMounted(() => {
  window.addEventListener('resize', syncViewportMode)
  window.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('beforeunload', handleBeforeUnload)
  loadWorkflow()
  loadTestResources()
})

onBeforeUnmount(() => {
  runRequestToken += 1
  runController?.abort()
  clearEdgeAnimations()
  if (noticeTimer) clearTimeout(noticeTimer)
  window.removeEventListener('resize', syncViewportMode)
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('beforeunload', handleBeforeUnload)
})

onBeforeRouteLeave(() => {
  if (saveState.value !== 'dirty' && saveState.value !== 'error') return true
  return window.confirm('当前工作流有未保存的更改，确定要离开吗？')
})

function createEmptyWorkflow() {
  return {
    id: '',
    name: '未命名工作流',
    scenarioCode: scenarios[0].code,
    description: '',
    enabled: true,
    nodes: [newNode('input', '接收请求'), newNode('output', '返回结果')]
  }
}

function newNode(type = 'condition', name = '新节点') {
  return { id: `node-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`, type, name, description: '', config: '{}' }
}

async function loadWorkflow() {
  runRequestToken += 1
  runController?.abort()
  clearEdgeAnimations()
  running.value = false
  approvalBusy.value = ''
  runStatusRefreshing.value = false
  closeRunDock(true)
  lastRun.value = null
  loading.value = true
  readyForChanges.value = false
  notice.value = ''
  loadError.value = ''
  try {
    const user = authState.user || await ensureAuth()
    if (user?.role !== 'ADMIN') throw Object.assign(new Error('仅管理员可试运行工作流。'), { status: 403 })
    if (isNew.value) {
      form.value = createEmptyWorkflow()
      initializeCanvasLayout(true)
      saveState.value = 'new'
      inspectorView.value = 'workflow'
    } else {
      const data = await workflowAPI.get(routeId.value)
      form.value = JSON.parse(JSON.stringify(data))
      initializeCanvasLayout()
      selectedNodeId.value = form.value.nodes[0]?.id || ''
      inspectorView.value = selectedNodeId.value ? 'node' : 'workflow'
      saveState.value = 'saved'
      lastSavedAt.value = formatTime(data.updatedAt)
      await loadLatestRun(routeId.value, runRequestToken)
    }
  } catch (error) {
    loadError.value = error.status === 404
      ? '这条工作流不存在或已被删除，请返回工作流中心重新选择。'
      : error.status === 403 ? '仅管理员可打开和试运行工作流。请使用管理员账号访问。'
      : (error.message || '工作流加载失败，请检查服务连接后重试。')
    saveState.value = 'idle'
  } finally {
    loading.value = false
    await nextTick()
    if (!loadError.value && editorMode.value === 'canvas') await fitCanvas(false)
    readyForChanges.value = true
    if (!loadError.value && route.query.run === '1' && !isNew.value) {
      await router.replace({ name: 'workflow-designer', params: { id: routeId.value }, query: { ...route.query, run: undefined } })
      if (runPhase.value === 'waiting') showNotice('这条工作流有待人工确认的运行，请先处理。', 'info')
      else openRunSetup()
    }
  }
}

async function loadTestResources() {
  resourceError.value = ''
  const results = await Promise.allSettled([workflowAPI.templates(), knowledgeAPI.list(), systemAPI.aiStatus()])
  if (results[0].status === 'fulfilled') templates.value = results[0].value
  if (results[1].status === 'fulfilled') knowledgeBases.value = results[1].value
  if (results[2].status === 'fulfilled') aiStatus.value = results[2].value
  if (results.some(result => result.status === 'rejected')) resourceError.value = '部分测试资源加载失败'
}

function hideTestPanel() {
  testPanelOpen.value = false
  if (paletteBeforeTest !== null) { leftCollapsed.value = paletteBeforeTest; paletteBeforeTest = null }
  if (runPhase.value === 'setup') closeRunDock()
  void fitCanvas(false)
}

function highlightRunNode(id) {
  selectedNodeId.value = id
  followActiveNode.value = false
}

function fixRunIssue(id) {
  hideTestPanel()
  selectedNodeId.value = id || ''
  inspectorView.value = id ? 'node' : 'workflow'
  rightCollapsed.value = false
  if (isCompact.value) leftCollapsed.value = true
}

function setNodeBranch(branch, target) {
  if (editingLocked.value || !selectedCanvasNode.value) return
  materializeEdges()
  const source = selectedCanvasNode.value.id
  form.value.edges = form.value.edges.filter(edge => edge.source !== source || (edge.branch && edge.branch !== branch))
  if (target) form.value.edges.push(newEdge(source, target, branch))
}

function applyScenarioTemplate() {
  if (editingLocked.value || !scenarioTemplate.value) return
  if (!window.confirm('将用本场景模板替换当前节点和连线，保存后生效。继续吗？')) return
  const template = JSON.parse(JSON.stringify(scenarioTemplate.value))
  form.value.nodes = template.nodes
  form.value.edges = template.edges
  form.value.description = template.description
  initializeCanvasLayout(true)
  selectedNodeId.value = form.value.nodes[0]?.id || ''
  void fitCanvas(false)
  showNotice('已载入可运行模板，点击试运行可选择场景用例。', 'success')
}

async function saveWorkflow(forRun = false) {
  if (editingLocked.value) return
  if (!form.value.name.trim()) return showNotice('请输入工作流名称。', 'error')
  if (!form.value.nodes.length) return showNotice('至少需要一个流程节点。', 'error')
  if (form.value.nodes.some(node => !node.name.trim())) return showNotice('请补充所有节点名称。', 'error')
  try {
    form.value.nodes.forEach(node => {
      const config = JSON.parse(node.config || '{}')
      if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error(`${node.name}的执行配置必须是 JSON 对象。`)
    })
  } catch (error) { return showNotice(error.message || '节点执行配置不是有效 JSON。', 'error') }
  saving.value = true
  saveState.value = 'saving'
  const wasNew = isNew.value
  const payload = {
    name: form.value.name,
    scenarioCode: form.value.scenarioCode,
    description: form.value.description,
    enabled: form.value.enabled,
    ...(hasExplicitEdges.value ? { edges: form.value.edges } : {}),
    nodes: form.value.nodes.map((node, index) => {
      const position = positionForNode(node.id, index)
      return { ...node, positionX: position.x, positionY: position.y }
    })
  }
  try {
    const saved = wasNew ? await workflowAPI.create(payload) : await workflowAPI.update(routeId.value, payload)
    const draftKey = layoutStorageKey('draft')
    persistCanvasLayout(saved.id)
    if (wasNew) localStorage.removeItem(draftKey)
    readyForChanges.value = false
    form.value = JSON.parse(JSON.stringify(saved))
    lastSavedAt.value = formatTime(saved.updatedAt || new Date().toISOString())
    saveState.value = 'saved'
    lastRun.value = null
    if (forRun !== true) closeRunDock()
    showNotice('工作流已保存。', 'success')
    await nextTick()
    readyForChanges.value = true
    if (wasNew) {
      skipNextRouteLoad = true
      await router.replace({ name: 'workflow-designer', params: { id: saved.id } })
    }
    return saved
  } catch (error) {
    saveState.value = 'error'
    showNotice(error.message || '工作流保存失败。', 'error')
  } finally {
    saving.value = false
  }
}

function openRunSetup() {
  if (!testPanelOpen.value) paletteBeforeTest = leftCollapsed.value
  leftCollapsed.value = true
  testPanelOpen.value = true
  if (editingLocked.value || saving.value) return
  if (isCompact.value) { leftCollapsed.value = true; rightCollapsed.value = true }
  runPhase.value = 'setup'
  runRecord.value = null
  runSteps.value = {}
  traversedEdges.value = []
  activeEdgeKey.value = ''
  clearEdgeAnimations()
  activeNodeId.value = ''
  runError.value = ''

  if (editorMode.value === 'canvas') void fitCanvas(false)
}

async function runWorkflow(payload) {
  if (runPhase.value !== 'setup' || running.value || saving.value) return
  if (isNew.value || ['dirty', 'error'].includes(saveState.value)) {
    const saved = await saveWorkflow(true)
    if (!saved) return
  }
  runSteps.value = Object.fromEntries(form.value.nodes.map(node => [node.id, { nodeId: node.id, nodeName: node.name, nodeType: node.type, status: 'waiting' }]))
  traversedEdges.value = []
  activeEdgeKey.value = ''
  clearEdgeAnimations()
  activeNodeId.value = ''
  runElapsedMs.value = 0
  runRecord.value = null
  testPanelOpen.value = true
  await executeRunStream((handlers, signal) => workflowAPI.runStream(form.value.id || routeId.value, payload, handlers, signal))
}

async function resumeWorkflow(approved) {
  if (runPhase.value !== 'waiting' || !runRecord.value?.id || running.value || approvalBusy.value || !canApprove.value) return
  approvalBusy.value = approved ? 'approve' : 'reject'

  try {
    await executeRunStream((handlers, signal) => workflowAPI.resumeStream(routeId.value, runRecord.value.id, { approved }, handlers, signal))
  } finally {
    approvalBusy.value = ''
  }
}

async function executeRunStream(openStream) {
  const requestToken = ++runRequestToken
  const controller = new AbortController()
  runController = controller
  running.value = true
  runPhase.value = 'running'
  runError.value = ''
  followActiveNode.value = true
  if (noticeTimer) clearTimeout(noticeTimer)
  notice.value = ''
  try {
    if (editorMode.value === 'canvas') await fitCanvas(false)
    const result = await openStream({
      'run-start': event => {
        if (requestToken !== runRequestToken || !event?.runId) return
        const record = {
          ...(runRecord.value || {}),
          id: event.runId,
          workflowId: routeId.value,
          mode: event.mode || 'execution',
          status: 'running',
          totalNodes: event.totalNodes || runRecord.value?.totalNodes,
          startedAt: event.startedAt || runRecord.value?.startedAt,
          definitionVersion: event.definitionVersion || runRecord.value?.definitionVersion,
          checkpoint: event.checkpoint || runRecord.value?.checkpoint
        }
        runRecord.value = record
        lastRun.value = record
      },
      'node-start': event => {
        if (requestToken !== runRequestToken) return
        activeNodeId.value = event.nodeId
        runSteps.value = { ...runSteps.value, [event.nodeId]: { ...runSteps.value[event.nodeId], ...event, status: 'running' } }
        if (followActiveNode.value) {
          selectedNodeId.value = event.nodeId
          inspectorView.value = 'run'
        }
      },
      'edge-traverse': event => {
        if (requestToken !== runRequestToken) return
        activeEdgeKey.value = edgeKey(event)
        traversedEdges.value = [...traversedEdges.value, event]
        pulseTraversedEdge(event)
      },
      'node-complete': event => {
        if (requestToken !== runRequestToken) return
        const step = event.step || event
        runSteps.value = { ...runSteps.value, [step.nodeId]: { ...runSteps.value[step.nodeId], ...step } }
        if (activeNodeId.value === step.nodeId && step.status !== 'waiting') activeNodeId.value = ''
        if (activeEdgeKey.value && traversedEdges.value.some(edge => edgeKey(edge) === activeEdgeKey.value && edge.target === step.nodeId)) activeEdgeKey.value = ''
        if (step.status === 'failed') {
          selectedNodeId.value = step.nodeId
          inspectorView.value = 'run'

        }
      }
    }, controller.signal)
    if (requestToken === runRequestToken) applyRunRecord(result)
  } catch (error) {
    if (requestToken !== runRequestToken || error.name === 'AbortError') return
    if (await recoverRunAfterStreamFailure(error, requestToken)) return
    runError.value = error.message || '试运行中断，请重试。'
    runPhase.value = 'failed'
    if (activeNodeId.value) {
      const nodeId = activeNodeId.value
      runSteps.value = { ...runSteps.value, [nodeId]: { ...runSteps.value[nodeId], status: 'failed', detail: runError.value } }
      selectedNodeId.value = nodeId
      inspectorView.value = 'run'
      activeNodeId.value = ''
    }
    activeEdgeKey.value = ''
    markRemainingNotRun()

  } finally {
    if (requestToken === runRequestToken) {
      running.value = false
      runController = null
    }
  }
}

async function recoverRunAfterStreamFailure(streamError, requestToken) {
  const runId = runRecord.value?.id
  if (!runId || !routeId.value) return false
  try {
    const persisted = await workflowAPI.getRun(routeId.value, runId)
    if (requestToken !== runRequestToken) return true
    applyRunRecord(persisted)
    if (persisted.status === 'waiting') {
      showNotice('运行事件流已结束，服务端仍处于待审批状态，已恢复审批卡。', 'info')
    } else if (persisted.status === 'running') {
      runPhase.value = 'interrupted'
      runError.value = `事件连接已中断（${streamError.message || '连接关闭'}）；服务端最后状态仍为执行中，请刷新状态。`

    } else if (persisted.status === 'interrupted') {
      showNotice('已从服务端恢复中断结果；为避免重复外部操作，本次运行不会自动重放。', 'error')
    }
    return true
  } catch {
    return false
  }
}

function showNotice(message, type = 'info') {
  if (noticeTimer) clearTimeout(noticeTimer)
  notice.value = message
  noticeType.value = type
  noticeTimer = setTimeout(() => { notice.value = ''; noticeTimer = null }, type === 'error' ? 6000 : type === 'success' ? 2500 : 3000)
}

async function refreshCurrentRun() {
  const runId = runRecord.value?.id
  if (!runId || !routeId.value || runStatusRefreshing.value || approvalBusy.value) return
  runStatusRefreshing.value = true
  try {
    const record = await workflowAPI.getRun(routeId.value, runId)
    applyRunRecord(record)
    if (record.status === 'running') {
      runPhase.value = 'interrupted'
      runError.value = '服务端最后状态仍为执行中；当前页面没有活动事件流，请稍后再次刷新。'

    } else {
      showNotice('已同步服务端运行状态。', 'success')
    }
  } catch (error) {
    showNotice(error.message || '运行状态刷新失败。', 'error')
  } finally {
    runStatusRefreshing.value = false
  }
}

async function loadLatestRun(id, requestToken) {
  try {
    const runs = await workflowAPI.runs(id)
    if (routeId.value === id && requestToken === runRequestToken && !running.value && Array.isArray(runs)) {
      lastRun.value = runs.find(run => run.mode === 'execution') || null
      if (lastRun.value && ['waiting', 'running'].includes(lastRun.value.status)) {
        applyRunRecord(lastRun.value)
        testPanelOpen.value = true
      }
    }
  } catch { /* history is optional while editing */ }
}

function applyRunRecord(record) {
  if (!record) return
  runRecord.value = record
  lastRun.value = record
  runSteps.value = { ...Object.fromEntries(form.value.nodes.map(node => [node.id, { nodeId: node.id, nodeName: node.name, status: 'waiting' }])), ...Object.fromEntries((record.steps || []).map(step => [step.nodeId, step])) }
  const serverStatus = String(record.status || '').toLowerCase()
  runPhase.value = ['completed', 'waiting', 'failed', 'interrupted'].includes(serverStatus)
    ? serverStatus
    : serverStatus === 'running' ? 'interrupted' : 'failed'
  if (['failed', 'interrupted'].includes(runPhase.value)) markRemainingNotRun()
  if (runPhase.value === 'completed') {
    const visited = new Set((record.steps || []).map(step => step.nodeId))
    runSteps.value = Object.fromEntries(Object.entries(runSteps.value).map(([id, step]) => [id, visited.has(id) ? step : { ...step, status: 'skipped', detail: '本次条件路径未经过该节点。' }]))
  }
  traversedEdges.value = record.traversedEdges || []
  activeEdgeKey.value = ''
  activeNodeId.value = runPhase.value === 'waiting' ? (record.waitingNodeId || record.steps?.find(step => step.status === 'waiting')?.nodeId || '') : ''
  runError.value = runPhase.value === 'interrupted'
    ? (serverStatus === 'running' ? '当前页面没有活动事件流，服务端最后状态仍为执行中，请刷新状态。' : (record.statusMessage || '本次运行已在安全检查点中断，不会自动重放。'))
    : runPhase.value === 'failed' ? (record.statusMessage || '') : ''
  if (record.startedAt) {
    const endedAt = record.completedAt ? new Date(record.completedAt).getTime() : Date.now()
    runElapsedMs.value = Math.max(0, endedAt - new Date(record.startedAt).getTime())
  }
  const focusStep = record.steps?.find(step => step.status === 'failed')
    || (runPhase.value === 'waiting' ? record.steps?.find(step => step.nodeId === activeNodeId.value) : null)
    || (runPhase.value === 'interrupted' ? record.steps?.find(step => step.status === 'running' || step.nodeId === record.nextNodeId) || record.steps?.at(-1) : null)
  if (focusStep) {
    selectedNodeId.value = focusStep.nodeId
    inspectorView.value = 'run'
  }
}

function showLastRun() {
  if (!lastRun.value) return
  applyRunRecord(lastRun.value)
  testPanelOpen.value = true
}

function closeRunDock(force = false) {
  if (editingLocked.value && !force) return
  runPhase.value = 'idle'
  testPanelOpen.value = false
  runRecord.value = null
  runSteps.value = {}
  traversedEdges.value = []
  activeEdgeKey.value = ''
  clearEdgeAnimations()
  activeNodeId.value = ''
  approvalBusy.value = ''

  if (inspectorView.value === 'run') inspectorView.value = selectedNodeId.value ? 'node' : 'workflow'
}

function markRemainingNotRun() {
  runSteps.value = Object.fromEntries(Object.entries(runSteps.value).map(([id, step]) => [id, step.status === 'waiting' ? { ...step, status: 'not-run' } : step]))
}

function nodeRunStatus(id) {
  if (!runVisible.value || runPhase.value === 'setup') return 'idle'
  if (runPhase.value === 'waiting' && id === activeNodeId.value) return 'approval-waiting'
  return runSteps.value[id]?.status || 'waiting'
}

function stepStatusLabel(status) {
  return ({ waiting: '待执行', 'approval-waiting': '等待人工确认', running: '执行中', completed: '已完成', failed: '失败', skipped: '未走分支', 'not-run': '未执行' })[status] || '未运行'
}

function openInspectorView(view) {
  inspectorView.value = view
  if (running.value) followActiveNode.value = false
}

function edgeRunColor(status) {
  return ({ active: 'var(--primary)', traversed: 'var(--success)' })[status] || 'var(--border-color)'
}

function edgeKey(edge) { return `${edge.source}->${edge.target}:${edge.branch || ''}` }
function edgeRunStatus(edge) {
  const key = edgeKey(edge)
  if (activeEdgeKey.value === key && runPhase.value === 'running' || recentEdgeKeys.value.includes(key)) return 'active'
  return traversedEdges.value.some(item => edgeKey(item) === key || (item.id && item.id === edge.id)) ? 'traversed' : 'idle'
}

function pulseTraversedEdge(edge) {
  const key = edgeKey(edge)
  recentEdgeKeys.value = [...new Set([...recentEdgeKeys.value, key])]
  const timer = setTimeout(() => {
    edgeAnimationTimers.delete(timer)
    recentEdgeKeys.value = recentEdgeKeys.value.filter(item => item !== key)
  }, 750)
  edgeAnimationTimers.add(timer)
}

function clearEdgeAnimations() {
  for (const timer of edgeAnimationTimers) clearTimeout(timer)
  edgeAnimationTimers.clear()
  recentEdgeKeys.value = []
}

function formatPayload(value) {
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) ?? '无' } catch { return String(value) }
}

function formatDuration(ms) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${Math.max(0, Math.round(ms || 0))} ms`
}

function selectRunNode(id) {
  selectedNodeId.value = id
  inspectorView.value = 'run'
  followActiveNode.value = false
  if (isCompact.value) {
    rightCollapsed.value = false
    leftCollapsed.value = true
  }
}

function followCurrentNode() {
  if (!activeNodeId.value) return
  followActiveNode.value = true
  selectedNodeId.value = activeNodeId.value
  inspectorView.value = 'run'
}

function backToCenter() {
  router.push({ name: 'workflows', query: form.value.id ? { workflow: form.value.id } : {} })
}

function setEditorMode(mode) {
  editorMode.value = mode
  localStorage.setItem('ai-service:workflow-editor-mode', mode)
  if (mode === 'canvas') nextTick(() => fitCanvas(false))
  if (mode === 'list') leftCollapsed.value = true
}

function toggleLeftPanel() {
  if (editorMode.value === 'list') setEditorMode('canvas')
  leftCollapsed.value = !leftCollapsed.value
  if (isCompact.value && !leftCollapsed.value) rightCollapsed.value = true
}

function toggleRightPanel() {
  testPanelOpen.value = false
  rightCollapsed.value = !rightCollapsed.value
  if (isCompact.value && !rightCollapsed.value) leftCollapsed.value = true
}

function closeOverlayPanels() {
  leftCollapsed.value = true
  rightCollapsed.value = true
}

function selectNode(id) {
  selectedNodeId.value = id
  inspectorView.value = runVisible.value && runPhase.value !== 'setup' ? 'run' : 'node'
  followActiveNode.value = false
  if (isCompact.value) {
    rightCollapsed.value = false
    leftCollapsed.value = true
  }
}

function highlightNode(id) {
  selectedNodeId.value = id
  inspectorView.value = runVisible.value && runPhase.value !== 'setup' ? 'run' : 'node'
  followActiveNode.value = false
}

function clearNodeSelection() {
  selectedNodeId.value = ''
  inspectorView.value = 'workflow'
  followActiveNode.value = false
}

function addNode(type = 'condition') {
  if (editingLocked.value) return
  const names = { input: '接收请求', condition: '条件判断', knowledge: '检索知识', tool: '调用业务工具', model: '模型处理', approval: '人工复核', output: '返回结果' }
  const node = newNode(type, names[type] || '新节点')
  form.value.nodes.push(node)
  nodePositions.value = { ...nodePositions.value, [node.id]: nextNodePosition(form.value.nodes.length - 1) }
  selectedNodeId.value = node.id
  inspectorView.value = 'node'
  persistCanvasLayout()
  if (isCompact.value) {
    leftCollapsed.value = true
    rightCollapsed.value = false
  }
}

function removeNode(index) {
  if (editingLocked.value) return
  if (form.value.nodes.length <= 1 || index < 0) return
  const [removed] = form.value.nodes.splice(index, 1)
  const next = { ...nodePositions.value }
  delete next[removed.id]
  nodePositions.value = next
  if (hasExplicitEdges.value) form.value.edges = form.value.edges.filter(edge => edge.source !== removed.id && edge.target !== removed.id)
  if (selectedNodeId.value === removed.id) {
    selectedNodeId.value = form.value.nodes[Math.min(index, form.value.nodes.length - 1)]?.id || ''
    if (!selectedNodeId.value) inspectorView.value = 'workflow'
  }
  persistCanvasLayout()
}

function removeSelectedNode() {
  removeNode(selectedNodeIndex.value)
}

function moveNode(index, direction) {
  if (editingLocked.value) return
  const target = index + direction
  if (index < 0 || target < 0 || target >= form.value.nodes.length) return
  const [node] = form.value.nodes.splice(index, 1)
  form.value.nodes.splice(target, 0, node)
}

function nodeById(id) { return form.value.nodes.find(node => node.id === id) }

function materializeEdges() {
  if (hasExplicitEdges.value) return
  form.value.edges = effectiveEdges.value.map(edge => ({ ...edge }))
}

function newEdge(source, target, branch = '') {
  return { id: `edge-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`, source, target, ...(branch ? { branch } : {}) }
}

function addExplicitEdge() {
  if (editingLocked.value || form.value.nodes.length < 2) return
  if (!hasExplicitEdges.value) {
    materializeEdges()
    showNotice('已转为显式连线。现在可修改起点、终点和条件分支。', 'info')
    return
  }
  const pair = form.value.nodes.flatMap(source => source.type === 'output' ? [] : form.value.nodes.filter(target => target.id !== source.id).map(target => [source.id, target.id]))
    .find(([source, target]) => !form.value.edges.some(edge => edge.source === source && edge.target === target))
  if (!pair) return showNotice('没有可添加的其他连线，请修改现有连线。', 'info')
  form.value.edges = [...form.value.edges, newEdge(...pair)]
}

function updateEdge(id, field, value) {
  if (editingLocked.value) return
  materializeEdges()
  const edge = form.value.edges.find(item => item.id === id)
  if (!edge) return
  if (field === 'source' && value === edge.target || field === 'target' && value === edge.source) return showNotice('连线不能指向自身。', 'error')
  edge[field] = value
  if (field === 'source' && nodeById(value)?.type !== 'condition') delete edge.branch
  if (field === 'branch' && !value) delete edge.branch
}

function removeEdge(id) {
  if (editingLocked.value) return
  materializeEdges()
  form.value.edges = form.value.edges.filter(edge => edge.id !== id)
}

function handleFlowConnect(connection) {
  if (editingLocked.value || !connection.source || !connection.target) return
  if (connection.source === connection.target) return showNotice('节点不能连接到自身。', 'error')
  const source = nodeById(connection.source)
  if (!source || source.type === 'output') return
  materializeEdges()
  let edges = [...form.value.edges]
  let branch = ''
  if (source.type === 'condition') {
    const outgoing = edges.filter(edge => edge.source === source.id)
    if (outgoing.length === 1 && !outgoing[0].branch) edges = edges.filter(edge => edge.source !== source.id)
    branch = ['true', 'false'].includes(connection.sourceHandle) ? connection.sourceHandle : !edges.some(edge => edge.source === source.id && edge.branch === 'true') ? 'true' : 'false'
    if (edges.some(edge => edge.source === source.id && edge.branch === branch)) return showNotice('true 和 false 分支已配置，请在“流程设置”中修改连线。', 'info')
  } else {
    edges = edges.filter(edge => edge.source !== source.id)
  }
  form.value.edges = [...edges, newEdge(connection.source, connection.target, branch)]
  showNotice(source.type === 'condition' ? `已连接 ${branch} 分支，请保存工作流。` : '已连接节点，请保存工作流。', 'success')
}

function layoutStorageKey(id = form.value.id || routeId.value || 'draft') {
  return `ai-service:workflow-layout:${id || 'draft'}`
}

function initializeCanvasLayout(reset = false) {
  let stored = {}
  if (!reset) {
    try { stored = JSON.parse(localStorage.getItem(layoutStorageKey()) || '{}') } catch { stored = {} }
  }
  const next = {}
  form.value.nodes.forEach((node, index) => {
    const persisted = { x: node.positionX, y: node.positionY }
    next[node.id] = validPosition(persisted)
      ? persisted
      : validPosition(stored[node.id]) ? stored[node.id] : nextNodePosition(index)
  })
  nodePositions.value = next
}

function validPosition(position) {
  return Number.isFinite(position?.x) && Number.isFinite(position?.y)
}

function nextNodePosition(index) {
  const columns = 4
  const row = Math.floor(index / columns)
  const rawColumn = index % columns
  const column = row % 2 ? columns - 1 - rawColumn : rawColumn
  return { x: 88 + column * 282, y: 92 + row * 178 }
}

function positionForNode(id, index) {
  return nodePositions.value[id] || nextNodePosition(index)
}

function connectionPosition(from, to, target) {
  if (!from || !to) return target ? Position.Left : Position.Right
  const deltaX = to.x - from.x
  const deltaY = to.y - from.y
  if (Math.abs(deltaY) > 100) {
    if (target) return deltaY >= 0 ? Position.Top : Position.Bottom
    return deltaY >= 0 ? Position.Bottom : Position.Top
  }
  if (target) return deltaX >= 0 ? Position.Left : Position.Right
  return deltaX >= 0 ? Position.Right : Position.Left
}

function persistCanvasLayout(id) {
  try { localStorage.setItem(layoutStorageKey(id), JSON.stringify(nodePositions.value)) } catch { /* storage may be unavailable */ }
}

function autoLayout() {
  if (editingLocked.value) return
  const next = {}
  form.value.nodes.forEach((node, index) => { next[node.id] = nextNodePosition(index) })
  nodePositions.value = next
  persistCanvasLayout()
  nextTick(() => fitCanvas())
}

async function fitCanvas(smooth = true) {
  await nextTick()
  await new Promise(resolve => requestAnimationFrame(resolve))
  try {
    const element = canvasElement.value
    if (!element || !form.value.nodes.length) return
    const positions = form.value.nodes.map((node, index) => positionForNode(node.id, index))
    const left = Math.min(...positions.map(point => point.x)), top = Math.min(...positions.map(point => point.y))
    const width = Math.max(...positions.map(point => point.x)) - left + 232
    const height = Math.max(...positions.map(point => point.y)) - top + (runVisible.value && runPhase.value !== 'setup' ? 132 : 112)
    const zoom = Math.max(.35, Math.min(1, (element.clientWidth - 80) / width, (element.clientHeight - 80) / height))
    await flowApi.setViewport({ x: (element.clientWidth - width * zoom) / 2 - left * zoom,
      y: (element.clientHeight - height * zoom) / 2 - top * zoom, zoom }, { duration: smooth ? 240 : 0 })
  } catch { /* flow instance can be unavailable while changing routes */ }
}

function handleFlowNodeClick({ node }) {
  selectNode(node.id)
}

function handleFlowNodeDragStop({ node }) {
  if (editingLocked.value) return
  nodePositions.value = {
    ...nodePositions.value,
    [node.id]: {
      x: Math.round(Math.max(24, node.position.x)),
      y: Math.round(Math.max(24, node.position.y))
    }
  }
  persistCanvasLayout()
}

function handleViewportChange(viewport) {
  canvasScale.value = Number.isFinite(viewport?.zoom) ? viewport.zoom : 1
}

function handleNodeKeydown(event, id) {
  if (editingLocked.value) return
  if (!event.altKey || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const index = form.value.nodes.findIndex(item => item.id === id)
  const position = positionForNode(id, index)
  const distance = event.shiftKey ? 24 : 8
  const delta = {
    ArrowLeft: [-distance, 0], ArrowRight: [distance, 0], ArrowUp: [0, -distance], ArrowDown: [0, distance]
  }[event.key]
  nodePositions.value = {
    ...nodePositions.value,
    [id]: { x: Math.max(24, position.x + delta[0]), y: Math.max(24, position.y + delta[1]) }
  }
  persistCanvasLayout()
}

function syncViewportMode() {
  const wasCompact = isCompact.value
  const wasPhone = isPhone.value
  isCompact.value = window.innerWidth < 1200
  isPhone.value = window.innerWidth <= 760
  if (!wasCompact && isCompact.value) {
    leftCollapsed.value = true
    rightCollapsed.value = true
  }
  if (!wasPhone && isPhone.value) {
    editorMode.value = 'list'
    leftCollapsed.value = true
    rightCollapsed.value = true
  }
}

function handleGlobalKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    if (!editingLocked.value && !saving.value) void saveWorkflow()
    return
  }
  if (event.key !== 'Escape') return
  if (testPanelOpen.value) return hideTestPanel()
  if (isCompact.value && !rightCollapsed.value) return void (rightCollapsed.value = true)
  if (isCompact.value && !leftCollapsed.value) return void (leftCollapsed.value = true)
  clearNodeSelection()
}

function handleBeforeUnload(event) {
  if (saveState.value !== 'dirty' && saveState.value !== 'error') return
  event.preventDefault()
  event.returnValue = ''
}

function nodeTypeLabel(type) {
  return nodeTypes.find(item => item.value === type)?.label || '处理节点'
}

function nodeIcon(type) {
  return ({
    input: CursorArrowRaysIcon,
    condition: FunnelIcon,
    knowledge: CircleStackIcon,
    tool: WrenchScrewdriverIcon,
    model: CpuChipIcon,
    approval: HandRaisedIcon,
    output: CheckCircleIcon
  })[type] || FunnelIcon
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped lang="scss">
.run-dock-indicator.interrupted{background:var(--warning);box-shadow:0 0 0 4px color-mix(in srgb,var(--warning) 13%,transparent)}.run-dock-toggle:disabled,.run-approval-actions button:disabled{cursor:not-allowed;opacity:.5}.run-dock-toggle svg.refresh-spinning{animation:spin .8s linear infinite}
.workflow-designer{height:100dvh;min-height:620px;display:grid;grid-template-rows:60px minmax(0,1fr) 30px;overflow:hidden;color:var(--text-color);background:var(--canvas)}
.designer-command-bar{position:relative;z-index:40;display:grid;grid-template-columns:minmax(280px,1fr) minmax(210px,320px) minmax(360px,1fr);align-items:center;gap:18px;padding:0 18px;background:var(--surface);border-bottom:1px solid var(--border-color);box-shadow:0 1px 0 rgba(15,23,42,.02)}
.command-leading,.command-actions,.command-context label{display:flex;align-items:center}.command-leading{min-width:0;gap:10px}.icon-button{width:36px;height:36px;display:grid;place-items:center;flex:none;padding:0;color:var(--text-muted);background:transparent;border:1px solid transparent;border-radius:8px}.icon-button:hover:not(:disabled){color:var(--text-color);background:var(--surface-subtle);border-color:var(--border-color)}.icon-button:disabled{opacity:.35}.icon-button svg{width:18px}.back-button{border-color:var(--border-color)}
.workflow-identity{min-width:0}.workflow-identity>span{display:block;color:var(--text-soft);font-size:10px;font-weight:750;letter-spacing:.08em}.workflow-identity input{width:min(360px,100%);margin-top:2px;padding:0;color:var(--text-color);background:transparent;border:0;outline:0;font-size:15px;font-weight:750;text-overflow:ellipsis}
.command-context{display:flex;justify-content:center}.command-context label{width:100%;height:38px;gap:10px;padding:0 10px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px}.command-context label>span{color:var(--text-soft);font-size:10px;white-space:nowrap}.command-context select{min-width:0;flex:1;color:var(--text-color);background:transparent;border:0;outline:0;font-size:12px}
.command-actions{justify-content:flex-end;gap:8px}.save-state{display:flex;align-items:center;gap:6px;margin-right:4px;color:var(--text-muted);font-size:11px;white-space:nowrap}.save-state>span{width:7px;height:7px;border-radius:50%;background:var(--text-soft)}.save-state.new>span{background:var(--primary)}.save-state.dirty>span{background:var(--warning)}.save-state.saving>span{background:var(--primary);animation:pulse 1s infinite}.save-state.saved>span{background:var(--success)}.save-state.error>span{background:var(--danger)}
.secondary-action,.primary-action{height:38px;display:flex;align-items:center;justify-content:center;gap:7px;padding:0 13px;border-radius:8px;font-size:12px;font-weight:700;white-space:nowrap}.secondary-action{color:var(--text-color);background:var(--surface);border:1px solid var(--border-color)}.secondary-action:hover:not(:disabled){background:var(--surface-subtle)}.secondary-action svg{width:16px}.secondary-action:disabled{opacity:.45}.primary-action{color:#fff;background:var(--primary);border:1px solid var(--primary)}.primary-action:hover:not(:disabled){background:var(--primary-hover)}.primary-action:disabled{opacity:.55}
.designer-notice{position:fixed;z-index:90;top:72px;left:50%;max-width:min(560px,calc(100vw - 32px));display:flex;align-items:center;gap:14px;transform:translateX(-50%);padding:10px 12px 10px 15px;color:var(--primary);background:var(--surface);border:1px solid color-mix(in srgb,var(--primary) 35%,var(--border-color));border-radius:9px;box-shadow:var(--shadow-float);font-size:12px}.designer-notice.success{color:var(--success);border-color:color-mix(in srgb,var(--success) 38%,var(--border-color))}.designer-notice.error{color:var(--danger);border-color:color-mix(in srgb,var(--danger) 38%,var(--border-color))}.designer-notice button{width:27px;height:27px;display:grid;place-items:center;padding:0;color:inherit;background:transparent;border:0;border-radius:6px}.designer-notice button:hover{background:var(--surface-subtle)}.designer-notice svg{width:15px}
.designer-loading{grid-row:2;display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--text-muted)}.designer-loading strong{margin-top:13px;color:var(--text-color);font-size:14px}.designer-loading small{margin-top:6px}.loading-mark{width:30px;height:30px;border:3px solid var(--primary-soft);border-top-color:var(--primary);border-radius:50%;animation:spin .75s linear infinite}
.designer-load-error{grid-row:2;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px;color:var(--text-muted);text-align:center}.designer-load-error>span{width:48px;height:48px;display:grid;place-items:center;color:var(--danger);background:color-mix(in srgb,var(--danger) 10%,var(--surface));border-radius:12px}.designer-load-error>span svg{width:24px}.designer-load-error>strong{margin-top:16px;color:var(--text-color);font-size:17px}.designer-load-error>p{max-width:520px;margin:7px 0 0;font-size:12px;line-height:1.7}.designer-load-error>div{display:flex;gap:8px;margin-top:18px}.designer-load-error .secondary-action svg{width:15px}
.designer-workspace{min-height:0;position:relative;display:grid;grid-template-columns:224px minmax(0,1fr) 328px;overflow:hidden}.designer-workspace.palette-collapsed{grid-template-columns:52px minmax(0,1fr) 328px}.designer-workspace.inspector-collapsed{grid-template-columns:224px minmax(0,1fr) 52px}.designer-workspace.palette-collapsed.inspector-collapsed{grid-template-columns:52px minmax(0,1fr) 52px}.designer-workspace.list-mode{grid-template-columns:0 minmax(0,1fr) 328px}.designer-workspace.list-mode.inspector-collapsed{grid-template-columns:0 minmax(0,1fr) 52px}
.node-palette,.node-inspector{min-width:0;position:relative;z-index:20;overflow:hidden;background:var(--surface);transition:width .18s ease,transform .18s ease}.node-palette{border-right:1px solid var(--border-color)}.node-inspector{border-left:1px solid var(--border-color)}.node-palette>header,.node-inspector>header{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid var(--border-color)}.node-palette>header>div strong,.node-palette>header>div span,.node-inspector>header>div strong,.node-inspector>header>div span{display:block}.node-palette>header strong,.node-inspector>header strong{font-size:12px}.node-palette>header span,.node-inspector>header span{margin-top:2px;color:var(--text-soft);font-size:9px}.palette-collapsed .node-palette>header>div,.palette-collapsed .node-palette .palette-content,.inspector-collapsed .node-inspector>header>div,.inspector-collapsed .node-inspector .inspector-tabs,.inspector-collapsed .node-inspector .inspector-content{display:none}.palette-collapsed .node-palette>header,.inspector-collapsed .node-inspector>header{justify-content:center;padding:0}.palette-collapsed .node-palette>header button{transform:rotate(180deg)}.inspector-collapsed .node-inspector>header button{transform:rotate(180deg)}.list-mode .node-palette{visibility:hidden}
.palette-content{height:calc(100% - 52px);overflow:auto;padding:10px}.palette-content>button{width:100%;display:grid;grid-template-columns:34px minmax(0,1fr) 15px;align-items:center;gap:9px;margin-bottom:6px;padding:9px;color:var(--text-color);background:transparent;border:1px solid transparent;border-radius:8px;text-align:left}.palette-content>button:hover{background:var(--surface-subtle);border-color:var(--border-color)}.palette-content>button>span:nth-child(2){min-width:0}.palette-content strong,.palette-content small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.palette-content strong{font-size:12px}.palette-content small{margin-top:3px;color:var(--text-soft);font-size:10px}.palette-content>button>svg{width:14px;color:var(--text-soft)}
.type-symbol,.node-symbol{display:grid;place-items:center;color:var(--primary);background:var(--primary-soft);border-radius:7px}.type-symbol{width:34px;height:34px}.type-symbol svg,.node-symbol svg{width:17px}.type-symbol.condition,.flow-node-card.condition .node-symbol{color:#966a05;background:color-mix(in srgb,#d9a413 14%,var(--surface))}.type-symbol.knowledge,.flow-node-card.knowledge .node-symbol{color:#6d52b5;background:color-mix(in srgb,#7357c6 13%,var(--surface))}.type-symbol.tool,.flow-node-card.tool .node-symbol{color:#b15d0a;background:color-mix(in srgb,#d97706 13%,var(--surface))}.type-symbol.approval,.flow-node-card.approval .node-symbol,.type-symbol.output,.flow-node-card.output .node-symbol{color:var(--success);background:var(--success-soft)}
.designer-stage{min-width:0;min-height:0;display:grid;grid-template-rows:52px minmax(0,1fr);background:var(--canvas)}.stage-toolbar{position:relative;z-index:10;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:0 12px;background:color-mix(in srgb,var(--surface) 96%,transparent);border-bottom:1px solid var(--border-color)}.stage-toolbar-leading,.canvas-tools,.mode-switch{display:flex;align-items:center}.stage-toolbar-leading{gap:9px}.panel-toggle{display:none;border-color:var(--border-color)}.mode-switch{padding:3px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px}.mode-switch button{height:30px;display:flex;align-items:center;gap:6px;padding:0 10px;color:var(--text-muted);background:transparent;border:0;border-radius:6px;font-size:11px;font-weight:700}.mode-switch button svg{width:15px}.mode-switch button.active{color:var(--primary);background:var(--surface);box-shadow:0 1px 4px rgba(15,23,42,.08)}
.canvas-tools{gap:4px}.canvas-tools output{min-width:45px;color:var(--text-muted);font-size:10px;text-align:center;font-variant-numeric:tabular-nums}.tool-button{height:34px;display:flex;align-items:center;gap:5px;padding:0 9px;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:7px;font-size:10px;font-weight:650}.tool-button:hover{color:var(--primary);border-color:color-mix(in srgb,var(--primary) 38%,var(--border-color))}.tool-button svg{width:15px}.tool-button.primary-tool{color:var(--primary);background:var(--primary-soft);border-color:transparent}
.canvas-shell{--flow-grid-dot:color-mix(in srgb,var(--text-soft) 34%,transparent);--flow-minimap-node:color-mix(in srgb,var(--primary) 78%,var(--surface));--flow-minimap-stroke:var(--primary);--flow-minimap-mask:color-mix(in srgb,var(--surface) 72%,transparent);min-width:0;min-height:0;position:relative;overflow:hidden;background:var(--surface)}.workflow-flow{width:100%;height:100%;background:var(--surface)}
:deep(.vue-flow__pane){cursor:grab}:deep(.vue-flow__pane.dragging){cursor:grabbing}:deep(.vue-flow__node-workflow){width:232px;background:transparent;border:0;box-shadow:none}:deep(.vue-flow__node-workflow:focus-visible){outline:none}.flow-node-card{position:relative;width:232px;height:112px;display:grid;grid-template-columns:25px 36px minmax(0,1fr);align-items:start;gap:9px;padding:14px;color:var(--text-color);background:var(--surface);border:1px solid var(--border-color);border-radius:11px;box-shadow:0 6px 20px rgba(15,23,42,.08);text-align:left;cursor:grab;user-select:none;transition:border-color .14s ease,box-shadow .14s ease,transform .14s ease}.flow-node-card:hover{transform:translateY(-1px);border-color:color-mix(in srgb,var(--primary) 42%,var(--border-color));box-shadow:0 10px 28px rgba(15,23,42,.12)}.flow-node-card.selected{border-color:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 14%,transparent),0 14px 34px rgba(15,23,42,.14)}:deep(.vue-flow__node.dragging) .flow-node-card{cursor:grabbing;box-shadow:0 18px 40px rgba(15,23,42,.18)}:deep(.vue-flow__node:focus-visible) .flow-node-card{outline:2px solid var(--primary);outline-offset:3px}.node-sequence{width:22px;height:22px;display:grid;place-items:center;color:var(--text-soft);background:var(--surface-subtle);border-radius:5px;font-size:10px;font-weight:750}.node-symbol{width:34px;height:34px}.node-copy{min-width:0;display:block}.node-copy small,.node-copy strong,.node-copy em{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-copy small{color:var(--text-soft);font-size:10px;letter-spacing:.04em}.node-copy strong{margin-top:3px;font-size:13px}.node-copy em{margin-top:6px;color:var(--text-muted);font-size:10px;font-style:normal}.flow-handle{width:11px!important;height:11px!important;background:var(--surface)!important;border:2px solid var(--primary)!important;box-shadow:0 0 0 2px color-mix(in srgb,var(--primary) 10%,transparent)}
:deep(.vue-flow__edge-path){stroke:color-mix(in srgb,var(--primary) 72%,var(--border-color));stroke-width:1.8}:deep(.vue-flow__edge.selected .vue-flow__edge-path){stroke:var(--primary);stroke-width:2}:deep(.vue-flow__controls){overflow:hidden;border:1px solid var(--border-color);border-radius:9px;box-shadow:0 7px 22px rgba(15,23,42,.1)}:deep(.vue-flow__controls-button){width:34px;height:34px;color:var(--text-muted);background:var(--surface);border-bottom-color:var(--border-color)}:deep(.vue-flow__controls-button:hover){color:var(--primary);background:var(--primary-soft)}:deep(.vue-flow__controls-button svg){fill:currentColor}:deep(.vue-flow__minimap){overflow:hidden;background:color-mix(in srgb,var(--surface) 96%,transparent);border:1px solid var(--border-color);border-radius:10px;box-shadow:0 8px 24px rgba(15,23,42,.1)}
.canvas-empty-state{position:absolute;left:50%;top:50%;display:flex;flex-direction:column;align-items:center;transform:translate(-50%,-50%);color:var(--text-muted);text-align:center}.canvas-empty-state>svg{width:38px;color:var(--text-soft)}.canvas-empty-state strong{margin-top:12px;color:var(--text-color);font-size:13px}.canvas-empty-state>span{margin-top:5px;font-size:11px}.canvas-empty-state button{height:36px;display:flex;align-items:center;gap:6px;margin-top:14px;padding:0 12px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:7px;font-size:11px}.canvas-empty-state button svg{width:15px}.mobile-preview-note{position:absolute;z-index:6;top:10px;left:50%;transform:translateX(-50%);padding:8px 11px;color:var(--text-muted);background:var(--surface);border:1px solid var(--border-color);border-radius:7px;box-shadow:0 6px 18px rgba(15,23,42,.08);font-size:10px;white-space:nowrap}
.list-workspace{min-height:0;overflow:auto;padding:22px clamp(16px,3vw,40px) 36px}.list-workspace>header{display:flex;align-items:end;justify-content:space-between;gap:20px;margin:0 auto 14px;max-width:1180px}.list-workspace>header strong,.list-workspace>header span{display:block}.list-workspace>header strong{font-size:15px}.list-workspace>header span{margin-top:4px;color:var(--text-muted);font-size:11px}.list-workspace>header button,.list-empty-state button{height:36px;display:flex;align-items:center;gap:6px;padding:0 12px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:7px;font-size:11px;font-weight:700}.list-workspace>header button svg{width:15px}.node-list{max-width:1180px;margin:0 auto}.node-list article{display:grid;grid-template-columns:32px 140px minmax(150px,220px) minmax(220px,1fr) 108px;align-items:end;gap:10px;margin-bottom:8px;padding:12px;background:var(--surface);border:1px solid var(--border-color);border-radius:9px}.node-list article:hover,.node-list article.selected{border-color:color-mix(in srgb,var(--primary) 36%,var(--border-color))}.list-index{width:28px;height:34px;display:grid;place-items:center;align-self:end;color:var(--primary);background:var(--primary-soft);border-radius:6px;font-size:11px;font-weight:750}.node-list label,.inspector-content>label{display:grid;gap:6px;color:var(--text-muted);font-size:10px;font-weight:650}.node-list input,.node-list select,.inspector-content input,.inspector-content select,.inspector-content textarea{width:100%;padding:8px 9px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:6px;outline:0;font-size:11px}.node-list input:focus,.node-list select:focus,.inspector-content input:focus,.inspector-content select:focus,.inspector-content textarea:focus{border-color:var(--primary);box-shadow:0 0 0 2px var(--primary-soft)}.inspector-content textarea{resize:vertical;line-height:1.55}.list-actions{display:flex;align-items:center;align-self:end}.list-actions button,.inspector-order button{width:34px;height:34px;display:grid;place-items:center;padding:0;color:var(--text-muted);background:transparent;border:0;border-radius:6px}.list-actions button:hover:not(:disabled),.inspector-order button:hover:not(:disabled){background:var(--surface-subtle)}.list-actions button:disabled,.inspector-order button:disabled{opacity:.3}.list-actions .danger-button{color:var(--danger)}.list-actions svg,.inspector-order svg{width:15px}.list-empty-state{min-height:300px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--text-muted)}.list-empty-state>svg{width:36px}.list-empty-state strong{margin:12px 0}
.inspector-tabs{display:grid;grid-template-columns:1fr 1fr;padding:8px;border-bottom:1px solid var(--border-color)}.inspector-tabs button{height:32px;color:var(--text-muted);background:transparent;border:0;border-radius:6px;font-size:10px;font-weight:700}.inspector-tabs button[aria-selected="true"]{color:var(--primary);background:var(--primary-soft)}.inspector-tabs button:disabled{opacity:.4}.inspector-content{height:calc(100% - 101px);overflow:auto;padding:14px}.inspector-title{display:flex;align-items:center;gap:10px;padding-bottom:14px;border-bottom:1px solid var(--border-color)}.inspector-title>div strong,.inspector-title>div small{display:block}.inspector-title>div strong{font-size:12px}.inspector-title>div small{margin-top:3px;color:var(--text-soft);font-size:10px}.inspector-content>label{margin-top:14px}.inspector-order{margin-top:17px;padding-top:14px;border-top:1px solid var(--border-color)}.inspector-order>span{color:var(--text-muted);font-size:10px;font-weight:650}.inspector-order>div{display:grid;grid-template-columns:34px 1fr 34px;align-items:center;margin-top:7px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px}.inspector-order strong{font-size:10px;text-align:center}.delete-node{width:100%;height:36px;display:flex;align-items:center;justify-content:center;gap:7px;margin-top:15px;color:var(--danger);background:transparent;border:1px solid color-mix(in srgb,var(--danger) 30%,var(--border-color));border-radius:7px;font-size:11px}.delete-node:disabled{opacity:.35}.delete-node svg{width:15px}.workflow-settings{padding-top:2px}.enable-setting{display:flex!important;align-items:center;justify-content:space-between;gap:16px;margin-top:18px!important;padding:13px;background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:8px}.enable-setting>span strong,.enable-setting>span small{display:block}.enable-setting>span strong{color:var(--text-color);font-size:11px}.enable-setting>span small{max-width:190px;margin-top:4px;color:var(--text-soft);font-size:9px;font-weight:400;line-height:1.5}.enable-setting input{width:18px;height:18px;flex:none;accent-color:var(--primary)}.setting-note{margin-top:14px;padding:12px;color:var(--text-muted);background:var(--primary-soft);border-radius:8px}.setting-note strong{color:var(--primary);font-size:10px}.setting-note p{margin:5px 0 0;font-size:10px;line-height:1.65}
.panel-backdrop{display:none}.designer-status-bar{display:flex;align-items:center;gap:18px;padding:0 14px;color:var(--text-soft);background:var(--surface);border-top:1px solid var(--border-color);font-size:9px}.status-copy{display:flex;align-items:center;gap:6px;color:var(--text-muted)}.status-copy i{width:6px;height:6px;border-radius:50%;background:var(--text-soft)}.status-copy.new i{background:var(--primary)}.status-copy.dirty i{background:var(--warning)}.status-copy.saved i{background:var(--success)}.status-copy.error i{background:var(--danger)}.local-layout-note{margin-left:auto}
.stage-run-tools{display:flex;align-items:center;gap:6px;margin-left:auto}.stage-run-tools:empty{display:none}.history-tool svg,.mobile-run-tool svg{width:14px}.mobile-run-tool{display:none}.designer-stage.has-run-dock{grid-template-rows:52px minmax(0,1fr) auto}
.flow-node-card.has-run{padding-bottom:29px}.flow-node-card.run-waiting{opacity:.76}.flow-node-card.run-running{border-color:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 16%,transparent),0 10px 26px rgba(15,23,42,.12)}.flow-node-card.run-completed{border-color:color-mix(in srgb,var(--success) 65%,var(--border-color))}.flow-node-card.run-failed{border-color:var(--danger);box-shadow:0 0 0 3px color-mix(in srgb,var(--danger) 14%,transparent),0 10px 26px rgba(15,23,42,.12)}.flow-node-card.run-skipped{opacity:.55}.flow-node-card.run-running.selected{box-shadow:0 0 0 4px color-mix(in srgb,var(--primary) 22%,transparent),0 14px 34px rgba(15,23,42,.14)}.flow-node-card.run-failed.selected{box-shadow:0 0 0 4px color-mix(in srgb,var(--danger) 19%,transparent),0 14px 34px rgba(15,23,42,.14)}
.node-run-status{position:absolute;left:85px;right:12px;bottom:10px;display:flex;align-items:center;gap:5px;overflow:hidden;color:var(--text-soft);font-size:10px;font-weight:700;white-space:nowrap}.node-run-status small{overflow:hidden;color:var(--text-soft);font-size:9px;font-weight:500;text-overflow:ellipsis}.node-run-status.running{color:var(--primary)}.node-run-status.completed{color:var(--success)}.node-run-status.failed{color:var(--danger)}.run-status-mark{width:8px;height:8px;display:inline-block;flex:none;background:var(--text-soft);border-radius:50%}.running .run-status-mark,.run-status-mark.running{width:10px;height:10px;background:transparent;border:2px solid color-mix(in srgb,var(--primary) 25%,transparent);border-top-color:var(--primary);animation:spin .8s linear infinite}.completed .run-status-mark,.run-status-mark.completed{background:var(--success)}.failed .run-status-mark,.run-status-mark.failed{background:var(--danger)}.skipped .run-status-mark,.run-status-mark.skipped{background:var(--text-soft)}
.node-list article.run-running{border-color:var(--primary);box-shadow:0 0 0 2px color-mix(in srgb,var(--primary) 14%,transparent)}.node-list article.run-completed{border-color:color-mix(in srgb,var(--success) 50%,var(--border-color))}.node-list article.run-failed{border-color:var(--danger)}.node-list article.run-skipped,.node-list article.run-not-run{opacity:.65}.list-index{display:flex;align-items:center;justify-content:center;flex-direction:column;gap:1px}.list-index small{font-size:8px;font-weight:700;white-space:nowrap}.list-index small.running{color:var(--primary)}.list-index small.completed{color:var(--success)}.list-index small.failed{color:var(--danger)}.flow-node-card.run-not-run{opacity:.58}.palette-content>button:disabled,.list-workspace>header button:disabled,.tool-button:disabled{cursor:not-allowed;opacity:.45}
.run-dock{position:relative;z-index:9;min-width:0;background:var(--surface);border-top:1px solid var(--border-color);box-shadow:0 -7px 22px rgba(15,23,42,.06)}.run-dock-summary{min-height:51px;display:flex;align-items:center;gap:11px;padding:0 14px;font-size:11px}.run-dock-indicator{width:9px;height:9px;flex:none;background:var(--text-soft);border-radius:50%}.run-dock-indicator.running{background:var(--primary);box-shadow:0 0 0 4px color-mix(in srgb,var(--primary) 13%,transparent);animation:pulse 1s ease-in-out infinite}.run-dock-indicator.completed{background:var(--success)}.run-dock-indicator.failed{background:var(--danger)}.run-dock-summary strong{font-size:12px;white-space:nowrap}.run-dock-progress{padding:4px 7px;color:var(--primary);background:var(--primary-soft);border-radius:5px;font-weight:700;white-space:nowrap;font-variant-numeric:tabular-nums}.run-dock-current{min-width:0;overflow:hidden;color:var(--text-muted);text-overflow:ellipsis;white-space:nowrap}.run-dock-time{margin-left:auto;color:var(--text-soft);white-space:nowrap;font-variant-numeric:tabular-nums}.run-dock-toggle,.run-dock-close{height:29px;display:flex;align-items:center;justify-content:center;gap:4px;padding:0 8px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:6px;font-size:10px;font-weight:700;white-space:nowrap}.run-dock-toggle:hover,.run-dock-close:hover{background:color-mix(in srgb,var(--primary) 16%,var(--surface))}.run-dock-toggle svg{width:13px;transition:transform .15s ease}.run-dock-toggle svg.rotated{transform:rotate(180deg)}.run-dock-close{width:29px;padding:0;color:var(--text-muted);background:transparent}.run-dock-close svg{width:15px}.run-dock-detail{padding:12px 14px 15px;border-top:1px solid var(--border-color)}.run-dock-detail>p{margin:0 0 12px;color:var(--text-muted);font-size:10px;line-height:1.5}.run-step-list{display:flex;gap:7px;overflow-x:auto;padding:2px 2px 5px}.run-step-list>button{min-width:176px;max-width:230px;min-height:64px;display:grid;grid-template-columns:20px 10px minmax(0,1fr);grid-template-rows:19px 17px;align-items:center;gap:2px 6px;flex:1;padding:9px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;text-align:left}.run-step-list>button:hover,.run-step-list>button.active{border-color:var(--primary)}.run-step-list>button.failed{border-color:var(--danger)}.run-step-index{width:19px;height:19px;display:grid;place-items:center;color:var(--text-soft);background:var(--surface);border-radius:4px;font-size:9px;font-weight:700}.run-step-list>button strong{min-width:0;overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.run-step-list>button small{grid-column:3;color:var(--text-soft);font-size:9px}.run-step-list>button.running small{color:var(--primary)}.run-step-list>button.completed small{color:var(--success)}.run-step-list>button.failed small{color:var(--danger)}.run-step-list>button time{grid-column:3;grid-row:2;justify-self:end;color:var(--text-soft);font-size:9px}
.inspector-tabs.with-run-tab{grid-template-columns:repeat(3,1fr)}.inspector-run-detail{padding-top:14px}.run-detail-state{display:flex;align-items:center;gap:8px;margin-top:15px;padding:10px;color:var(--text-muted);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;font-size:11px}.run-detail-state.running{color:var(--primary)}.run-detail-state.completed{color:var(--success)}.run-detail-state.failed{color:var(--danger)}.run-detail-state time{margin-left:auto;color:var(--text-soft);font-size:10px}.run-detail-block{margin-top:19px}.run-detail-block>span{color:var(--text-muted);font-size:10px;font-weight:700}.run-detail-block p{margin:8px 0 0;padding:11px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;font-size:11px;line-height:1.65;text-wrap:pretty;overflow-wrap:anywhere}.follow-run-button{width:100%;height:34px;margin-top:18px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:7px;font-size:11px;font-weight:700}
.run-dock-detail{max-height:min(48dvh,390px);overflow:auto}.run-input-form{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto;align-items:end;gap:10px}.run-input-form label{min-width:0;display:grid;gap:5px;color:var(--text-muted);font-size:10px;font-weight:700}.run-input-form textarea{width:100%;min-height:62px;resize:vertical;padding:9px;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;font-size:11px;line-height:1.5}.run-input-form textarea:focus-visible{outline:2px solid var(--primary);outline-offset:1px}.run-input-form p{grid-column:1/-1;margin:0;color:var(--text-soft);font-size:10px}.run-input-form p.run-config-warning{color:var(--warning);font-weight:700}.run-submit{grid-column:3;grid-row:1;height:36px;display:flex;align-items:center;justify-content:center;gap:6px;padding:0 13px;color:#fff;background:var(--primary);border:0;border-radius:7px;font-size:11px;font-weight:700;white-space:nowrap}.run-submit svg{width:14px}.run-submit:hover{background:var(--primary-hover)}.run-approval-actions{display:flex;align-items:center;gap:8px;margin:0 0 12px;padding:10px;background:var(--primary-soft);border-radius:8px}.run-approval-actions span{min-width:0;flex:1;color:var(--text-color);font-size:11px}.run-approval-actions button{height:31px;padding:0 9px;color:#fff;background:var(--primary);border:0;border-radius:6px;font-size:10px;font-weight:700;white-space:nowrap}.run-approval-actions button.reject{color:var(--danger);background:var(--surface);border:1px solid color-mix(in srgb,var(--danger) 30%,var(--border-color))}.run-final-output{margin-top:14px;padding-top:12px;border-top:1px solid var(--border-color)}.run-final-output strong{font-size:11px}.run-final-output pre,.run-detail-block pre{margin:7px 0 0;padding:10px;max-height:220px;overflow:auto;color:var(--text-color);background:var(--surface-subtle);border:1px solid var(--border-color);border-radius:7px;font:11px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere}.run-detail-block pre.run-error-output{color:var(--danger);border-color:color-mix(in srgb,var(--danger) 28%,var(--border-color))}.config-hint{margin:8px 0 0;color:var(--text-soft);font-size:10px;line-height:1.6;overflow-wrap:anywhere}.fill-config-template{margin-top:7px;padding:6px 8px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:5px;font-size:10px;font-weight:700}.fill-config-template:disabled{opacity:.45}.edge-editor{margin-top:18px;padding-top:14px;border-top:1px solid var(--border-color)}.edge-editor-heading{display:flex;align-items:center;justify-content:space-between;gap:8px}.edge-editor-heading strong{font-size:11px}.edge-editor-heading button{padding:5px 7px;color:var(--primary);background:var(--primary-soft);border:0;border-radius:5px;font-size:10px}.edge-editor>p{margin:7px 0 10px;color:var(--text-soft);font-size:10px;line-height:1.5}.edge-editor-row{display:grid;grid-template-columns:minmax(0,1fr) 12px minmax(0,1fr) 55px 26px;align-items:center;gap:3px;margin-top:7px}.edge-editor-row select{min-width:0;padding:6px 3px!important;font-size:9px!important}.edge-editor-row>span{color:var(--text-soft);font-size:10px;text-align:center}.edge-editor-row button{height:28px;display:grid;place-items:center;padding:0;color:var(--danger);background:transparent;border:0;border-radius:5px}.edge-editor-row button:hover{background:var(--surface-subtle)}.edge-editor-row svg{width:14px}.flow-node-card.run-approval-waiting{border-color:var(--warning);box-shadow:0 0 0 3px color-mix(in srgb,var(--warning) 15%,transparent)}.node-run-status.approval-waiting,.list-index small.approval-waiting{color:var(--warning)}.approval-waiting .run-status-mark{background:var(--warning)}.run-detail-state.approval-waiting{color:var(--warning)}.run-step-list>button.approval-waiting{border-color:var(--warning)}.run-step-list>button.approval-waiting small{color:var(--warning)}
:deep(.vue-flow__edge.run-edge-idle){opacity:.28}:deep(.vue-flow__edge.run-edge-traversed .vue-flow__edge-path){stroke:var(--success)}:deep(.vue-flow__edge.run-edge-active .vue-flow__edge-path){stroke:var(--primary);stroke-width:2.7;stroke-dasharray:10 7;animation:edge-flow .75s linear infinite}:deep(.vue-flow__edge-textbg){fill:var(--surface)}:deep(.vue-flow__edge-text){fill:var(--text-muted);font-size:10px;font-weight:700}
@keyframes edge-flow{to{stroke-dashoffset:-17}}
@keyframes spin{to{transform:rotate(360deg)}}@keyframes pulse{50%{opacity:.35}}

@media(max-width:1199px){
  .designer-command-bar{grid-template-columns:minmax(240px,1fr) minmax(300px,1fr)}.command-context{display:none}.save-state{display:none}
  .designer-workspace,.designer-workspace.palette-collapsed,.designer-workspace.inspector-collapsed,.designer-workspace.palette-collapsed.inspector-collapsed,.designer-workspace.list-mode,.designer-workspace.list-mode.inspector-collapsed{grid-template-columns:minmax(0,1fr)}
  .node-palette,.node-inspector{position:absolute;top:0;bottom:0;z-index:35;width:264px;box-shadow:var(--shadow-float)}.node-palette{left:0}.node-inspector{right:0;width:328px}.palette-collapsed .node-palette,.list-mode .node-palette{transform:translateX(-105%);visibility:visible}.inspector-collapsed .node-inspector{transform:translateX(105%)}
  .panel-backdrop{position:absolute;inset:0;z-index:30;display:block;background:rgba(8,13,23,.28);border:0;backdrop-filter:blur(1px)}.palette-collapsed .stage-toolbar-leading>.panel-toggle,.inspector-collapsed .stage-toolbar>.panel-toggle{display:grid}
  .node-list article{grid-template-columns:32px 130px minmax(150px,210px) minmax(190px,1fr) 108px}
}

@media(max-width:760px){
  .workflow-designer{min-height:520px;grid-template-rows:56px minmax(0,1fr) 32px}.designer-command-bar{grid-template-columns:minmax(0,1fr) auto;gap:8px;padding:0 10px}.workflow-identity>span{display:none}.workflow-identity input{font-size:13px}.command-actions{gap:5px}.run-action,.command-actions .save-state{display:none}.primary-action{height:36px;padding:0 10px}.back-button{width:36px;height:36px}.designer-notice{top:64px}
  .stage-toolbar{padding:0 8px}.mode-switch button{padding:0 8px}.mode-switch button span{display:none}.canvas-tools{gap:1px}.canvas-tools .tool-button span,.canvas-tools .primary-tool{display:none}.canvas-tools output{min-width:38px}.canvas-tools .icon-button,.stage-toolbar>.icon-button{width:34px;height:34px}.node-inspector{width:min(328px,calc(100vw - 26px))}.node-palette{width:min(264px,calc(100vw - 50px))}.history-tool{width:34px;padding:0}.history-tool span{display:none}.mobile-run-tool{display:flex;padding:0 7px}.stage-run-tools{margin-left:auto}.run-dock-summary{gap:7px;padding:0 8px}.run-dock-current,.run-dock-time{display:none}.run-dock-progress{margin-left:auto}.run-dock-toggle{padding:0 6px}
  .list-workspace{padding:14px 10px 28px}.list-workspace>header{align-items:center}.list-workspace>header span{display:none}.node-list article{grid-template-columns:32px 1fr 88px;padding:10px}.node-list label{grid-column:2/-1}.node-list .description-field{grid-column:2/-1}.list-actions{grid-column:3;grid-row:1}.list-actions button{width:29px}.keyboard-tip,.local-layout-note{display:none}.designer-status-bar{gap:12px;padding:0 10px;font-size:9px}.mobile-preview-note{max-width:calc(100vw - 44px);overflow:hidden;text-overflow:ellipsis}
  .run-input-form{grid-template-columns:1fr}.run-input-form p{grid-column:1}.run-submit{grid-column:1;grid-row:auto;justify-self:stretch}.run-approval-actions{flex-wrap:wrap}.run-approval-actions span{flex-basis:100%}
}

@media(prefers-reduced-motion:reduce){.node-palette,.node-inspector,.flow-node-card,.run-dock-toggle svg{transition:none}.save-state.saving>span,.loading-mark,.run-dock-indicator.running,.running .run-status-mark,.run-status-mark.running,.run-dock-toggle svg.refresh-spinning,:deep(.vue-flow__edge.run-edge-active .vue-flow__edge-path){animation:none}}
</style>
<style scoped src="../assets/workflow-designer.css"></style>
