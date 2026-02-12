name = "Galaxy"
description = "Spiral galaxy - cosmic visualization"
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.arm_twist = state.arm_twist or 0
    state.core_pulse = state.core_pulse or 0

    -- Galaxy rotation
    state.rotation = state.rotation + (0.2 + audio.peak * 0.3) * dt

    -- Arm twist increases with highs
    state.arm_twist = 2.3 + audio.bands[5] * 2.2 + audio.bands[4] * 0.8

    -- Core pulse on beat
    if audio.beat then
        state.core_pulse = 1.0
    end
    state.core_pulse = decay(state.core_pulse, 0.9, dt)

    -- Core entities (dense center) - 20%
    local core_count = math.floor(n / 5)

    -- Spiral arm entities - 80%
    local arm_count = n - core_count
    local num_arms = 2
    if (audio.bands[4] + audio.bands[5]) > 1.0 then
        num_arms = 3
    end

    -- === CORE ===
    for i = 0, core_count - 1 do
        local seed_angle = (i * 137.5) * math.pi / 180
        local seed_radius = math.sqrt(i / core_count) * 0.08
        seed_radius = seed_radius + audio.bands[3] * 0.02

        local angle = seed_angle + state.rotation * 2
        local radius = seed_radius * (1.0 + state.core_pulse * 0.3)

        local x = center + math.cos(angle) * radius
        local z = center + math.sin(angle) * radius
        local y = center + math.sin(seed_angle * 3) * 0.02

        local band_idx = i % 5
        local scale = config.base_scale * 1.2 + audio.bands[band_idx + 1] * 0.3 + state.core_pulse * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", i),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
        }
    end

    -- === SPIRAL ARMS ===
    local per_arm = math.floor(arm_count / num_arms)

    for arm = 0, num_arms - 1 do
        local arm_offset = arm * math.pi

        for j = 0, per_arm - 1 do
            local idx = core_count + arm * per_arm + j
            if idx >= n then
                break
            end

            -- Position along arm
            local t = j / per_arm

            -- Logarithmic spiral
            local radius = 0.08 + t * 0.32

            -- Spiral angle
            local spiral_angle = arm_offset + state.rotation + t * math.pi * state.arm_twist

            -- Scatter
            local scatter = math.sin(j * 0.5) * 0.02
            radius = radius + scatter + audio.bands[2] * 0.02 * (1 - t)

            local x = center + math.cos(spiral_angle) * radius
            local z = center + math.sin(spiral_angle) * radius

            -- Slight vertical variation
            local y = center + math.sin(spiral_angle * 2) * 0.04 * t

            local band_idx = j % 5
            local bass_react = (1 - t) * audio.bands[1] * 0.3
            local high_react = t * audio.bands[5] * 0.3
            local scale = config.base_scale + bass_react + high_react
            scale = scale + state.core_pulse * 0.15 * (1 - t)

            if audio.beat then
                scale = scale * 1.15
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
end
