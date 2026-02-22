-- Pattern metadata
name = "Drop Sequence"
description = "EDM build-up tension over 16 beats, then explosive drop — the tension-release cycle"
category = "Mainstage"
static_camera = true
recommended_entities = 80

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.phase then
        state.phase = "build"
        state.build_progress = 0
        state.build_beats = 0
        state.drop_timer = 0
        state.orbit_phase = 0
        state.time = 0
        state.positions = {}
        state.velocities = {}
        state.flash = 0

        -- Initialize positions on fibonacci sphere (cache for reuse)
        state.sphere = fibonacci_sphere(n)
        for i = 1, n do
            state.positions[i] = {
                x = 0.5 + state.sphere[i].x * 0.4,
                y = 0.5 + state.sphere[i].y * 0.4,
                z = 0.5 + state.sphere[i].z * 0.4,
            }
            state.velocities[i] = {x = 0, y = 0, z = 0}
        end
    end

    state.time = state.time + dt

    -- Target build beats based on BPM
    local target_beats = 16
    if audio.bpm > 170 then
        target_beats = 8
    elseif audio.bpm > 140 then
        target_beats = 12
    end

    -- Beat handling
    if audio.is_beat then
        if state.phase == "build" then
            state.build_beats = state.build_beats + 1
            state.build_progress = math.min(1.0, state.build_beats / target_beats)

            -- Check for drop trigger
            if state.build_progress >= 1.0 and (audio.beat_intensity or 0) > 0.35 then
                state.phase = "drop"
                state.drop_timer = 0
                state.flash = 1.0

                -- Generate explosion velocities using cached sphere
                for i = 1, n do
                    local speed = 1.2 + math.random() * 0.6
                    -- Amplitude scales explosion
                    speed = speed * (0.8 + audio.amplitude * 0.5)
                    state.velocities[i] = {
                        x = state.sphere[i].x * speed,
                        y = state.sphere[i].y * speed + 0.3,  -- slight upward bias
                        z = state.sphere[i].z * speed,
                    }
                end
            end

        elseif state.phase == "drop" then
            state.flash = 0.8  -- re-flash on beats during drop
        end
    end

    -- Phase-specific update
    if state.phase == "build" then
        -- Orbit entities in a contracting sphere
        local build_t = state.build_progress
        local radius = lerp(0.35, 0.04, build_t)
        local orbit_speed = 1.0 + build_t * 5.0

        state.orbit_phase = state.orbit_phase + orbit_speed * dt

        -- Bass wobble on radius
        local wobble = audio.bands[1] * 0.03

        for i = 1, n do
            local r = radius + wobble * math.sin(i * 0.5 + state.time * 3)

            -- Apply orbit rotation using cached sphere points
            local sx = state.sphere[i].x * r
            local sy = state.sphere[i].y * r
            local sz = state.sphere[i].z * r

            -- Rotate around Y axis
            local angle = state.orbit_phase + (i / n) * 0.3
            local cos_a = math.cos(angle)
            local sin_a = math.sin(angle)
            local rx = sx * cos_a - sz * sin_a
            local rz = sx * sin_a + sz * cos_a

            state.positions[i].x = smooth(state.positions[i].x, 0.5 + rx, 0.2, dt)
            state.positions[i].y = smooth(state.positions[i].y, 0.5 + sy, 0.2, dt)
            state.positions[i].z = smooth(state.positions[i].z, 0.5 + rz, 0.2, dt)
        end

    elseif state.phase == "drop" then
        state.drop_timer = state.drop_timer + dt
        state.flash = decay(state.flash, 0.85, dt)

        -- Update positions with explosion velocities + gravity
        for i = 1, n do
            local v = state.velocities[i]
            local p = state.positions[i]

            p.x = p.x + v.x * dt
            p.y = p.y + v.y * dt
            p.z = p.z + v.z * dt

            -- Gravity
            v.y = v.y - 0.5 * dt

            -- Velocity decay (air resistance)
            v.x = v.x * (1.0 - 2.0 * dt)
            v.z = v.z * (1.0 - 2.0 * dt)
        end

        -- Reset after drop completes
        if state.drop_timer > 2.5 then
            state.phase = "build"
            state.build_progress = 0
            state.build_beats = 0
            state.drop_timer = 0
            state.orbit_phase = 0
            state.flash = 0

            -- Reset positions to sphere using cached points
            for i = 1, n do
                state.positions[i] = {
                    x = 0.5 + state.sphere[i].x * 0.35,
                    y = 0.5 + state.sphere[i].y * 0.35,
                    z = 0.5 + state.sphere[i].z * 0.35,
                }
                state.velocities[i] = {x = 0, y = 0, z = 0}
            end
        end
    end

    -- Render entities
    local entities = {}
    local build_t = state.build_progress

    for i = 1, n do
        local p = state.positions[i]

        local scale, brightness, glow, material, visible, interp
        local band = (i - 1) % 5

        if state.phase == "build" then
            -- Scale shrinks as entities compress
            scale = config.base_scale * (1.0 - build_t * 0.5)

            -- Brightness ramps up with build
            brightness = math.floor(5 + build_t * 10)

            -- Tension flicker at >70% progress
            visible = true
            if build_t > 0.7 then
                local flicker = simple_noise(i, math.floor(state.time * 15), 0)
                visible = flicker > (build_t - 0.7) * 2  -- more flicker as progress increases
            end

            glow = build_t > 0.5
            material = build_t > 0.8 and "GLOWSTONE" or "WHITE_CONCRETE"
            interp = 5  -- smooth orbiting

        else  -- drop
            local drop_life = math.max(0, 1.0 - state.drop_timer / 2.5)

            scale = config.base_scale * (0.5 + state.flash * 1.0) * drop_life
            brightness = math.floor(clamp(state.flash + drop_life * 0.5) * 15)
            glow = drop_life > 0.3
            material = drop_life > 0.5 and "GLOWSTONE" or "WHITE_CONCRETE"
            visible = drop_life > 0.05
            interp = 1  -- fast for explosion
        end

        entities[i] = {
            id = string.format("block_%d", i - 1),
            x = clamp(p.x),
            y = clamp(p.y),
            z = clamp(p.z),
            scale = math.min(config.max_scale, math.max(0.01, scale)),
            rotation = (state.orbit_phase * 50 + i * 10) % 360,
            band = band,
            visible = visible,
            glow = glow,
            brightness = math.min(15, brightness),
            material = material,
            interpolation = interp,
        }
    end

    return entities
end
