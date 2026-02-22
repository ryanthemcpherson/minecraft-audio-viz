name = "Sword"
description = "Giant floating sword with energy pulses traveling up the blade on beat"
recommended_entities = 96
category = "Epic"
static_camera = false
state = {}

local CENTER = 0.5

-- Blade geometry constants
local BLADE_BASE_Y = 0.30
local BLADE_TIP_Y = 0.80
local BLADE_LENGTH = BLADE_TIP_Y - BLADE_BASE_Y
local BLADE_HALF_WIDTH = 0.06

-- Part boundaries
local POMMEL_Y = 0.16
local GRIP_BASE_Y = 0.18
local GRIP_TOP_Y = 0.30
local GUARD_Y = 0.30
local GUARD_HALF_X = 0.12
local FULLER_BASE_Y = 0.35
local FULLER_TIP_Y = 0.70
local POMMEL_RADIUS = 0.025
local GRIP_RADIUS = 0.02

local MAX_PULSES = 3
local PULSE_SPEED = 0.6

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

local function blade_half_width(t)
    return BLADE_HALF_WIDTH * (1 - t ^ 1.5)
end

local function init_state()
    state.rotation = state.rotation or 0
    state.time = state.time or 0
    state.beat_intensity = state.beat_intensity or 0
    state.energy_pulses = state.energy_pulses or {}
    state.breathe = state.breathe or 0
    state.sword_scale = state.sword_scale or 1.0
end

