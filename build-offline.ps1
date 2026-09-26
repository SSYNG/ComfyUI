$ErrorActionPreference = 'Stop'

$project = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required.' }
$tools = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$source = Join-Path $project 'app\src\main'
$build = Join-Path $project 'manual-build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$jdkBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-17\bin' }

foreach ($directory in @($classes, $dex)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

function Invoke-Checked($program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed with exit code $LASTEXITCODE" }
}

$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $jdkBin 'javac.exe') (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', $androidJar, '-d', $classes) + $javaFiles)
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('compile', '--dir', (Join-Path $source 'res'), '-o', (Join-Path $build 'res.zip'))
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('link', '-o', (Join-Path $build 'unsigned.apk'), '-I', $androidJar,
    '--manifest', (Join-Path $source 'AndroidManifest.xml'), '--min-sdk-version', '29', '--target-sdk-version', '34',
    '-A', (Join-Path $source 'assets'), (Join-Path $build 'res.zip'))
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $tools 'd8.bat') (@('--min-api', '29', '--lib', $androidJar, '--output', $dex) + $classFiles)
Invoke-Checked (Join-Path $jdkBin 'jar.exe') @('uf', (Join-Path $build 'unsigned.apk'), '-C', $dex, 'classes.dex')
Invoke-Checked (Join-Path $tools 'zipalign.exe') @('-p', '-f', '4', (Join-Path $build 'unsigned.apk'), (Join-Path $build 'aligned.apk'))

$keyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path -LiteralPath $keyStore)) { throw "Debug keystore not found: $keyStore" }
$apk = Join-Path $project 'TangHua-debug.apk'
Invoke-Checked (Join-Path $tools 'apksigner.bat') @('sign', '--ks', $keyStore, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $apk, (Join-Path $build 'aligned.apk'))
Invoke-Checked (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', $apk)
Write-Host "APK ready: $apk"
