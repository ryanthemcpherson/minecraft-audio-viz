# generate-branding.ps1
# Generates all installer branding assets for MCAV DJ Client
# Run from any directory: powershell -ExecutionPolicy Bypass -File generate-branding.ps1

Add-Type -AssemblyName System.Drawing

$outDir = $PSScriptRoot
$iconDir = Join-Path (Split-Path $PSScriptRoot) "icons"

# Brand colors
$indigo    = [System.Drawing.Color]::FromArgb(67, 56, 202)    # #4338CA
$purple    = [System.Drawing.Color]::FromArgb(124, 58, 237)   # #7C3AED
$darkBg    = [System.Drawing.Color]::FromArgb(15, 12, 41)     # #0F0C29
$midBg     = [System.Drawing.Color]::FromArgb(32, 22, 77)     # #20164D
$white     = [System.Drawing.Color]::White
$lightGray = [System.Drawing.Color]::FromArgb(200, 200, 220)

function New-GradientBrush($rect, $color1, $color2, $angle = 90) {
    return New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect, $color1, $color2,
        [float]$angle
    )
}

function Draw-EqBars($g, [float]$x, [float]$y, [float]$maxH, [float]$barW, [float]$gap, [int]$count, [System.Drawing.Color]$color1, [System.Drawing.Color]$color2) {
    # Predefined bar heights (normalized 0-1) resembling an EQ curve
    $heights = @(0.4, 0.7, 1.0, 0.85, 0.6, 0.9, 0.75, 0.5, 0.65, 0.45, 0.8, 0.55, 0.35)

    for ($i = 0; $i -lt $count; $i++) {
        $h = $heights[$i % $heights.Length] * $maxH
        $bx = $x + $i * ($barW + $gap)
        $by = $y + ($maxH - $h)

        $barRect = New-Object System.Drawing.RectangleF($bx, $by, $barW, $h)
        if ($barRect.Height -lt 1) { $barRect = New-Object System.Drawing.RectangleF($bx, $by, $barW, 1) }
        $brush = New-GradientBrush $barRect $color1 $color2 270
        $g.FillRectangle($brush, $barRect)
        $brush.Dispose()
    }
}

function Draw-RoundedRect($g, $brush, [float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($x, $y, $r * 2, $r * 2, 180, 90)
    $path.AddArc($x + $w - $r * 2, $y, $r * 2, $r * 2, 270, 90)
    $path.AddArc($x + $w - $r * 2, $y + $h - $r * 2, $r * 2, $r * 2, 0, 90)
    $path.AddArc($x, $y + $h - $r * 2, $r * 2, $r * 2, 90, 90)
    $path.CloseFigure()
    $g.FillPath($brush, $path)
    $path.Dispose()
}

# ---------------------------------------------------------------------------
# App Icon (multiple sizes)
# ---------------------------------------------------------------------------
function Create-AppIcon([int]$size, [string]$outputPath) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    # Gradient background
    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $bgBrush = New-GradientBrush $rect $indigo $purple 135
    # Rounded square (radius = 20% of size)
    Draw-RoundedRect $g $bgBrush 0 0 $size $size ($size * 0.2)
    $bgBrush.Dispose()

    # Draw EQ bars centered
    $barCount = 5
    $barW = $size * 0.1
    $gap = $size * 0.06
    $totalW = $barCount * $barW + ($barCount - 1) * $gap
    $maxH = $size * 0.5
    $startX = ($size - $totalW) / 2
    $startY = $size * 0.25

    $barHeights = @(0.5, 0.8, 1.0, 0.7, 0.45)
    for ($i = 0; $i -lt $barCount; $i++) {
        $h = $barHeights[$i] * $maxH
        $bx = $startX + $i * ($barW + $gap)
        $by = $startY + ($maxH - $h)

        $barRect = New-Object System.Drawing.RectangleF($bx, $by, $barW, $h)
        if ($barRect.Height -lt 1) { $barRect = New-Object System.Drawing.RectangleF($bx, $by, $barW, 1) }
        $barBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(230, 255, 255, 255))
        # Round the bar tops
        $radius = [Math]::Min($barW / 2, 4)
        if ($size -ge 64) {
            Draw-RoundedRect $g $barBrush $bx $by $barW $h $radius
        } else {
            $g.FillRectangle($barBrush, $barRect)
        }
        $barBrush.Dispose()
    }

    $g.Dispose()
    $bmp.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

