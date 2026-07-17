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

    // Create toast with stable layout and inline styles for guaranteed visibility
    const toast = document.createElement('div');
    // Using inline styles for the background to avoid dependency on Tailwind bundle
    toast.style.backgroundColor = '#1F2630';
    toast.style.border = '1px solid rgba(255, 255, 255, 0.1)';
    toast.className = 'relative text-white px-5 py-3 rounded-lg shadow-2xl flex items-center gap-3 animate-toast-in overflow-hidden max-w-md';
    toast.style.boxShadow = '0 10px 15px -3px rgba(0, 0, 0, 0.4)';
    toast.style.minWidth = '280px';

    toast.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px; width: 100%;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0;"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>
            <span style="font-size: 0.875rem; font-weight: 600; line-height: 1.25rem; color: #fee2e2; white-space: normal; word-break: break-word;">${msg}</span>
        </div>
        <div style="position: absolute; bottom: 0; left: 0; height: 3px; width: 100%; background-color: #ef4444; animation: progress-drain 3.5s linear forwards;"></div>
    `;

    container.appendChild(toast);

    // Auto-remove after 3.5 seconds
    setTimeout(() => {
        toast.classList.replace('animate-toast-in', 'animate-toast-out');

        // Remove from DOM after transition finishes
        setTimeout(() => {
            toast.remove();
        }, 300);
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
