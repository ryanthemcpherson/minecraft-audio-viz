-- Pattern metadata
name = "LED Wall"
description = "Giant LED screen with spectrum bars, waveform, color wash, and beat geometry modes"
category = "Mainstage"
static_camera = true
recommended_entities = 100

state = {}

local MATERIALS = {
    "RED_CONCRETE",
    "ORANGE_CONCRETE",
    "YELLOW_CONCRETE",
    "LIME_CONCRETE",
    "BLUE_CONCRETE",
}

local function sample_band(bands, col_frac)
    -- Interpolate 5 bands across a continuous 0-1 range
    local pos = col_frac * 4
    local lo = math.floor(pos)
    local hi = math.min(lo + 1, 4)
    local t = pos - lo
    return lerp(bands[lo + 1] or 0, bands[hi + 1] or 0, t)
end

function calculate(audio, config, dt)
    local n = config.entity_count
    local cols = math.max(2, math.floor(math.sqrt(n)))
    local rows = math.max(2, math.floor(n / cols))

    -- Initialize state
    if not state.mode then
        state.mode = 0
        state.mode_timer = 0
        state.beat_count = 0
        state.scroll_offset = 0
        state.amp_buffer = {}
        state.amp_idx = 1
        state.shape = 0
        state.smooth_bands = {0, 0, 0, 0, 0}
        state.flash = 0
    end

    -- Smooth bands for spectrum display
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], audio.bands[i], 0.4, dt)
    end

    -- Beat handling
    if audio.beat then
        state.beat_count = state.beat_count + 1
        state.flash = 1.0
        state.mode_timer = state.mode_timer + 1
        if state.mode_timer >= 32 then
            state.mode_timer = 0
            state.mode = (state.mode + 1) % 4
        end
        -- Update amplitude ring buffer (for waveform mode)
        state.amp_buffer[state.amp_idx] = audio.amplitude
        state.amp_idx = (state.amp_idx % cols) + 1
        -- Cycle geometry shape
        state.shape = (state.shape + 1) % 4
    end

    state.flash = decay(state.flash, 0.8, dt)
    state.scroll_offset = state.scroll_offset + dt * 0.3

    local entities = {}
    local idx = 0
    local mode = state.mode

    for row = 0, rows - 1 do
        for col = 0, cols - 1 do
            if idx >= n then break end

            local col_frac = col / math.max(1, cols - 1)
            local row_frac = row / math.max(1, rows - 1)
            local x = 0.1 + col_frac * 0.8
            local y = 0.1 + row_frac * 0.8

            local intensity = 0
            local band_idx = math.floor(col_frac * 4.99)
            local visible = true
            local pixel_scale = config.base_scale

            if mode == 0 then
                -- Spectrum bars: columns as frequency bars rising from bottom
                local bar_height = sample_band(state.smooth_bands, col_frac)
                if row_frac <= bar_height then
                    intensity = 0.5 + bar_height * 0.5
                    band_idx = math.floor(col_frac * 4.99)
                else
                    intensity = 0.05
                    visible = false
                end

            elseif mode == 1 then
                -- Waveform: ring buffer displayed as horizontal wave
                local buf_idx = ((col + math.floor(state.amp_idx)) % cols) + 1
                local amp_val = state.amp_buffer[buf_idx] or 0
                local wave_y = amp_val * 0.8
                local dist = math.abs(row_frac - wave_y)
                if dist < 0.15 then
                    intensity = 1.0 - dist / 0.15
                    band_idx = math.floor(amp_val * 4.99)
                else
                    intensity = 0.02
                    visible = false
                end

            elseif mode == 2 then
                -- Color wash: scrolling gradient
                local shifted = (col_frac + state.scroll_offset) % 1.0
                band_idx = math.floor(shifted * 4.99)
                local band_val = state.smooth_bands[band_idx + 1] or 0
                intensity = 0.3 + band_val * 0.7
                pixel_scale = config.base_scale * (0.8 + band_val * 0.4)

            elseif mode == 3 then
                -- Beat geometry: flash shapes on beat
                local shape = state.shape
                local cx = math.floor(cols / 2)
                local cy = math.floor(rows / 2)
                local in_shape = false

                if shape == 0 then
                    -- X shape: diagonals
                    in_shape = math.abs(col - row * cols / rows) < 1.5
                             or math.abs(col - (rows - 1 - row) * cols / rows) < 1.5
                elseif shape == 1 then
                    -- Diamond
                    local dx = math.abs(col - cx)
                    local dy = math.abs(row - cy)
                    in_shape = (dx / cx + dy / cy) < 0.8 and (dx / cx + dy / cy) > 0.5
                elseif shape == 2 then
                    -- Border
                    in_shape = col == 0 or col == cols - 1 or row == 0 or row == rows - 1
                elseif shape == 3 then
                    -- Cross
                    in_shape = math.abs(col - cx) <= 1 or math.abs(row - cy) <= 1
                end

                if in_shape then
                    intensity = state.flash
                    band_idx = (state.beat_count + col) % 5
                else
                    intensity = state.flash * 0.1
                    visible = state.flash > 0.3
                end
            end

            -- Global amplitude boost
            intensity = clamp(intensity + audio.amplitude * 0.1)

            local brightness = math.floor(clamp(intensity) * 15)
            local glow = intensity > 0.4
            local mat = MATERIALS[(band_idx % 5) + 1]

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = 0.5,
                scale = math.min(config.max_scale, pixel_scale * (0.5 + intensity * 0.5)),
                rotation = 0,
                band = band_idx % 5,
                visible = visible,
                glow = glow,
                brightness = brightness,
                material = glow and mat or "GRAY_CONCRETE",
                interpolation = 2,
            }

            idx = idx + 1
        end
    end

    return normalize_entities(entities, n)
end
