let projects = [];
let currentProjectId = null;
const sensorHistory = new Map();
const selectedSeries = new Map();
const chartColors = ['#6ee7ff', '#8b7cff', '#42e6a4', '#ffb86b', '#ff6b9d', '#d8f45b', '#b794f4', '#60a5fa'];
const historyWindowMs = 60_000;

const getModules = project => project?.widgets || project?.moduleList || [];
const getDevices = module => module?.device_list || module?.deviceList || [];
const getModuleType = module => String(module?.type || module?.module_type || module?.moduleType || 'Module');
const getModuleTitle = module => module?.title || module?.module_title || module?.moduleTitle || 'Untitled module';
const getModuleDescription = module => module?.module_description || module?.description || '';

async function loadProjects() {
    try {
        const response = await fetch('/projects.json', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        projects = (data.projects || []).sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));

        if (!projects.length) {
            currentProjectId = null;
            renderSidebar();
            renderEmptyState();
            return;
        }
        if (!currentProjectId || !projects.some(project => project.id === currentProjectId)) {
            currentProjectId = projects[0].id;
        }
        renderSidebar();
        selectProject(currentProjectId, false);
    } catch (error) {
        console.error('Project load failed:', error);
        setConnectionState(false);
    }
}

function renderSidebar() {
    const container = document.getElementById('project-list');
    if (!container) return;
    container.replaceChildren();
    projects.forEach(project => {
        const button = document.createElement('button');
        button.className = `project-nav-item ${project.id === currentProjectId ? 'project-nav-item--active' : ''}`;
        button.innerHTML = `<span class="project-nav-item__mark"></span><span></span>`;
        button.lastElementChild.textContent = project.name || 'Untitled project';
        button.onclick = () => selectProject(project.id);
        container.appendChild(button);
    });
}

function selectProject(id, refreshSidebar = true) {
    currentProjectId = id;
    const project = projects.find(item => item.id === id);
    if (!project) return;
    if (refreshSidebar) renderSidebar();
    document.getElementById('project-heading').textContent = project.name || 'Dashboard';
    document.getElementById('project-subtitle').textContent = project.description || 'Live device overview';
    updateModuleDisplay(project);
}

function updateModuleDisplay(project) {
    const grid = document.getElementById('module-grid');
    if (!grid) return;
    grid.replaceChildren();
    const modules = getModules(project).slice().sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
    document.getElementById('module-count').textContent = `${modules.length} module${modules.length === 1 ? '' : 's'}`;
    if (!modules.length) {
        grid.innerHTML = '<div class="dashboard-empty">No modules have been configured for this project yet.</div>';
        return;
    }
    modules.forEach(module => grid.appendChild(createModuleCard(module, project.id)));
}

function createModuleCard(module, projectId) {
    const type = getModuleType(module);
    const isSwitch = type.toLowerCase() === 'switch';
    const card = document.createElement('article');
    card.className = `dashboard-card ${isSwitch ? 'dashboard-card--switch' : ''}`;

    const header = document.createElement('header');
    header.className = 'dashboard-card__header';
    const heading = document.createElement('div');
    const eyebrow = document.createElement('span');
    eyebrow.className = 'dashboard-card__eyebrow';
    eyebrow.textContent = isSwitch ? 'ESP8266 control' : type;
    const title = document.createElement('h3');
    title.className = 'dashboard-card__title';
    title.textContent = getModuleTitle(module);
    heading.append(eyebrow, title);
    header.appendChild(heading);
    card.appendChild(header);

    if (isSwitch) {
        card.appendChild(createSwitchControl(module, projectId));
        return card;
    }

    const devices = getDevices(module).filter(device => device.type !== 'SWITCH');
    if (!devices.length) {
        const empty = document.createElement('p');
        empty.className = 'dashboard-card__empty';
        empty.textContent = getModuleDescription(module) || 'No sensors assigned.';
        card.appendChild(empty);
        return card;
    }

    const list = document.createElement('div');
    list.className = 'dashboard-device-list';
    devices.forEach(device => list.appendChild(createDeviceRow(device)));
    card.appendChild(list);
    return card;
}

