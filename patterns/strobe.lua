-- Pattern metadata
name = "Strobe Wall"
description = "Full-zone grid that flashes on/off in sync with beats — 4 strobe modes cycle automatically"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    -- Grid dimensions: closest rectangle to n
    local cols = math.max(1, math.floor(math.sqrt(n * 1.5)))
    local rows = math.max(1, math.floor(n / cols))

    -- Initialize state
    if not state.flash then
        state.flash = 0
        state.mode = 0
        state.mode_timer = 0
        state.beat_count = 0
        state.parity = 0
    end

    -- Beat handling
    if audio.is_beat then
        state.flash = 1.0
        state.beat_count = state.beat_count + 1
        state.parity = 1 - state.parity
        -- Cycle mode every 16 beats
        state.mode_timer = state.mode_timer + 1
        if state.mode_timer >= 16 then
            state.mode_timer = 0
            state.mode = (state.mode + 1) % 4
        end
    end

    -- Decay flash between beats
    state.flash = decay(state.flash, 0.75, dt)

    -- BPM-adaptive subdivision
    local subdiv = 1
    if audio.bpm > 140 then
        subdiv = 0.5  -- half-time for fast tracks
    elseif audio.bpm > 0 and audio.bpm < 100 then
        subdiv = 2    -- double-time for slow tracks
    end

    -- Beat pulse for sustained strobe between discrete beats
    local pulse = beat_pulse(audio.beat_phase, subdiv, 8.0)
    local base_intensity = math.max(state.flash, pulse)

    -- Bass adds a brightness floor
    local bass_floor = audio.bands[1] * 0.3

    local entities = {}
    local idx = 0

    for row = 0, rows - 1 do
        for col = 0, cols - 1 do
            if idx >= n then break end

            local x = 0.1 + (col / math.max(1, cols - 1)) * 0.8
            local z = 0.1 + (row / math.max(1, rows - 1)) * 0.8

            -- Per-entity intensity based on current mode
            local intensity = 0
            local mode = state.mode

            if mode == 0 then
                -- Mode 0: Full flash — all entities strobe together
                intensity = base_intensity

            elseif mode == 1 then
                -- Mode 1: Checkerboard — alternating halves flash
                local is_even = (row + col) % 2 == state.parity
                intensity = is_even and base_intensity or (base_intensity * 0.1)

            elseif mode == 2 then
                -- Mode 2: Wave sweep — flash propagates left to right
                local wave_pos = beat_sub(audio.beat_phase, subdiv) * (cols + 2)
                local dist = math.abs(col - wave_pos)
                local wave_intensity = math.max(0, 1.0 - dist * 0.4)
                intensity = wave_intensity * math.max(state.flash, 0.5)

            elseif mode == 3 then
                -- Mode 3: Random scatter — noise-based 40% selection on beat
                local noise = simple_noise(col, row, state.beat_count)
                local threshold = 0.2  -- ~40% of noise range [-1,1] is above 0.2
                if noise > threshold then
                    intensity = base_intensity
                else
                    intensity = base_intensity * 0.05
                end
            end

            -- Add bass floor
            intensity = clamp(intensity + bass_floor)

            -- Amplitude affects decay rate perception (high amp = hold longer)
            if audio.amplitude > 0.6 then
                intensity = math.max(intensity, base_intensity * 0.5)
            end

            local scale = config.base_scale + intensity * (config.max_scale - config.base_scale)
            local brightness = math.floor(intensity * 15)
            local glow = intensity > 0.4
            local material = intensity > 0.25 and "GLOWSTONE" or "WHITE_CONCRETE"

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = 0.5,
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = math.floor(col / math.max(1, cols) * 5) % 5,
                visible = true,
                glow = glow,
                brightness = brightness,
                material = material,
                interpolation = 0,
            }

            idx = idx + 1
        end
    end

    return normalize_entities(entities, n)
end
