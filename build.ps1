#!/usr/bin/env pwsh
# 用 AndroidForge 的内置工具链构建 CodePad，不需要设置 JAVA_HOME / ANDROID_HOME
$ErrorActionPreference = 'Stop'

$tc = 'D:\DSH\plugins\dsh-androidforge\toolchain'
if (-not (Test-Path $tc)) {
    throw "找不到 AndroidForge 工具链: $tc"
}

$env:JAVA_HOME = "$tc\jdk17"
$env:ANDROID_HOME = "$tc\android-sdk"
$env:ANDROID_SDK_ROOT = "$tc\android-sdk"
$env:GRADLE_USER_HOME = "$tc\gradle-home"
$env:ANDROID_USER_HOME = "$tc\android-user"
$env:TEMP = "$tc\tmp"
$env:TMP = "$tc\tmp"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& "$tc\gradle-8.10.2\bin\gradle.bat" -p $root assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
"`nAPK: $apk"