function createSwitchControl(module, projectId) {
    const enabled = Number(module.value) === 1;
    const wrapper = document.createElement('div');
    wrapper.className = 'switch-control';
    const copy = document.createElement('div');
    copy.innerHTML = `<span class="switch-control__status ${enabled ? 'switch-control__status--on' : ''}">${enabled ? 'ON' : 'OFF'}</span><small></small>`;
    const endpoint = getDevices(module).find(device => device.ipAddress)?.ipAddress;
    copy.querySelector('small').textContent = endpoint ? `POST → ${endpoint}` : 'Configure an ESP8266 endpoint';
    const control = createMasterSwitch(projectId, module.id, enabled);
    wrapper.append(copy, control);
    return wrapper;
}

function createDeviceRow(device) {
    const row = document.createElement('div');
    row.className = 'dashboard-device';
    row.dataset.deviceId = device.id;

    const header = document.createElement('div');
    header.className = 'dashboard-device__header';
    const name = document.createElement('div');
    name.className = 'dashboard-device__name';
    name.innerHTML = getDashboardIconForType(device.type);
    const label = document.createElement('span');
    label.textContent = device.name || device.type || 'Sensor';
    name.appendChild(label);
    const graphButton = document.createElement('button');
    graphButton.className = `graph-button ${hasDeviceSeries(device.id) ? 'graph-button--active' : ''}`;
    graphButton.type = 'button';
    graphButton.title = 'Add or remove from timeline';
    graphButton.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 17l5-5 4 3 7-8"/><path d="M17 7h2v2"/></svg><span>Graph</span>';
    graphButton.onclick = () => toggleDeviceChart(device);
    header.append(name, graphButton);

    const values = document.createElement('div');
    values.className = 'sensor-values';
    renderSensorValues(values, device, device.values || []);
    row.append(header, values);
    return row;
}

function sensorLabels(type, count) {
    const labels = {
        ACCELEROMETER: ['X', 'Y', 'Z'], GYROSCOPE: ['X', 'Y', 'Z'], MAGNETIC_FIELD: ['X', 'Y', 'Z'],
        GRAVITY: ['X', 'Y', 'Z'], LINEAR_ACCELERATION: ['X', 'Y', 'Z'], ORIENTATION: ['Azimuth', 'Pitch', 'Roll'],
        ROTATION_VECTOR: ['X', 'Y', 'Z', 'Cos θ', 'Heading'], GAME_ROTATION_VECTOR: ['X', 'Y', 'Z', 'Cos θ'],
        GEOMAGNETIC_ROTATION_VECTOR: ['X', 'Y', 'Z', 'Cos θ', 'Heading'],
        ACCELEROMETER_UNCALIBRATED: ['X', 'Y', 'Z', 'Bias X', 'Bias Y', 'Bias Z'],
        GYROSCOPE_UNCALIBRATED: ['X', 'Y', 'Z', 'Drift X', 'Drift Y', 'Drift Z'],
        MAGNETIC_FIELD_UNCALIBRATED: ['X', 'Y', 'Z', 'Bias X', 'Bias Y', 'Bias Z'],
        POSE_6DOF: ['X', 'Y', 'Z', 'qx', 'qy', 'qz']
    };
    return labels[type] || Array.from({ length: count }, (_, index) => `Value ${index + 1}`);
}

function renderSensorValues(container, device, values) {
    const numeric = Array.isArray(values) ? values : [];
    if (numeric.length <= 1) {
        container.className = 'sensor-values dashboard-reading';
        const value = numeric.length ? Number(numeric[0]).toFixed(1) : '--';
        container.innerHTML = `<div class="dashboard-reading__value">${value}<span>${device.unit || ''}</span></div>`;
        return;
    }
    const labels = sensorLabels(device.type, numeric.length);
    container.className = 'sensor-values dashboard-vector';
    container.innerHTML = numeric.map((value, index) => `<div class="dashboard-vector__item"><div>${labels[index] || `Value ${index + 1}`}</div><strong>${Number(value).toFixed(2)}</strong></div>`).join('');
}

