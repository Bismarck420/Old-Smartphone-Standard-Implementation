let projects = [];
let currentProjectId = null;

async function loadProjects() {
    try {
        const response = await fetch('/projects.json');
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const data = await response.json();

        const newProjects = data.projects || [];

        // Only trigger UI logic if we actually got projects or if the list changed
        if (newProjects.length > 0 || projects.length > 0) {
            if (newProjects.length !== projects.length) {
                projects = newProjects;
                renderSidebar();
            } else {
                projects = newProjects;
            }

            if (projects.length > 0) {
                if (!currentProjectId || !projects.find(p => p.id === currentProjectId)) {
                    selectProject(projects[0].id);
                } else {
                    const project = projects.find(p => p.id === currentProjectId);
                    updateWidgetsDisplay(project);
                }
            } else {
                renderEmptyState();
            }
        } else {
            renderEmptyState();
        }
    } catch (error) {
        console.error('Error loading projects:', error);
    }
}

function renderSidebar() {
    const container = document.querySelector('aside div.flex');
    if (!container) return;

    container.innerHTML = '';
    projects.forEach(project => {
        const btn = document.createElement('button');
        btn.className = `min-w-40 rounded-lg px-4 py-3 text-left transition-all md:min-w-0 pointer-events-auto cursor-pointer`;
        btn.textContent = project.name || 'Project';
        btn.onclick = (e) => {
            e.preventDefault();
            selectProject(project.id);
        };
        container.appendChild(btn);
    });
}

function selectProject(id) {
    currentProjectId = id;

    document.querySelectorAll('aside div.flex button').forEach((btn, index) => {
        const project = projects[index];
        if (project && project.id === id) {
            btn.className = 'min-w-40 rounded-lg px-4 py-3 text-left transition-all md:min-w-0 bg-orange-500 text-white shadow-lg pointer-events-auto cursor-pointer';
        } else {
            btn.className = 'min-w-40 rounded-lg px-4 py-3 text-left transition-all md:min-w-0 bg-white/5 text-slate-300 hover:bg-white/10 pointer-events-auto cursor-pointer';
        }
    });

    const project = projects.find(p => p.id === id);
    if (!project) return;

    const headerTitle = document.querySelector('header h2');
    if (headerTitle) headerTitle.textContent = project.name;

    updateWidgetsDisplay(project);
}

function updateWidgetsDisplay(project) {
    const main = document.querySelector('main');
    if (!main) return;

    main.innerHTML = '';
    const grid = document.createElement('div');
    grid.className = 'grid grid-cols-1 gap-4 sm:grid-cols-2 md:gap-6 xl:grid-cols-3 relative z-20';

    const widgets = project.widgets || project.moduleList || [];
    if (widgets.length === 0) {
        main.innerHTML = '<div class="p-8 text-center text-slate-500">No modules in this project.</div>';
        return;
    }

    widgets.forEach(widget => {
        grid.appendChild(createModuleCard(widget, project.id));
    });

    main.appendChild(grid);
}

function createModuleCard(module, projectId) {
    const card = document.createElement('div');
    card.className = 'rounded-lg border border-white/5 bg-[#1F2630] p-5 shadow-lg flex flex-col pointer-events-auto transition-transform hover:scale-[1.01]';

    const header = document.createElement('div');
    header.className = 'flex justify-between items-start mb-4';

    const title = document.createElement('h3');
    title.className = 'text-xs font-bold uppercase tracking-widest text-slate-500';
    title.textContent = module.module_title || 'Module';
    header.appendChild(title);

    if (module.module_type === 'Switch') {
        const isActive = module.value === 1 || module.value === 1.0;
        header.appendChild(createMasterSwitch(projectId, module.id, isActive));
    }

    card.appendChild(header);

    const devices = module.device_list || [];
    if (devices.length === 0) {
        const p = document.createElement('p');
        p.className = 'text-sm text-slate-400 italic mt-2';
        p.textContent = module.module_description || 'No sensors assigned.';
        card.appendChild(p);
    } else {
        const deviceListContainer = document.createElement('div');
        deviceListContainer.className = 'flex flex-col gap-4';

        devices.forEach(device => {
            deviceListContainer.appendChild(createDeviceRow(device));
        });

        card.appendChild(deviceListContainer);
    }

    return card;
}

function createDeviceRow(device) {
    const row = document.createElement('div');
    row.className = 'bg-white/5 rounded-lg p-3 border border-white/5';
    row.dataset.deviceId = device.id;

    const name = document.createElement('div');
    name.className = 'text-[10px] font-bold text-slate-400 uppercase mb-2 flex items-center gap-2';
    name.innerHTML = `<span>${getIconForType(device.type)}</span> ${device.name}`;
    row.appendChild(name);

    const dataContainer = document.createElement('div');
    dataContainer.className = 'sensor-values';
    renderSensorValues(dataContainer, device, device.values || []);

    row.appendChild(dataContainer);
    return row;
}

function sensorLabels(type, valueCount) {
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
    return labels[type] || Array.from({ length: valueCount }, (_, index) => `Value ${index + 1}`);
}

