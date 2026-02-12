-- Pattern metadata
name = "Black Hole"
description = "Accretion disk with jets - gravity visualization"
category = "Cosmic"
static_camera = false

-- Per-instance state
state = {
    particles = {},       -- {r, theta, dr, layer}
    jet_intensity = 0.0,
    rotation = 0.0,
    warp = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Allocate: 75% disk, 25% jets
    local disk_count = math.floor(n * 0.75)
    local jet_count = n - disk_count

    -- Initialize particles
    if #state.particles ~= disk_count then
        state.particles = {}
        for i = 1, disk_count do
            local r = 0.1 + math.random() * 0.3
            local theta = math.random() * math.pi * 2
            local dr = 0.0
            local layer = math.random(0, 2)
            state.particles[i] = {r = r, theta = theta, dr = dr, layer = layer}
        end
    end

    -- Jet intensity on beat
    if audio.is_beat then
        state.jet_intensity = 1.0
        state.warp = 0.5
    end
    state.jet_intensity = decay(state.jet_intensity, 0.92, dt)
    state.warp = decay(state.warp, 0.95, dt)

    -- Accretion rate from bass
    local accretion_rate = 0.001 + audio.bands[1] * 0.003 + audio.bands[2] * 0.002

    state.rotation = state.rotation + 0.5 * dt

    -- === ACCRETION DISK ===
    for i = 1, disk_count do
        local p = state.particles[i]
        local r = p.r
        local theta = p.theta
        local layer = p.layer

        -- Keplerian velocity: v proportional to 1/sqrt(r) (faster when closer)
        local orbital_speed = 0.5 / math.sqrt(math.max(0.05, r))
        theta = theta + orbital_speed * dt

        -- Spiral inward (accretion)
        local dr = -accretion_rate * (1.0 + math.random() * 0.5)
        r = r + dr

        -- Respawn if too close to center
        if r < 0.05 then
            r = 0.35 + math.random() * 0.1
            theta = math.random() * math.pi * 2
            layer = math.random(0, 2)
        end

        state.particles[i].r = r
        state.particles[i].theta = theta
        state.particles[i].layer = layer

        -- Position
        local x = center + math.cos(theta) * r
        local z = center + math.sin(theta) * r

        -- Thin disk with slight vertical variation
        local layer_offset = (layer - 1) * 0.02
        local y = center + layer_offset + math.sin(theta * 4) * 0.01

        -- Warp effect near center (light bending)
        if r < 0.15 then
            local warp_strength = (0.15 - r) / 0.15 * state.warp
            x = center + (x - center) * (1.0 - warp_strength * 0.3)
            z = center + (z - center) * (1.0 - warp_strength * 0.3)
        end

        local band_idx = (i - 1) % 5
        -- Inner disk hotter (higher frequencies)
        if r < 0.15 then
            band_idx = 3 + ((i - 1) % 2)  -- high-mid and high bands (0-indexed: 3,4)
        elseif r < 0.25 then
            band_idx = 2 + ((i - 1) % 2)  -- mid and high-mid bands (0-indexed: 2,3)
        end

        local scale = config.base_scale * (0.5 + (0.4 - r))  -- Larger near center
        scale = scale + audio.bands[band_idx + 1] * 0.2

        if audio.is_beat and r < 0.2 then
            scale = scale * 1.4
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
        }
    end

    -- === RELATIVISTIC JETS ===
    local jet_height = 0.3 + state.jet_intensity * 0.15 + audio.bands[1] * 0.1
    local points_per_jet = math.floor(jet_count / 2)

    for jet = 0, 1 do
        local direction = 1
        if jet == 1 then direction = -1 end

        for j = 0, points_per_jet - 1 do
            local idx = disk_count + jet * points_per_jet + j
            if idx >= n then break end

            -- Position along jet
            local t = (j + 1) / points_per_jet
            local jet_r = 0.02 + t * 0.04  -- Slight cone shape
            local jet_y = center + direction * (0.05 + t * jet_height)

            -- Spiral in jet
            local jet_angle = state.rotation * 3 + t * math.pi * 2 + jet * math.pi
            local x = center + math.cos(jet_angle) * jet_r
            local z = center + math.sin(jet_angle) * jet_r
            local y = jet_y

            local band_idx = 3 + (j % 2)  -- Jets are hot = high-mid and high bands
            local scale = config.base_scale * (1.0 - t * 0.5) * (0.5 + state.jet_intensity)
            scale = scale + audio.bands[5] * 0.3

            local visible = state.jet_intensity > 0.1 or audio.bands[1] > 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = visible,
            }
        end
    end

    return entities
end
