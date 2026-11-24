#!/usr/bin/env pwsh
# 构建 CodePad（debug APK）。
# 工具链目录需要包含：jdk17/  android-sdk/  gradle-8.10.2/
# 查找顺序：-Toolchain 参数 → 环境变量 CODEPAD_TOOLCHAIN → ./toolchain → 已设置的 JAVA_HOME/ANDROID_HOME
param([string]$Toolchain = "")

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-Toolchain([string]$explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    if ($env:CODEPAD_TOOLCHAIN) { $candidates += $env:CODEPAD_TOOLCHAIN }
    $candidates += (Join-Path $root 'toolchain')
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'gradle-8.10.2\bin\gradle.bat'))) { return (Resolve-Path $c).Path }
    }
    return $null
}

$tc = Resolve-Toolchain $Toolchain

if ($tc) {
    $env:JAVA_HOME = Join-Path $tc 'jdk17'
    $env:ANDROID_HOME = Join-Path $tc 'android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $tc 'gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $tc 'android-user'
    $env:TEMP = Join-Path $tc 'tmp'
    $env:TMP = $env:TEMP
    $gradle = Join-Path $tc 'gradle-8.10.2\bin\gradle.bat'
} elseif ($env:JAVA_HOME -and ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT)) {
    # 用系统里已装好的 JDK 17 + SDK 35
    $gradle = 'gradle'
} else {
    Write-Host ''
    Write-Host '没有找到可用的构建环境。三种方式任选：' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '  1) 用自己的 JDK 17 + Android SDK 35 + Gradle 8.10.2：'
    Write-Host '       $env:JAVA_HOME  = "C:\path\to\jdk17"'
    Write-Host '       $env:ANDROID_HOME = "C:\path\to\android-sdk"'
    Write-Host '       gradle assembleDebug'
    Write-Host ''
    Write-Host '  2) 把工具链目录放到仓库下的 ./toolchain（含 jdk17/、android-sdk/、gradle-8.10.2/）'
    Write-Host ''
    Write-Host '  3) 指定工具链目录：'
    Write-Host '       $env:CODEPAD_TOOLCHAIN = "D:\path\to\toolchain"'
    Write-Host '       ./build.ps1'
    Write-Host ''
    Write-Host '注意：仓库不含 app/src/main/assets/*.zip（工具链资源），构建可以通过，但运行时相关功能会提示缺失。' -ForegroundColor DarkGray
    Write-Host '生成方式见 docs/c-toolchain.md。' -ForegroundColor DarkGray
    exit 1
}

& $gradle -p $root assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host ""
Write-Host "APK: $apk"
