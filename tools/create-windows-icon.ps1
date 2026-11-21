param(
  [Parameter(Mandatory = $true)][string]$SourcePng,
  [Parameter(Mandatory = $true)][string]$OutputIco,
  [Parameter(Mandatory = $true)][string]$OutputPng
)

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $SourcePng))
try {
  # The supplied 1024 px artwork has the rounded-square mark centered on white.
  # Crop the white border so the mark remains legible in a small Windows icon.
  $crop = [System.Drawing.Rectangle]::new(
    [int]($source.Width * 0.2), [int]($source.Height * 0.2),
    [int]($source.Width * 0.6), [int]($source.Height * 0.6)
  )
  $iconSource = [System.Drawing.Bitmap]::new(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($iconSource)
    try {
      $graphics.Clear([System.Drawing.Color]::White)
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      $graphics.DrawImage($source, [System.Drawing.Rectangle]::new(0, 0, 512, 512), $crop, [System.Drawing.GraphicsUnit]::Pixel)
    } finally { $graphics.Dispose() }
    $iconSource.Save((Join-Path (Split-Path -Parent $OutputPng) (Split-Path -Leaf $OutputPng)), [System.Drawing.Imaging.ImageFormat]::Png)

    $sizes = @(16, 24, 32, 48, 64, 128, 256)
    $images = [System.Collections.Generic.List[byte[]]]::new()
    foreach ($size in $sizes) {
      $bitmap = [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
      try {
        $g = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
          $g.Clear([System.Drawing.Color]::White)
          $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
          $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
          $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
          $g.DrawImage($iconSource, [System.Drawing.Rectangle]::new(0, 0, $size, $size))
          $stream = [System.IO.MemoryStream]::new()
          try { $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png); $images.Add($stream.ToArray()) }
          finally { $stream.Dispose() }
        } finally { $g.Dispose() }
      } finally { $bitmap.Dispose() }
    }

    $fullIco = [System.IO.File]::Create((Join-Path (Split-Path -Parent $OutputIco) (Split-Path -Leaf $OutputIco)))
    try {
      $writer = [System.IO.BinaryWriter]::new($fullIco)
      $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
      $offset = 6 + (16 * $sizes.Count)
      for ($i = 0; $i -lt $sizes.Count; $i++) {
        $size = $sizes[$i]
        $writer.Write([byte]($(if ($size -eq 256) { 0 } else { $size })))
        $writer.Write([byte]($(if ($size -eq 256) { 0 } else { $size })))
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
        $offset += $images[$i].Length
      }
      foreach ($image in $images) { $writer.Write($image) }
      $writer.Flush()
    } finally { $fullIco.Dispose() }
  } finally { $iconSource.Dispose() }
} finally { $source.Dispose() }
