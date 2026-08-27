$src = 'D:\Documents\heliboard-fixed'
$dst = 'D:\heliboard new\heliboard-fixed-17aug2026.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $dst) { Remove-Item $dst }
$zip = [System.IO.Compression.ZipFile]::Open($dst, 'Create')
$excludeDirs = '(^|\\|\/)(app\\build|build|\\.gradle|\\.kotlin|\\.idea)(\\|\/|$)'
$files = Get-ChildItem -LiteralPath $src -Recurse -File | Where-Object {
    $_.FullName -notmatch $excludeDirs -and
    $_.Name -ne 'local.properties' -and
    $_.Name -ne 'pack_zip.ps1' -and
    $_.Extension -ne '.apk' -and
    $_.FullName -notmatch 'REFERENCE_APPS'
}
$count = 0
foreach ($f in $files) {
    $rel = $f.FullName.Substring($src.Length + 1).Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    $count++
}
$zip.Dispose()
Write-Output ("entries: " + $count)
Write-Output ("size: " + (Get-Item $dst).Length)
