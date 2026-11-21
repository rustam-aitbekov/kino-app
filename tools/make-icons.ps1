# Generates the Android TV home-screen banner (320x180) and launcher icon (192x192).
# ASCII-only on purpose: Windows PowerShell 5.1 misreads non-BOM UTF-8; Cyrillic is built from char codes.
Add-Type -AssemblyName System.Drawing
$dir = Join-Path $PSScriptRoot "..\app\src\main\res\drawable-nodpi"
New-Item -ItemType Directory -Force $dir | Out-Null

function Cyr([int[]]$codes) { -join ([char[]]$codes) }
$kino = Cyr 0x41A,0x418,0x41D,0x41E          # KINO (Cyrillic)

function New-Tile([int]$w, [int]$h, [string]$path, [bool]$banner) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
    $c1 = [System.Drawing.Color]::FromArgb(255, 229, 57, 53); $c2 = [System.Drawing.Color]::FromArgb(255, 70, 8, 14)
    $g.FillRectangle((New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $c1, $c2, 35), $rect)

    $s = [float]($h * $(if ($banner) { 0.30 } else { 0.30 }))
    $cx = [float]($w * $(if ($banner) { 0.18 } else { 0.5 }))
    $cy = [float]($h * $(if ($banner) { 0.5 } else { 0.38 }))
    $pts = @(
        (New-Object System.Drawing.PointF ($cx - $s * 0.45), ($cy - $s * 0.6)),
        (New-Object System.Drawing.PointF ($cx - $s * 0.45), ($cy + $s * 0.6)),
        (New-Object System.Drawing.PointF ($cx + $s * 0.65), $cy))
    $g.FillPolygon([System.Drawing.Brushes]::White, $pts)

    $fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = 'Center'; $fmt.LineAlignment = 'Center'
    if ($banner) {
        $f1 = New-Object System.Drawing.Font "Segoe UI", 60, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
        $f2 = New-Object System.Drawing.Font "Segoe UI", 20, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
        $g.DrawString($kino, $f1, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF ([float]($w*0.32)), ([float]($h*0.10)), ([float]($w*0.66)), ([float]($h*0.58))), $fmt)
        $g.DrawString("oc.kg  |  ts.kg", $f2, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF ([float]($w*0.32)), ([float]($h*0.62)), ([float]($w*0.66)), ([float]($h*0.26))), $fmt)
    } else {
        $f1 = New-Object System.Drawing.Font "Segoe UI", 46, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
        $g.DrawString($kino, $f1, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, ([float]($h*0.60)), ([float]$w), ([float]($h*0.36))), $fmt)
    }
    $g.Dispose(); $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

New-Tile 320 180 (Join-Path $dir "banner.png") $true
New-Tile 192 192 (Join-Path $dir "icon.png") $false
"written to $dir"