function calculate(audio, config, dt)
    init_state()
    local n = config.entity_count or 0

    state.time = state.time + dt
    state.rotation = state.rotation + 0.15 * dt

    -- Beat response
    if audio.is_beat then
        state.beat_intensity = 1.0
        state.sword_scale = 1.08
        -- Spawn energy pulse (max 3)
        if #state.energy_pulses < MAX_PULSES then
            state.energy_pulses[#state.energy_pulses + 1] = { y = BLADE_BASE_Y, life = 0 }
        end
    end
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.sword_scale = smooth(state.sword_scale, 1.0, 0.08, dt)

    -- Update energy pulses
    local active_pulses = {}
    for _, pulse in ipairs(state.energy_pulses) do
        pulse.y = pulse.y + PULSE_SPEED * dt
        pulse.life = pulse.life + dt
        if pulse.y <= BLADE_TIP_Y then
            active_pulses[#active_pulses + 1] = pulse
        end
    end
    state.energy_pulses = active_pulses

    -- Breathing for grip
    state.breathe = state.breathe + dt * 1.5

    -- Audio-reactive values
    local blade_width_boost = audio.bands[2] * 0.015
    local blade_brightness = math.floor((0.3 + audio.bands[3] * 0.4 + state.beat_intensity * 0.3) * 15)
    blade_brightness = clamp(blade_brightness, 0, 15)

    -- Rotation
    local yaw = state.rotation
    local tilt_x = math.sin(state.time * 0.8) * 0.05
    local cos_y = math.cos(yaw)
    local sin_y = math.sin(yaw)
    local cos_tx = math.cos(tilt_x)
    local sin_tx = math.sin(tilt_x)

    local scale_factor = state.sword_scale
    local base = config.base_scale
    local blade_depth = 0.008 + audio.bands[4] * 0.01 + state.beat_intensity * 0.006
    local parts = allocate_counts(n, {
        blade_edges = 32,
        blade_fill = 16,
        fuller = 8,
        guard = 14,
        grip = 10,
        pommel = 6,
        tip = 2,
        pulses = 8,
    })

    local blade_edge_rows = math.floor(parts.blade_edges / 2)
    local blade_edge_extra = parts.blade_edges - blade_edge_rows * 2
    local blade_fill_levels = math.floor(parts.blade_fill / 2)
    local blade_fill_extra = parts.blade_fill - blade_fill_levels * 2
    local fuller_count = parts.fuller
    local guard_left = math.floor(parts.guard / 2)
    local guard_right = parts.guard - guard_left
    local grip_count = parts.grip
    local pommel_count = parts.pommel
    local tip_count = parts.tip
    local pulse_slots = parts.pulses

    local entities = {}
    local idx = 0

    local function add_entity(x, y, z, scl, band, opts)
        idx = idx + 1
        -- Apply tilt around X axis (relative to center y=0.48)
        local rel_y = y - 0.48
        local rel_z = z - CENTER
        local ty = rel_y * cos_tx - rel_z * sin_tx
        local tz = rel_y * sin_tx + rel_z * cos_tx
        -- Apply Y rotation around center
        local rel_x = x - CENTER
        local rx = rel_x * cos_y - tz * sin_y
        local rz = rel_x * sin_y + tz * cos_y

        local fx = CENTER + rx * scale_factor
        local fy = 0.48 + ty * scale_factor
        local fz = CENTER + rz * scale_factor

        local ent = {
            id = string.format("block_%d", idx - 1),
            x = clamp(fx),
            y = clamp(fy),
            z = clamp(fz),
            scale = math.min(config.max_scale, scl),
            rotation = 0,
            band = band,
            visible = true,
        }
        if opts then
            if opts.glow then ent.glow = true end
            if opts.brightness then ent.brightness = opts.brightness end
            if opts.material then ent.material = opts.material end
            if opts.interpolation then ent.interpolation = opts.interpolation end
        end
        entities[idx] = ent
    end

    -- === BLADE EDGES ===
    for i = 0, blade_edge_rows - 1 do
        local t = i / math.max(1, blade_edge_rows - 1)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(t) + blade_width_boost

        -- Check proximity to energy pulses for wake glow
        local near_pulse = false
        for _, pulse in ipairs(state.energy_pulses) do
            if math.abs(y - pulse.y) < 0.05 then
                near_pulse = true
                break
            end
        end
        local edge_glow = near_pulse
        local edge_bright = blade_brightness
        if near_pulse then
            edge_bright = clamp(blade_brightness + 4, 0, 15)
        end

        -- Left edge
        add_entity(CENTER - hw, y, 0.5 - blade_depth, base * 0.85, 2,
            { brightness = edge_bright, material = "IRON_BLOCK", interpolation = 3, glow = edge_glow or nil })
        -- Right edge
        add_entity(CENTER + hw, y, 0.5 + blade_depth, base * 0.85, 2,
            { brightness = edge_bright, material = "IRON_BLOCK", interpolation = 3, glow = edge_glow or nil })
    end
    for i = 0, blade_edge_extra - 1 do
        local t = (i + 0.5) / math.max(1, blade_edge_extra)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        add_entity(CENTER, y, 0.5 + ((i % 2 == 0) and blade_depth or -blade_depth), base * 0.75, 2,
            { brightness = math.max(0, blade_brightness - 1), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === BLADE FILL ===
    for level = 0, blade_fill_levels - 1 do
        local t = (level + 0.5) / math.max(1, blade_fill_levels)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(t) + blade_width_boost
        local left_x = CENTER - hw
        local right_x = CENTER + hw

        -- Check proximity to energy pulses for wake glow
        local near_pulse = false
        for _, pulse in ipairs(state.energy_pulses) do
            if math.abs(y - pulse.y) < 0.05 then
                near_pulse = true
                break
            end
        end
        local fill_glow = near_pulse
        local fill_bright = blade_brightness
        if near_pulse then
            fill_bright = clamp(blade_brightness + 4, 0, 15)
        end

        -- 1/3 and 2/3 between edges
        add_entity(lerp(left_x, right_x, 0.333), y, 0.5 - blade_depth * 0.6, base * 0.75, 2,
            { brightness = fill_bright, material = "IRON_BLOCK", interpolation = 3, glow = fill_glow or nil })
        add_entity(lerp(left_x, right_x, 0.667), y, 0.5 + blade_depth * 0.6, base * 0.75, 2,
            { brightness = fill_bright, material = "IRON_BLOCK", interpolation = 3, glow = fill_glow or nil })
    end
    for i = 0, blade_fill_extra - 1 do
        local t = (i + 0.5) / math.max(1, blade_fill_extra)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        add_entity(CENTER, y, 0.5 + ((i % 2 == 0) and blade_depth * 0.5 or -blade_depth * 0.5), base * 0.7, 2,
            { brightness = math.max(0, blade_brightness - 2), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === FULLER / GROOVE ===
    for i = 0, fuller_count - 1 do
        local t = i / math.max(1, fuller_count - 1)
        local y = FULLER_BASE_Y + t * (FULLER_TIP_Y - FULLER_BASE_Y)
        add_entity(CENTER, y, 0.5 - 0.005, base * 0.6, 2,
            { brightness = math.max(0, blade_brightness - 2), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === CROSSGUARD ===
    local guard_scale = base * 1.1 + audio.bands[1] * 0.2
    local guard_flash = state.beat_intensity > 0.7
    local guard_brightness = 8
    if guard_flash then
        guard_brightness = 15
    end
    local function build_guard_side(side, count)
        for i = 0, count - 1 do
            local t = i / math.max(1, count - 1)
            local x = CENTER + side * t * GUARD_HALF_X
            local y_off = 0
            if t > 0.8 then
                y_off = (t - 0.8) * 0.05
            end
            add_entity(x, GUARD_Y + y_off, 0.5, guard_scale, 0,
                { material = "GOLD_BLOCK", interpolation = 4, brightness = guard_brightness, glow = guard_flash or nil })
        end
    end
    build_guard_side(-1, guard_left)
    build_guard_side(1, guard_right)

    -- === GRIP ===
    local grip_breathe = math.sin(state.breathe) * 0.02
    local grip_brightness = 5 + math.floor(math.sin(state.breathe) * 2 + 2)
    for i = 0, grip_count - 1 do
        local t = i / math.max(1, grip_count - 1)
        local y = GRIP_BASE_Y + t * (GRIP_TOP_Y - GRIP_BASE_Y)
        local angle = i * 2.5
        local gx = CENTER + math.cos(angle) * GRIP_RADIUS
        local gz = 0.5 + math.sin(angle) * GRIP_RADIUS
        add_entity(gx, y, gz, base * 0.8 + grip_breathe, 1,
            { material = "NETHER_BRICKS", interpolation = 5, brightness = clamp(grip_brightness, 0, 15) })
    end

    -- === POMMEL ===
    local pommel_brightness = clamp(math.floor(6 + state.beat_intensity * 6), 0, 15)
    if not state.pommel_pts or #state.pommel_pts ~= pommel_count then
        state.pommel_pts = fibonacci_sphere(pommel_count)
    end
    local pommel_pts = state.pommel_pts
    for i = 1, pommel_count do
        local pt = pommel_pts[i]
        local px = CENTER + pt.x * POMMEL_RADIUS
        local py = POMMEL_Y + pt.y * POMMEL_RADIUS
        local pz = 0.5 + pt.z * POMMEL_RADIUS
        add_entity(px, py, pz, base * 0.9, 3,
            { material = "GOLD_BLOCK", interpolation = 4, glow = true, brightness = pommel_brightness })
    end

    -- === TIP GLOW ===
    local tip_brightness = 8 + math.floor(audio.bands[5] * 7)
    for i = 0, tip_count - 1 do
        local t = i / math.max(1, tip_count - 1)
        local tip_y = BLADE_TIP_Y + t * 0.02
        local tip_scale = lerp(base * 0.7, base * 0.5, t)
        add_entity(CENTER, tip_y, 0.5, tip_scale, 4,
            { glow = true, material = "END_ROD", brightness = tip_brightness, interpolation = 2 })
    end

    -- === ENERGY PULSES ===
    local pulse_idx = 0
    for _, pulse in ipairs(state.energy_pulses) do
        local points_per_pulse = math.max(1, math.floor(pulse_slots / math.max(1, #state.energy_pulses)))
        for j = 0, points_per_pulse - 1 do
            if pulse_idx >= pulse_slots then break end
            local angle = (j / math.max(1, points_per_pulse)) * math.pi * 2
            local t = (pulse.y - BLADE_BASE_Y) / BLADE_LENGTH
            local hw = blade_half_width(clamp(t, 0, 1)) * 0.7
            local px = CENTER + math.cos(angle) * hw
            local pz = 0.5 + math.sin(angle) * hw * 0.3
            add_entity(px, pulse.y, pz, base * 1.0, 2,
                { glow = true, material = "SEA_LANTERN", brightness = 15, interpolation = 1 })
            pulse_idx = pulse_idx + 1
        end
    end
    -- Fill remaining pulse slots with faint arc accents so budget stays visible.
    while pulse_idx < pulse_slots do
        local t = (pulse_idx + 0.5) / math.max(1, pulse_slots)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(clamp(t, 0, 1)) * 0.35
        local px = CENTER + hw
        local pz = 0.5
        add_entity(px, y, pz, base * 0.35, 2,
            { glow = false, material = "IRON_BLOCK", brightness = 5, interpolation = 2 })
        pulse_idx = pulse_idx + 1
    end

    return normalize_entities(entities, n)
end
