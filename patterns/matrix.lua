-- Pattern metadata
name = "Fountain"
description = "Upward spray with gravity arcs"
category = "Original"
static_camera = false

-- Per-instance state
state = {
    particles = {},  -- array of {x, y, z, vx, vy, vz, age, spin}
}

-- Spawn a new fountain particle
local function spawn_particle(audio, idx)
    local center = 0.5

    -- Random upward velocity
    local speed = 0.015 + audio.amplitude * 0.02
    if audio.is_beat then
        speed = speed * 1.5
    end

    local angle = math.random() * math.pi * 2
    local band_idx = (idx % 5) + 1
    local spread = 0.003 + audio.bands[band_idx] * 0.003

    local vx = math.cos(angle) * spread
    local vz = math.sin(angle) * spread
    local vy = speed + math.random() * 0.008

    local spawn_radius = 0.02 + audio.bands[2] * 0.03
    local x = center + math.cos(angle) * spawn_radius
    local z = center + math.sin(angle) * spawn_radius
    local spin = (math.random() * 2 - 1)  -- -1 to 1

    return { x, 0.05, z, vx, vy, vz, 0.0, spin }
end

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize particles
    if #state.particles ~= n then
        state.particles = {}
        for i = 1, n do
            state.particles[i] = spawn_particle(audio, i - 1)
        end
    end

    local entities = {}
    local gravity = 0.015
    local drag = 0.985

    for i = 1, n do
        local p = state.particles[i]
        local x, y, z, vx, vy, vz, age, spin = p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]

        -- Update physics
        vy = vy - gravity * (dt / 0.016)
        age = age + dt

        -- Add gentle swirl and drag for a more fluid fountain
        local swirl = 0.02 + audio.bands[3] * 0.05
        vx = vx + (-(z - 0.5)) * swirl
        vz = vz + (x - 0.5) * swirl

        local spin_speed = (0.6 + audio.bands[4] * 1.2) * spin
        local cos_s = math.cos(spin_speed * dt)
        local sin_s = math.sin(spin_speed * dt)
        local new_vx = vx * cos_s - vz * sin_s
        local new_vz = vx * sin_s + vz * cos_s
        vx = new_vx
        vz = new_vz

        vx = decay(vx, drag, dt)
        vz = decay(vz, drag, dt)
        x = x + vx * dt * 60
        y = y + vy * dt * 60
        z = z + vz * dt * 60

        -- Respawn if below ground or on beat
        if y < 0 or (audio.is_beat and math.random() < 0.3) then
            local sp = spawn_particle(audio, i - 1)
            x, y, z, vx, vy, vz, age, spin = sp[1], sp[2], sp[3], sp[4], sp[5], sp[6], sp[7], sp[8]
        end

        state.particles[i] = { x, y, z, vx, vy, vz, age, spin }

        local band_idx = ((i - 1) % 5) + 1
        local scale = config.base_scale + audio.bands[band_idx] * 0.4

        -- Scale based on height (bigger at peak)
        local height_scale = 1.0
        if y > 0.5 then
            height_scale = 1.0 + (y - 0.5) * 0.5
        end
        scale = scale * height_scale
        scale = scale + math.min(0.3, age * 0.1)

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
