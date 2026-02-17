-- Pattern metadata
name = "Fireflies"
description = "Swarm of synchronized flashing lights"
recommended_entities = 40
category = "Organic"
static_camera = false

-- Per-instance state
state = {
    fireflies = {},          -- {x, y, z, vx, vy, vz, glow_phase, group}
    group_flash = {0.0, 0.0, 0.0, 0.0},  -- Flash intensity per group (1-indexed)
    cascade_timer = 0.0,
    cascade_active = false,
    drift_time = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count
    local center = 0.5

    -- Initialize fireflies
    if #state.fireflies ~= n then
        state.fireflies = {}
        for i = 1, n do
            state.fireflies[i] = {
                x = center + (math.random() - 0.5) * 0.6,
                y = 0.2 + math.random() * 0.6,
                z = center + (math.random() - 0.5) * 0.6,
                vx = (math.random() - 0.5) * 0.01,
                vy = (math.random() - 0.5) * 0.01,
                vz = (math.random() - 0.5) * 0.01,
                glow_phase = math.random() * math.pi * 2,
                group = ((i - 1) % 4) + 1,  -- Groups 1-4 (1-indexed)
            }
        end
    end

    state.drift_time = state.drift_time + dt

    -- Beat triggers cascade
    if audio.is_beat then
        state.group_flash[1] = 1.0
        state.cascade_active = true
        state.cascade_timer = 0.0
    end

    -- Cascade to other groups with delays (100ms = 0.1s)
    if state.cascade_active then
        state.cascade_timer = state.cascade_timer + dt
        if state.cascade_timer > 0.1 and state.group_flash[2] < 0.5 then
            state.group_flash[2] = 1.0
        end
        if state.cascade_timer > 0.2 and state.group_flash[3] < 0.5 then
            state.group_flash[3] = 1.0
        end
        if state.cascade_timer > 0.3 and state.group_flash[4] < 0.5 then
            state.group_flash[4] = 1.0
        end
        if state.cascade_timer > 0.5 then
            state.cascade_active = false
        end
    end

    -- Decay group flashes
    for g = 1, 4 do
        state.group_flash[g] = decay(state.group_flash[g], 0.92, dt)
    end

    local entities = {}

    for i = 1, n do
        local ff = state.fireflies[i]
        local x, y, z = ff.x, ff.y, ff.z
        local vx, vy, vz = ff.vx, ff.vy, ff.vz
        local glow_phase = ff.glow_phase
        local group = ff.group

        -- Organic drifting motion (Perlin-like smooth random walk)
        local t = state.drift_time + i * 0.1
        local ax = math.sin(t * 0.5 + i) * 0.0002
        local ay = math.sin(t * 0.3 + i * 1.3) * 0.0001
        local az = math.cos(t * 0.4 + i * 0.7) * 0.0002

        -- Center bias: cubic pull toward center, strong near edges
        local cx, cy, cz = 0.5 - x, 0.5 - y, 0.5 - z
        local dx, dy, dz = math.abs(cx), math.abs(cy), math.abs(cz)
        local bias = 0.008
        ax = ax + cx * dx * dx * bias
        ay = ay + cy * dy * dy * bias
        az = az + cz * dz * dz * bias

        -- Update velocity with drift + bias acceleration
        vx = decay(vx, 0.98, dt) + ax * (dt / 0.016)
        vy = decay(vy, 0.98, dt) + ay * (dt / 0.016)
        vz = decay(vz, 0.98, dt) + az * (dt / 0.016)

        -- Clamp velocity
        local max_v = 0.015
        vx = clamp(vx, -max_v, max_v)
        vy = clamp(vy, -max_v, max_v)
        vz = clamp(vz, -max_v, max_v)

        -- Update position
        x = x + vx * (dt / 0.016)
        y = y + vy * (dt / 0.016)
        z = z + vz * (dt / 0.016)

        -- Hard boundary: clamp position and kill outward velocity
        if x < 0.1 then x = 0.1; if vx < 0 then vx = 0 end end
        if x > 0.9 then x = 0.9; if vx > 0 then vx = 0 end end
        if y < 0.1 then y = 0.1; if vy < 0 then vy = 0 end end
        if y > 0.9 then y = 0.9; if vy > 0 then vy = 0 end end
        if z < 0.1 then z = 0.1; if vz < 0 then vz = 0 end end
        if z > 0.9 then z = 0.9; if vz > 0 then vz = 0 end end

        -- Update glow phase
        glow_phase = glow_phase + (0.05 + math.random() * 0.02) * (dt / 0.016)

        -- Store updated values
        state.fireflies[i].x = x
        state.fireflies[i].y = y
        state.fireflies[i].z = z
        state.fireflies[i].vx = vx
        state.fireflies[i].vy = vy
        state.fireflies[i].vz = vz
        state.fireflies[i].glow_phase = glow_phase

        -- Individual glow cycle
        local individual_glow = (math.sin(glow_phase) + 1) * 0.3  -- 0 to 0.6

        -- Group flash overlay
        local group_glow = state.group_flash[group]

        -- Combined glow
        local total_glow = individual_glow + group_glow

        -- Audio reactivity - fireflies respond to amplitude
        total_glow = total_glow + audio.amplitude * 0.2

        -- Band based on group (groups 1-4 map to bands 0-3, covering bass through high-mid)
        local band_idx = group - 1  -- groups 1-4 → bands 0-3 (0-indexed output)

        local scale = config.base_scale * 0.5 + total_glow * 0.6
        scale = scale + audio.bands[group] * 0.2  -- bands[1]=bass, [2]=low, [3]=mid, [4]=high-mid

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = x,
            y = y,
            z = z,
            scale = math.min(config.max_scale, math.max(0.05, scale)),
            band = band_idx,
            visible = total_glow > 0.15,  -- Only visible when glowing enough
        }
    end

    return entities
end
