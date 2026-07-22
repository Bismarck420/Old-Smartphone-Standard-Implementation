let projects = [];
let currentProjectId = null;
let managerStatusKnown = false;
let managersById = new Map();
let managerInventorySignature = 'unknown';
const sensorHistory = new Map();
const selectedSeries = new Map();
const chartColors = ['#6ee7ff', '#8b7cff', '#42e6a4', '#ffb86b', '#ff6b9d', '#d8f45b', '#b794f4', '#60a5fa'];
const historyWindowMs = 60_000;

const getModules = project => project?.widgets || project?.moduleList || [];
const getDevices = module => module?.device_list || module?.deviceList || [];
const getModuleType = module => String(module?.type || module?.module_type || module?.moduleType || 'Module');
const getModuleTitle = module => module?.title || module?.module_title || module?.moduleTitle || 'Untitled module';
const getManagerId = device => device?.managerID || device?.manager_id || '';
const getManagerPeripheralId = device => {
    const explicit = device?.managerPeripheralId || device?.manager_peripheral_id || '';
    if (explicit) return explicit;
    return String(device?.description || '').match(/\(([^()]*)\)\s*$/)?.[1] || '';
};
const isAdvertisedSwitch = peripheral => {
    const kind = String(peripheral?.kind || '').toLowerCase();
    const type = String(peripheral?.type || '').toLowerCase();
    return kind === 'switch' || type.includes('switch') || type.includes('relay');
};
const normalizeDeviceType = value => {
    const type = String(value || '').toUpperCase().replaceAll(' ', '_');
    if (type.includes('SWITCH') || type.includes('RELAY')) return 'SWITCH';
    if (type.includes('HUMIDITY')) return 'RELATIVE_HUMIDITY';
    if (type.includes('TEMPERATURE')) return 'AMBIENT_TEMPERATURE';
    if (type.includes('PRESSURE') || type.includes('BAROMETER')) return 'PRESSURE';
    if (type.includes('LIGHT')) return 'LIGHT_SENSOR';
    if (type.includes('ACCEL')) return 'ACCELEROMETER';
    if (type.includes('GYRO')) return 'GYROSCOPE';
    if (type.includes('MAGNETIC')) return 'MAGNETIC_FIELD';
    if (type.includes('PROXIMITY')) return 'PROXIMITY';
    return type || 'UNKNOWN';
};
const managerStatusLabels = {
    online: 'Online', offline: 'Manager offline', removed: 'Channel removed',
    changed: 'Type changed', unknown: 'Unknown', unmanaged: 'Available'
};

function managerStatus(managerId, peripheralId, expectedType) {
    if (!managerId) return 'unmanaged';
    if (!managerStatusKnown) return 'unknown';
    const manager = managersById.get(managerId);
    if (!manager) return 'offline';
    if (!peripheralId) return 'online';
    const peripheral = (manager.devices || []).find(device => device.id === peripheralId);
    if (!peripheral) return 'removed';
    const expectsSwitch = normalizeDeviceType(expectedType) === 'SWITCH';
    if (isAdvertisedSwitch(peripheral) !== expectsSwitch) return 'changed';
    const currentType = normalizeDeviceType(peripheral.type);
    const configuredType = normalizeDeviceType(expectedType);
    return !expectsSwitch && currentType !== 'UNKNOWN' && configuredType !== 'UNKNOWN' && currentType !== configuredType
        ? 'changed'
        : 'online';
}

function managedDeviceStatus(device, expectsSwitch = device?.type === 'SWITCH') {
    return managerStatus(
        getManagerId(device),
        getManagerPeripheralId(device),
        expectsSwitch ? 'SWITCH' : device?.type
    );
}

function isEndpointAvailable(state) {
    return state === 'online' || state === 'unmanaged';
}

function inventorySignature(managers, known) {
    if (!known) return 'unknown';
    return JSON.stringify([...managers.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([id, manager]) => [
        id,
        manager.title,
        manager.ipAddress,
        manager.httpPort,
        (manager.devices || []).map(device => [device.id, device.kind, device.type]).sort()
    ]));
}

