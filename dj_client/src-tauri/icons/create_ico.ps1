Add-Type -AssemblyName System.Drawing

function Create-Icon {
    param(
        [int]$size,
        [string]$outputPath
    )

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    # Fill with indigo color
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(99, 102, 241))
    $g.FillRectangle($brush, 0, 0, $size, $size)

    # Draw a simple wave pattern
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, [Math]::Max(1, $size / 16))
    $centerY = $size / 2
    $amplitude = $size / 4

    for ($x = 0; $x -lt $size; $x++) {
        $y1 = $centerY + [Math]::Sin($x * 0.3) * $amplitude
        $y2 = $centerY + [Math]::Sin(($x + 1) * 0.3) * $amplitude
        $g.DrawLine($pen, $x, $y1, [Math]::Min($x + 1, $size - 1), $y2)
    }

    $g.Dispose()
    $brush.Dispose()
    $pen.Dispose()

    $bmp.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# Create PNG files
Create-Icon -size 32 -outputPath "$PSScriptRoot\32x32.png"
Create-Icon -size 128 -outputPath "$PSScriptRoot\128x128.png"
Create-Icon -size 256 -outputPath "$PSScriptRoot\128x128@2x.png"

# Create ICO file with proper format
# ICO header: 6 bytes
# ICO directory entry: 16 bytes per image
# Image data: PNG data

$ico32 = "$PSScriptRoot\32x32.png"
$pngData = [System.IO.File]::ReadAllBytes($ico32)

# Create ICO file manually
$ms = New-Object System.IO.MemoryStream

# ICO Header (6 bytes)
$ms.WriteByte(0)  # Reserved
$ms.WriteByte(0)
$ms.WriteByte(1)  # Type (1 = ICO)
$ms.WriteByte(0)
$ms.WriteByte(1)  # Number of images
$ms.WriteByte(0)

# ICO Directory Entry (16 bytes)
$ms.WriteByte(32)  # Width (32 = 32px, 0 = 256px)
$ms.WriteByte(32)  # Height
$ms.WriteByte(0)   # Color palette (0 = no palette)
$ms.WriteByte(0)   # Reserved
$ms.WriteByte(1)   # Color planes
$ms.WriteByte(0)
$ms.WriteByte(32)  # Bits per pixel
$ms.WriteByte(0)

# Size of image data (4 bytes, little-endian)
$sizeBytes = [BitConverter]::GetBytes([int]$pngData.Length)
$ms.Write($sizeBytes, 0, 4)

# Offset to image data (4 bytes, little-endian) - starts after header (6) + directory (16) = 22
$offsetBytes = [BitConverter]::GetBytes([int]22)
$ms.Write($offsetBytes, 0, 4)

# Image data (PNG)
$ms.Write($pngData, 0, $pngData.Length)

# Save ICO file
[System.IO.File]::WriteAllBytes("$PSScriptRoot\icon.ico", $ms.ToArray())
$ms.Dispose()

Write-Host "Icons created successfully"
Get-ChildItem $PSScriptRoot
