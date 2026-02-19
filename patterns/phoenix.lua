-- Pattern metadata
name = "Phoenix"
description = "Fiery phoenix in flight with flapping wings and fire trail"
recommended_entities = 128
category = "Epic"
static_camera = false

-- Per-instance state
state = {
    time = 0,
    flap_phase = 0,
    hover_y = 0.45,
    beat_intensity = 0,
    body_scale = 1.0,
    rotation = 0,
    smooth_bands = {0, 0, 0, 0, 0},
}

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
    local entities = {}
    local n = config.entity_count

    -- Update time
    state.time = state.time + dt

    -- Smooth bands for fluid motion
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], audio.bands[i], 0.3, dt)
    end

    -- Wing flap: advance phase, speed up with bass
    local flap_speed = 2 + state.smooth_bands[1] * 3
    state.flap_phase = state.flap_phase + flap_speed * dt

    -- Hover bob
    state.hover_y = 0.45 + math.sin(state.time * 1.2) * 0.02 + audio.amplitude * 0.03

    -- Slow Y rotation
    state.rotation = state.rotation + 0.15 * dt

    -- Beat response
    if audio.beat then
        state.beat_intensity = 1.0
        state.body_scale = 1.12
        state.flap_phase = math.pi * 0.5 -- snap wings to full extension
    end
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.body_scale = 1.0 + decay(state.body_scale - 1.0, 0.92, dt)

    -- Allocate entities by weighted parts (adaptive across low/high budgets).
    local parts = allocate_counts(n, {
        body = 16,
        head = 8,
        lwing = 23,
        rwing = 23,
        tail = 20,
        crest = 6,
        fire = 4,
    })
    local body_count = parts.body
    local head_count = parts.head
    local lwing_count = parts.lwing
    local rwing_count = parts.rwing
    local tail_count = parts.tail
    local crest_count = parts.crest
    local fire_count = parts.fire

    local entity_idx = 0
    local center_x = 0.5
    local center_y = state.hover_y
    local center_z = 0.5
    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)

    -- Helper: rotate point around Y axis then offset to world center
    local function to_world(lx, ly, lz)
        local rx = lx * cos_r - lz * sin_r
        local rz = lx * sin_r + lz * cos_r
        return clamp(center_x + rx), clamp(ly), clamp(center_z + rz)
    end

    -- Helper: add entity (every entity gets material, brightness, interpolation)
    local function add_entity(lx, ly, lz, s, band, opts)
        local wx, wy, wz = to_world(lx, ly, lz)
        opts = opts or {}
        local bright = opts.brightness or 8
        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = wx,
            y = wy,
            z = wz,
            scale = math.min(config.max_scale, math.max(0.01, s)),
            band = band,
            visible = true,
            material = opts.material or "GOLD_BLOCK",
            brightness = clamp(math.floor(bright), 0, 15),
            interpolation = opts.interpolation or 5,
            glow = opts.glow or false,
        }
        entity_idx = entity_idx + 1
    end

    -- Flap displacement: outer points get more vertical movement
    local flap_base = math.sin(state.flap_phase) * 0.04 * (1 + audio.amplitude * 0.5)

    -- === BODY CORE (fibonacci ellipsoid) ===
    local body_points = fibonacci_sphere(body_count)
    local body_rx = 0.04 * state.body_scale
    local body_ry = 0.08 * state.body_scale
    local body_rz = 0.035 * state.body_scale

    for i = 1, body_count do
        local pt = body_points[i]
        local lx = pt.x * body_rx
        local ly = center_y + pt.y * body_ry
        local lz = pt.z * body_rz

        local s = config.base_scale * 0.9 + state.smooth_bands[3] * 0.2 + state.beat_intensity * 0.15
        add_entity(lx, ly, lz, s, 2, {
            material = "GOLD_BLOCK",
            brightness = 8 + state.smooth_bands[3] * 5,
            interpolation = 5,
            glow = state.beat_intensity > 0.6,
        })
    end

    -- === HEAD + BEAK ===
    local head_y = center_y + body_ry + 0.03
    local head_r = 0.025
    local head_sphere_count = 0
    if head_count > 0 then
        head_sphere_count = math.max(1, math.floor(head_count * 0.7))
    end
    local beak_count = head_count - head_sphere_count

    -- Head sphere
    local head_points = fibonacci_sphere(head_sphere_count)
    local head_bob = state.smooth_bands[3] * 0.02

    for i = 1, head_sphere_count do
        local pt = head_points[i]
        local lx = pt.x * head_r
        local ly = head_y + head_bob + pt.y * head_r
        local lz = pt.z * head_r

        local s = config.base_scale * 0.7 + state.smooth_bands[3] * 0.15
        add_entity(lx, ly, lz, s, 2, {
            material = "GOLD_BLOCK",
            brightness = 9 + state.smooth_bands[3] * 4,
            interpolation = 5,
        })
    end

    -- Beak: 2-3 points extending forward (-z direction), narrowing
    for b = 0, beak_count - 1 do
        local t = (b + 1) / (beak_count + 1)
        local lx = 0
        local ly = head_y + head_bob - 0.005 * t
        local lz = -head_r - t * 0.04

        local s = config.base_scale * (0.5 - t * 0.2)
        add_entity(lx, ly, lz, s, 2, {
            material = "ORANGE_CONCRETE",
            brightness = 7,
            interpolation = 5,
        })
    end

    -- === WING GENERATION ===
    -- Quadratic bezier helper
    local function bezier2(t, p0, p1, p2)
        local u = 1 - t
        return u * u * p0 + 2 * u * t * p1 + t * t * p2
    end

    -- Generate one wing (side = -1 for left, +1 for right)
    local function build_wing(side, wing_count)
        local feather_lines = 6
        local pts_per_feather = math.max(2, math.floor(wing_count / feather_lines))
        local placed = 0

        for f = 0, feather_lines - 1 do
            if placed >= wing_count then break end

            local spread = -20 + f * 35 -- fan from -20 to 155 degrees
            local feather_length = 0.15 + f * 0.02

            -- Root at body side
            local root_x = side * 0.04
            local root_y = center_y
            local root_z = 0

            -- Control point: sweeps outward and slightly back
            local ctrl_x = side * (0.15 + f * 0.02)
            local ctrl_y = center_y + math.sin(math.rad(spread)) * 0.04
            local ctrl_z = -0.04

            -- Tip: extends far out
            local tip_x = side * (0.25 + f * 0.02)
            local tip_y = center_y + math.sin(math.rad(spread)) * 0.08 - 0.02
            local tip_z = -0.08 - f * 0.01

            local actual_pts = math.min(pts_per_feather, wing_count - placed)
            for p = 0, actual_pts - 1 do
                local t = p / math.max(1, actual_pts - 1)

                local lx = bezier2(t, root_x, ctrl_x, tip_x)
                local ly = bezier2(t, root_y, ctrl_y, tip_y)
                local lz = bezier2(t, root_z, ctrl_z, tip_z)

                -- Wing flap: outer points displaced more
                local flap_amount = flap_base * t * (0.6 + f * 0.08)
                ly = ly + flap_amount

                -- Wing material gradient: inner gold, mid orange, outer red
                local wing_mat
                if t < 0.33 then
                    wing_mat = "GOLD_BLOCK"
                elseif t < 0.66 then
                    wing_mat = "ORANGE_CONCRETE"
                else
                    wing_mat = "REDSTONE_BLOCK"
                end

                local wing_glow = state.beat_intensity > 0.5 and t > 0.5

                -- Beat flash: outer tips get max brightness on beat
                local wing_bright = 7 + state.smooth_bands[1] * 6
                if state.beat_intensity > 0.5 and t > 0.6 then
                    wing_bright = 15
                end

                local s = config.base_scale * (0.7 + t * 0.3) + state.smooth_bands[1] * 0.25 * t
                add_entity(lx, ly, lz, s, 0, {
                    material = wing_mat,
                    brightness = wing_bright,
                    interpolation = 4,
                    glow = wing_glow,
                })
                placed = placed + 1
            end
        end

        -- Fill remaining if any
        while placed < wing_count do
            local t = placed / wing_count
            local lx = side * (0.04 + t * 0.2)
            local ly = center_y + flap_base * t
            local lz = -t * 0.06
            add_entity(lx, ly, lz, config.base_scale * 0.5, 0, {
                material = "ORANGE_CONCRETE",
                brightness = 7 + state.smooth_bands[1] * 4,
                interpolation = 4,
            })
            placed = placed + 1
        end
    end

    -- Left wing (negative X)
    build_wing(-1, lwing_count)

    -- Right wing (positive X)
    build_wing(1, rwing_count)

    -- === TAIL FEATHERS ===
    local tail_streams = 5
    local pts_per_stream = math.max(2, math.floor(tail_count / tail_streams))
    local tail_placed = 0
    local tail_length_base = 0.12 + state.smooth_bands[1] * 0.06
    local tail_curve = 0.06 + state.smooth_bands[2] * 0.04

    for s = 0, tail_streams - 1 do
        if tail_placed >= tail_count then break end

        -- Fan spread across tail streams: center stream is longest
        local stream_t = s / math.max(1, tail_streams - 1)
        local fan_angle = (stream_t - 0.5) * 0.6 -- -0.3 to 0.3 radians lateral spread
        local length_factor = 1.0 - math.abs(stream_t - 0.5) * 0.6 -- center longer

        local actual_pts = math.min(pts_per_stream, tail_count - tail_placed)
        for p = 0, actual_pts - 1 do
            local t = p / math.max(1, actual_pts - 1)

            -- Origin at body bottom-back
            local lx = math.sin(fan_angle) * t * 0.08
            local ly = center_y - body_ry * 0.5 - t * tail_curve * length_factor
            local lz = body_rz + t * tail_length_base * length_factor

            -- Gentle wave along tail
            local wave = math.sin(state.time * 2 + t * math.pi + s) * 0.01 * t
            lx = lx + wave

            -- Tail material gradient: gold at root, red at tips
            local tail_mat = t < 0.5 and "GOLD_BLOCK" or "REDSTONE_BLOCK"

            -- Tail tips glow softly, intensify on beat
            local tail_bright = 8 + state.smooth_bands[1] * 5
            if t > 0.6 then
                -- Soft ember glow on tips, intensified by beat
                tail_bright = 6 + state.smooth_bands[1] * 4 + state.beat_intensity * 5
            end

            local sc = config.base_scale * (0.8 - t * 0.3) + state.smooth_bands[1] * 0.2 * (1 - t * 0.5)
            add_entity(lx, ly, lz, sc, 1, {
                material = tail_mat,
                brightness = tail_bright,
                interpolation = 4,
                glow = t > 0.6,
            })
            tail_placed = tail_placed + 1
        end
    end

    -- Fill remaining tail
    while tail_placed < tail_count do
        local t = tail_placed / tail_count
        local lx = math.sin(t * 3) * 0.02
        local ly = center_y - body_ry * 0.5 - t * tail_curve
        local lz = body_rz + t * tail_length_base * 0.5
        add_entity(lx, ly, lz, config.base_scale * 0.4, 1, {
            material = t > 0.5 and "REDSTONE_BLOCK" or "GOLD_BLOCK",
            brightness = 7 + state.smooth_bands[1] * 4,
            interpolation = 4,
            glow = t > 0.6,
        })
        tail_placed = tail_placed + 1
    end

    -- === CREST (fan above/behind head) ===
    local crest_base_y = head_y + head_r * 0.5
    local crest_spread = 0.5 + state.smooth_bands[4] * 0.3

    for c = 0, crest_count - 1 do
        local t = c / math.max(1, crest_count - 1)
        local fan_t = (t - 0.5) * 2 -- -1 to 1

        -- Fan above and behind head, curving backward
        local lx = fan_t * 0.03 * crest_spread
        local ly = crest_base_y + (1 - math.abs(fan_t)) * 0.04
        local lz = 0.01 + math.abs(fan_t) * 0.02 -- curve backward

        -- Audio-reactive height
        ly = ly + state.smooth_bands[4] * 0.015

        local s = config.base_scale * 0.55 + state.smooth_bands[4] * 0.2
        add_entity(lx, ly, lz, s, 4, {
            material = "ORANGE_CONCRETE",
            brightness = 10 + state.smooth_bands[4] * 5,
            interpolation = 4,
            glow = true,
        })
    end

    -- === FIRE TRAIL (phase-cycling below tail on beat) ===
    local fire_visible = state.beat_intensity > 0.2

    for f = 0, fire_count - 1 do
        local t = f / math.max(1, fire_count - 1)

        -- Cycle position with beat phase
        local phase = state.time * 3 + t * math.pi * 2
        local lx = math.sin(phase) * 0.03
        local ly = center_y - body_ry - 0.04 - t * 0.08
        local lz = body_rz + 0.04 + t * 0.06

        -- Flicker
        local flicker = 0.7 + simple_noise(t * 10, state.time * 5, 0) * 0.3

        -- Flickering material: cycle between GLOWSTONE and REDSTONE_BLOCK
        local fire_noise = simple_noise(t * 7, state.time * 3, 0.5)
        local fire_mat = fire_noise > 0 and "GLOWSTONE" or "REDSTONE_BLOCK"

        local s = config.base_scale * 0.6 * flicker * state.beat_intensity
        local bright = math.floor(state.beat_intensity * 15)

        add_entity(lx, ly, lz, s, 0, {
            material = fire_mat,
            brightness = bright,
            interpolation = 3,
            glow = fire_visible,
        })
    end

    return normalize_entities(entities, n)
end
