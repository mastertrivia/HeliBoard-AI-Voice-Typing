Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead('D:\heliboard new\desh_v5.zip')
$zip.Entries | Where-Object { $_.FullName -match 'translation|translate' } | ForEach-Object { Write-Host $_.FullName }
$zip.Dispose()
