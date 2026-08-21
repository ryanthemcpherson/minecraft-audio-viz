/**
 * ConnectCodeManager - Connect code generation, display, and revocation.
 */

export class ConnectCodeManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    setupListeners() {
        // Generate code button
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.addEventListener('click', () => {
                this.elements.btnGenerateCode.disabled = true;
                this.elements.btnGenerateCode.classList.add('btn-loading');
                this.elements.btnGenerateCode.textContent = 'Generating...';
                this.ws.send({ type: 'generate_connect_code', ttl_minutes: 30 });
            });
        }

        // Copy code button (inline)
        if (this.elements.btnCopyCode) {
            this.elements.btnCopyCode.addEventListener('click', () => {
                const code = this.elements.generatedCodeText?.textContent || '';
                this.app.ui.copyToClipboard(code).then((ok) => {
                    if (ok) {
                        this.elements.btnCopyCode.textContent = 'Copied!';
                        this.elements.btnCopyCode.classList.add('btn-copy-success');
                        setTimeout(() => {
                            this.elements.btnCopyCode.textContent = 'Copy';
                            this.elements.btnCopyCode.classList.remove('btn-copy-success');
                        }, 2000);
                    }
                });
            });
        }

        // Event delegation for revoke buttons
        if (this.elements.activeCodes) {
            this.elements.activeCodes.addEventListener('click', (e) => {
                const btn = e.target.closest('.btn-revoke');
                if (btn && btn.dataset.code) {
                    this.ws.send({ type: 'revoke_connect_code', code: btn.dataset.code });
                }
            });
        }
    }

    resetGenerateButton() {
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.disabled = false;
            this.elements.btnGenerateCode.classList.remove('btn-loading');
            this.elements.btnGenerateCode.textContent = 'Generate Connect Code';
        }
    }

    showGeneratedCode(code, ttlMinutes = 30) {
        // Reset generate button
        if (this.elements.btnGenerateCode) {
            this.elements.btnGenerateCode.disabled = false;
            this.elements.btnGenerateCode.classList.remove('btn-loading');
            this.elements.btnGenerateCode.textContent = 'Generate Connect Code';
        }
        if (this.elements.generatedCodeText) {
            this.elements.generatedCodeText.textContent = code;
        }
        if (this.elements.generatedCodeTtl) {
            this.elements.generatedCodeTtl.textContent = ttlMinutes;
        }
        if (this.elements.generatedCodeDisplay) {
            this.elements.generatedCodeDisplay.classList.remove('hidden');
        }
    }

    renderConnectCodes() {
        const container = this.elements.activeCodes;
        if (!container) return;

        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!this.state.connectCodes || this.state.connectCodes.length === 0) {
            return;
        }

        const now = Date.now() / 1000;

        this.state.connectCodes.forEach(codeObj => {
            const item = document.createElement('div');
            item.className = 'active-code-item';

            const codeSpan = document.createElement('span');
            codeSpan.className = 'code-text';
            codeSpan.textContent = codeObj.code;

            const expiresSpan = document.createElement('span');
            expiresSpan.className = 'code-expires';
            const remaining = Math.max(0, Math.floor((codeObj.expires_at - now) / 60));
            expiresSpan.textContent = `${remaining}m`;
            if (remaining < 5) {
                expiresSpan.classList.add('expiring-soon');
            }

            const revokeBtn = document.createElement('button');
            revokeBtn.className = 'btn-revoke';
            revokeBtn.dataset.code = codeObj.code;
            revokeBtn.textContent = 'X';
            revokeBtn.title = 'Revoke code';

            item.appendChild(codeSpan);
            item.appendChild(expiresSpan);
            item.appendChild(revokeBtn);
            container.appendChild(item);
        });
    }
}
