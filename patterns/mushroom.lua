-- Pattern metadata
name = "Mushroom"
description = "Psychedelic toadstool with spots, gills, and spores"

-- Per-instance state
state = {
    rotation = 0.0,
    pulse = 0.0,
    glow = 0.0,
    breathe = 0.0,
    breathe_dir = 1,
    wobble = 0.0,
    spore_time = 0.0,
    grow = 1.0,
    cap_tilt = 0.0,
    stem_sway = 0.0,
    -- Pre-generate spot positions (golden angle spacing)
    spot_angles = {},
    spot_phis = {},
}

-- Initialize spot positions
local function init_spots()
    if #state.spot_angles == 0 then
        for i = 0, 6 do
            state.spot_angles[i + 1] = i * 2.39996
            state.spot_phis[i + 1] = 0.3 + (i % 3) * 0.2
        end
    end
end

-- Main calculation function
function calculate(audio, config, dt)
    init_spots()

    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Rotation - faster with amplitude
    state.rotation = state.rotation + (0.2 + audio.amplitude * 0.4) * dt

    -- Breathing animation (slow oscillation)
    state.breathe = state.breathe + 0.02 * state.breathe_dir
    if state.breathe > 1.0 then
        state.breathe_dir = -1
    elseif state.breathe < 0.0 then
        state.breathe_dir = 1
    end

    -- Wobble on beat
    if audio.is_beat then
        state.pulse = 1.0
        state.glow = 1.0
        state.wobble = 0.15 * audio.amplitude
        state.grow = 1.15
        state.cap_tilt = 0.08 + audio.amplitude * 0.08
    end
    state.pulse = state.pulse * 0.92
    state.glow = state.glow * 0.93
    state.wobble = state.wobble * 0.9
    state.grow = 1.0 + (state.grow - 1.0) * 0.95
    state.cap_tilt = state.cap_tilt * 0.92

    state.spore_time = state.spore_time + dt

    -- Calculate wobble offset
    local wobble_x = math.sin(state.spore_time * 2) * state.wobble
    local wobble_z = math.cos(state.spore_time * 2.5) * state.wobble * 0.7
    state.stem_sway = math.sin(state.spore_time * 1.1) * 0.03 + audio.bands[2] * 0.04
    local cap_offset_x = math.sin(state.rotation * 0.8) * state.cap_tilt + state.stem_sway * 0.6
    local cap_offset_z = math.cos(state.rotation * 0.6) * state.cap_tilt * 0.8 + state.stem_sway * 0.3

    -- Allocate entities: 20% stem, 42% cap, 15% gills, 10% spots, 7% rim, rest spores
    local stem_count = math.max(6, math.floor(n / 5))
    local cap_count = math.max(10, math.floor(n * 0.42))
    local gill_count = math.max(6, math.floor(n * 0.15))
    local spot_count = math.max(5, math.floor(n / 10))
    local rim_count = math.max(4, math.floor(n / 14))
    local spore_count = n - stem_count - cap_count - gill_count - spot_count - rim_count
    if spore_count < 0 then
        cap_count = math.max(6, cap_count + spore_count)
        spore_count = 0
    end

    local entity_idx = 0
    local breathe_scale = 1.0 + state.breathe * 0.05

    -- === STEM (organic with varied heights) ===
    local stem_radius = 0.06 * breathe_scale + state.pulse * 0.015
    local stem_height = 0.34 * state.grow

    for i = 0, stem_count - 1 do
        -- Use golden angle for even distribution around cylinder
        local golden_angle = 2.39996323
        local angle_base = i * golden_angle

        -- Vertical position with variation
        local base_t = i / stem_count
        local height_variation = math.sin(angle_base * 3) * 0.04 + math.cos(angle_base * 5) * 0.02
        local y_t = base_t + height_variation
        y_t = clamp(y_t, 0, 1)

        local ring_y = 0.06 + y_t * stem_height

        -- Radius varies with height: bulge at base, taper at top
        local taper = 1.0 - y_t * 0.3 + math.sin(y_t * math.pi) * 0.2
        local radius_variation = 1.0 + math.sin(i * 1.7) * 0.1
        local current_radius = stem_radius * taper * radius_variation

        -- Spiral twist increases with height
        local twist = y_t * math.pi * 0.6
        local angle = state.rotation + twist + angle_base

        local sway_amount = state.stem_sway * (0.2 + y_t * 0.8)
        local x = center + math.cos(angle) * current_radius + wobble_x * y_t + sway_amount
        local z = center + math.sin(angle) * current_radius + wobble_z * y_t + sway_amount * 0.6
        local y = ring_y

        -- Vary band assignment for color variation in stem
        local band_idx = 1 + (i % 2)  -- Alternate between bands 1 and 2 (0-indexed: 1 and 2)
        local base_s = config.base_scale * (0.6 + y_t * 0.3)  -- Smaller at base, larger at top
        local scale = base_s + audio.bands[band_idx + 1] * 0.2  -- +1 for 1-indexed bands

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
        }
        entity_idx = entity_idx + 1
    end

    -- === CAP (dome with classic toadstool shape) ===
    local cap_base_y = 0.08 + stem_height
    local cap_radius = (0.22 + state.pulse * 0.06 + audio.bands[1] * 0.04) * breathe_scale * state.grow
    local layers = math.max(4, math.floor(math.sqrt(cap_count)))
    local points_placed = 0

    for layer = 0, layers - 1 do
        if points_placed >= cap_count then
            break
        end

        local layer_t = layer / math.max(1, layers - 1)
        -- Classic toadstool: flatter on top, curves down at edges
        local phi = layer_t * (math.pi * 0.55)
        local layer_radius = cap_radius * math.sin(phi)
        -- Flatten top, curve down at edge
        local height_factor = math.cos(phi) * 0.5 + (1 - layer_t) * 0.15
        local ripple = math.sin(state.rotation * 1.8 + layer * 0.8) * audio.bands[3] * 0.025
        local layer_y = cap_base_y + cap_radius * height_factor + ripple

        local points_this_layer = math.max(4, math.floor(6 + layer * 4))

        for j = 0, points_this_layer - 1 do
            if points_placed >= cap_count then
                break
            end

            local angle = state.rotation * 0.4 + (j / points_this_layer) * math.pi * 2
            local x = center + math.cos(angle) * layer_radius + wobble_x + cap_offset_x
            local z = center + math.sin(angle) * layer_radius + wobble_z + cap_offset_z
            local y = layer_y

            local band_idx = 0  -- Cap uses bass
            local scale = config.base_scale * 1.1 + audio.bands[1] * 0.4 + state.glow * 0.25

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
            entity_idx = entity_idx + 1
            points_placed = points_placed + 1
        end
    end

    -- === RIM (lip around cap edge) ===
    local rim_radius = cap_radius * 0.92
    local rim_drop = 0.02 + audio.bands[3] * 0.03
    for r = 0, rim_count - 1 do
        local angle = state.rotation * 0.5 + (r / rim_count) * math.pi * 2
        local lip_wave = math.sin(angle * 2 + state.rotation) * 0.01
        local x = center + math.cos(angle) * rim_radius + cap_offset_x
        local z = center + math.sin(angle) * rim_radius + cap_offset_z
        local y = cap_base_y + cap_radius * 0.12 - rim_drop + lip_wave

        local band_idx = 2
        local scale = config.base_scale * 0.8 + audio.bands[3] * 0.25 + state.glow * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
        }
        entity_idx = entity_idx + 1
    end

    -- === GILLS (radial lines under cap) ===
    local gill_y = cap_base_y - 0.02 - audio.bands[2] * 0.015
    local num_gill_lines = math.max(4, math.floor(gill_count / 3))
    local points_per_gill = math.floor(gill_count / num_gill_lines)

    for g = 0, num_gill_lines - 1 do
        local gill_angle = state.rotation * 0.4 + (g / num_gill_lines) * math.pi * 2

        for p = 0, points_per_gill - 1 do
            if entity_idx >= stem_count + cap_count + gill_count then
                break
            end

            -- Gills extend from stem to cap edge
            local t = (p + 1) / (points_per_gill + 1)
            local r = stem_radius + t * (cap_radius * 0.85 - stem_radius)

            local x = center + math.cos(gill_angle) * r + wobble_x + cap_offset_x * 0.4
            local z = center + math.sin(gill_angle) * r + wobble_z + cap_offset_z * 0.4
            local y = gill_y - t * (0.03 + audio.bands[2] * 0.02)  -- Slight droop

            local band_idx = 3  -- Gills use mid-high
            local scale = config.base_scale * 0.5 + audio.bands[4] * 0.22

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
            entity_idx = entity_idx + 1
        end
    end

    -- === SPOTS (white dots on cap - classic Amanita) ===
    for s = 0, math.min(spot_count, #state.spot_angles) - 1 do
        local spot_phi = state.spot_phis[s + 1] * (math.pi * 0.4)
        local spot_r = cap_radius * 0.85 * math.sin(spot_phi)
        local spot_y = cap_base_y + cap_radius * math.cos(spot_phi) * 0.5 + 0.02

        local spot_angle = state.rotation * 0.4 + state.spot_angles[s + 1]
        local x = center + math.cos(spot_angle) * spot_r + wobble_x + cap_offset_x
        local z = center + math.sin(spot_angle) * spot_r + wobble_z + cap_offset_z
        local y = spot_y

        local band_idx = 4  -- Spots use high freq
        local scale = config.base_scale * 0.9 + state.glow * 0.4 + audio.bands[5] * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
        }
        entity_idx = entity_idx + 1
    end

    -- === SPORES (floating particles rising up) ===
    for sp = 0, spore_count - 1 do
        -- Each spore has unique phase
        local phase = sp * 1.618 + state.spore_time
        local spore_life = (phase % 3.0) / 3.0  -- 0-1 lifecycle

        -- Spiral upward from cap
        local spore_angle = phase * 2.0 + sp
        local spore_r = 0.05 + spore_life * 0.15 + math.sin(phase * 3) * 0.03
        local spore_y = cap_base_y + 0.1 + spore_life * 0.4 + audio.bands[5] * 0.05

        local x = center + math.cos(spore_angle) * spore_r + wobble_x * 0.5 + cap_offset_x * 0.6
        local z = center + math.sin(spore_angle) * spore_r + wobble_z * 0.5 + cap_offset_z * 0.6
        local y = spore_y

        local band_idx = 4  -- Spores use high-mid
        -- Fade in then out
        local fade = math.sin(spore_life * math.pi)
        local scale = config.base_scale * 0.3 * fade + audio.bands[5] * 0.15 * fade

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, math.max(0.01, scale)),
            band = band_idx,
            visible = fade > 0.1,
        }
        entity_idx = entity_idx + 1
    end

    return entities
end
