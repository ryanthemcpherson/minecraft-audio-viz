Add-Type -AssemblyName System.Drawing

$size = 32
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)

# Fill with a purple/indigo color (matching the app theme)
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(99, 102, 241))
$g.FillRectangle($brush, 0, 0, $size, $size)

# Draw a simple "DJ" text or wave pattern
$font = New-Object System.Drawing.Font("Arial", 10, [System.Drawing.FontStyle]::Bold)
$whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$g.DrawString("DJ", $font, $whiteBrush, 4, 8)

$g.Dispose()
$brush.Dispose()
$font.Dispose()
$whiteBrush.Dispose()

# Save as PNG first, then we'll use it
$bmp.Save("$PSScriptRoot\icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Save("$PSScriptRoot\32x32.png", [System.Drawing.Imaging.ImageFormat]::Png)

# Create 128x128 version
$bmp128 = New-Object System.Drawing.Bitmap(128, 128)
$g128 = [System.Drawing.Graphics]::FromImage($bmp128)
$g128.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g128.DrawImage($bmp, 0, 0, 128, 128)
$bmp128.Save("$PSScriptRoot\128x128.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp128.Save("$PSScriptRoot\128x128@2x.png", [System.Drawing.Imaging.ImageFormat]::Png)

# For ICO, we need to create it manually with proper icon format
# Save as BMP first, then copy to ico (simple workaround)
$bmp.Save("$PSScriptRoot\icon.ico", [System.Drawing.Imaging.ImageFormat]::Icon)

$bmp.Dispose()
$bmp128.Dispose()
$g128.Dispose()

Write-Host "Icons created successfully"
