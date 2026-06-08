$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Select-ExistingPath($Name, $Candidates) {
  foreach ($Candidate in $Candidates) {
    if ($Candidate -and (Test-Path $Candidate)) {
      return $Candidate
    }
  }
  throw "Missing $Name. Checked: $($Candidates -join ', ')"
}

$Sdk = Select-ExistingPath 'Android SDK' @(
  'C:\Users\23613\AppData\Local\Android\Sdk',
  'C:\Users\23613\Documents\Codex\2026-05-07\files-mentioned-by-the-user-deepseek\android-sdk'
)
$BuildTools = Join-Path $Sdk 'build-tools\35.0.0'
$PlatformJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$JavaHome = Select-ExistingPath 'JBR' @(
  'C:\Program Files\Android\Android Studio\jbr',
  'C:\Users\23613\.jdks\ms-21.0.10',
  'C:\Program Files\JetBrains\PyCharm Community Edition 2025.2.3\jbr',
  'C:\Program Files\JetBrains\CLion 2025.3.1\jbr'
)
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"

$Build = Join-Path $Root 'build'
$Classes = Join-Path $Build 'classes'
$Dex = Join-Path $Build 'dex'
$ClassesJar = Join-Path $Build 'classes.jar'
$Out = Join-Path $Root 'dist'
$Res = Join-Path $Root 'app\src\main\res'
$UnsignedAp = Join-Path $Build 'watch-ai.ap_'
$UnsignedApk = Join-Path $Build 'watch-ai-unsigned.apk'
$AlignedApk = Join-Path $Build 'watch-ai-aligned.apk'
$SignedApk = Join-Path $Out 'WatchAI-0.1-watch.apk'
$Keystore = Select-ExistingPath 'debug keystore' @(
  (Join-Path (Split-Path -Parent $Root) 'output\codex-debug.keystore'),
  (Join-Path (Split-Path -Parent $Sdk) 'output\codex-debug.keystore')
)

Remove-Item -Recurse -Force $Build, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Classes, $Dex, $Out | Out-Null

$AaptArgs = @(
  'package',
  '-f',
  '-M', (Join-Path $Root 'app\src\main\AndroidManifest.xml'),
  '-I', $PlatformJar,
  '-F', $UnsignedAp
)
if (Test-Path $Res) {
  $AaptArgs += @('-S', $Res)
}
& (Join-Path $BuildTools 'aapt.exe') @AaptArgs

$Sources = Get-ChildItem -Recurse (Join-Path $Root 'app\src\main\java') -Filter '*.java' | ForEach-Object { $_.FullName }
& (Join-Path $JavaHome 'bin\javac.exe') `
  -encoding UTF-8 `
  -source 1.8 `
  -target 1.8 `
  -bootclasspath $PlatformJar `
  -d $Classes `
  $Sources

& (Join-Path $JavaHome 'bin\jar.exe') cf $ClassesJar -C $Classes .

& (Join-Path $BuildTools 'd8.bat') `
  --min-api 23 `
  --output $Dex `
  $ClassesJar

Copy-Item $UnsignedAp $UnsignedApk
Push-Location $Dex
& (Join-Path $BuildTools 'aapt.exe') add $UnsignedApk 'classes.dex'
Pop-Location

& (Join-Path $BuildTools 'zipalign.exe') -p -f 4 $UnsignedApk $AlignedApk
& (Join-Path $BuildTools 'apksigner.bat') sign `
  --ks $Keystore `
  --ks-key-alias codexdebug `
  --ks-pass pass:android `
  --key-pass pass:android `
  --out $SignedApk `
  $AlignedApk

& (Join-Path $BuildTools 'apksigner.bat') verify --min-sdk-version 30 $SignedApk
Write-Output $SignedApk
