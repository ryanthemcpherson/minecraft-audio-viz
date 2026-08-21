/**
 * BannerManager - Banner system (text/image banners for DJ names).
 */

export class BannerManager {
    constructor(app) {
        this.app = app;
        this.state = app.state;
        this.ws = app.ws;
        this.elements = app.elements;
    }

    setupBannerListeners() {
        // Banner mode toggle
        const modeButtons = document.querySelectorAll('[data-banner-mode]');
        modeButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                modeButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                const mode = btn.dataset.bannerMode;
                const textSettings = document.getElementById('banner-text-settings');
                const imageSettings = document.getElementById('banner-image-settings');
                if (textSettings) textSettings.classList.toggle('hidden', mode !== 'text');
                if (imageSettings) imageSettings.classList.toggle('hidden', mode !== 'image');
            });
        });

        // Color mode dropdown
        const colorModeSelect = document.getElementById('banner-text-color-mode');
        if (colorModeSelect) {
            colorModeSelect.addEventListener('change', () => {
                const fixedRow = document.getElementById('banner-fixed-color-row');
                if (fixedRow) fixedRow.classList.toggle('hidden', colorModeSelect.value !== 'fixed');
            });
        }

        // Grid width/height sliders
        const gridW = document.getElementById('banner-grid-width');
        const gridH = document.getElementById('banner-grid-height');
        if (gridW) {
            gridW.addEventListener('input', () => {
                const label = document.getElementById('val-banner-grid-width');
                if (label) label.textContent = gridW.value;
            });
        }
        if (gridH) {
            gridH.addEventListener('input', () => {
                const label = document.getElementById('val-banner-grid-height');
                if (label) label.textContent = gridH.value;
            });
        }

        // Pulse intensity slider
        const pulse = document.getElementById('banner-pulse-intensity');
        if (pulse) {
            pulse.addEventListener('input', () => {
                const label = document.getElementById('val-banner-pulse');
                if (label) label.textContent = pulse.value + '%';
            });
        }

        // Upload logo button
        const uploadBtn = document.getElementById('btn-upload-logo');
        const fileInput = document.getElementById('banner-logo-file');
        if (uploadBtn && fileInput) {
            uploadBtn.addEventListener('click', () => fileInput.click());
            fileInput.addEventListener('change', () => this._handleLogoUpload());
        }

        // Save profile button
        const saveBtn = document.getElementById('btn-save-banner-profile');
        if (saveBtn) {
            saveBtn.addEventListener('click', () => this._saveBannerProfile());
        }

        // Apply now button
        const applyBtn = document.getElementById('btn-apply-banner-now');
        if (applyBtn) {
            applyBtn.addEventListener('click', () => this._applyBannerNow());
        }

        // DJ selector
        const djSelect = document.getElementById('banner-dj-select');
        if (djSelect) {
            djSelect.addEventListener('change', () => {
                const djId = djSelect.value;
                if (djId) {
                    this.ws.send({ type: 'get_banner_profile', dj_id: djId });
                }
            });
        }
    }

    _handleLogoUpload() {
        const fileInput = document.getElementById('banner-logo-file');
        const filenameLabel = document.getElementById('banner-logo-filename');
        const djSelect = document.getElementById('banner-dj-select');
        if (!fileInput || !fileInput.files[0] || !djSelect) return;

        const file = fileInput.files[0];
        if (filenameLabel) filenameLabel.textContent = file.name;

        const reader = new FileReader();
        reader.onload = (e) => {
            this._drawLogoPreview(e.target.result);

            const base64 = e.target.result.split(',')[1];
            const gridW = parseInt(document.getElementById('banner-grid-width')?.value || '24');
            const gridH = parseInt(document.getElementById('banner-grid-height')?.value || '12');

            this.ws.send({
                type: 'upload_banner_logo',
                dj_id: djSelect.value,
                image_base64: base64,
                grid_width: gridW,
                grid_height: gridH,
                filename: file.name,
            });
        };
        reader.readAsDataURL(file);
    }

    _drawLogoPreview(dataUrl) {
        const canvas = document.getElementById('banner-preview-canvas');
        if (!canvas) return;

        const ctx = canvas.getContext('2d');
        const img = new Image();
        img.onload = () => {
            const gridW = parseInt(document.getElementById('banner-grid-width')?.value || '24');
            const gridH = parseInt(document.getElementById('banner-grid-height')?.value || '12');

            canvas.width = gridW * 10;
            canvas.height = gridH * 10;
            ctx.imageSmoothingEnabled = false;

            const tempCanvas = document.createElement('canvas');
            tempCanvas.width = gridW;
            tempCanvas.height = gridH;
            const tempCtx = tempCanvas.getContext('2d');
            tempCtx.drawImage(img, 0, 0, gridW, gridH);

            ctx.drawImage(tempCanvas, 0, 0, canvas.width, canvas.height);
        };
        img.src = dataUrl;
    }

    _saveBannerProfile() {
        const djSelect = document.getElementById('banner-dj-select');
        if (!djSelect || !djSelect.value) {
            this.app.ui.showToast('Select a DJ first', 'error');
            return;
        }

        const activeMode = document.querySelector('[data-banner-mode].active');
        const profile = {
            banner_mode: activeMode ? activeMode.dataset.bannerMode : 'text',
            text_style: document.getElementById('banner-text-style')?.value || 'bold',
            text_color_mode: document.getElementById('banner-text-color-mode')?.value || 'frequency',
            text_fixed_color: document.getElementById('banner-text-fixed-color')?.value || 'f',
            text_format: document.getElementById('banner-text-format')?.value || '%s',
            grid_width: parseInt(document.getElementById('banner-grid-width')?.value || '24'),
            grid_height: parseInt(document.getElementById('banner-grid-height')?.value || '12'),
        };

        this.ws.send({
            type: 'set_banner_profile',
            dj_id: djSelect.value,
            profile: profile,
        });
    }

    _applyBannerNow() {
        const djSelect = document.getElementById('banner-dj-select');
        if (!djSelect || !djSelect.value) {
            this.app.ui.showToast('Select a DJ first', 'error');
            return;
        }

        const activeMode = document.querySelector('[data-banner-mode].active');
        const msg = {
            type: 'banner_config',
            banner_mode: activeMode ? activeMode.dataset.bannerMode : 'text',
            text_style: document.getElementById('banner-text-style')?.value || 'bold',
            text_color_mode: document.getElementById('banner-text-color-mode')?.value || 'frequency',
            text_fixed_color: document.getElementById('banner-text-fixed-color')?.value || 'f',
            text_format: document.getElementById('banner-text-format')?.value || '%s',
            grid_width: parseInt(document.getElementById('banner-grid-width')?.value || '24'),
            grid_height: parseInt(document.getElementById('banner-grid-height')?.value || '12'),
        };

        this.ws.send(msg);
    }

    handleBannerProfile(data) {
        if (!data.profile) return;
        const p = data.profile;

        const modeButtons = document.querySelectorAll('[data-banner-mode]');
        modeButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.bannerMode === (p.banner_mode || 'text'));
        });
        const textSettings = document.getElementById('banner-text-settings');
        const imageSettings = document.getElementById('banner-image-settings');
        if (textSettings) textSettings.classList.toggle('hidden', p.banner_mode === 'image');
        if (imageSettings) imageSettings.classList.toggle('hidden', p.banner_mode !== 'image');

        const textStyle = document.getElementById('banner-text-style');
        if (textStyle) textStyle.value = p.text_style || 'bold';

        const colorMode = document.getElementById('banner-text-color-mode');
        if (colorMode) {
            colorMode.value = p.text_color_mode || 'frequency';
            const fixedRow = document.getElementById('banner-fixed-color-row');
            if (fixedRow) fixedRow.classList.toggle('hidden', colorMode.value !== 'fixed');
        }

        const fixedColor = document.getElementById('banner-text-fixed-color');
        if (fixedColor) fixedColor.value = p.text_fixed_color || 'f';

        const textFormat = document.getElementById('banner-text-format');
        if (textFormat) textFormat.value = p.text_format || '%s';

        const gridW = document.getElementById('banner-grid-width');
        if (gridW) {
            gridW.value = p.grid_width || 24;
            const label = document.getElementById('val-banner-grid-width');
            if (label) label.textContent = gridW.value;
        }

        const gridH = document.getElementById('banner-grid-height');
        if (gridH) {
            gridH.value = p.grid_height || 12;
            const label = document.getElementById('val-banner-grid-height');
            if (label) label.textContent = gridH.value;
        }

        const filenameLabel = document.getElementById('banner-logo-filename');
        if (filenameLabel) {
            filenameLabel.textContent = p.logo_filename || (p.has_image ? 'Logo uploaded' : 'No file selected');
        }
    }

    updateBannerDJSelector() {
        const select = document.getElementById('banner-dj-select');
        if (!select) return;

        const currentVal = select.value;

        // Clear options using safe DOM methods
        while (select.firstChild) {
            select.removeChild(select.firstChild);
        }
        const defaultOpt = document.createElement('option');
        defaultOpt.value = '';
        defaultOpt.textContent = '-- Select DJ --';
        select.appendChild(defaultOpt);

        if (this.state.djRoster) {
            this.state.djRoster.forEach(dj => {
                const opt = document.createElement('option');
                opt.value = dj.dj_id;
                opt.textContent = dj.dj_name + (dj.is_active ? ' (Active)' : '');
                select.appendChild(opt);
            });
        }

        if (currentVal) select.value = currentVal;
    }
}
