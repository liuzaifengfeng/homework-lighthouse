param([switch]$DebugOnly, [string]$WorkspacePath)
$ErrorActionPreference = 'Stop'
if (-not $WorkspacePath) {
    $WorkspacePath = (Get-Content -LiteralPath (Join-Path $PSScriptRoot '.local/workspace-path.txt') -Raw).Trim()
}
$toolPath = Join-Path $workspacePath 'work\tools'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $toolPath 'java') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $toolPath 'android-sdk'
$env:ANDROID_USER_HOME = Join-Path $toolPath 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolPath 'gradle-user'
$sdkProperty = $env:ANDROID_HOME.Replace('\','/')
"sdk.dir=$sdkProperty" | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'android\local.properties') -Encoding ascii
$keyFile = Join-Path $WorkspacePath 'work\private\android-test.jks'
$env:LIGHTHOUSE_SIGNING_KEY = $keyFile
if (-not (Test-Path -LiteralPath $keyFile)) {
    New-Item -ItemType Directory -Path (Split-Path $keyFile -Parent) -Force | Out-Null
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keyFile -storepass android -keypass android -alias androiddebugkey -dname 'CN=Homework Lighthouse Test' -keyalg RSA -keysize 2048 -validity 3650
    if ($LASTEXITCODE -ne 0) { throw 'Cannot generate test signing key' }
}
$gradlePath = Join-Path $toolPath 'gradle-8.11.1\bin\gradle.bat'
$tasks = @(if ($DebugOnly) { ':app:assembleDebug' } else { ':app:assembleDebug'; ':app:assembleRelease' })
& $gradlePath -p (Join-Path $PSScriptRoot 'android') --console=plain @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