async function loadManagerStatuses() {
    let nextManagers = new Map();
    let nextKnown = false;
    try {
        const response = await fetch('/managers', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        nextManagers = new Map((payload.managers || []).map(manager => [manager.managerId, manager]));
        nextKnown = true;
    } catch (error) {}

    const nextSignature = inventorySignature(nextManagers, nextKnown);
    const changed = nextSignature !== managerInventorySignature;
    managersById = nextManagers;
    managerStatusKnown = nextKnown;
    managerInventorySignature = nextSignature;
    if (changed) {
        const project = projects.find(item => item.id === currentProjectId);
        if (project) updateModuleDisplay(project);
    } else {
        refreshManagerStatusIndicators();
    }
}

function applyManagerStatus(element) {
    const state = managerStatus(
        element.dataset.managerId || '',
        element.dataset.peripheralId || '',
        element.dataset.expectedType || 'UNKNOWN'
    );
    const label = managerStatusLabels[state];
    element.className = `dashboard-card__manager-state dashboard-card__manager-state--${state}`;
    element.textContent = label;
    element.title = `${element.dataset.managerName}: ${label}`;
}

function refreshManagerStatusIndicators() {
    document.querySelectorAll('.dashboard-card__manager-state').forEach(applyManagerStatus);
}

async function loadProjects() {
    try {
        const response = await fetch('/projects.json', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        projects = (data.projects || []).sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));

        if (!projects.length) {
            currentProjectId = null;
            resetTimeline();
            renderSidebar();
            renderEmptyState();
            return;
        }
        const nextProjectId = currentProjectId && projects.some(project => project.id === currentProjectId)
            ? currentProjectId
            : projects[0].id;
        selectProject(nextProjectId, false);
        renderSidebar();
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
        button.textContent = project.name || 'Untitled project';
        button.onclick = () => selectProject(project.id);
        container.appendChild(button);
    });
}

function selectProject(id, refreshSidebar = true) {
    const project = projects.find(item => item.id === id);
    if (!project) return;
    const projectChanged = currentProjectId !== id;
    currentProjectId = id;
    scopeTimelineToProject(project, projectChanged);
    if (refreshSidebar) renderSidebar();
    document.getElementById('project-heading').textContent = project.name || 'Dashboard';
    updateModuleDisplay(project);
}

function projectDeviceIds(project) {
    return new Set(getModules(project).flatMap(module => getDevices(module).map(device => device.id)));
}

function scopeTimelineToProject(project, reset = false) {
    const validDeviceIds = projectDeviceIds(project);
    if (reset) {
        selectedSeries.clear();
        sensorHistory.clear();
    } else {
        [...selectedSeries.entries()]
            .filter(([, series]) => !validDeviceIds.has(series.deviceId))
            .forEach(([key]) => selectedSeries.delete(key));
        [...sensorHistory.keys()]
            .filter(deviceId => !validDeviceIds.has(deviceId))
            .forEach(deviceId => sensorHistory.delete(deviceId));
    }
    updateChartLegend();
    drawTimeline();
}

function resetTimeline() {
    selectedSeries.clear();
    sensorHistory.clear();
    updateChartLegend();
    drawTimeline();
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
    const isSwitch = getModuleType(module).toLowerCase() === 'switch';
    const card = document.createElement('article');
    card.className = `dashboard-card ${isSwitch ? 'dashboard-card--switch' : ''}`;

    const header = document.createElement('header');
    header.className = 'dashboard-card__header';
    const title = document.createElement('h3');
    title.className = 'dashboard-card__title';
    title.textContent = getModuleTitle(module);
    header.appendChild(title);
    const managerDevice = getDevices(module).find(device => getManagerId(device));
    const endpointState = managerDevice ? managedDeviceStatus(managerDevice, isSwitch) : 'unmanaged';
    if (managerDevice) {
        const managerMeta = document.createElement('div');
        managerMeta.className = 'dashboard-card__manager-meta';
        const managerId = getManagerId(managerDevice);
        const managerName = managerDevice.sourceDeviceName || managerDevice.source_device_name || managerId;
        const managerState = document.createElement('span');
        managerState.dataset.managerId = managerId;
        managerState.dataset.managerName = managerName;
        managerState.dataset.peripheralId = getManagerPeripheralId(managerDevice);
        managerState.dataset.expectedType = isSwitch ? 'SWITCH' : managerDevice.type;
        applyManagerStatus(managerState);
        const managerBadge = document.createElement('span');
        managerBadge.className = 'dashboard-card__manager-icon';
        managerBadge.title = `Manager · ${managerName}`;
        managerBadge.setAttribute('aria-label', managerBadge.title);
        managerBadge.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 12.6a10 10 0 0 1 14 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19" r="1" fill="currentColor" stroke="none"/></svg>';
        managerMeta.append(managerState, managerBadge);
        header.appendChild(managerMeta);
    }
    card.appendChild(header);

    if (isSwitch) {
        card.appendChild(createSwitchControl(module, projectId, endpointState));
        return card;
    }

    const devices = getDevices(module).filter(device => device.type !== 'SWITCH');
    if (!devices.length) {
        const empty = document.createElement('p');
        empty.className = 'dashboard-card__empty';
        empty.textContent = 'No sensors assigned';
        card.appendChild(empty);
        return card;
    }

    const list = document.createElement('div');
    list.className = 'dashboard-device-list';
    devices.forEach(device => list.appendChild(createDeviceRow(device)));
    card.appendChild(list);
    return card;
}

