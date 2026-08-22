/**
 * ModalDialog - Themed modal dialogs replacing native alert/confirm/prompt.
 * Matches the MCAV admin panel dark VJ aesthetic.
 *
 * Usage:
 *   await ModalDialog.alert("Title", "Message")
 *   const ok = await ModalDialog.confirm("Title", "Are you sure?")
 *   const val = await ModalDialog.prompt("Title", "Enter value:", defaultValue)
 */

const STYLES = `
.mcav-modal-overlay {
    position: fixed;
    inset: 0;
    z-index: 10000;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(0, 0, 0, 0.7);
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
    opacity: 0;
    transition: opacity 0.2s ease;
}

.mcav-modal-overlay.visible {
    opacity: 1;
}

.mcav-modal-card {
    background: var(--bg-secondary, #0f1118);
    border: 1px solid var(--border-color, rgba(255, 255, 255, 0.06));
    border-radius: var(--border-radius-lg, 10px);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6), 0 0 1px rgba(255, 255, 255, 0.05);
    min-width: 340px;
    max-width: 460px;
    width: 90vw;
    padding: 0;
    transform: scale(0.95) translateY(8px);
    transition: transform 0.2s cubic-bezier(0.25, 1, 0.5, 1);
    font-family: var(--font-sans, "Inter", sans-serif);
}

.mcav-modal-overlay.visible .mcav-modal-card {
    transform: scale(1) translateY(0);
}

.mcav-modal-header {
    padding: 16px 20px 0;
    font-size: var(--font-size-lg, 15px);
    font-weight: 600;
    color: var(--text-primary, #f5f5f5);
    letter-spacing: 0.01em;
}

.mcav-modal-body {
    padding: 12px 20px 20px;
    font-size: var(--font-size-base, 13px);
    color: var(--text-secondary, #a1a1aa);
    line-height: 1.5;
    white-space: pre-wrap;
}

.mcav-modal-input {
    width: 100%;
    box-sizing: border-box;
    margin-top: 12px;
    padding: 8px 12px;
    background: var(--bg-primary, #08090d);
    border: 1px solid var(--border-visible, rgba(255, 255, 255, 0.1));
    border-radius: var(--border-radius, 6px);
    color: var(--text-primary, #f5f5f5);
    font-family: var(--font-sans, "Inter", sans-serif);
    font-size: var(--font-size-base, 13px);
    outline: none;
    transition: border-color 0.15s ease;
}

.mcav-modal-input:focus {
    border-color: var(--accent-primary, #00CCFF);
    box-shadow: 0 0 0 2px rgba(0, 204, 255, 0.15);
}

.mcav-modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 0 20px 16px;
}

.mcav-modal-btn {
    padding: 8px 18px;
    border-radius: var(--border-radius, 6px);
    font-family: var(--font-sans, "Inter", sans-serif);
    font-size: var(--font-size-sm, 11px);
    font-weight: 500;
    cursor: pointer;
    border: 1px solid transparent;
    transition: all 0.2s ease;
    user-select: none;
}

.mcav-modal-btn:active {
    transform: scale(0.97);
}

.mcav-modal-btn-cancel {
    background: var(--bg-tertiary, #151530);
    color: var(--text-secondary, #a1a1aa);
    border-color: var(--border-color, rgba(255, 255, 255, 0.06));
}

.mcav-modal-btn-cancel:hover {
    background: rgba(255, 255, 255, 0.06);
    color: var(--text-primary, #f5f5f5);
}

.mcav-modal-btn-primary {
    background: rgba(0, 204, 255, 0.12);
    color: var(--accent-primary, #00CCFF);
    border-color: rgba(0, 204, 255, 0.3);
}

.mcav-modal-btn-primary:hover {
    background: var(--accent-primary, #00CCFF);
    color: var(--bg-primary, #08090d);
    box-shadow: 0 0 12px rgba(0, 204, 255, 0.3);
}

.mcav-modal-btn-danger {
    background: rgba(255, 71, 87, 0.08);
    color: var(--accent-danger, #ff4757);
    border-color: rgba(255, 71, 87, 0.3);
}

.mcav-modal-btn-danger:hover {
    background: var(--accent-danger, #ff4757);
    color: var(--text-primary, #f5f5f5);
    box-shadow: 0 0 12px rgba(255, 71, 87, 0.3);
}

@media (prefers-reduced-motion: reduce) {
    .mcav-modal-overlay,
    .mcav-modal-card,
    .mcav-modal-btn {
        transition: none;
    }
}
`;

let stylesInjected = false;

function injectStyles() {
    if (stylesInjected) return;
    const style = document.createElement('style');
    style.textContent = STYLES;
    document.head.appendChild(style);
    stylesInjected = true;
}

/**
 * Show a modal dialog and return a promise that resolves when the user responds.
 *
 * @param {object} opts
 * @param {string} opts.title
 * @param {string} opts.message
 * @param {'alert'|'confirm'|'prompt'} opts.type
 * @param {string} [opts.defaultValue]     - for prompt
 * @param {boolean} [opts.destructive]     - use red confirm button
 * @returns {Promise<boolean|string|null>}
 */
