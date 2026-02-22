-- Pattern metadata
name = "Shockwave"
description = "Expanding ring pulses radiate from center on each beat"
category = "Mainstage"
static_camera = true
recommended_entities = 80

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.waves then
        state.waves = {}
        state.time = 0
    end

    state.time = state.time + dt

    -- Spawn new wave on beat (max 4 concurrent)
    if audio.is_beat then
        local active = 0
        for _, w in ipairs(state.waves) do
            if w.life > 0 then active = active + 1 end
        end
        if active < 4 then
            state.waves[#state.waves + 1] = {
                radius = 0,
                speed = 0.6 + (audio.beat_intensity or 0.5) * 0.6,
                life = 1.0,
                band = math.floor(math.random() * 5),
                y_base = 0.5,
                vertical = math.random() > 0.7,  -- 30% chance of vertical wave
            }
        end
    end

    -- Update waves
    for i = #state.waves, 1, -1 do
        local w = state.waves[i]
        w.radius = w.radius + w.speed * dt
        -- Faster fade as wave reaches edges
        local edge_factor = 1.0 + w.radius * 3.0
        w.life = w.life - dt * edge_factor
        -- Slight speed decay for natural deceleration
        w.speed = w.speed * (1.0 - 0.3 * dt)

        -- Remove dead waves
        if w.life <= 0 or w.radius > 0.55 then
            table.remove(state.waves, i)
        end
    end

    -- Count active waves
    local active_waves = {}
    for _, w in ipairs(state.waves) do
        if w.life > 0 then
            active_waves[#active_waves + 1] = w
        end
    end

    local entities = {}
    local idx = 0

    if #active_waves == 0 then
        -- No active waves — show dim center point as idle indicator
        for i = 0, n - 1 do
            entities[#entities + 1] = {
                id = string.format("block_%d", i),
                x = 0.5,
                y = 0.5,
                z = 0.5,
                scale = 0,
                rotation = 0,
                band = 0,
                visible = false,
            }
        end
        return entities
    end

    -- Distribute entities across active waves
    local per_wave = math.max(4, math.floor(n / #active_waves))

    for wi, w in ipairs(active_waves) do
        local count = per_wave
        if wi == #active_waves then
            count = n - idx  -- remaining entities
        end
        if count <= 0 then break end

        local ring_thickness = 0.02 + audio.bands[1] * 0.02
        local high_wobble = audio.bands[5] * 0.05

        for j = 0, count - 1 do
            if idx >= n then break end

            local angle = (j / count) * math.pi * 2
            -- Slight radius variation for ring thickness
            local r_offset = (j % 3 - 1) * ring_thickness
            local r = w.radius + r_offset

            local x, y, z
            if w.vertical then
                -- Vertical ring: expands in x-y plane
                x = 0.5 + math.cos(angle) * r
                y = 0.5 + math.sin(angle) * r
                z = 0.5 + math.sin(angle * 3 + state.time * 5) * high_wobble
            else
                -- Horizontal ring: expands in x-z plane
                x = 0.5 + math.cos(angle) * r
                z = 0.5 + math.sin(angle) * r
                y = w.y_base + math.sin(angle * 3 + state.time * 5) * high_wobble
            end

            local intensity = w.life
            local scale = config.base_scale * (0.5 + intensity * 1.0)
            local brightness = math.floor(clamp(intensity) * 15)

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (angle * 180 / math.pi) % 360,
                band = w.band,
                visible = true,
                glow = true,
                brightness = brightness,
                material = "SEA_LANTERN",
                interpolation = 1,
            }

            idx = idx + 1
        end
    end

    -- Fill remaining entities as invisible
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
