-- Pattern metadata
name = "Moving Heads"
description = "Concert moving-head lights with sweeping beams and ballyhoo snap on beat"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    local num_fixtures = 8
    local points_per_beam = math.max(2, math.floor(n / num_fixtures))

    -- Initialize state
    if not state.time then
        state.time = 0
        state.snap_timer = 0
        state.fixtures = {}
        for i = 1, num_fixtures do
            state.fixtures[i] = {
                pan = 0,
                tilt = 0,
                target_pan = 0,
                target_tilt = 0,
            }
        end
    end

    state.time = state.time + dt

    -- Beat: trigger ballyhoo (all snap to center)
    if audio.beat then
        state.snap_timer = 1.0  -- full beat cycle to return
    end

    -- Sweep speed tied to BPM
    local sweep_speed = 0.5
    if audio.bpm > 0 then
        sweep_speed = audio.bpm / 200.0
    end

    -- Update fixture targets
    local snap_active = state.snap_timer > 0.7  -- snap during first 30% of timer
    state.snap_timer = math.max(0, state.snap_timer - dt * 2)

    for i = 1, num_fixtures do
        local fix = state.fixtures[i]
        local phase_offset = (i - 1) * 0.8

        if snap_active then
            -- Ballyhoo: all snap straight down center
            fix.target_pan = 0
            fix.target_tilt = -1.2  -- steep downward angle
        else
            -- Normal: Lissajous figure-8 sweep
            local t = state.time * sweep_speed
            fix.target_pan = math.sin(t + phase_offset) * 0.6
            fix.target_tilt = math.sin(t * 1.5 + phase_offset) * 0.4 - 0.8
        end

        -- Smooth toward target: fast during snap, slow during sweep
        local rate = snap_active and 0.6 or 0.12
        fix.pan = smooth(fix.pan, fix.target_pan, rate, dt)
        fix.tilt = smooth(fix.tilt, fix.target_tilt, rate, dt)
    end

    -- Beam reach scales with amplitude
    local beam_reach = 0.55 + audio.amplitude * 0.2

    local entities = {}
    local idx = 0

    for i = 1, num_fixtures do
        local fix = state.fixtures[i]

        -- Fixture position: top edge, evenly spaced
        local fixture_x = 0.12 + ((i - 1) / math.max(1, num_fixtures - 1)) * 0.76
        local fixture_y = 0.92
        local fixture_z = 0.5

        -- Beam direction from pan/tilt
        local dir_x = math.sin(fix.pan)
        local dir_y = fix.tilt  -- negative = downward
        local dir_z = math.cos(fix.pan) * 0.3

        -- Normalize
        local len = math.sqrt(dir_x * dir_x + dir_y * dir_y + dir_z * dir_z)
        if len > 0.001 then
            dir_x, dir_y, dir_z = dir_x / len, dir_y / len, dir_z / len
        end

        -- Per-fixture brightness driven by corresponding frequency band
        local fixture_band = ((i - 1) % 5)
        local band_val = audio.bands[fixture_band + 1] or 0
        local fixture_brightness = 10 + math.floor(band_val * 5)

        for j = 0, points_per_beam - 1 do
            if idx >= n then break end

            local t = (j + 1) / points_per_beam

            local x = fixture_x + dir_x * t * beam_reach
            local y = fixture_y + dir_y * t * beam_reach
            local z = fixture_z + dir_z * t * beam_reach

            -- Scale: slightly thicker beam than lasers, tapers at end
            local scale = lerp(0.12, 0.05, t)
            -- Snap pulse makes beams briefly larger
            if snap_active then
                scale = scale * 1.4
            end

            -- Intensity fades along beam
            local intensity = 1.0 - t * 0.4

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (fix.pan * 180 / math.pi + 90) % 360,
                band = fixture_band,
                visible = true,
                glow = true,
                brightness = math.min(15, math.floor(fixture_brightness * intensity)),
                material = "SEA_LANTERN",
                interpolation = 3,
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
