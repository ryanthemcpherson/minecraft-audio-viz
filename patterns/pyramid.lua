name = "Pyramid"
description = "Egyptian pyramid - inverts on drops"
recommended_entities = 64
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.invert = state.invert or 0
    state.hover = state.hover or 0

    -- Slow majestic rotation
    state.rotation = state.rotation + (0.3 + audio.peak * 0.35) * dt

    -- Invert on strong beats
    if audio.is_beat and audio.beat_intensity > 0.6 then
        if state.invert > 0.5 then
            state.invert = 0
        else
            state.invert = 1
        end
    end

    -- Hover with bass
    local target_hover = audio.bands[1] * 0.1 + audio.bands[2] * 0.05
    state.hover = smooth(state.hover, target_hover, 0.1, dt)

    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)

    -- Pyramid geometry
    local half = 0.35
    local base_y = 0.15 + state.hover
    local apex_y = 0.85 + state.hover

    if state.invert > 0.5 then
        base_y, apex_y = apex_y, base_y
    end

    -- 4 base corners (rotated)
    local raw = {
        {-half, -half},
        { half, -half},
        { half,  half},
        {-half,  half},
    }
    local corners = {}
    for i, c in ipairs(raw) do
        corners[i] = {
            c[1] * cos_r - c[2] * sin_r,
            c[1] * sin_r + c[2] * cos_r,
        }
    end

    local entity_idx = 0

    local function place(rx, rz, y, band, extra_scale)
        if entity_idx >= n then return end
        local scale = config.base_scale + audio.bands[band + 1] * 0.4 + (extra_scale or 0)
        if audio.is_beat then scale = scale * 1.25 end
        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(center + rx),
            y = clamp(y),
            z = clamp(center + rz),
            scale = math.min(config.max_scale, scale),
            rotation = (state.rotation * 57.3) % 360,
            band = band,
            visible = true,
        }
        entity_idx = entity_idx + 1
    end

    -- Entity budget: base edges 35%, slant edges 30%, horizontal layers 35%
    local base_per_edge = math.max(2, math.floor(n * 0.088))
    local slant_per_edge = math.max(2, math.floor(n * 0.075))
    local edge_total = base_per_edge * 4 + slant_per_edge * 4
    local layer_budget = math.max(0, n - edge_total)

    -- === BASE EDGES (prominent, bass-reactive) ===
    for edge = 1, 4 do
        local c1 = corners[edge]
        local c2 = corners[(edge % 4) + 1]
        for i = 0, base_per_edge - 1 do
            local t = base_per_edge > 1 and (i / (base_per_edge - 1)) or 0.5
            local rx = lerp(c1[1], c2[1], t)
            local rz = lerp(c1[2], c2[2], t)
            place(rx, rz, base_y, 0, audio.bands[1] * 0.2)
        end
    end

    -- === SLANT EDGES (corners to apex) ===
    for edge = 1, 4 do
        local c = corners[edge]
        for i = 0, slant_per_edge - 1 do
            local t = slant_per_edge > 1 and (i / (slant_per_edge - 1)) or 0.5
            local rx = c[1] * (1 - t)
            local rz = c[2] * (1 - t)
            local y = lerp(base_y, apex_y, t)
            local band = ((edge - 1) % 4) + 1
            -- Apex glow
            local apex_boost = t > 0.85 and audio.peak * 0.3 or 0
            place(rx, rz, y, band, apex_boost)
        end
    end

    -- === HORIZONTAL LAYER RINGS ===
    if layer_budget > 0 then
        local num_layers = math.max(1, math.min(4, math.floor(layer_budget / 8)))
        local per_layer = math.floor(layer_budget / num_layers)

        for layer = 1, num_layers do
            local t = layer / (num_layers + 1)
            local layer_size = 1.0 - t
            local y = lerp(base_y, apex_y, t)
            local lh = half * layer_size

            -- Warp with mid frequencies
            local warp = math.sin(state.rotation * 2 + layer * 1.2) * audio.bands[3] * 0.03
            y = y + warp

            -- Layer corners (rotated)
            local lc = {}
            for i, c in ipairs(raw) do
                lc[i] = {
                    (c[1] * layer_size) * cos_r - (c[2] * layer_size) * sin_r,
                    (c[1] * layer_size) * sin_r + (c[2] * layer_size) * cos_r,
                }
            end

            -- Distribute points evenly around the 4 edges
            local per_edge = math.max(1, math.floor(per_layer / 4))
            for edge = 1, 4 do
                local c1 = lc[edge]
                local c2 = lc[(edge % 4) + 1]
                for i = 0, per_edge - 1 do
                    local et = per_edge > 1 and (i / (per_edge - 1)) or 0.5
                    local rx = lerp(c1[1], c2[1], et)
                    local rz = lerp(c1[2], c2[2], et)
                    local band = (edge + layer) % 5
                    place(rx, rz, y, band, 0)
                end
            end
        end
    end

    return normalize_entities(entities, n)
end
