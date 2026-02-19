name = "Crown"
description = "Floating royal crown with 5 frequency-reactive spikes and glowing jewels"
recommended_entities = 96
category = "Epic"
static_camera = false
state = {}

local function allocate_counts(total, weights)
    local counts = {}
    local fracs = {}
    local weight_sum = 0
    for k, v in pairs(weights) do
        weight_sum = weight_sum + v
    end
    local used = 0
    for k, v in pairs(weights) do
        local exact = (total * v) / weight_sum
        local base = math.floor(exact)
        counts[k] = base
        fracs[#fracs + 1] = {k = k, frac = exact - base}
        used = used + base
    end
    table.sort(fracs, function(a, b) return a.frac > b.frac end)
    local rem = total - used
    local idx = 1
    while rem > 0 and #fracs > 0 do
        local key = fracs[idx].k
        counts[key] = counts[key] + 1
        rem = rem - 1
        idx = idx + 1
        if idx > #fracs then
            idx = 1
        end
    end
    return counts
end

function calculate(audio, config, dt)
    local n = config.entity_count or 0
    -- Initialize state
    if not state.rotation then
        state.rotation = 0
        state.time = 0
        state.beat_flash = 0
        state.hover = 0
        state.spike_impulse = {0, 0, 0, 0, 0}
        state.smooth_bands = {0, 0, 0, 0, 0}
        state.crown_scale = 1.0
    end

    local bands = audio.bands
    local amplitude = audio.amplitude or 0
    local beat = audio.beat
    local beat_phase = audio.beat_phase or 0

    -- Smooth bands
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], bands[i] or 0, 0.3, dt)
    end

    -- Beat response
    if beat then
        state.beat_flash = 1.0
        state.crown_scale = 1.15
        for i = 1, 5 do
            state.spike_impulse[i] = 0.06
        end
    end

    -- Decay state
    state.beat_flash = decay(state.beat_flash, 0.85, dt)
    state.crown_scale = smooth(state.crown_scale, 1.0, 0.08, dt)
    for i = 1, 5 do
        state.spike_impulse[i] = decay(state.spike_impulse[i], 0.9, dt)
    end

    -- Update time and rotation
    state.time = state.time + dt
    state.rotation = state.rotation + (0.3 + amplitude * 0.2) * dt

    -- Hover oscillation (subtle 3-axis bob for floating feel)
    local hover_y = math.sin(state.time * 1.5) * 0.02
    local hover_x = math.sin(state.time * 0.8) * 0.005
    local hover_z = math.cos(state.time * 0.7) * 0.005

    local entities = {}
    local idx = 1
    local base_y = 0.35 + hover_y
    local crown_s = state.crown_scale
    local base_scale = config.base_scale or 0.15
    local max_scale = config.max_scale or 0.5
    local entity_count = n

    -- Rotation helper: rotate (x, z) around center (0.5, 0.5) by state.rotation
    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)
    local function rotate_y(x, z)
        local dx = x - 0.5
        local dz = z - 0.5
        return 0.5 + (dx * cos_r - dz * sin_r) * crown_s,
               0.5 + (dx * sin_r + dz * cos_r) * crown_s
    end

    -- Beat brightness boost (applied to all entities)
    local beat_bright_boost = (state.beat_flash > 0.1) and math.floor(state.beat_flash * 3) or 0

    local function add_entity(x, y, z, scale, opts)
        if idx > entity_count then return end
        local rx, rz = rotate_y(x + hover_x, z + hover_z)
        -- Apply crown scale to y offset from base
        local ry = base_y + (y - base_y) * crown_s
        local raw_brightness = ((opts and opts.brightness) or 0) + beat_bright_boost
        entities[idx] = {
            id = string.format("block_%d", idx - 1),
            x = clamp(rx, 0, 1),
            y = clamp(ry, 0, 1),
            z = clamp(rz, 0, 1),
            scale = clamp(scale, 0, max_scale),
            rotation = 0,
            band = (opts and opts.band) or 0,
            visible = true,
            glow = (opts and opts.glow) or false,
            brightness = clamp(math.floor(raw_brightness), 0, 15),
            material = (opts and opts.material) or nil,
            interpolation = 2,
        }
        idx = idx + 1
    end

    local outer_radius = 0.15
    local inner_radius = 0.12

    -- Scale crown parts with entity budget (baseline 96).
    local parts = allocate_counts(entity_count, {
        ring = 24,
        spikes = 40,
        jewels = 5,
        detail = 16,
        filigree = 11,
    })
    local ring_count = math.max(1, parts.ring)
    local spike_count = math.max(0, parts.spikes)
    local jewel_count = math.max(0, math.min(5, parts.jewels))
    local jewel_overflow = math.max(0, parts.jewels - jewel_count)
    local detail_count = math.max(0, parts.detail + jewel_overflow)
    local filigree_count = math.max(0, parts.filigree)

    -- === BASE RING ===
    local ring_scale = base_scale * (0.8 + amplitude * 0.2)
    local ring_brightness = math.floor(8 + amplitude * 5)
    for i = 0, ring_count - 1 do
        local angle = i * 2 * math.pi / ring_count
        local x = 0.5 + math.cos(angle) * outer_radius
        local z = 0.5 + math.sin(angle) * outer_radius
        add_entity(x, base_y, z, ring_scale, {
            band = 0,
            material = "GOLD_BLOCK",
            brightness = ring_brightness,
        })
    end

    -- === 5 SPIKES (adaptive density) ===
    local base_points_per_spike = math.floor(spike_count / 5)
    local spike_rem = spike_count - base_points_per_spike * 5
    for k = 0, 4 do
        local points_this_spike = base_points_per_spike
        if k < spike_rem then
            points_this_spike = points_this_spike + 1
        end
        if points_this_spike > 0 then
            local base_angle = k * 2 * math.pi / 5
            local band_val = state.smooth_bands[k + 1]
            local spike_height = 0.12 + band_val * 0.08 + state.spike_impulse[k + 1]
            local spike_brightness = math.floor(7 + band_val * 6)

            for j = 0, points_this_spike - 1 do
                local t = j / math.max(1, points_this_spike - 1)
                -- Taper width from 0.04 at base to 0.005 at tip
                local width = 0.04 * (1 - t * 0.875)
                local y = base_y + t * spike_height
                -- Slight outward lean
                local lean = math.sin(t * math.pi) * 0.01
                local r = outer_radius + lean - t * 0.03

                local cx = 0.5 + math.cos(base_angle) * r
                local cz = 0.5 + math.sin(base_angle) * r

                -- Width perpendicular to radial direction
                local perp_angle = base_angle + math.pi / 2
                local wobble = math.sin(j * math.pi / 4)
                local wx = math.cos(perp_angle) * width * wobble
                local wz = math.sin(perp_angle) * width * wobble

                -- Tip glow: entities near tip glow when band is active
                local tip_glow = (t > 0.7) and (band_val > 0.5)

                local spike_scale = base_scale * (0.7 + band_val * 0.3) * (1 - t * 0.3)
                add_entity(cx + wx, y, cz + wz, spike_scale, {
                    band = k,
                    material = "GOLD_BLOCK",
                    brightness = spike_brightness,
                    glow = tip_glow,
                })
            end
        end
    end

    -- === JEWELS (adaptive, up to one per spike tip) ===
    local jewel_pulse = beat_pulse(beat_phase, 1, 3)
    local jewel_materials = {
        "DIAMOND_BLOCK", "EMERALD_BLOCK", "AMETHYST_BLOCK",
        "REDSTONE_BLOCK", "LAPIS_BLOCK",
    }
    for j = 0, jewel_count - 1 do
        local k = math.floor(j * 5 / math.max(1, jewel_count))
        local base_angle = k * 2 * math.pi / 5
        local band_val = state.smooth_bands[k + 1]
        local spike_height = 0.12 + band_val * 0.08 + state.spike_impulse[k + 1]

        local tip_r = outer_radius + math.sin(math.pi) * 0.01 - 0.03
        local tip_x = 0.5 + math.cos(base_angle) * tip_r
        local tip_z = 0.5 + math.sin(base_angle) * tip_r
        local tip_y = base_y + spike_height

        local jewel_brightness = math.floor(jewel_pulse * 15)
        add_entity(tip_x, tip_y, tip_z, base_scale * 0.9, {
            band = k,
            glow = true,
            brightness = jewel_brightness,
            material = jewel_materials[k + 1],
        })
    end

    -- === BAND DETAIL (adaptive split: inner ring + cross-braces) ===
    local band_y = base_y + 0.03
    local mid = state.smooth_bands[3]
    local mid_react = mid * 0.15
    local detail_scale = base_scale * (0.6 + mid_react)
    local detail_brightness = math.floor(6 + mid * 5)
    local inner_count = math.floor(detail_count / 2)
    local brace_count = detail_count - inner_count

    -- Inner ring
    for i = 0, inner_count - 1 do
        local angle = i * 2 * math.pi / math.max(1, inner_count)
        local x = 0.5 + math.cos(angle) * inner_radius
        local z = 0.5 + math.sin(angle) * inner_radius
        add_entity(x, band_y, z, detail_scale, {
            band = 3,
            material = "GOLD_BLOCK",
            brightness = detail_brightness,
        })
    end

    -- Cross-braces connecting inner to outer ring
    for i = 0, brace_count - 1 do
        local angle = i * 2 * math.pi / math.max(1, brace_count) + (math.pi / math.max(2, brace_count))
        local mid_r = (inner_radius + outer_radius) / 2
        local x = 0.5 + math.cos(angle) * mid_r
        local z = 0.5 + math.sin(angle) * mid_r
        local brace_y = base_y + 0.015
        add_entity(x, brace_y, z, detail_scale * 0.8, {
            band = 3,
            material = "GOLD_BLOCK",
            brightness = detail_brightness,
        })
    end

    -- === FILIGREE (adaptive arches between adjacent spikes) ===
    local filigree_brightness = math.floor(5 + amplitude * 4)
    local filigree_idx = 0
    local base_arch_points = math.floor(filigree_count / 5)
    local arch_rem = filigree_count - base_arch_points * 5
    for k = 0, 4 do
        local angle_a = k * 2 * math.pi / 5
        local angle_b = ((k + 1) % 5) * 2 * math.pi / 5

        local arch_points = base_arch_points
        if k < arch_rem then
            arch_points = arch_points + 1
        end
        for j = 1, arch_points do
            local t = j / (arch_points + 1)
            local angle = angle_a + (angle_b - angle_a) * t
            -- Handle wrap-around for last spike pair
            if k == 4 then
                local diff = angle_b + 2 * math.pi - angle_a
                angle = angle_a + diff * t
            end

            -- Parabolic arch: peaks at midpoint between spikes
            local arch_height = 0.025 * (4 * t * (1 - t))
            local x = 0.5 + math.cos(angle) * outer_radius
            local z = 0.5 + math.sin(angle) * outer_radius
            local y = base_y + arch_height

            add_entity(x, y, z, detail_scale * 0.7, {
                band = 0,
                material = "GOLD_BLOCK",
                brightness = filigree_brightness,
            })
            filigree_idx = filigree_idx + 1
        end
    end

    return normalize_entities(entities, n)
end
