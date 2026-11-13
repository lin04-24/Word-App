param([string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$project = Split-Path $PSScriptRoot -Parent
$output = Join-Path $project '.test-tools\update-fixtures'
$assets = Join-Path $project 'app\src\androidTest\assets'
New-Item -ItemType Directory -Path $output,$assets -Force | Out-Null
$suffix = if ($IsWindows) { '.exe' } else { '' }
$signerSuffix = if ($IsWindows) { '.bat' } else { '' }
$aapt = Join-Path $Sdk "build-tools/36.0.0/aapt$suffix"
$signer = Join-Path $Sdk "build-tools/36.0.0/apksigner$signerSuffix"
$androidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$profilePath = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }
$debugKey = Join-Path $profilePath '.android/debug.keystore'
if (-not (Test-Path $debugKey)) { throw 'Run assembleDebug first to create the Android debug keystore.' }
$wrongKey = Join-Path $output 'fixture.keystore'
if (-not (Test-Path $wrongKey)) {
    & (Join-Path $env:JAVA_HOME "bin/keytool$suffix") -genkeypair -keystore $wrongKey -storepass fixture-test -keypass fixture-test -alias fixture -dname 'CN=Update Test Fixture' -keyalg RSA -validity 3650 -noprompt 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Fixture key generation failed.' }
}
foreach ($kind in @('valid','wrong-package','wrong-signer','old-version')) {
    $package = if ($kind -eq 'wrong-package') { 'com.example.moci.fixture' } else { 'com.example.moci' }
    $code = if ($kind -eq 'old-version') { 2 } else { 3 }
    $version = if ($kind -eq 'old-version') { '0.2' } else { '0.3' }
    $manifest = Join-Path $output 'AndroidManifest.xml'
    @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package" android:versionCode="$code" android:versionName="$version">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
<application android:label="Moci Update Fixture" android:debuggable="true" android:hasCode="false" />
</manifest>
"@ | Set-Content -LiteralPath $manifest -Encoding utf8
    $unsigned = Join-Path $output "$kind-unsigned.apk"
    & $aapt package -f -M $manifest -I $androidJar -F $unsigned
    if ($LASTEXITCODE -ne 0) { throw "Fixture packaging failed: $kind" }
    $key = if ($kind -eq 'wrong-signer') { $wrongKey } else { $debugKey }
    $password = if ($kind -eq 'wrong-signer') { 'fixture-test' } else { 'android' }
    $alias = if ($kind -eq 'wrong-signer') { 'fixture' } else { 'androiddebugkey' }
    $apk = Join-Path $assets "update-$kind.apk"
    & $signer sign --ks $key --ks-key-alias $alias --ks-pass "pass:$password" --key-pass "pass:$password" --out $apk $unsigned
    if ($LASTEXITCODE -ne 0) { throw "Fixture signing failed: $kind" }
    Write-Output "Created $apk"
}
