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
        // Silently log initial fetch errors or minor sync issues to avoid annoying the user
        // unless it's a persistent failure.
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
        grid.appendChild(createWidgetCard(widget, project.id));
    });

    main.appendChild(grid);
}

function createWidgetCard(widget, projectId) {
    const card = document.createElement('div');
    card.className = 'rounded-lg border border-white/5 bg-[#1F2630] p-5 shadow-lg flex flex-col pointer-events-auto transition-transform hover:scale-[1.02]';

    const title = document.createElement('h3');
    title.className = 'text-xs font-bold uppercase tracking-widest text-slate-500 mb-6';
    title.textContent = widget.title || widget.module_title || 'Module';
    card.appendChild(title);

    const type = (widget.type || widget.module_type || '').toLowerCase();
    const val = widget.value !== undefined ? widget.value : 0;

    const content = document.createElement('div');
    content.className = 'flex-1';

    if (type === 'switch') {
        const isActive = val === true || val === "true" || val === 1 || val === 1.0;

        const container = document.createElement('div');
        container.className = 'flex items-center justify-between w-full';

        const statusText = document.createElement('span');
        statusText.className = `text-sm font-semibold ${isActive ? 'text-blue-400' : 'text-slate-400'}`;
        statusText.textContent = isActive ? 'Enabled' : 'Disabled';
        container.appendChild(statusText);

        const switchWrapper = document.createElement('div');
        switchWrapper.style.cssText = `
            width: 56px; height: 30px; background: ${isActive ? '#3b82f6' : '#334155'};
            border-radius: 15px; padding: 3px; cursor: pointer; transition: background 0.3s;
            position: relative; display: flex; align-items: center; pointer-events: auto;
        `;

        const knob = document.createElement('div');
        knob.style.cssText = `
            width: 24px; height: 24px; background: white; border-radius: 50%;
            position: absolute; left: ${isActive ? '29px' : '3px'}; transition: 0.3s;
            box-shadow: 0 2px 4px rgba(0,0,0,0.3);
        `;

        switchWrapper.appendChild(knob);
        switchWrapper.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            toggleSwitch(projectId, widget.id, !isActive);
        };

        container.appendChild(switchWrapper);
        content.appendChild(container);
    } else if (type === 'temperature' || type === 'pressure' || type === 'radiation') {
        const valDiv = document.createElement('div');
        valDiv.className = 'text-4xl font-bold flex items-baseline gap-2';
        valDiv.innerHTML = `${val} <span class="text-sm text-slate-500 font-normal">${widget.unit || ''}</span>`;
        content.appendChild(valDiv);
    } else if (type === 'air_quality') {
        content.className = 'flex items-center gap-6';
        content.innerHTML = `
            <div style="width: 70px; height: 70px; border-radius: 50%; background: conic-gradient(#f97316 ${val}%, #334155 0); display: flex; align-items: center; justify-content: center;">
                <div style="width: 56px; height: 56px; background: #1F2630; border-radius: 50%; display: flex; align-items: center; justify-content: center;">
                    <span style="font-size: 1rem; font-weight: bold;">${val}%</span>
                </div>
            </div>
            <div class="text-xs text-slate-500 font-bold uppercase tracking-tighter">Harmful<br>Gases</div>
        `;
    } else {
        const p = document.createElement('p');
        p.className = 'text-sm text-slate-400 italic';
        p.textContent = widget.description || 'Module details synced from host.';
        content.appendChild(p);
    }

    card.appendChild(content);
    return card;
}

async function toggleSwitch(projectId, widgetId, newValue) {
    console.log('Sending Toggle Request...');

    // Optimistic UI Update
    const project = projects.find(p => p.id === projectId);
    if (project) {
        const widgets = project.widgets || project.moduleList || [];
        const widget = widgets.find(w => w.id === widgetId);
        if (widget) {
            widget.value = newValue ? 1.0 : 0.0;
            updateWidgetsDisplay(project);
        }
    }

    try {
        const response = await fetch('/toggleSwitch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectId, moduleId: widgetId, value: newValue })
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || `Status: ${response.status}`);
        }

        console.log('Toggle verified');
    } catch (e) {
        console.error('Toggle failed:', e);
        showToast(`Failed to toggle: ${e.message}`);
        loadProjects(); // Rollback
    }
}

function showToast(msg) {
    const container = document.getElementById('toast-container');
    if (!container) return;

    // Create a fresh toast element with Red "Error" styling
    const toast = document.createElement('div');
    toast.className = 'bg-[#1F2630] border-l-4 border-red-500 text-white px-5 py-3 rounded-lg shadow-2xl flex items-center gap-3 transition-all duration-500 transform translate-y-4 opacity-0 scale-95';
    toast.style.boxShadow = '0 10px 15px -3px rgba(0, 0, 0, 0.4)';

    toast.innerHTML = `
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>
        <span class="text-sm font-semibold tracking-tight text-red-50">${msg}</span>
    `;

    container.appendChild(toast);

    // Animate In
    requestAnimationFrame(() => {
        toast.classList.remove('translate-y-4', 'opacity-0', 'scale-95');
        toast.classList.add('translate-y-0', 'opacity-100', 'scale-100');
    });

    // Auto-remove after 3.5 seconds
    setTimeout(() => {
        toast.classList.remove('translate-y-0', 'opacity-100', 'scale-100');
        toast.classList.add('translate-y-[-10px]', 'opacity-0', 'scale-95');

        // Remove from DOM after transition finishes
        setTimeout(() => {
            toast.remove();
        }, 500);
    }, 3500);
}

function renderEmptyState() {
    const main = document.querySelector('main');
    if (main) main.innerHTML = '<div class="p-12 text-center text-slate-500">Waiting for projects from host server...</div>';
}

function renderError(msg) {
    const main = document.querySelector('main');
    if (main) main.innerHTML = `<div class="bg-red-500/10 text-red-500 p-6 rounded-lg border border-red-500/20 m-4">${msg}</div>`;
}

// Initial load
loadProjects();

// Immediate second fetch after a short delay to catch the server if it just started
setTimeout(loadProjects, 500);
setTimeout(loadProjects, 2000);

// Regular polling
setInterval(loadProjects, 5000);

// Force reload when window becomes visible
window.addEventListener('focus', () => {
    console.log('Window focused, reloading projects...');
    loadProjects();
});

document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        console.log('Visibility changed to visible, reloading projects...');
        loadProjects();
    }
});
