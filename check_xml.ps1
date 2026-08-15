$files = @(
  'app/src/main/res/layout/translation_view_panel.xml',
  'app/src/main/res/layout/translation_language_select_dialog.xml',
  'app/src/main/res/layout/translation_language_item.xml',
  'app/src/main/res/layout/translation_language_item_header.xml',
  'app/src/main/res/layout/main_keyboard_frame.xml',
  'app/src/main/res/drawable/ic_no_internet.xml',
  'app/src/main/res/drawable/ic_unknown_error.xml',
  'app/src/main/res/drawable/ic_translate_language_switch.xml',
  'app/src/main/res/drawable/translation_language_name_bg.xml',
  'app/src/main/res/drawable/secondary_button_background_rounded.xml',
  'app/src/main/res/drawable/translation_view_edit_text_bg.xml',
  'app/src/main/res/drawable/translation_dialog_card_bg.xml',
  'app/src/main/res/drawable/cursor_drawable_with_primary_text_color.xml',
  'app/src/main/res/values/strings.xml'
)
foreach ($f in $files) {
  try {
    [xml](Get-Content -Raw $f) | Out-Null
    Write-Host "OK  $f"
  } catch {
    Write-Host "PARSE-FAIL $f : $($_.Exception.Message)"
  }
}
