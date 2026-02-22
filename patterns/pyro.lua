-- Pattern metadata
name = "Pyro"
description = "Beat-triggered firework bursts with ballistic physics and gravity"
category = "Mainstage"
static_camera = true
recommended_entities = 100

state = {}

local BURST_MATERIALS = {
    "REDSTONE_BLOCK",
    "GOLD_BLOCK",
    "DIAMOND_BLOCK",
    "EMERALD_BLOCK",
    "LAPIS_BLOCK",
}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.fireworks then
        state.fireworks = {}
        state.time = 0
        state.next_color = 1
    end

    state.time = state.time + dt

    -- Gravity strength: high amplitude = sparks hang longer
    local gravity = 0.4 - audio.amplitude * 0.15

    -- Launch new firework on beat (max 5 concurrent)
    if audio.is_beat then
        local active = 0
        for _, fw in ipairs(state.fireworks) do
            if fw.phase ~= "dead" then active = active + 1 end
        end
        if active < 5 then
            local peak_y = 0.55 + (audio.beat_intensity or 0.5) * 0.3
            state.fireworks[#state.fireworks + 1] = {
                phase = "rise",
                x = 0.3 + math.random() * 0.4,
                y = 0.05,
                z = 0.3 + math.random() * 0.4,
                peak_y = math.min(0.9, peak_y),
                rise_speed = 0.8 + (audio.beat_intensity or 0.5) * 0.4,
                sparks = {},
                life = 1.0,
                material = BURST_MATERIALS[state.next_color],
                band = (state.next_color - 1) % 5,
                trail = {},
            }
            state.next_color = (state.next_color % #BURST_MATERIALS) + 1
        end
    end

    -- Update fireworks
    for i = #state.fireworks, 1, -1 do
        local fw = state.fireworks[i]

        if fw.phase == "rise" then
            -- Rising: move upward toward peak
            fw.y = fw.y + fw.rise_speed * dt

            -- Store trail positions
            fw.trail[#fw.trail + 1] = {x = fw.x, y = fw.y, z = fw.z}
            if #fw.trail > 3 then
                table.remove(fw.trail, 1)
            end

            -- Reached peak: burst
            if fw.y >= fw.peak_y then
                fw.phase = "burst"
                fw.y = fw.peak_y

                -- Generate sparks using fibonacci sphere
                local spark_count = 15 + math.floor((audio.beat_intensity or 0.5) * 10)
                local dirs = fibonacci_sphere(spark_count)
                fw.sparks = {}
                for j = 1, spark_count do
                    local spread = 0.25 + math.random() * 0.15
                    -- Bass adds upward bias
                    local bass_boost = audio.bands[1] * 0.1
                    fw.sparks[j] = {
                        x = fw.x,
                        y = fw.y,
                        z = fw.z,
                        dx = dirs[j].x * spread,
                        dy = dirs[j].y * spread + bass_boost,
                        dz = dirs[j].z * spread,
                        life = 1.0,
                    }
                end
                fw.trail = {}  -- clear trail on burst
            end

        elseif fw.phase == "burst" then
            -- Update sparks
            local all_dead = true
            for _, spark in ipairs(fw.sparks) do
                if spark.life > 0 then
                    all_dead = false
                    spark.x = spark.x + spark.dx * dt
                    spark.y = spark.y + spark.dy * dt
                    spark.z = spark.z + spark.dz * dt
                    spark.dy = spark.dy - gravity * dt
                    -- Velocity drag
                    spark.dx = spark.dx * (1.0 - 1.5 * dt)
                    spark.dz = spark.dz * (1.0 - 1.5 * dt)
                    spark.life = spark.life - dt * 0.7

                    -- High frequencies add sparkle (scale flicker)
                    if audio.bands[5] > 0.3 then
                        spark.life = spark.life + dt * 0.1  -- slightly longer sparkle
                    end
                end
            end

            if all_dead then
                fw.phase = "dead"
            end
        end

        -- Remove dead fireworks
        if fw.phase == "dead" then
            table.remove(state.fireworks, i)
        end
    end

    -- Render entities
    local entities = {}
    local idx = 0

    for _, fw in ipairs(state.fireworks) do
        if fw.phase == "rise" then
            -- Rocket head
            if idx < n then
                entities[#entities + 1] = {
                    id = string.format("block_%d", idx),
                    x = clamp(fw.x),
                    y = clamp(fw.y),
                    z = clamp(fw.z),
                    scale = math.min(config.max_scale, 0.15),
                    rotation = 0,
                    band = fw.band,
                    visible = true,
                    glow = true,
                    brightness = 15,
                    material = "GLOWSTONE",
                    interpolation = 1,
                }
                idx = idx + 1
            end

            -- Trail
            for ti, tp in ipairs(fw.trail) do
                if idx >= n then break end
                local trail_life = ti / (#fw.trail + 1)
                entities[#entities + 1] = {
                    id = string.format("block_%d", idx),
                    x = clamp(tp.x),
                    y = clamp(tp.y),
                    z = clamp(tp.z),
                    scale = math.min(config.max_scale, 0.08 * trail_life),
                    rotation = 0,
                    band = fw.band,
                    visible = true,
                    glow = true,
                    brightness = math.floor(trail_life * 10),
                    material = fw.material,
                    interpolation = 1,
                }
                idx = idx + 1
            end

        elseif fw.phase == "burst" then
            -- Sparks
            for _, spark in ipairs(fw.sparks) do
                if idx >= n then break end
                if spark.life > 0 then
                    local sparkle = 1.0
                    if audio.bands[5] > 0.3 then
                        sparkle = 0.7 + simple_noise(spark.x * 10, spark.y * 10, state.time * 5) * 0.3
                    end

                    local scale = config.base_scale * spark.life * sparkle * 0.8
                    entities[#entities + 1] = {
                        id = string.format("block_%d", idx),
                        x = clamp(spark.x),
                        y = clamp(spark.y),
                        z = clamp(spark.z),
                        scale = math.min(config.max_scale, math.max(0.02, scale)),
                        rotation = (spark.dx * 500) % 360,
                        band = fw.band,
                        visible = true,
                        glow = spark.life > 0.3,
                        brightness = math.floor(clamp(spark.life) * 15),
                        material = fw.material,
                        interpolation = 1,
                    }
                    idx = idx + 1
                end
            end
        end
    end

    -- Fill remaining entities as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
