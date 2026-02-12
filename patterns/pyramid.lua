name = "Pyramid"
description = "Egyptian pyramid - inverts on drops"
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
    if audio.beat and audio.beat_intensity > 0.6 then
        if state.invert > 0.5 then
            state.invert = 0
        else
            state.invert = 1
        end
    end

    -- Hover with bass
    local target_hover = audio.bands[1] * 0.1 + audio.bands[2] * 0.05
    state.hover = smooth(state.hover, target_hover, 0.1, dt)

    -- Pyramid layers
    local layers = math.max(3, math.floor(math.sqrt(n)))

    local entity_idx = 0
    for layer = 0, layers - 1 do
        local layer_norm
        if layers > 1 then
            layer_norm = layer / (layers - 1)
        else
            layer_norm = 0
        end

        -- Inversion
        if state.invert > 0.5 then
            layer_norm = 1.0 - layer_norm
        end

        -- Layer properties
        local layer_size = 1.0 - layer_norm * 0.9
        local layer_warp = math.sin(state.rotation + layer * 0.6) * audio.bands[3] * 0.08
        local layer_y = 0.1 + layer_norm * 0.7 + state.hover + layer_warp * 0.1

        -- Points per layer (square arrangement)
        local side_points = math.max(1, math.floor(math.sqrt(n / layers) * layer_size))

        for i = 0, side_points - 1 do
            for j = 0, side_points - 1 do
                if entity_idx >= n then
                    break
                end

                -- Position within layer
                local local_x = (i / math.max(1, side_points - 1) - 0.5) * layer_size * 0.4
                local local_z = (j / math.max(1, side_points - 1) - 0.5) * layer_size * 0.4

                -- Rotate
                local cos_r = math.cos(state.rotation)
                local sin_r = math.sin(state.rotation)
                local rx = local_x * cos_r - local_z * sin_r
                local rz = local_x * sin_r + local_z * cos_r

                local x = center + rx
                local z = center + rz
                local y = layer_y

                local band_idx = entity_idx % 5
                local scale = config.base_scale + audio.bands[band_idx + 1] * 0.4

                -- Highlight edges
                if i == 0 or i == side_points - 1 or j == 0 or j == side_points - 1 then
                    scale = scale + audio.bands[5] * 0.25
                end

                -- Apex glows more
                if layer_norm > 0.8 then
                    scale = scale + audio.peak * 0.3
                end

                if audio.beat then
                    scale = scale * 1.25
                end

                entities[#entities + 1] = {
                    id = string.format("block_%d", entity_idx),
                    x = clamp(x),
                    y = clamp(y),
                    z = clamp(z),
                    scale = math.min(config.max_scale, scale),
                    band = band_idx,
                    visible = true,
                }
                entity_idx = entity_idx + 1
            end

            if entity_idx >= n then
                break
            end
        end
    end

    return entities
end
