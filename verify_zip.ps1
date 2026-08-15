Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead('D:\heliboard new\desh_v5.zip')
$entries = $zip.Entries | ForEach-Object { $_.FullName }
$checks = @(
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationEngine.kt',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationView.kt',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationState.kt',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationRequest.kt',
  'app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationLanguage.kt',
  'app\src\main\java\helium314\keyboard\latin\LatinIME.java',
  'app\src\main\res\layout\translation_view_panel.xml',
  'app\src\main\res\layout\translation_language_select_dialog.xml',
  'app\src\main\res\layout\translation_language_item.xml',
  'app\src\main\res\layout\translation_language_item_header.xml',
  'app\src\main\res\layout\main_keyboard_frame.xml',
  'app\src\main\res\drawable\ic_no_internet.xml',
  'app\src\main\res\drawable\ic_unknown_error.xml',
  'app\src\main\res\drawable\ic_translate_language_switch.xml',
  'app\src\main\res\drawable\translation_language_name_bg.xml',
  'app\src\main\res\drawable\secondary_button_background_rounded.xml',
  'app\src\main\res\drawable\translation_view_edit_text_bg.xml',
  'app\src\main\res\drawable\translation_dialog_card_bg.xml',
  'app\src\main\res\drawable\cursor_drawable_with_primary_text_color.xml',
  'app\src\main\res\values\strings.xml'
)
$all = 0
foreach ($c in $checks) {
  if ($entries -contains $c) { $all++ } else { Write-Host "MISSING $c" }
}
Write-Host "present: $all / $($checks.Count)"
# content spot checks
$e = $zip.GetEntry('app\src\main\java\helium314\keyboard\latin\translation\DeshTranslationRequest.kt')
$r = New-Object System.IO.StreamReader($e.Open())
$content = $r.ReadToEnd(); $r.Close()
Write-Host "mainHandler fix in zip: $($content.Contains('mainHandler'))"
$e2 = $zip.GetEntry('app\src\main\res\layout\translation_view_panel.xml')
$r2 = New-Object System.IO.StreamReader($e2.Open())
$panel = $r2.ReadToEnd(); $r2.Close()
Write-Host "panel has NO mic/speak: $(-not ($panel -match 'mic|speak'))"
Write-Host "panel has etTranslate: $($panel.Contains('etTranslate'))"
$zip.Dispose()
