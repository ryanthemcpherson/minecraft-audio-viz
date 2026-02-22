$src = "$PSScriptRoot/../shared/tokens.css"
$targets = @(
    "$PSScriptRoot/../admin_panel/css/mcav-tokens.css",
    "$PSScriptRoot/../preview_tool/frontend/css/mcav-tokens.css"
)
foreach ($t in $targets) {
    Copy-Item $src $t -Force
    Write-Host "Synced → $t"
}
