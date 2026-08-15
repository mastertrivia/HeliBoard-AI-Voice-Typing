$json = Get-Content -Raw 'app/src/main/assets/layouts/main/desh_hindi.json'
$matches = [regex]::Matches($json, '"label":\s*"([^"]*)"')
$seen = @{}
foreach ($m in $matches) {
  $lab = $m.Groups[1].Value
  $cps = ($lab.ToCharArray() | ForEach-Object { 'U+{0:X4}' -f [int][char]$_ }) -join ' '
  if (-not $seen.ContainsKey($lab)) {
    $seen[$lab] = $true
    Write-Host "$lab  $cps"
  }
}
