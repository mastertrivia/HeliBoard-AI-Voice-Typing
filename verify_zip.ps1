Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead('D:\heliboard new\heliboard-fixed-17aug2026.zip')
$names = $z.Entries | ForEach-Object { $_.FullName }
$z.Dispose()

Write-Output "=== total entries: $($names.Count) ==="
Write-Output "=== build essentials ==="
$names | Where-Object { $_ -match '^(settings.gradle|build.gradle.kts|gradlew.bat|gradle/wrapper/gradle-wrapper.properties|.github/workflows/build.yml|app/build.gradle.kts)$' }

Write-Output "=== NEW layout profiles present? ==="
$names | Where-Object { $_ -match 'symbols_new|symbols_shifted_new|number_row.json' }

Write-Output "=== APKs present (should be NONE)? ==="
($names | Where-Object { $_ -match '\.apk$' }).Count

Write-Output "=== local.properties present (should be NONE)? ==="
($names | Where-Object { $_ -match 'local.properties$' }).Count

Write-Output "=== desh predictor assets present (required)? ==="
$names | Where-Object { $_ -match 'assets/desh_predictor/' } | Select-Object -First 6

Write-Output "=== strings.xml contains NEW profile names? ==="
$matches = $names | Where-Object { $_ -match 'values/strings.xml$' }
Write-Output "strings.xml entries: $($matches.Count)"
