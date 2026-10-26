$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime] | Out-Null

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

$lang = New-Object Windows.Globalization.Language "zh-Hans-CN"
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
if ($null -eq $engine) { throw "no ocr engine for zh-Hans-CN" }

$dir = "C:\Users\何金波\Desktop\管理信息系统\.work\prototype\unz\word\media"
$outDir = "C:\Users\何金波\Desktop\管理信息系统\.work\ocr"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Get-ChildItem "$dir\*.png" | Sort-Object { [int]($_.BaseName -replace 'image', '') } | ForEach-Object {
    $file = $_.FullName
    $sf = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($file)) ([Windows.Storage.StorageFile])
    $stream = Await ($sf.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    $lines = @()
    foreach ($line in $result.Lines) {
        $words = @()
        foreach ($w in $line.Words) {
            $words += [pscustomobject]@{ t = $w.Text; x = [int]$w.BoundingRect.X; y = [int]$w.BoundingRect.Y; w = [int]$w.BoundingRect.Width; h = [int]$w.BoundingRect.Height }
        }
        if ($words.Count -gt 0) {
            $minX = ($words | Measure-Object -Property x -Minimum).Minimum
            $minY = ($words | Measure-Object -Property y -Minimum).Minimum
            $maxX = ($words | ForEach-Object { $_.x + $_.w } | Measure-Object -Maximum).Maximum
            $maxY = ($words | ForEach-Object { $_.y + $_.h } | Measure-Object -Maximum).Maximum
            $lines += [pscustomobject]@{ text = $line.Text; x = $minX; y = $minY; w = ($maxX - $minX); h = ($maxY - $minY) }
        }
    }
    $obj = [pscustomobject]@{ image = $_.Name; width = $decoder.PixelWidth; height = $decoder.PixelHeight; lines = $lines }
    $json = $obj | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText("$outDir\$($_.BaseName).json", $json, [System.Text.Encoding]::UTF8)
    Write-Output ("$($_.Name): " + $lines.Count + " lines")
    $stream.Dispose()
}
