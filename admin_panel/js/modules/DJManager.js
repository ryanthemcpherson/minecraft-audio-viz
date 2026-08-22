/**
 * DJManager - DJ queue/roster management and pending DJ approvals.
 */

import { ModalDialog } from '../ui/ModalDialog.js';

export class DJManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    // === DJ Roster ===

    handleDJRoster(data) {
        this.state.djRoster = data.dj_roster || data.roster || [];
        this.state.activeDJ = data.active_dj || null;
        this.renderDJQueue();
        this.app.banner.updateBannerDJSelector();
    }

    renderDJQueue() {
        this.renderCommandSummary();
        const container = this.elements.djQueue;
        if (!container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!this.state.djRoster || this.state.djRoster.length === 0) {
            const emptyEl = document.createElement('div');
            emptyEl.className = 'dj-empty';
            emptyEl.textContent = 'No DJs in the booth \u2014 generate a connect code below';
            container.appendChild(emptyEl);
            return;
        }

        this.state.djRoster.forEach((dj, index) => {
            const isActive = dj.dj_id === this.state.activeDJ;
            const djEl = document.createElement('div');
            djEl.className = 'dj-item' + (isActive ? ' active' : '');

            // Position number
            const posSpan = document.createElement('span');
            posSpan.className = 'dj-position';
            posSpan.textContent = `#${index + 1}`;

            // Avatar
            let avatarEl;
            if (dj.avatar_url && /^https?:\/\//.test(dj.avatar_url)) {
                avatarEl = document.createElement('img');
                avatarEl.className = 'dj-avatar';
                avatarEl.src = dj.avatar_url;
                avatarEl.alt = '';
                avatarEl.loading = 'lazy';
            } else {
                avatarEl = document.createElement('div');
                avatarEl.className = 'dj-avatar dj-avatar-initials';
                avatarEl.textContent = (dj.dj_name || '?').charAt(0).toUpperCase();
            }

            // Name and stats
            const infoDiv = document.createElement('div');
            infoDiv.className = 'dj-info';

            // Name + palette wrapper
            const namePaletteDiv = document.createElement('div');
            namePaletteDiv.className = 'dj-name-palette';

            const nameSpan = document.createElement('span');
            nameSpan.className = 'dj-name';
            nameSpan.textContent = dj.dj_name;
            namePaletteDiv.appendChild(nameSpan);

            // Color palette swatches (max 5)
            if (Array.isArray(dj.color_palette)) {
                const colors = dj.color_palette.slice(0, 5);
                colors.forEach(color => {
                    const swatch = document.createElement('span');
                    swatch.className = 'palette-swatch';
                    swatch.style.background = color;
                    namePaletteDiv.appendChild(swatch);
                });
            }

            infoDiv.appendChild(namePaletteDiv);

            // Stats row (BPM, latency, FPS)
            if (dj.bpm || dj.latency_ms !== undefined || dj.fps !== undefined) {
                const statsDiv = document.createElement('div');
                statsDiv.className = 'dj-stats';
                if (dj.bpm) {
                    const bpmStat = document.createElement('span');
                    bpmStat.className = 'dj-stat';
                    bpmStat.textContent = `${Math.round(dj.bpm)} BPM`;
                    statsDiv.appendChild(bpmStat);
                }
                if (dj.latency_ms !== undefined) {
                    const latStat = document.createElement('span');
                    latStat.className = 'dj-stat';
                    latStat.textContent = `${Math.round(dj.latency_ms)}ms`;
                    if (dj.latency_ms > 200) latStat.classList.add('warning');
                    statsDiv.appendChild(latStat);
                }
                if (dj.fps !== undefined) {
                    const fpsStat = document.createElement('span');
                    fpsStat.className = 'dj-stat';
                    fpsStat.textContent = `${Math.round(dj.fps)} FPS`;
                    statsDiv.appendChild(fpsStat);
                }
                infoDiv.appendChild(statsDiv);
            }

            // Per-DJ sync health badges
            const syncHealthDiv = document.createElement('div');
            syncHealthDiv.className = 'dj-sync-health';
            if (dj.clock_sync_age_s !== undefined && dj.clock_sync_age_s !== null) {
                const clockBadge = document.createElement('span');
                clockBadge.className = 'dj-sync-badge ' + (dj.clock_sync_age_s < 60 ? 'fresh' : 'stale');
                clockBadge.textContent = dj.clock_sync_age_s < 60 ? 'CLK OK' : `CLK ${Math.round(dj.clock_sync_age_s)}s`;
                syncHealthDiv.appendChild(clockBadge);
            }
            if (dj.clock_drift_rate !== undefined && dj.clock_drift_rate > 0.1) {
                const driftBadge = document.createElement('span');
                driftBadge.className = 'dj-sync-badge ' + (dj.clock_drift_rate > 5 ? 'stale' : '');
                driftBadge.textContent = `Drift: ${dj.clock_drift_rate.toFixed(1)}ms/m`;
                syncHealthDiv.appendChild(driftBadge);
            }
            if (dj.jitter_ms !== undefined && dj.jitter_ms > 0) {
                const jitterBadge = document.createElement('span');
                jitterBadge.className = 'dj-sync-badge ' + (dj.jitter_ms > 10 ? 'stale' : 'fresh');
                jitterBadge.textContent = `Jtr: ${dj.jitter_ms.toFixed(1)}ms`;
                syncHealthDiv.appendChild(jitterBadge);
            }
            if (syncHealthDiv.children.length > 0) {
                infoDiv.appendChild(syncHealthDiv);
            }

            // Actions area
            const actionsDiv = document.createElement('div');
            actionsDiv.className = 'dj-actions';

            if (isActive) {
                const badge = document.createElement('span');
                badge.className = 'dj-badge-live';
                badge.textContent = 'LIVE';
                actionsDiv.appendChild(badge);
            }
            if (dj.direct_mode) {
                const directBadge = document.createElement('span');
                directBadge.className = 'dj-badge-direct';
                directBadge.textContent = dj.mc_connected ? 'DIRECT: MC OK' : 'DIRECT: RELAY';
                if (!dj.mc_connected) directBadge.classList.add('dj-badge-direct-relay');
                actionsDiv.appendChild(directBadge);
            }
            if (!isActive) {
                const goLiveBtn = document.createElement('button');
                goLiveBtn.dataset.requiresConnection = '';
                goLiveBtn.className = 'btn btn-go-live';
                goLiveBtn.dataset.action = 'activate';
                goLiveBtn.dataset.dj = dj.dj_id;
                goLiveBtn.textContent = 'Go Live';
                actionsDiv.appendChild(goLiveBtn);
            }

            // Queue reorder controls
            const queueControls = document.createElement('div');
            queueControls.className = 'dj-queue-controls';

            if (index > 0) {
                const upBtn = document.createElement('button');
                upBtn.dataset.requiresConnection = '';
                upBtn.className = 'btn btn-queue-move';
                upBtn.dataset.action = 'move_up';
                upBtn.dataset.dj = dj.dj_id;
                upBtn.dataset.position = index;
                upBtn.textContent = '\u25B2';
                upBtn.title = 'Move up';
                queueControls.appendChild(upBtn);
            }
            if (index < this.state.djRoster.length - 1) {
                const downBtn = document.createElement('button');
                downBtn.dataset.requiresConnection = '';
                downBtn.className = 'btn btn-queue-move';
                downBtn.dataset.action = 'move_down';
                downBtn.dataset.dj = dj.dj_id;
                downBtn.dataset.position = index;
                downBtn.textContent = '\u25BC';
                downBtn.title = 'Move down';
                queueControls.appendChild(downBtn);
            }

            const kickBtn = document.createElement('button');
            kickBtn.dataset.requiresConnection = '';
            kickBtn.className = 'btn btn-kick';
            kickBtn.dataset.action = 'kick';
            kickBtn.dataset.dj = dj.dj_id;
            kickBtn.dataset.name = dj.dj_name;
            kickBtn.textContent = 'Kick';
            kickBtn.title = 'Kick DJ';
            queueControls.appendChild(kickBtn);

            djEl.appendChild(posSpan);
            djEl.appendChild(avatarEl);
            djEl.appendChild(infoDiv);
            djEl.appendChild(actionsDiv);
            djEl.appendChild(queueControls);
            container.appendChild(djEl);
        });
    }

    renderCommandSummary() {
        const summary = this.elements.activeDjSummary;
        const nameElement = this.elements.activeDjName;
        const healthElement = this.elements.activeDjHealth;
        if (!summary || !nameElement || !healthElement) return;

        const activeDJ = this.state.djRoster?.find(
            (dj) => dj.dj_id === this.state.activeDJ,
        );
        if (!activeDJ) {
            nameElement.textContent = 'No active DJ';
            healthElement.textContent = 'Sync idle';
            summary.dataset.health = 'idle';
            return;
        }

        nameElement.textContent = activeDJ.dj_name || 'Unnamed DJ';
        const clockAge = activeDJ.clock_sync_age_s;
        const driftRate = activeDJ.clock_drift_rate;
        const jitter = activeDJ.jitter_ms;
        const hasClockAge = Number.isFinite(clockAge);
        const hasDriftRate = Number.isFinite(driftRate);
        const hasJitter = Number.isFinite(jitter);
        const issues = [];
        if (hasClockAge && clockAge >= 60) issues.push(`clock ${Math.round(clockAge)}s`);
        if (hasDriftRate && driftRate > 5) issues.push(`drift ${driftRate.toFixed(1)}ms/m`);
        if (hasJitter && jitter > 10) issues.push(`${jitter.toFixed(1)}ms jitter`);

        if (issues.length > 0) {
            healthElement.textContent = `Sync degraded · ${issues.join(' · ')}`;
            summary.dataset.health = 'degraded';
        } else if (hasClockAge) {
            const detail = hasJitter
                ? `${jitter.toFixed(1)}ms jitter`
                : `clock ${Math.round(clockAge)}s`;
            healthElement.textContent = `Sync locked · ${detail}`;
            summary.dataset.health = 'locked';
        } else {
            healthElement.textContent = 'Sync data pending';
            summary.dataset.health = 'pending';
        }
    }

    setupQueueDelegation() {
        const container = this.elements.djQueue;
        if (!container || container._delegationSetup) return;

        container.addEventListener('click', async (e) => {
            const btn = e.target.closest('[data-action]');
            if (!btn || !btn.dataset.dj) return;

            const action = btn.dataset.action;
            const djId = btn.dataset.dj;

            switch (action) {
                case 'activate':
                    this.ws.send({ type: 'set_active_dj', dj_id: djId });
                    this.app.ui.showToast('Switching active DJ...', 'info');
                    break;

                case 'move_up': {
                    const pos = parseInt(btn.dataset.position);
                    if (pos > 0) {
                        this.ws.send({ type: 'reorder_dj_queue', dj_id: djId, new_position: pos - 1 });
                    }
                    break;
                }

                case 'move_down': {
                    const pos = parseInt(btn.dataset.position);
                    if (pos < this.state.djRoster.length - 1) {
                        this.ws.send({ type: 'reorder_dj_queue', dj_id: djId, new_position: pos + 1 });
                    }
                    break;
                }

                case 'kick': {
                    const name = btn.dataset.name || djId;
                    if (await ModalDialog.confirm('Kick DJ', `Kick DJ "${name}"?`, { destructive: true })) {
                        this.ws.send({ type: 'kick_dj', dj_id: djId });
                        this.app.ui.showToast(`Kicked DJ "${name}"`, 'info');
                    }
                    break;
                }
            }
        });
        container._delegationSetup = true;
    }

    // === Pending DJ Approval ===

    handleDJPending(data) {
        const dj = data.dj || data;
        const exists = this.state.pendingDJs.some(d => d.dj_id === dj.dj_id);
        if (!exists) {
            this.state.pendingDJs.push(dj);
        }
        this.renderPendingDJs();
        this.app.ui.showToast(`DJ "${dj.dj_name || 'Unknown'}" requesting approval`, 'info', 8000);
    }

    renderPendingDJs() {
        const section = this.elements.djPendingSection;
        const container = this.elements.djPendingQueue;
        if (!section || !container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        section.classList.toggle('hidden', this.state.pendingDJs.length === 0);

        this.state.pendingDJs.forEach(dj => {
            const item = document.createElement('div');
            item.className = 'dj-pending-item';
            item.dataset.djId = dj.dj_id;

            const info = document.createElement('div');
            info.className = 'dj-pending-info';

            const name = document.createElement('span');
            name.className = 'dj-pending-name';
            name.textContent = dj.dj_name || 'Unknown DJ';

            const meta = document.createElement('span');
            meta.className = 'dj-pending-meta';
            const waitTime = dj.waiting_since
                ? Math.floor((Date.now() / 1000 - dj.waiting_since) / 60)
                : 0;
            meta.textContent = waitTime > 0 ? `Waiting ${waitTime}m` : 'Just now';
            if (dj.direct_mode) {
                const badge = document.createElement('span');
                badge.className = 'dj-badge-direct';
                badge.textContent = dj.mc_connected ? 'DIRECT: MC OK' : 'DIRECT: RELAY';
                if (!dj.mc_connected) badge.classList.add('dj-badge-direct-relay');
                meta.appendChild(badge);
            }

            info.appendChild(name);
            info.appendChild(meta);

            const actions = document.createElement('div');
            actions.className = 'dj-pending-actions';

            const approveBtn = document.createElement('button');
            approveBtn.dataset.requiresConnection = '';
            approveBtn.className = 'btn btn-approve';
            approveBtn.dataset.action = 'approve';
            approveBtn.dataset.dj = dj.dj_id;
            approveBtn.textContent = 'Approve';

            const denyBtn = document.createElement('button');
            denyBtn.dataset.requiresConnection = '';
            denyBtn.className = 'btn btn-deny';
            denyBtn.dataset.action = 'deny';
            denyBtn.dataset.dj = dj.dj_id;
            denyBtn.textContent = 'Deny';

            actions.appendChild(approveBtn);
            actions.appendChild(denyBtn);

            item.appendChild(info);
            item.appendChild(actions);
            container.appendChild(item);
        });
    }

    setupPendingDelegation() {
        const container = this.elements.djPendingQueue;
        if (!container || container._delegationSetup) return;

        container.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-action]');
            if (!btn || !btn.dataset.dj) return;

            if (btn.dataset.action === 'approve') {
                this.ws.send({ type: 'approve_dj', dj_id: btn.dataset.dj });
                this.app.ui.showToast('DJ approved', 'success');
                this.state.pendingDJs = this.state.pendingDJs.filter(d => d.dj_id !== btn.dataset.dj);
                this.renderPendingDJs();
            } else if (btn.dataset.action === 'deny') {
                this.ws.send({ type: 'deny_dj', dj_id: btn.dataset.dj });
                this.app.ui.showToast('DJ denied', 'info');
                this.state.pendingDJs = this.state.pendingDJs.filter(d => d.dj_id !== btn.dataset.dj);
                this.renderPendingDJs();
            }
        });
        container._delegationSetup = true;
    }
}
