$src = 'D:\Documents\heliboard-fixed'
$zipPath = 'D:\heliboard new\Source Files & Packages\heliboard-fixed-2026-08-27-FINAL-CLEAN-GITHUB.zip'

if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

Add-Type -AssemblyName System.IO.Compression.FileSystem

# Directories to exclude (relative to root)
$excludeDirs = @(
    'build'
    'app\build'
    'tools\make-emoji-keys\build'
    '.gradle'
    '.kotlin'
    '.postman'
    'backend'
    'Decompiled Versions'
    'REFERENCE_APPS'
    'CODEX_DESBOARD_THEMES'
    'CODEX_DESH_TRANSLATION_COMPLETE_2026-08-13'
    'CODEX_DESH_WHOLE_SUBSYSTEM_PORT'
    'CODEX_SPEECHKEYS_VOICE_EXACT_REVERIFICATION_2026-08-13'
    'CODEX_SPEECHNOTES_VOICE_CONTINUOUS_MIC_TOUCH_FIX_2026-08-13'
    'old previous github built'
    'postman'
    'image'
    'null'
)

$zw = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
$fileCount = 0

Get-ChildItem -Path $src -Recurse -Force | ForEach-Object {
    $rel = $_.FullName.Substring($src.Length + 1)

    # Skip excluded directories
    foreach ($ed in $excludeDirs) {
        if ($rel -eq $ed -or $rel.StartsWith($ed + '\')) { return }
    }

    # Skip excluded file patterns
    if ($rel -eq 'local.properties') { return }
    if ($rel -like '*.apk') { return }
    if ($rel -like '*.aab') { return }
    if ($rel -like '*.iml') { return }
    if ($rel -like '*.log') { return }
    if ($rel -like '*.tmp') { return }
    if ($rel -like '*.bak') { return }
    if ($rel -eq '.DS_Store') { return }
    if ($rel -eq 'Thumbs.db') { return }
    if ($rel -like 'Unit)*') { return }

    # Skip IDE directories
    if ($rel -like '.idea*') { return }
    if ($rel -like '.vscode*') { return }
    if ($rel -like '.claude*') { return }
    if ($rel -like '.gemini*') { return }
    if ($rel -like '.opencode*') { return }

    if ($_.PSIsContainer) { return }

    try {
        $entryName = $rel.Replace('\', '/')
        $entry = $zw.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
        $inStream = [System.IO.File]::OpenRead($_.FullName)
        $outStream = $entry.Open()
        $inStream.CopyTo($outStream)
        $outStream.Close()
        $inStream.Close()
        $fileCount++
        if ($fileCount % 500 -eq 0) { Write-Host "  ...$fileCount files packed" }
    } catch {
        Write-Host "  WARN: $rel"
    }
}

$zw.Dispose()
Write-Host "Done: $fileCount files"
Write-Host "ZIP: $zipPath"
$zipItem = Get-Item $zipPath
Write-Host "Size: $([math]::Round($zipItem.Length / 1MB, 1)) MB"