Write-Host "Generating app icons..."
Create-AppIcon -size 16 -outputPath "$iconDir\16x16.png"
Create-AppIcon -size 32 -outputPath "$iconDir\32x32.png"
Create-AppIcon -size 48 -outputPath "$iconDir\48x48.png"
Create-AppIcon -size 128 -outputPath "$iconDir\128x128.png"
Create-AppIcon -size 256 -outputPath "$iconDir\128x128@2x.png"
Create-AppIcon -size 512 -outputPath "$iconDir\icon.png"

# Build multi-resolution ICO
Write-Host "Building icon.ico..."
$icoSizes = @(16, 32, 48, 256)
$pngDataList = @()
foreach ($s in $icoSizes) {
    $tmpPath = "$iconDir\${s}x${s}.png"
    if (-not (Test-Path $tmpPath)) {
        Create-AppIcon -size $s -outputPath $tmpPath
    }
    $pngDataList += ,[System.IO.File]::ReadAllBytes($tmpPath)
}

$ms = New-Object System.IO.MemoryStream
# ICO header
$ms.WriteByte(0); $ms.WriteByte(0)  # Reserved
$ms.WriteByte(1); $ms.WriteByte(0)  # Type = ICO
$imageCount = $icoSizes.Length
$ms.Write([BitConverter]::GetBytes([uint16]$imageCount), 0, 2)

# Calculate offsets: header(6) + entries(16 * count) + image data
$dataOffset = 6 + 16 * $imageCount
$offsets = @()
for ($i = 0; $i -lt $imageCount; $i++) {
    $offsets += $dataOffset
    $dataOffset += $pngDataList[$i].Length
}

# Directory entries
for ($i = 0; $i -lt $imageCount; $i++) {
    $s = $icoSizes[$i]
    $ms.WriteByte([byte]$(if ($s -ge 256) { 0 } else { $s }))  # Width
    $ms.WriteByte([byte]$(if ($s -ge 256) { 0 } else { $s }))  # Height
    $ms.WriteByte(0)   # Color palette
    $ms.WriteByte(0)   # Reserved
    $ms.Write([BitConverter]::GetBytes([uint16]1), 0, 2)   # Color planes
    $ms.Write([BitConverter]::GetBytes([uint16]32), 0, 2)  # Bits per pixel
    $ms.Write([BitConverter]::GetBytes([int]$pngDataList[$i].Length), 0, 4)
    $ms.Write([BitConverter]::GetBytes([int]$offsets[$i]), 0, 4)
}

# Image data
for ($i = 0; $i -lt $imageCount; $i++) {
    $ms.Write($pngDataList[$i], 0, $pngDataList[$i].Length)
}

[System.IO.File]::WriteAllBytes("$iconDir\icon.ico", $ms.ToArray())
$ms.Dispose()

# Clean up temp sizes not needed by Tauri
if (Test-Path "$iconDir\16x16.png") { Remove-Item "$iconDir\16x16.png" }
if (Test-Path "$iconDir\48x48.png") { Remove-Item "$iconDir\48x48.png" }

# ---------------------------------------------------------------------------
# NSIS Sidebar (164 x 314)
# ---------------------------------------------------------------------------
Write-Host "Generating NSIS sidebar..."
$w = 164; $h = 314
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$rect = New-Object System.Drawing.RectangleF(0, 0, $w, $h)
$bgBrush = New-GradientBrush $rect $darkBg $midBg 180
$g.FillRectangle($bgBrush, $rect)
$bgBrush.Dispose()

# EQ bars in center
Draw-EqBars $g 17 80 100 8 4 10 $white $purple