async function loadSensorValues() {
    try {
        const response = await fetch('/sensor-values.json', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        const now = Date.now();
        Object.entries(payload.values || {}).forEach(([deviceId, values]) => {
            const device = findDevice(deviceId);
            if (!device) return;
            device.values = values;
            recordHistory(deviceId, now, values);
            const container = document.querySelector(`[data-device-id="${deviceId}"] .sensor-values`);
            if (container) renderSensorValues(container, device, values);
        });
        setConnectionState(true);
        drawTimeline();
    } catch (error) {
        setConnectionState(false);
    }
}

function recordHistory(deviceId, time, values) {
    const history = sensorHistory.get(deviceId) || [];
    history.push({ time, values: values.map(Number) });
    const cutoff = time - historyWindowMs;
    while (history.length && history[0].time < cutoff) history.shift();
    sensorHistory.set(deviceId, history);
}

function findDevice(deviceId) {
    for (const project of projects) {
        for (const module of getModules(project)) {
            const device = getDevices(module).find(item => item.id === deviceId);
            if (device) return device;
        }
    }
    return null;
}

function hasDeviceSeries(deviceId) {
    return [...selectedSeries.values()].some(series => series.deviceId === deviceId);
}

function toggleDeviceChart(device) {
    if (hasDeviceSeries(device.id)) {
        [...selectedSeries.entries()].filter(([, series]) => series.deviceId === device.id).forEach(([key]) => selectedSeries.delete(key));
    } else {
        const history = sensorHistory.get(device.id) || [];
        const latest = history.length ? history[history.length - 1].values : (device.values || [0]);
        const labels = sensorLabels(device.type, Math.max(1, latest.length));
        labels.slice(0, Math.max(1, latest.length)).forEach((axis, index) => {
            const key = `${device.id}:${index}`;
            selectedSeries.set(key, {
                deviceId: device.id,
                valueIndex: index,
                label: `${device.name} · ${axis}`,
                unit: device.unit || '',
                color: chartColors[selectedSeries.size % chartColors.length]
            });
        });
    }
    const project = projects.find(item => item.id === currentProjectId);
    if (project) updateModuleDisplay(project);
    updateChartLegend();
    drawTimeline();
}

function clearChart() {
    selectedSeries.clear();
    const project = projects.find(item => item.id === currentProjectId);
    if (project) updateModuleDisplay(project);
    updateChartLegend();
    drawTimeline();
}

function updateChartLegend() {
    const legend = document.getElementById('chart-legend');
    const empty = document.getElementById('chart-empty');
    legend.replaceChildren();
    empty.hidden = selectedSeries.size > 0;
    selectedSeries.forEach(series => {
        const item = document.createElement('span');
        item.className = 'chart-legend__item';
        item.innerHTML = `<i style="background:${series.color}"></i><span></span>`;
        item.lastElementChild.textContent = series.label;
        legend.appendChild(item);
    });
}

function drawTimeline() {
    const canvas = document.getElementById('timeline-chart');
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(rect.width * ratio) || canvas.height !== Math.round(rect.height * ratio)) {
        canvas.width = Math.round(rect.width * ratio);
        canvas.height = Math.round(rect.height * ratio);
    }
    const context = canvas.getContext('2d');
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, rect.width, rect.height);
    const padding = { left: 50, right: 18, top: 18, bottom: 30 };
    const width = rect.width - padding.left - padding.right;
    const height = rect.height - padding.top - padding.bottom;
    if (width <= 0 || height <= 0) return;

    context.strokeStyle = 'rgba(148, 163, 184, .12)';
    context.fillStyle = '#718096';
    context.font = '11px system-ui';
    for (let index = 0; index <= 4; index++) {
        const y = padding.top + height * index / 4;
        context.beginPath(); context.moveTo(padding.left, y); context.lineTo(padding.left + width, y); context.stroke();
    }
    if (!selectedSeries.size) return;

    const now = Date.now();
    const start = now - historyWindowMs;
    const allValues = [];
    selectedSeries.forEach(series => {
        (sensorHistory.get(series.deviceId) || []).forEach(point => {
            const value = point.values[series.valueIndex];
            if (point.time >= start && Number.isFinite(value)) allValues.push(value);
        });
    });
    if (!allValues.length) return;
    let min = Math.min(...allValues), max = Math.max(...allValues);
    if (min === max) { min -= 1; max += 1; }
    const margin = (max - min) * .08;
    min -= margin; max += margin;

    context.fillText(max.toFixed(1), 5, padding.top + 4);
    context.fillText(min.toFixed(1), 5, padding.top + height);
    context.fillText('−60s', padding.left, rect.height - 7);
    context.fillText('now', padding.left + width - 22, rect.height - 7);

    selectedSeries.forEach(series => {
        const points = (sensorHistory.get(series.deviceId) || []).filter(point => point.time >= start && Number.isFinite(point.values[series.valueIndex]));
        if (!points.length) return;
        context.beginPath();
        context.strokeStyle = series.color;
        context.lineWidth = 2;
        context.lineJoin = 'round';
        points.forEach((point, index) => {
            const x = padding.left + ((point.time - start) / historyWindowMs) * width;
            const y = padding.top + (1 - (point.values[series.valueIndex] - min) / (max - min)) * height;
            if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
        });
        context.stroke();
    });
}

