/**
 * diagnosis-engine 诊断控制台
 *
 * 三个分区互相独立：
 *   平台层  共享资源，不随项目切换变化，数据来自 /api/monitoring/aiops-dashboard
 *   项目层  当前所选项目的业务指标 / 活跃异常 / 最近变更，数据来自 /api/diagnosis/*
 *   报告层  按项目分槽存储的诊断报告，平台级报告存在固定槽位
 */
Object.assign(SuperBizAgentApp.prototype, {

    DE_PLATFORM_SLOT: '__platform__',
    DE_REPORTS_STORAGE_KEY: 'diagnosis_engine_reports',

    // ==================== 视图切换 ====================

    switchMainView(view) {
        this.currentMainView = view;
        const isChat = view === 'chat';
        const isMonitor = view === 'aiops-monitor';

        if (this.chatView) {
            this.chatView.classList.toggle('active', isChat);
        }
        if (this.aiopsMonitorView) {
            this.aiopsMonitorView.classList.toggle('active', isMonitor);
        }
        if (this.aiOpsMonitorNavBtn) {
            this.aiOpsMonitorNavBtn.classList.toggle('active', isMonitor);
        }
        if (this.newChatBtn) {
            this.newChatBtn.classList.toggle('active', isChat);
        }
        if (isMonitor) {
            // 先露出面板再刷新：Chart.js 在 display:none 容器里会画成 0 宽空图
            this.refreshConsole();
            // 手动点进控制台时，也要把本地已保存的报告重新挂上去
            this.renderReportPanel(this.deReportSlot || this.DE_PLATFORM_SLOT);
            this.resizeMonitorCharts();
        }
    },

    /** 视图从隐藏切到可见后，强制 Chart.js 按真实容器尺寸重算 */
    resizeMonitorCharts() {
        if (!this.aiopsMonitorCharts || !this.aiopsMonitorCharts.length) return;
        requestAnimationFrame(() => {
            this.aiopsMonitorCharts.forEach(chart => {
                try { chart.resize(); } catch (e) { /* ignore */ }
            });
        });
    },

    // ==================== 报告存储（按项目分槽） ====================

    /** 读取报告仓库，首次读取时把旧的单键结构迁移到平台级槽位 */
    loadReportStore() {
        let store = {};
        try {
            const raw = localStorage.getItem(this.DE_REPORTS_STORAGE_KEY);
            if (raw) {
                store = JSON.parse(raw) || {};
            }
        } catch (e) {
            console.warn('读取诊断报告存储失败，按空仓库处理', e);
            store = {};
        }

        if (!store[this.DE_PLATFORM_SLOT]) {
            try {
                const legacyRaw = localStorage.getItem(this.LAST_AIOPS_STORAGE_KEY);
                if (legacyRaw) {
                    const legacy = JSON.parse(legacyRaw);
                    if (legacy && (legacy.report !== undefined || legacy.dashboard !== undefined)) {
                        store[this.DE_PLATFORM_SLOT] = Object.assign({
                            mode: 'PLATFORM',
                            projectName: '全平台共享资源',
                            dataState: 'live'
                        }, legacy);
                        localStorage.setItem(this.DE_REPORTS_STORAGE_KEY, JSON.stringify(store));
                    }
                }
            } catch (e) {
                console.warn('迁移旧版 AI Ops 记录失败', e);
            }
        }
        return store;
    },

    loadReportRecord(slot) {
        const store = this.loadReportStore();
        return store[slot || this.DE_PLATFORM_SLOT] || null;
    },

    saveReportRecord(slot, record) {
        const store = this.loadReportStore();
        store[slot || this.DE_PLATFORM_SLOT] = record;
        try {
            localStorage.setItem(this.DE_REPORTS_STORAGE_KEY, JSON.stringify(store));
        } catch (e) {
            console.warn('保存诊断报告失败', e);
        }
    },

    loadLastAiOpsResult() {
        return this.loadReportRecord(this.DE_PLATFORM_SLOT);
    },

    saveLastAiOpsResult(record) {
        this.saveReportRecord(this.DE_PLATFORM_SLOT, record);
    },

    // ==================== 分区数据状态 ====================

    /** state: live | placeholder | sample */
    setPanelState(panel, state) {
        this.dePanelStates = this.dePanelStates || {};
        this.dePanelStates[panel] = state;

        const badge = {
            platform: this.dePlatformBadge,
            project: this.deProjectBadge,
            report: this.deReportBadge
        }[panel];
        if (!badge) return;

        if (state === 'placeholder') {
            badge.hidden = false;
            badge.className = 'de-badge placeholder';
            badge.textContent = '占位数据';
            badge.title = '后端占位接口返回的固定数据，不反映真实系统状态';
        } else if (state === 'sample') {
            badge.hidden = false;
            badge.className = 'de-badge sample';
            badge.textContent = '样例数据';
            badge.title = '接口不可达，当前展示本地样例，不可作为诊断依据';
        } else {
            badge.hidden = true;
            badge.className = 'de-badge';
            badge.textContent = '';
            badge.title = '';
        }
    },

    getPanelState(panel) {
        return (this.dePanelStates || {})[panel] || 'live';
    },

    isSamplePanel(panel) {
        return this.getPanelState(panel) === 'sample';
    },

    // ==================== 项目列表与切换 ====================

    getCurrentProject() {
        if (!this.currentProjectId) return null;
        return (this.deProjects || []).find(p => p.id === this.currentProjectId) || null;
    },

    async loadProjects() {
        let projects;
        try {
            const response = await fetch(`${this.apiBaseUrl}/diagnosis/projects`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            projects = data.projects || [];
            this.deProjectListState = data.placeholder ? 'placeholder' : 'live';
        } catch (error) {
            console.warn('项目列表接口不可用，降级到样例数据', error);
            projects = typeof DIAGNOSIS_SAMPLE_PROJECTS !== 'undefined' ? DIAGNOSIS_SAMPLE_PROJECTS : [];
            this.deProjectListState = 'sample';
        }

        this.deProjects = projects;
        this.renderProjectList(projects);
        this.renderProjectSelect(projects);
        this.restoreCurrentProject(projects);
        this.renderConsoleMeta();
        return projects;
    },

    renderProjectList(projects) {
        if (!this.deProjectList) return;

        if (!projects || projects.length === 0) {
            this.deProjectList.innerHTML = '<div class="de-project-empty">暂无已登记的项目</div>';
            return;
        }

        this.deProjectList.innerHTML = projects.map(project => {
            const classes = ['de-project-item'];
            if (project.id === this.currentProjectId) classes.push('active');
            if (!project.diagnosable) classes.push('disabled');

            const reason = project.diagnosable
                ? ''
                : `<span class="de-project-item-reason">未接入：${this.escapeHtml(project.reason || '缺少项目档案')}</span>`;
            const alertDot = project.alert_count > 0
                ? `<span class="de-project-alert-dot" title="活跃异常 ${project.alert_count} 条">${project.alert_count}</span>`
                : '';

            return `
                <button type="button" class="${classes.join(' ')}" data-project-id="${this.escapeHtml(project.id)}"
                        ${project.diagnosable ? '' : 'disabled'}
                        title="${this.escapeHtml(project.diagnosable ? project.name : (project.reason || '该项目未接入诊断'))}">
                    <span class="de-project-item-main">
                        <span class="de-project-item-name">${this.escapeHtml(project.name)}</span>
                        ${reason}
                    </span>
                    ${alertDot}
                </button>`;
        }).join('');

        this.deProjectList.querySelectorAll('.de-project-item').forEach(item => {
            item.addEventListener('click', () => {
                if (item.disabled) return;
                this.switchMainView('aiops-monitor');
                this.setCurrentProject(item.dataset.projectId);
            });
        });
    },

    renderProjectSelect(projects) {
        if (!this.deProjectSelect) return;

        const options = (projects || []).map(project => {
            const suffix = project.diagnosable ? '' : '（未接入诊断）';
            return `<option value="${this.escapeHtml(project.id)}" ${project.diagnosable ? '' : 'disabled'}>${this.escapeHtml(project.name)}${suffix}</option>`;
        });
        // 空值选项对应「不选任何项目」，此时控制台回到平台级形态，报告层展示平台级报告
        this.deProjectSelect.innerHTML = '<option value="">全平台 · 平台级诊断</option>' + options.join('');
        this.deProjectSelect.value = this.currentProjectId || '';
    },

    restoreCurrentProject(projects) {
        const list = projects || [];
        const stored = localStorage.getItem(this.DE_CURRENT_PROJECT_KEY);
        const firstDiagnosable = list.find(p => p.diagnosable);
        const storedProject = stored ? list.find(p => p.id === stored) : null;

        if (stored && !storedProject) {
            this.showNotification(
                firstDiagnosable
                    ? `上次选择的项目已不在列表中，已切换到「${firstDiagnosable.name}」`
                    : '上次选择的项目已不在列表中，且当前没有可诊断的项目',
                'warning'
            );
        }

        const target = storedProject ? storedProject.id : (firstDiagnosable ? firstDiagnosable.id : null);
        this.setCurrentProject(target);
    },

    setCurrentProject(projectId, options = {}) {
        const next = projectId || null;
        const changed = next !== this.currentProjectId;
        this.currentProjectId = next;

        if (next) {
            localStorage.setItem(this.DE_CURRENT_PROJECT_KEY, next);
        } else {
            localStorage.removeItem(this.DE_CURRENT_PROJECT_KEY);
        }

        // 两处入口的选中态保持一致
        if (this.deProjectSelect) {
            this.deProjectSelect.value = next || '';
        }
        if (this.deProjectList) {
            this.deProjectList.querySelectorAll('.de-project-item').forEach(item => {
                item.classList.toggle('active', item.dataset.projectId === next);
            });
        }

        const project = this.getCurrentProject();
        if (this.deProjectTitleName) {
            this.deProjectTitleName.textContent = project ? project.name : '未选择项目';
        }
        if (this.deModeBadge) {
            const projectMode = !!(project && project.diagnosable);
            this.deModeBadge.textContent = projectMode ? '项目级诊断' : '平台级诊断';
            this.deModeBadge.classList.toggle('platform', !projectMode);
        }
        this.setDiagnosisStatus(null);
        this.updateDiagnosisButtonState();

        if (!changed && !options.force) return;

        // 切换项目时先清空项目层，不保留上一个项目的数据
        if (!project) {
            this.setPanelState('project', 'live');
            this.renderProjectPanelMessage('<div class="de-empty-hint">当前为平台级形态，只诊断共享资源。要查看某个项目的业务链路，请从左侧「被监控项目」列表或顶部切换器选择一个项目。</div>');
        } else if (!project.diagnosable) {
            this.setPanelState('project', 'live');
            this.renderProjectPanelMessage(
                `<div class="de-empty-hint">项目「${this.escapeHtml(project.name)}」未接入诊断：${this.escapeHtml(project.reason || '缺少项目档案')}</div>`
            );
        } else {
            this.renderProjectPanelMessage('<div class="aiops-dashboard-loading">正在加载项目数据...</div>');
            this.scheduleProjectRefresh();
        }

        // 报告层跟随当前项目
        this.deReportSlot = next || this.DE_PLATFORM_SLOT;
        this.renderReportPanel(this.deReportSlot);
    },

    // ==================== 控制台刷新编排 ====================

    async refreshConsole() {
        await this.loadProjects();
        // 两层各自处理失败，互不影响
        await Promise.allSettled([
            this.refreshPlatformPanel(),
            this.refreshProjectPanel()
        ]);
    },

    async refreshPlatformPanel() {
        if (!this.aiopsMonitorDashboard) return;
        try {
            const dashboard = await this.fetchAiOpsDashboard(60);
            this.renderPlatformPanel(dashboard);
        } catch (error) {
            console.error('平台层数据加载失败', error);
            this.renderPlatformError(error.message);
        }
    },

    renderPlatformPanel(dashboard) {
        if (!this.aiopsMonitorDashboard || !dashboard) return;
        this.dePlatformDashboard = dashboard;
        this.setPanelState('platform', 'live');
        this.renderDashboardIntoContainer(this.aiopsMonitorDashboard, dashboard, 'monitor');
        this.renderConsoleMeta();
    },

    /** 平台层失败时展示失败原因与重试入口，不用零值冒充数据 */
    renderPlatformError(message) {
        if (!this.aiopsMonitorDashboard) return;
        this.destroyMonitorCharts();
        this.aiopsMonitorDashboard.innerHTML = `
            <div class="de-error-hint">
                <span>平台层数据加载失败：${this.escapeHtml(message || '未知错误')}</span>
                <button type="button" class="monitor-action-btn secondary" id="dePlatformRetryBtn">重试</button>
            </div>`;
        const retry = this.aiopsMonitorDashboard.querySelector('#dePlatformRetryBtn');
        if (retry) {
            retry.addEventListener('click', () => this.refreshPlatformPanel());
        }
    },

    renderConsoleMeta() {
        if (!this.aiopsMonitorMeta) return;
        const dashboard = this.dePlatformDashboard;
        const projectCount = (this.deProjects || []).length;
        const diagnosableCount = (this.deProjects || []).filter(p => p.diagnosable).length;
        this.aiopsMonitorMeta.innerHTML = `
            <span><strong>宿主机：</strong>${this.escapeHtml(dashboard?.hostname || '-')}</span>
            <span><strong>平台指标时间：</strong>${this.escapeHtml(dashboard?.generated_at || '暂无')}</span>
            <span><strong>平台告警：</strong>${dashboard?.alerts?.length ?? 0} 条</span>
            <span><strong>被监控项目：</strong>${diagnosableCount}/${projectCount} 个可诊断</span>`;
    },

    // ==================== 项目层 ====================

    scheduleProjectRefresh() {
        if (this.deProjectRefreshTimer) {
            clearTimeout(this.deProjectRefreshTimer);
        }
        this.deProjectRefreshTimer = setTimeout(() => this.refreshProjectPanel(), 200);
    },

    async refreshProjectPanel() {
        const project = this.getCurrentProject();
        if (!project || !project.diagnosable) return;

        const projectId = project.id;
        const seq = (this.deProjectRequestSeq = (this.deProjectRequestSeq || 0) + 1);

        try {
            const result = await this.fetchProjectSummary(projectId);
            // 慢响应不覆盖已经切走的项目
            if (seq !== this.deProjectRequestSeq || projectId !== this.currentProjectId) return;
            this.setPanelState('project', result.state);
            this.renderProjectPanel(result.summary);
        } catch (error) {
            if (seq !== this.deProjectRequestSeq || projectId !== this.currentProjectId) return;
            console.error('项目层数据加载失败', error);
            this.renderProjectPanelMessage(`
                <div class="de-error-hint">
                    <span>${this.escapeHtml(error.message || '项目数据加载失败')}</span>
                    <button type="button" class="monitor-action-btn secondary" id="deProjectRetryBtn">重试</button>
                </div>`);
            const retry = this.deProjectBody && this.deProjectBody.querySelector('#deProjectRetryBtn');
            if (retry) {
                retry.addEventListener('click', () => this.refreshProjectPanel());
            }
        } finally {
            this.updateDiagnosisButtonState();
        }
    },

    async fetchProjectSummary(projectId) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/diagnosis/projects/${encodeURIComponent(projectId)}/summary`);
            if (response.status === 404) {
                const notFound = new Error(`项目「${projectId}」未在诊断引擎中注册，请先登记项目档案`);
                notFound.projectNotFound = true;
                throw notFound;
            }
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            return { summary: data, state: data.placeholder ? 'placeholder' : 'live' };
        } catch (error) {
            if (error.projectNotFound) throw error;
            const sample = typeof DIAGNOSIS_SAMPLE_SUMMARIES !== 'undefined'
                ? DIAGNOSIS_SAMPLE_SUMMARIES[projectId]
                : null;
            if (!sample) throw error;
            console.warn('项目概览接口不可用，降级到样例数据', error);
            return { summary: sample, state: 'sample' };
        }
    },

    renderProjectPanelMessage(html) {
        if (!this.deProjectBody) return;
        this.deProjectBody.innerHTML = html;
    },

    renderProjectPanel(summary) {
        if (!this.deProjectBody || !summary) return;

        const metrics = summary.metrics || [];
        const alerts = summary.alerts || [];
        const change = summary.last_change || {};

        const metricsHtml = metrics.length
            ? `<div class="de-metric-grid">${metrics.map(metric => this.renderProjectMetric(metric)).join('')}</div>`
            : '<div class="de-empty-hint">该项目档案中尚未定义业务指标</div>';

        const alertsHtml = alerts.length
            ? `<div class="de-alert-list">${alerts.map(alert => this.renderProjectAlert(alert)).join('')}</div>`
            : '<div class="de-empty-hint ok">当前无活跃异常</div>';

        let changeHtml;
        if (change.available === false) {
            changeHtml = '<div class="de-change-box">变更信息不可用：该项目未接入发布系统</div>';
        } else if (!change.change_id && !change.summary) {
            changeHtml = '<div class="de-change-box">近期无变更记录</div>';
        } else {
            changeHtml = `
                <div class="de-change-box">
                    <div><strong>变更时间：</strong>${this.escapeHtml(change.at_display || '-')}</div>
                    <div><strong>变更编号：</strong>${this.escapeHtml(change.change_id || '-')}</div>
                    <div><strong>内容：</strong>${this.escapeHtml(change.summary || '-')}</div>
                </div>`;
        }

        const sampleHint = this.isSamplePanel('project')
            ? '<div class="de-empty-hint">当前项目层为本地样例数据，诊断入口已给出提示，结果不可作为真实判断依据。</div>'
            : '';

        this.deProjectBody.innerHTML = `
            ${sampleHint}
            <div class="de-subsection">
                <h4 class="de-subsection-title">业务指标</h4>
                ${metricsHtml}
            </div>
            <div class="de-subsection">
                <h4 class="de-subsection-title">活跃异常</h4>
                ${alertsHtml}
            </div>
            <div class="de-subsection">
                <h4 class="de-subsection-title">最近变更</h4>
                ${changeHtml}
            </div>`;
    },

    renderProjectMetric(metric) {
        const classes = ['de-metric-item'];
        let valueHtml;

        if (metric.available === false || metric.value === null || metric.value === undefined) {
            classes.push('unavailable');
            valueHtml = '<div class="de-metric-value na">指标不可用</div>';
        } else {
            const lower = metric.threshold_direction === 'lower';
            const breach = typeof metric.threshold === 'number' && typeof metric.value === 'number'
                && (lower ? metric.value < metric.threshold : metric.value > metric.threshold);
            if (breach) classes.push('breach');
            valueHtml = `<div class="de-metric-value">${this.escapeHtml(String(metric.value))}<span class="de-metric-unit">${this.escapeHtml(metric.unit || '')}</span></div>`;
        }

        const thresholdHtml = typeof metric.threshold === 'number'
            ? `<div class="de-metric-threshold">阈值：${metric.threshold_direction === 'lower' ? '不低于' : '不高于'} ${metric.threshold}${this.escapeHtml(metric.unit || '')}</div>`
            : '';

        return `
            <div class="${classes.join(' ')}">
                <div class="de-metric-name">${this.escapeHtml(metric.name || '-')}</div>
                ${valueHtml}
                <div class="de-metric-meaning">${this.escapeHtml(metric.meaning || '')}</div>
                ${thresholdHtml}
            </div>`;
    },

    renderProjectAlert(alert) {
        const severity = (alert.severity || 'warning').toLowerCase();
        const isWarning = severity !== 'critical';
        return `
            <div class="de-alert-item ${isWarning ? 'warning' : ''}">
                <span class="de-alert-name">${this.escapeHtml(alert.alert_name || '-')}</span>
                <span class="de-alert-sev">${this.escapeHtml(severity)}</span>
                <span>首次触发：${this.escapeHtml(alert.active_at_display || '-')}</span>
                <span>持续：${this.escapeHtml(alert.duration || '-')}</span>
            </div>`;
    },

    setDiagnosisStatus(html, variant = 'de-empty-hint') {
        if (!this.deDiagnosisStatus) return;
        if (!html) {
            this.deDiagnosisStatus.hidden = true;
            this.deDiagnosisStatus.innerHTML = '';
            return;
        }
        this.deDiagnosisStatus.hidden = false;
        this.deDiagnosisStatus.innerHTML = `<div class="${variant}">${html}</div>`;
    },

    updateDiagnosisButtonState() {
        if (!this.deRunDiagnosisBtn) return;
        const project = this.getCurrentProject();
        const running = !!this.deDiagnosing;

        this.deRunDiagnosisBtn.disabled = running || !project || !project.diagnosable;
        this.deRunDiagnosisBtn.textContent = running ? '诊断进行中...' : '一键诊断（项目级）';

        if (!project) {
            this.deRunDiagnosisBtn.title = '请先选择一个项目';
        } else if (!project.diagnosable) {
            this.deRunDiagnosisBtn.title = `该项目未接入诊断：${project.reason || '缺少项目档案'}`;
        } else if (this.isSamplePanel('project')) {
            this.deRunDiagnosisBtn.title = '当前项目层展示的是本地样例数据，诊断结果不可作为真实依据';
        } else {
            this.deRunDiagnosisBtn.title = `对「${project.name}」发起项目级诊断`;
        }
    },

    async runProjectDiagnosis() {
        const project = this.getCurrentProject();
        if (!project || !project.diagnosable || this.deDiagnosing) return;

        if (this.isSamplePanel('project')) {
            this.showNotification('当前项目层为样例数据，诊断仅用于界面演示', 'warning');
        }

        this.deDiagnosing = true;
        this.updateDiagnosisButtonState();
        this.setDiagnosisStatus(`正在对「${this.escapeHtml(project.name)}」执行项目级诊断，请稍候...`);

        try {
            const checkpointHeaders = await this.checkpointHeaders();
            const taskKey = `diagnosis_checkpoint_task_${project.id}`;
            let resumeTaskId = localStorage.getItem(taskKey);
            if (resumeTaskId) {
                const saved = await fetch(
                    `${this.apiBaseUrl}/checkpoint/tasks/${encodeURIComponent(resumeTaskId)}`,
                    { headers: checkpointHeaders }
                );
                if (!saved.ok || (await saved.json()).status === 'COMPLETED') {
                    resumeTaskId = null;
                }
            }
            const query = resumeTaskId ? `?task_id=${encodeURIComponent(resumeTaskId)}` : '';
            const response = await fetch(`${this.apiBaseUrl}/diagnosis/diagnoses${query}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', ...checkpointHeaders },
                body: JSON.stringify({ project_id: project.id, mode: 'PROJECT' })
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) {
                throw new Error(data.message || `诊断请求失败（HTTP ${response.status}）`);
            }
            if (data.task_id) {
                localStorage.setItem(taskKey, data.task_id);
            }

            const record = {
                reportId: data.report_id || `project-${project.id}-${Date.now()}`,
                mode: 'PROJECT',
                projectId: project.id,
                projectName: project.name,
                completedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
                report: data.report || '',
                traceId: data.trace_id || '',
                confidence: data.confidence || '',
                toolCalls: data.tool_calls || [],
                dataState: 'live'
            };
            this.saveReportRecord(project.id, record);
            this.deReportSlot = project.id;
            this.renderReportPanel(project.id);
            this.setDiagnosisStatus(null);
            this.showNotification('项目级诊断完成', 'info');
        } catch (error) {
            console.error('项目级诊断失败', error);
            this.setDiagnosisStatus(
                `项目级诊断未完成：${this.escapeHtml(error.message)}`,
                'de-error-hint'
            );
            this.showNotification('项目级诊断失败: ' + error.message, 'error');
        } finally {
            this.deDiagnosing = false;
            this.updateDiagnosisButtonState();
        }
    },

    // ==================== 报告层 ====================

    renderReportPanel(slot) {
        this.deReportSlot = slot || this.DE_PLATFORM_SLOT;
        const record = this.loadReportRecord(this.deReportSlot);
        const isPlatform = this.deReportSlot === this.DE_PLATFORM_SLOT;

        this.setPanelState('report', record?.dataState === 'sample' ? 'sample' : (record?.dataState === 'placeholder' ? 'placeholder' : 'live'));

        if (!record || !record.report) {
            if (this.deReportMeta) {
                this.deReportMeta.textContent = isPlatform ? '暂无平台级诊断报告' : '该项目暂无诊断报告';
            }
            if (this.deTraceBar) this.deTraceBar.innerHTML = '';
            if (this.aiopsMonitorReport) {
                this.aiopsMonitorReport.innerHTML = isPlatform
                    ? '<div class="de-empty-hint">还没有平台级诊断报告，点击顶部「平台级诊断」发起一次分析。</div>'
                    : '<div class="de-empty-hint">该项目还没有诊断报告，点击项目层的「一键诊断」发起一次分析。</div>';
            }
            if (this.deReportActions) this.deReportActions.innerHTML = '';
            return;
        }

        const modeText = record.mode === 'PROJECT' ? '项目级诊断' : '平台级诊断';
        const owner = record.mode === 'PROJECT'
            ? (record.projectName || record.projectId || '-')
            : '全平台共享资源';
        if (this.deReportMeta) {
            const confidence = record.confidence ? ` · 置信度 ${record.confidence}` : '';
            this.deReportMeta.textContent = `${modeText} · ${owner} · 生成于 ${record.completedAt || '-'}${confidence}`;
        }

        this.renderReportTrace(record);

        if (this.aiopsMonitorReport) {
            this.aiopsMonitorReport.innerHTML = this.renderMarkdown(record.report);
            this.highlightCodeBlocks(this.aiopsMonitorReport);
        }

        this.renderReportActions(record);
    },

    renderReportTrace(record) {
        if (!this.deTraceBar) return;

        const hasTrace = !!record.traceId;
        const toolCalls = record.toolCalls || [];

        if (!hasTrace && toolCalls.length === 0) {
            this.deTraceBar.innerHTML = '<span>追踪信息暂不可用：当前诊断链路尚未回传 trace 标识与工具调用记录</span>';
            return;
        }

        const traceHtml = hasTrace
            ? `<span>追踪标识：<code class="de-trace-id">${this.escapeHtml(record.traceId)}</code></span>
               <button type="button" class="de-trace-toggle" id="deCopyTraceBtn">复制</button>`
            : '<span>追踪标识暂不可用</span>';

        const toolsHtml = toolCalls.length
            ? `<button type="button" class="de-trace-toggle" id="deToggleToolsBtn">工具调用摘要（${toolCalls.length}）</button>
               <div class="de-tool-calls" id="deToolCalls" hidden>${toolCalls.map((call, index) =>
                    this.escapeHtml(`${index + 1}. ${call.name || call.tool || '-'} → ${call.summary || call.result || '-'}`)
               ).join('\n')}</div>`
            : '<span>工具调用摘要暂不可用</span>';

        this.deTraceBar.innerHTML = traceHtml + toolsHtml;

        const copyBtn = this.deTraceBar.querySelector('#deCopyTraceBtn');
        if (copyBtn) {
            copyBtn.addEventListener('click', () => {
                navigator.clipboard.writeText(record.traceId)
                    .then(() => this.showNotification('追踪标识已复制', 'info'))
                    .catch(() => this.showNotification('复制失败，请手动选择复制', 'warning'));
            });
        }
        const toggleBtn = this.deTraceBar.querySelector('#deToggleToolsBtn');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', () => {
                const box = this.deTraceBar.querySelector('#deToolCalls');
                if (box) box.hidden = !box.hidden;
            });
        }
    },

    renderReportActions(record) {
        if (!this.deReportActions) return;

        if (record.feedback) {
            this.deReportActions.innerHTML = `
                <span class="de-feedback-result">已${record.feedback.decision === 'accept' ? '采纳' : '驳回'}</span>
                <span>提交时间：${this.escapeHtml(record.feedback.at || '-')}</span>
                ${record.feedback.reason ? `<span>原因：${this.escapeHtml(record.feedback.reason)}</span>` : ''}`;
            return;
        }

        const sample = this.isSamplePanel('report') || record.dataState === 'sample';
        const disabled = sample ? 'disabled' : '';
        const hint = sample
            ? '<span>基于样例数据的报告不可提交采纳或驳回</span>'
            : '';

        this.deReportActions.innerHTML = `
            <span>这份报告是否可用？</span>
            <button type="button" class="monitor-action-btn primary" id="deAcceptBtn" ${disabled}>采纳</button>
            <input type="text" class="de-reject-reason" id="deRejectReason" placeholder="驳回原因（驳回时必填）" ${disabled}>
            <button type="button" class="monitor-action-btn secondary" id="deRejectBtn" ${disabled}>驳回</button>
            ${hint}`;

        const acceptBtn = this.deReportActions.querySelector('#deAcceptBtn');
        const rejectBtn = this.deReportActions.querySelector('#deRejectBtn');
        if (acceptBtn) acceptBtn.addEventListener('click', () => this.submitReportFeedback('accept'));
        if (rejectBtn) rejectBtn.addEventListener('click', () => this.submitReportFeedback('reject'));
    },

    async submitReportFeedback(decision) {
        const slot = this.deReportSlot || this.DE_PLATFORM_SLOT;
        const record = this.loadReportRecord(slot);
        if (!record) return;

        const reasonInput = this.deReportActions && this.deReportActions.querySelector('#deRejectReason');
        const reason = reasonInput ? reasonInput.value.trim() : '';
        if (decision === 'reject' && !reason) {
            this.showNotification('驳回需要填写原因', 'warning');
            if (reasonInput) reasonInput.focus();
            return;
        }

        const reportId = record.reportId || slot;
        try {
            const response = await fetch(`${this.apiBaseUrl}/diagnosis/reports/${encodeURIComponent(reportId)}/feedback`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ decision, reason })
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) {
                throw new Error(data.message || `HTTP ${response.status}`);
            }

            record.feedback = {
                decision,
                reason,
                at: data.feedback_at || new Date().toLocaleString('zh-CN', { hour12: false }),
                placeholder: !!data.placeholder
            };
            this.saveReportRecord(slot, record);
            this.renderReportPanel(slot);
            this.showNotification(decision === 'accept' ? '已记录采纳' : '已记录驳回', 'info');
        } catch (error) {
            // 保留已填写的原因供重试，不重绘操作条
            console.error('提交反馈失败', error);
            this.showNotification('提交反馈失败: ' + error.message, 'error');
        }
    },

    // ==================== 平台级 AI Ops 分析（既有链路） ====================

    initAiOpsMonitorPage() {
        const record = this.loadLastAiOpsResult();
        if (record) {
            this.renderAiOpsMonitorPage(record);
        } else {
            this.renderReportPanel(this.DE_PLATFORM_SLOT);
        }
        this.loadProjects().catch(error => console.warn('初始化项目列表失败', error));
    },

    async persistAndShowAiOpsResult(report) {
        // 空报告或误把 SSE 错误 JSON 当正文时，绝不能弹"已更新"
        if (!report || !String(report).trim()) {
            this.showNotification('平台级诊断未生成有效报告', 'warning');
            return;
        }
        const text = String(report).trim();
        if (text.startsWith('{') && (text.includes('"type":"error"') || text.includes('"Arrearage"'))) {
            this.showNotification('平台级诊断失败，未更新报告', 'error');
            return;
        }

        const buildRecord = (dashboard) => ({
            reportId: `platform-${Date.now()}`,
            mode: 'PLATFORM',
            projectId: null,
            projectName: '全平台共享资源',
            completedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
            report: text,
            dashboard,
            dataState: 'live'
        });

        try {
            const dashboard = await this.fetchAiOpsDashboard(60);
            const record = buildRecord(dashboard);
            this.saveLastAiOpsResult(record);
            // 必须先切到控制台（容器可见），再画图表和报告，否则可视化不会更新
            this.switchMainView('aiops-monitor');
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            this.renderAiOpsMonitorPage(record);
            this.resizeMonitorCharts();
            this.showNotification('平台级诊断完成，已更新控制台', 'info');
        } catch (error) {
            console.error('保存平台级诊断结果失败:', error);
            const record = buildRecord(null);
            this.saveLastAiOpsResult(record);
            this.switchMainView('aiops-monitor');
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            this.renderAiOpsMonitorPage(record);
            this.showNotification('报告已保存，但平台层数据加载失败', 'warning');
        }
    },

    async refreshAiOpsMonitorData() {
        try {
            this.showNotification('正在刷新控制台...', 'info');
            await this.refreshConsole();
            this.showNotification('控制台已刷新', 'info');
        } catch (error) {
            this.showNotification('刷新失败: ' + error.message, 'error');
        }
    },

    /** 渲染一份已保存的平台级记录（平台层快照 + 平台级报告） */
    renderAiOpsMonitorPage(record) {
        if (this.aiopsMonitorContent) {
            this.aiopsMonitorContent.style.display = 'block';
        }
        if (record && record.dashboard) {
            this.renderPlatformPanel(record.dashboard);
        } else {
            this.renderConsoleMeta();
        }
        this.deReportSlot = this.DE_PLATFORM_SLOT;
        this.renderReportPanel(this.DE_PLATFORM_SLOT);
    },

    destroyMonitorCharts() {
        if (!this.aiopsMonitorCharts) {
            this.aiopsMonitorCharts = [];
            return;
        }
        this.aiopsMonitorCharts.forEach(chart => {
            try { chart.destroy(); } catch (e) { /* ignore */ }
        });
        this.aiopsMonitorCharts = [];
    },

    renderChartsInContainer(container, data, idPrefix) {
        this.destroyMonitorCharts();
        const thresholds = data.thresholds || { cpu: 80, memory: 85, disk: 90 };
        const chartConfigs = [
            { id: `${idPrefix}-cpu-chart`, key: 'cpu', label: 'CPU', color: '#1a73e8', threshold: thresholds.cpu },
            { id: `${idPrefix}-memory-chart`, key: 'memory', label: '内存', color: '#9334e6', threshold: thresholds.memory },
            { id: `${idPrefix}-disk-chart`, key: 'disk', label: '磁盘', color: '#188038', threshold: thresholds.disk }
        ];

        chartConfigs.forEach(cfg => {
            const canvas = container.querySelector('#' + cfg.id);
            if (!canvas) return;
            const points = data.series ? data.series[cfg.key] : [];
            const chart = this.buildMetricChart(canvas, cfg.label, points, cfg.threshold, cfg.color);
            if (chart) {
                this.aiopsMonitorCharts.push(chart);
            }
        });
    },

    renderDashboardIntoContainer(container, data, idPrefix) {
        if (!container || !data) return;

        container.innerHTML = `
            <div class="aiops-dashboard-header">
                <h3>📊 平台共享资源指标与告警</h3>
                <div class="aiops-dashboard-meta">
                    <span>主机: ${this.escapeHtml(data.hostname || '-')}</span>
                    <span>数据时间: ${this.escapeHtml(data.generated_at || '-')}</span>
                    <span>趋势范围: 近 ${data.range_minutes || 60} 分钟</span>
                </div>
            </div>

            <div class="aiops-alert-section">
                <h4 class="aiops-section-title">活跃告警（含触发时间）</h4>
                <div class="aiops-alert-cards">${this.renderAlertCards(data.alerts)}</div>
            </div>

            <div class="aiops-gauge-grid">${this.renderGaugeCards(data.current_metrics, data.thresholds)}</div>

            <h4 class="aiops-section-title">指标趋势</h4>
            <div class="aiops-charts-grid">
                <div class="aiops-chart-card">
                    <div class="aiops-chart-title">CPU 使用率趋势</div>
                    <div class="aiops-chart-wrapper"><canvas id="${idPrefix}-cpu-chart"></canvas></div>
                </div>
                <div class="aiops-chart-card">
                    <div class="aiops-chart-title">内存使用率趋势</div>
                    <div class="aiops-chart-wrapper"><canvas id="${idPrefix}-memory-chart"></canvas></div>
                </div>
                <div class="aiops-chart-card">
                    <div class="aiops-chart-title">磁盘使用率趋势</div>
                    <div class="aiops-chart-wrapper"><canvas id="${idPrefix}-disk-chart"></canvas></div>
                </div>
            </div>`;

        this.renderChartsInContainer(container, data, idPrefix);
        this.resizeMonitorCharts();
    },

    /** 只刷新平台层，保留已有报告内容 */
    async loadDashboardOnlyToMonitor() {
        try {
            const dashboard = await this.fetchAiOpsDashboard(60);
            const existing = this.loadLastAiOpsResult() || {};
            const record = Object.assign({}, existing, {
                mode: 'PLATFORM',
                dashboard,
                refreshedAt: new Date().toLocaleString('zh-CN', { hour12: false })
            });
            this.saveLastAiOpsResult(record);
            if (this.currentMainView !== 'aiops-monitor') {
                this.switchMainView('aiops-monitor');
            }
            this.renderPlatformPanel(dashboard);
        } catch (error) {
            this.renderPlatformError(error.message);
            this.showNotification('加载平台层数据失败: ' + error.message, 'error');
        }
    }
});