function createSwitchControl(module, projectId, endpointState) {
    const enabled = Number(module.value) === 1;
    const available = isEndpointAvailable(endpointState);
    const wrapper = document.createElement('div');
    wrapper.className = 'switch-control';
    const copy = document.createElement('div');
    copy.innerHTML = available
        ? `<span class="switch-control__status ${enabled ? 'switch-control__status--on' : ''}">${enabled ? 'On' : 'Off'}</span>`
        : `<span class="switch-control__status switch-control__status--unavailable">${managerStatusLabels[endpointState]}</span>`;
    const control = createMasterSwitch(projectId, module.id, enabled, !available);
    if (!available) control.title = managerStatusLabels[endpointState];
    wrapper.append(copy, control);
    return wrapper;
}

function createDeviceRow(device) {
    const endpointState = managedDeviceStatus(device, false);
    const available = isEndpointAvailable(endpointState);
    const row = document.createElement('div');
    row.className = `dashboard-device ${available ? '' : 'dashboard-device--unavailable'}`;
    row.dataset.deviceId = device.id;

    const header = document.createElement('div');
    header.className = 'dashboard-device__header';
    const name = document.createElement('div');
    name.className = 'dashboard-device__name';
    const sensorName = device.name || device.type || 'Sensor';
    const sourceName = device.sourceDeviceName || device.source_device_name || '';
    name.textContent = sourceName ? `${sensorName} · ${sourceName}` : sensorName;
    name.title = name.textContent;
    const graphButton = document.createElement('button');
    graphButton.className = `graph-button ${hasDeviceSeries(device.id) ? 'graph-button--active' : ''}`;
    graphButton.type = 'button';
    graphButton.title = 'Add or remove from timeline';
    graphButton.disabled = !available;
    graphButton.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 17l5-5 4 3 7-8"/><path d="M17 7h2v2"/></svg><span>Graph</span>';
    graphButton.onclick = () => toggleDeviceChart(device);
    header.append(name, graphButton);

    const values = document.createElement('div');
    values.className = 'sensor-values';
    if (available) {
        renderSensorValues(values, device, device.values || []);
    } else {
        renderUnavailableSensor(values, endpointState);
    }
    row.append(header, values);
    return row;
}

function renderUnavailableSensor(container, endpointState) {
    container.className = 'sensor-values dashboard-endpoint-unavailable';
    container.textContent = managerStatusLabels[endpointState];
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
            const endpointState = managedDeviceStatus(device, device.type === 'SWITCH');
            if (!isEndpointAvailable(endpointState)) {
                device.values = [];
                sensorHistory.delete(deviceId);
                selectedSeries.delete(deviceId);
                const unavailableContainer = document.querySelector(`[data-device-id="${deviceId}"] .sensor-values`);
                if (unavailableContainer) renderUnavailableSensor(unavailableContainer, endpointState);
                return;
            }
            device.values = values;
            if (currentProjectId && findDeviceInProject(currentProjectId, deviceId)) {
                recordHistory(deviceId, now, values);
            }
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

function findDeviceInProject(projectId, deviceId) {
    const project = projects.find(item => item.id === projectId);
    if (!project) return null;
    for (const module of getModules(project)) {
        const device = getDevices(module).find(item => item.id === deviceId);
        if (device) return device;
    }
    return null;
}

function hasDeviceSeries(deviceId) {
    return [...selectedSeries.values()].some(
        series => series.projectId === currentProjectId && series.deviceId === deviceId
    );
}

function toggleDeviceChart(device) {
    if (!currentProjectId || !findDeviceInProject(currentProjectId, device.id)) return;
    if (hasDeviceSeries(device.id)) {
        [...selectedSeries.entries()]
            .filter(([, series]) => series.projectId === currentProjectId && series.deviceId === device.id)
            .forEach(([key]) => selectedSeries.delete(key));
    } else {
        const history = sensorHistory.get(device.id) || [];
        const latest = history.length ? history[history.length - 1].values : (device.values || [0]);
        const labels = sensorLabels(device.type, Math.max(1, latest.length));
        const sourceName = device.sourceDeviceName || device.source_device_name || '';
        const seriesName = sourceName ? `${device.name} (${sourceName})` : device.name;
        labels.slice(0, Math.max(1, latest.length)).forEach((axis, index) => {
            const key = `${device.id}:${index}`;
            selectedSeries.set(key, {
                projectId: currentProjectId,
                deviceId: device.id,
                valueIndex: index,
                label: `${seriesName} · ${axis}`,
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
    const panel = document.getElementById('timeline-panel');
    panel.hidden = selectedSeries.size === 0;
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

function createMasterSwitch(projectId, moduleId, enabled, disabled = false) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `dashboard-switch ${enabled ? 'dashboard-switch--on' : ''}`;
    button.setAttribute('role', 'switch');
    button.setAttribute('aria-checked', String(enabled));
    button.disabled = disabled;
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
        // This page is served by the Client, which forwards the command to the
        // configured ESP8266 endpoint on its local room network.
        const response = await fetch('/client/toggleSwitch', {
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
async function bootstrapDashboard() {
    await loadManagerStatuses();
    await loadProjects();
    loadSensorValues();
}
bootstrapDashboard();
setInterval(loadProjects, 5000);
setInterval(loadSensorValues, 250);
setInterval(loadManagerStatuses, 3000);