function getDashboardIconForType(type) {
    const paths = {
        LIGHT_SENSOR: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4m11.4-11.4 1.4-1.4"/>',
        HEART_RATE: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.9-8.6a5.5 5.5 0 0 0-.1-7.8Z"/>',
        PRESSURE: '<path d="M4 14a8 8 0 1 1 16 0"/><path d="m12 14 3-4"/><path d="M7 18h10"/>',
        STEP_COUNTER: '<path d="M7 4c2 0 3 2 3 4v5H6c-2 0-3-1-3-3V7c0-2 2-3 4-3Zm10 7c2 0 4 2 4 4v2c0 2-2 3-4 3h-4v-5c0-2 2-4 4-4Z"/>'
    };
    const path = paths[type] || '<path d="M12 3v18M3 12h18"/><circle cx="12" cy="12" r="8"/>';
    return `<svg class="dashboard-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;
}

function createMasterSwitch(projectId, moduleId, enabled) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `dashboard-switch ${enabled ? 'dashboard-switch--on' : ''}`;
    button.setAttribute('role', 'switch');
    button.setAttribute('aria-checked', String(enabled));
    button.innerHTML = '<span class="dashboard-switch__knob"></span>';
    button.onclick = () => toggleSwitch(projectId, moduleId, !enabled, button);
    return button;
}

async function toggleSwitch(projectId, moduleId, enabled, button) {
    const project = projects.find(item => item.id === projectId);
    const module = getModules(project).find(item => item.id === moduleId);
    if (!module || button.disabled) return;
    const previous = module.value;
    module.value = enabled ? 1 : 0;
    button.disabled = true;
    button.classList.toggle('dashboard-switch--on', enabled);
    try {
        const response = await fetch('/toggleSwitch', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectId, moduleId, value: enabled })
        });
        if (!response.ok) throw new Error(await response.text());
        showToast(enabled ? 'Switch turned on' : 'Switch turned off', 'success');
    } catch (error) {
        module.value = previous;
        showToast(`Switch failed: ${error.message}`, 'error');
    } finally {
        updateModuleDisplay(project);
    }
}

function showToast(message, type = 'error') {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = `dashboard-toast dashboard-toast--${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(() => toast.classList.add('dashboard-toast--out'), 3200);
    setTimeout(() => toast.remove(), 3550);
}

function setConnectionState(online) {
    const indicator = document.getElementById('live-state');
    if (!indicator) return;
    indicator.classList.toggle('live-state--offline', !online);
    indicator.querySelector('span:last-child').textContent = online ? 'Live' : 'Reconnecting';
}

function renderEmptyState() {
    document.getElementById('module-grid').innerHTML = '<div class="dashboard-empty">Waiting for projects from the host.</div>';
    document.getElementById('module-count').textContent = '0 modules';
}

document.getElementById('clear-chart').addEventListener('click', clearChart);
window.addEventListener('resize', drawTimeline);
if ('ResizeObserver' in window) new ResizeObserver(drawTimeline).observe(document.getElementById('timeline-chart'));
updateChartLegend();
loadProjects();
loadSensorValues();
setInterval(loadProjects, 5000);
setInterval(loadSensorValues, 250);
