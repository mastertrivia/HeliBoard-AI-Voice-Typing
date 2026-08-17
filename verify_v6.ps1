Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead('D:\heliboard new\desh_v6.zip')
$entries = $zip.Entries | ForEach-Object { $_.FullName }
$checks = @(
  'app\src\main\java\helium314\keyboard\latin\DeshHindiComposer.kt',
  'app\src\main\java\helium314\keyboard\voice\VoiceSounds.java',
  'app\src\main\java\helium314\keyboard\latin\LatinIME.java',
  'app\src\main\java\helium314\keyboard\keyboard\KeyboardTheme.kt',
  'app\src\main\java\helium314\keyboard\latin\AppUpgrade.kt',
  'app\build.gradle.kts',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationEngine.kt',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationView.kt'
)
$all = 0
foreach ($c in $checks) { if ($entries -contains $c) { $all++ } else { Write-Host "MISSING $c" } }
Write-Host "present: $all / $($checks.Count)"

function ReadEntry($name) {
  $e = $zip.GetEntry($name)
  $r = New-Object System.IO.StreamReader($e.Open())
  $c = $r.ReadToEnd(); $r.Close()
  return $c
}
$latin = ReadEntry 'app\src\main\java\helium314\keyboard\latin\LatinIME.java'
Write-Host "voice beep hook: $($latin.Contains('playListeningStart'))"
Write-Host "composer hook in onEvent: $($latin.Contains('mDeshHindiComposer.onKey'))"
Write-Host "composer hook in onTextInput: $($latin.Contains('mDeshHindiComposer.onText'))"
$theme = ReadEntry 'app\src\main\java\helium314\keyboard\keyboard\KeyboardTheme.kt'
Write-Host "stale-theme fallback: $($theme.Contains('theme name is stale'))"
$up = ReadEntry 'app\src\main\java\helium314\keyboard\latin\AppUpgrade.kt'
Write-Host "migration gate: $($up.Contains('oldVersion <= 4006'))"
$bg = ReadEntry 'app\build.gradle.kts'
Write-Host "versionCode 4007: $($bg.Contains('versionCode = 4007'))"
$zip.Dispose()