function showModal({ title, message, type = 'alert', defaultValue = '', destructive = false }) {
    injectStyles();

    return new Promise((resolve) => {
        // --- Build DOM ---
        const overlay = document.createElement('div');
        overlay.className = 'mcav-modal-overlay';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', title);

        const card = document.createElement('div');
        card.className = 'mcav-modal-card';

        // Header
        const header = document.createElement('div');
        header.className = 'mcav-modal-header';
        header.textContent = title;

        // Body
        const body = document.createElement('div');
        body.className = 'mcav-modal-body';
        body.textContent = message;

        // Input (prompt only)
        let input = null;
        if (type === 'prompt') {
            input = document.createElement('input');
            input.className = 'mcav-modal-input';
            input.type = 'text';
            input.value = defaultValue;
            input.setAttribute('aria-label', message);
            body.appendChild(input);
        }

        // Footer
        const footer = document.createElement('div');
        footer.className = 'mcav-modal-footer';

        // Track which focusable elements exist for the focus trap
        const focusable = [];

        if (type !== 'alert') {
            const cancelBtn = document.createElement('button');
            cancelBtn.className = 'mcav-modal-btn mcav-modal-btn-cancel';
            cancelBtn.textContent = 'Cancel';
            cancelBtn.type = 'button';
            footer.appendChild(cancelBtn);
            focusable.push(cancelBtn);
        }

        const confirmBtn = document.createElement('button');
        if (type === 'alert') {
            confirmBtn.className = 'mcav-modal-btn mcav-modal-btn-primary';
            confirmBtn.textContent = 'OK';
        } else if (destructive) {
            confirmBtn.className = 'mcav-modal-btn mcav-modal-btn-danger';
            confirmBtn.textContent = 'Confirm';
        } else {
            confirmBtn.className = 'mcav-modal-btn mcav-modal-btn-primary';
            confirmBtn.textContent = type === 'prompt' ? 'OK' : 'Confirm';
        }
        confirmBtn.type = 'button';
        footer.appendChild(confirmBtn);
        focusable.push(confirmBtn);

        if (input) focusable.unshift(input);

        card.append(header, body, footer);
        overlay.appendChild(card);
        document.body.appendChild(overlay);

        // Remember previously focused element to restore later
        const previousFocus = document.activeElement;

        // --- Animate in ---
        requestAnimationFrame(() => {
            overlay.classList.add('visible');
            // Focus: input for prompt, confirm button otherwise
            if (input) {
                input.focus();
                input.select();
            } else {
                confirmBtn.focus();
            }
        });

        // --- Resolve helpers ---
        function close(value) {
            overlay.classList.remove('visible');
            overlay.addEventListener('transitionend', () => {
                overlay.remove();
                if (previousFocus && typeof previousFocus.focus === 'function') {
                    previousFocus.focus();
                }
            }, { once: true });
            // Fallback: if transition is disabled (prefers-reduced-motion), remove immediately
            setTimeout(() => {
                if (overlay.parentNode) {
                    overlay.remove();
                    if (previousFocus && typeof previousFocus.focus === 'function') {
                        previousFocus.focus();
                    }
                }
            }, 300);
            resolve(value);
        }

        function handleConfirm() {
            if (type === 'alert') close(undefined);
            else if (type === 'confirm') close(true);
            else close(input.value);
        }

        function handleCancel() {
            if (type === 'alert') close(undefined);
            else if (type === 'confirm') close(false);
            else close(null);
        }

        // --- Event listeners ---
        confirmBtn.addEventListener('click', handleConfirm);

        const cancelBtn = footer.querySelector('.mcav-modal-btn-cancel');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', handleCancel);
        }

        // Click overlay backdrop to cancel (not for alert)
        overlay.addEventListener('mousedown', (e) => {
            if (e.target === overlay) {
                handleCancel();
            }
        });

        // Keyboard
        overlay.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                handleCancel();
            } else if (e.key === 'Enter') {
                // For prompt, only confirm on Enter if input is focused
                // For other types, always confirm
                if (type !== 'prompt' || document.activeElement === input) {
                    e.preventDefault();
                    handleConfirm();
                }
            } else if (e.key === 'Tab') {
                // Focus trap
                if (focusable.length === 0) return;
                const first = focusable[0];
                const last = focusable[focusable.length - 1];
                if (e.shiftKey) {
                    if (document.activeElement === first) {
                        e.preventDefault();
                        last.focus();
                    }
                } else {
                    if (document.activeElement === last) {
                        e.preventDefault();
                        first.focus();
                    }
                }
            }
        });
    });
}

export const ModalDialog = {
    /**
     * Show an alert dialog.
     * @param {string} title
     * @param {string} message
     * @returns {Promise<void>}
     */
    alert(title, message) {
        return showModal({ title, message, type: 'alert' });
    },

    /**
     * Show a confirmation dialog.
     * @param {string} title
     * @param {string} message
     * @param {object} [opts]
     * @param {boolean} [opts.destructive=false] - Use red button styling
     * @returns {Promise<boolean>}
     */
    confirm(title, message, { destructive = false } = {}) {
        return showModal({ title, message, type: 'confirm', destructive });
    },

    /**
     * Show a prompt dialog.
     * @param {string} title
     * @param {string} message
     * @param {string} [defaultValue='']
     * @returns {Promise<string|null>}
     */
    prompt(title, message, defaultValue = '') {
        return showModal({ title, message, type: 'prompt', defaultValue });
    },
};