# App name
$fontLg = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Bold)
$fontSm = New-Object System.Drawing.Font("Segoe UI", 9)
$textBrush = New-Object System.Drawing.SolidBrush($white)
$subtextBrush = New-Object System.Drawing.SolidBrush($lightGray)

$g.DrawString("MCAV", $fontLg, $textBrush, 35, 210)
$g.DrawString("DJ Client", $fontSm, $subtextBrush, 45, 240)

$fontLg.Dispose(); $fontSm.Dispose()
$textBrush.Dispose(); $subtextBrush.Dispose()
$g.Dispose()
$bmp.Save("$outDir\nsis-sidebar.bmp", [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Dispose()

# ---------------------------------------------------------------------------
# NSIS Header (150 x 57)
# ---------------------------------------------------------------------------
Write-Host "Generating NSIS header..."
$w = 150; $h = 57
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$rect = New-Object System.Drawing.RectangleF(0, 0, $w, $h)
$bgBrush = New-GradientBrush $rect $indigo $purple 90
$g.FillRectangle($bgBrush, $rect)
$bgBrush.Dispose()

# Small EQ bars on the left
Draw-EqBars $g 8 12 33 4 2 5 $white ([System.Drawing.Color]::FromArgb(180, 255, 255, 255))

# MCAV text
$font = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush($white)
$g.DrawString("MCAV", $font, $textBrush, 48, 14)
$font.Dispose(); $textBrush.Dispose()
$g.Dispose()
$bmp.Save("$outDir\nsis-header.bmp", [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Dispose()

# ---------------------------------------------------------------------------
# WiX Banner (493 x 58)
# ---------------------------------------------------------------------------
Write-Host "Generating WiX banner..."
$w = 493; $h = 58
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$rect = New-Object System.Drawing.RectangleF(0, 0, $w, $h)
$bgBrush = New-GradientBrush $rect $indigo $purple 0
$g.FillRectangle($bgBrush, $rect)
$bgBrush.Dispose()

# EQ bars on the right side
Draw-EqBars $g 370 8 42 6 3 8 $white ([System.Drawing.Color]::FromArgb(160, 255, 255, 255))

# MCAV DJ Client text
$font = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush($white)
$g.DrawString("MCAV DJ Client", $font, $textBrush, 16, 14)
$font.Dispose(); $textBrush.Dispose()
$g.Dispose()
$bmp.Save("$outDir\wix-banner.bmp", [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Dispose()

# ---------------------------------------------------------------------------
# WiX Dialog (493 x 312)
# ---------------------------------------------------------------------------
Write-Host "Generating WiX dialog..."
$w = 493; $h = 312
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$rect = New-Object System.Drawing.RectangleF(0, 0, $w, $h)
$bgBrush = New-GradientBrush $rect $darkBg $midBg 180
$g.FillRectangle($bgBrush, $rect)
$bgBrush.Dispose()

# Large EQ bars
Draw-EqBars $g 40 50 140 20 12 13 $indigo $purple

# App name
$fontLg = New-Object System.Drawing.Font("Segoe UI", 22, [System.Drawing.FontStyle]::Bold)
$fontSm = New-Object System.Drawing.Font("Segoe UI", 11)
$textBrush = New-Object System.Drawing.SolidBrush($white)
$subtextBrush = New-Object System.Drawing.SolidBrush($lightGray)

$g.DrawString("MCAV DJ Client", $fontLg, $textBrush, 100, 220)
$g.DrawString("Audio Visualization for Minecraft", $fontSm, $subtextBrush, 115, 260)

$fontLg.Dispose(); $fontSm.Dispose()
$textBrush.Dispose(); $subtextBrush.Dispose()
$g.Dispose()
$bmp.Save("$outDir\wix-dialog.bmp", [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Dispose()

Write-Host ""
Write-Host "All branding assets generated:" -ForegroundColor Green
Get-ChildItem $outDir -Filter "*.bmp" | ForEach-Object { Write-Host "  $_" }
Get-ChildItem $iconDir -Filter "*.png" | ForEach-Object { Write-Host "  $_" }
Get-ChildItem $iconDir -Filter "*.ico" | ForEach-Object { Write-Host "  $_" }
