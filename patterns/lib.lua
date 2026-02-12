-- lib.lua: Shared utility functions for Lua pattern scripts

function clamp(value, min_val, max_val)
    min_val = min_val or 0.0
    max_val = max_val or 1.0
    return math.max(min_val, math.min(max_val, value))
end

function lerp(a, b, t)
    return a + (b - a) * t
end

function smoothstep(edge0, edge1, x)
    local t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)
end

function rotate_point_3d(x, y, z, rx, ry, rz)
    -- Rotate around X axis
    local cos_x, sin_x = math.cos(rx), math.sin(rx)
    local y1 = y * cos_x - z * sin_x
    local z1 = y * sin_x + z * cos_x
    -- Rotate around Y axis
    local cos_y, sin_y = math.cos(ry), math.sin(ry)
    local x1 = x * cos_y + z1 * sin_y
    local z2 = -x * sin_y + z1 * cos_y
    -- Rotate around Z axis
    local cos_z, sin_z = math.cos(rz), math.sin(rz)
    local x2 = x1 * cos_z - y1 * sin_z
    local y2 = x1 * sin_z + y1 * cos_z
    return x2, y2, z2
end

function fibonacci_sphere(n)
    local points = {}
    local phi = math.pi * (3.0 - math.sqrt(5.0))
    for i = 0, n - 1 do
        local y
        if n > 1 then
            y = 1 - (i / (n - 1)) * 2
        else
            y = 0
        end
        local radius = math.sqrt(1 - y * y)
        local theta = phi * i
        local x = math.cos(theta) * radius
        local z = math.sin(theta) * radius
        points[#points + 1] = {x = x, y = y, z = z}
    end
    return points
end

function simple_noise(x, y, z, seed)
    seed = seed or 0
    -- Use modular arithmetic to avoid bit operation compatibility issues
    -- across LuaJIT and Lua 5.4
    local n = math.floor(x * 73 + y * 179 + z * 283 + seed * 397)
    -- Scramble using large prime multiplications with modular wraparound
    n = (n * 8191) % 2147483647
    n = (n * 131071) % 2147483647
    local m = ((n * 15731 + 789221) % 2147483647 * n + 1376312589) % 2147483647
    return 1.0 - m / 1073741824.0
end
