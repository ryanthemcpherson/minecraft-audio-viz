-- Pattern metadata
name = "Laser Fan"
description = "Floor-origin laser beams sweep in synchronized arcs and freeze on beat"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    local num_beams = 8
    local points_per_beam = math.max(2, math.floor(n / num_beams))

    -- Initialize state
    if not state.sweep_phase then
        state.sweep_phase = 0
        state.freeze_timer = 0
        state.smooth_bass = 0
        state.smooth_high = 0
    end

    -- Smooth inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.35, dt)
    state.smooth_high = smooth(state.smooth_high, audio.bands[5], 0.3, dt)

    -- Beat: freeze sweep momentarily
    if audio.beat then
        state.freeze_timer = 0.15
    end

    -- Update freeze timer
    if state.freeze_timer > 0 then
        state.freeze_timer = state.freeze_timer - dt
    else
        -- Advance sweep, speed tied to BPM
        local sweep_speed = 1.0
        if audio.bpm > 0 then
            sweep_speed = audio.bpm / 128.0
        end
        state.sweep_phase = state.sweep_phase + sweep_speed * dt
    end

    -- Fan geometry
    -- Fan half-angle widens with bass
    local fan_half = 0.3 + state.smooth_bass * 0.5
    -- Beam length scales with amplitude
    local beam_length = 0.4 + audio.amplitude * 0.3

    -- Origin point: bottom center
    local ox, oy, oz = 0.5, 0.1, 0.5

    -- Sweep oscillation
    local sweep_offset = math.sin(state.sweep_phase * math.pi * 2) * 0.3

    local entities = {}
    local idx = 0

    for b = 0, num_beams - 1 do
        -- Base angle for this beam within the fan
        local beam_frac = (b / math.max(1, num_beams - 1)) - 0.5  -- -0.5 to 0.5
        local beam_angle = beam_frac * fan_half * 2 + sweep_offset

        -- Each beam has a slight independent phase offset
        local phase_offset = math.sin(state.sweep_phase * 1.7 + b * 0.8) * 0.1
        beam_angle = beam_angle + phase_offset

        -- Beam direction: upward and outward
        -- angle controls left-right spread, beam goes upward
        local dir_x = math.sin(beam_angle)
        local dir_y = 1.0  -- always upward
        local dir_z = math.cos(beam_angle) * 0.3  -- slight depth

        -- Normalize direction
        local len = math.sqrt(dir_x * dir_x + dir_y * dir_y + dir_z * dir_z)
        dir_x, dir_y, dir_z = dir_x / len, dir_y / len, dir_z / len

        -- Beam visibility: dimmer beams disappear at low amplitude
        local beam_threshold = (b % 4) / 8  -- stagger thresholds
        local beam_visible = audio.amplitude > beam_threshold

        -- Band per beam for color variety
        local beam_band = b % 5

        for j = 0, points_per_beam - 1 do
            if idx >= n then break end

            local t = (j + 1) / points_per_beam
            local x = ox + dir_x * t * beam_length
            local y = oy + dir_y * t * beam_length
            local z = oz + dir_z * t * beam_length

            -- Scale tapers: thick at base, thin at tip
            local base_scale = lerp(0.15, 0.04, t)
            -- High frequencies add shimmer
            local shimmer = state.smooth_high * simple_noise(b, j, math.floor(state.sweep_phase * 10)) * 0.03
            local scale = base_scale + shimmer

            -- Intensity fades slightly along beam
            local intensity = 1.0 - t * 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (beam_angle * 180 / math.pi + 90) % 360,
                band = beam_band,
                visible = beam_visible,
                glow = true,
                brightness = math.floor(intensity * 15),
                material = "END_ROD",
                interpolation = 2,
            }

            idx = idx + 1
        end
    end

    -- Fill remaining as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