function renderSensorValues(container, device, values) {
    const numericValues = Array.isArray(values) ? values : [];
    if (device.type === 'SWITCH') {
        container.innerHTML = `<div class="text-blue-400 text-sm font-bold">${numericValues[0] == 1 ? 'ON' : 'OFF'}</div>`;
        return;
    }
    if (numericValues.length <= 1) {
        container.className = 'sensor-values';
        const value = numericValues.length ? Number(numericValues[0]).toFixed(1) : '--';
        container.innerHTML = `<div class="text-2xl font-bold flex items-baseline gap-1">${value}<span class="text-xs text-slate-500 font-normal">${device.unit || ''}</span></div>`;
        return;
    }
    const labels = sensorLabels(device.type, numericValues.length);
    container.className = 'sensor-values grid grid-cols-2 gap-2 sm:grid-cols-3';
    container.innerHTML = numericValues.map((value, index) => `
        <div class="rounded bg-black/10 px-2 py-1 text-center">
            <div class="text-[9px] text-slate-500 font-bold">${labels[index] || `Value ${index + 1}`}</div>
            <div class="text-sm font-mono font-bold text-blue-400">${Number(value).toFixed(2)}</div>
        </div>`).join('');
}

async function loadSensorValues() {
    try {
        const response = await fetch('/sensor-values.json', { cache: 'no-store' });
        if (!response.ok) return;
        const payload = await response.json();
        const valuesByDevice = payload.values || {};
        Object.entries(valuesByDevice).forEach(([deviceId, values]) => {
            const device = findDevice(deviceId);
            const container = document.querySelector(`[data-device-id="${deviceId}"] .sensor-values`);
            if (device && container) renderSensorValues(container, device, values);
        });
    } catch (error) {
        console.debug('Sensor update unavailable:', error);
    }
}

function findDevice(deviceId) {
    for (const project of projects) {
        for (const module of (project.widgets || project.moduleList || [])) {
            const device = (module.device_list || []).find(item => item.id === deviceId);
            if (device) return device;
        }
    }
    return null;
}

function getIconForType(type) {
    switch(type) {
        case 'LIGHT_SENSOR': return '☀️';
        case 'ACCELEROMETER': return '📏';
        case 'GYROSCOPE': return '🔄';
        case 'MAGNETIC_FIELD': return '🧲';
        case 'PROXIMITY': return '📏';
        case 'PRESSURE': return '⏲️';
        case 'AMBIENT_TEMPERATURE': return '🌡️';
        case 'RELATIVE_HUMIDITY': return '💧';
        case 'GRAVITY': return '🌎';
        case 'LINEAR_ACCELERATION': return '🚀';
        case 'ROTATION_VECTOR': return '📐';
        case 'STEP_COUNTER': return '👣';
        case 'HEART_RATE': return '❤️';
        case 'HEART_BEAT': return '💓';
        case 'SWITCH': return '💡';
        default: return '🔘';
    }
}

function createMasterSwitch(projectId, moduleId, isActive) {
    const switchWrapper = document.createElement('div');
    switchWrapper.style.cssText = `
        width: 44px; height: 24px; background: ${isActive ? '#3b82f6' : '#334155'};
        border-radius: 12px; padding: 2px; cursor: pointer; transition: background 0.3s;
        position: relative; display: flex; align-items: center;
    `;

    const knob = document.createElement('div');
    knob.style.cssText = `
        width: 20px; height: 20px; background: white; border-radius: 50%;
        position: absolute; left: ${isActive ? '22px' : '2px'}; transition: 0.3s;
        box-shadow: 0 1px 3px rgba(0,0,0,0.3);
    `;

    switchWrapper.appendChild(knob);
    switchWrapper.onclick = (e) => {
        e.preventDefault();
        e.stopPropagation();
        toggleSwitch(projectId, moduleId, !isActive);
    };

    return switchWrapper;
}

async function toggleSwitch(projectId, moduleId, newValue) {
    console.log('Toggling Switch...');

    // Optimistic update
    const project = projects.find(p => p.id === projectId);
    if (project) {
        const module = (project.moduleList || []).find(m => m.id === moduleId);
        if (module) {
            module.value = newValue ? 1.0 : 0.0;
            updateWidgetsDisplay(project);
        }
    }

    try {
        const response = await fetch('/toggleSwitch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectId, moduleId, value: newValue })
        });

        if (!response.ok) throw new Error(await response.text());
    } catch (e) {
        console.error('Toggle failed:', e);
        showToast(`Failed to toggle: ${e.message}`);
        loadProjects();
    }
}

function showToast(msg) {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.style.backgroundColor = '#1F2630';
    toast.style.border = '1px solid rgba(255, 255, 255, 0.1)';
    toast.className = 'relative text-white px-5 py-3 rounded-lg shadow-2xl flex items-center gap-3 animate-toast-in overflow-hidden max-w-md';
    toast.style.minWidth = '280px';

    toast.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px; width: 100%;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0;"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>
            <span style="font-size: 0.875rem; font-weight: 600; line-height: 1.25rem; color: #fee2e2; white-space: normal; word-break: break-word;">${msg}</span>
        </div>
        <div style="position: absolute; bottom: 0; left: 0; height: 3px; width: 100%; background-color: #ef4444; animation: progress-drain 3.5s linear forwards;"></div>
    `;

    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.replace('animate-toast-in', 'animate-toast-out');
        setTimeout(() => toast.remove(), 300);
    }, 3500);
}

function renderEmptyState() {
    const main = document.querySelector('main');
    if (main) main.innerHTML = '<div class="p-12 text-center text-slate-500">Waiting for projects from host server...</div>';
}

function renderError(msg) {
    const main = document.querySelector('main');
    if (main) {
        main.innerHTML = `
            <div class="flex justify-center p-8 animate-fade-in-scale">
                <div class="bg-red-500/10 text-red-500 p-6 rounded-xl border border-red-500/20 max-w-lg w-full text-center shadow-lg">
                    <svg class="mx-auto mb-4" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                    <h4 class="text-lg font-bold mb-1">Connection Issue</h4>
                    <p class="text-sm text-red-400/80">${msg}</p>
                </div>
            </div>`;
    }
}

loadProjects();
setInterval(loadProjects, 5000);
setInterval(loadSensorValues, 250);
