# 构建 PC 端 jar：编译 HybridFileXfer-PC/src，复制文案资源并打包。
# 用法：pwsh -File script/build-pc.ps1 [-Output <jar 路径>]
param(
    [string]$Output = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'HybridFileXfer-PC\src'
$build = Join-Path $root 'build\pc'
$classes = Join-Path $build 'classes'
$stubSrc = Join-Path $build 'annotations-src'
$stubClasses = Join-Path $build 'annotations-classes'
$stubJar = Join-Path $build 'annotations.jar'
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $build 'HybridFileXfer.jar'
}

foreach ($dir in @($classes, $stubSrc, $stubClasses)) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Force $dir | Out-Null
}

# 源码只用到 @NotNull，这里生成最小注解桩，避免依赖 IDE 的 jar
$annotationDir = Join-Path $stubSrc 'org\jetbrains\annotations'
New-Item -ItemType Directory -Force $annotationDir | Out-Null
Set-Content -Path (Join-Path $annotationDir 'NotNull.java') -Encoding utf8 -Value @'
package org.jetbrains.annotations;
public @interface NotNull {}
'@
Set-Content -Path (Join-Path $annotationDir 'Nullable.java') -Encoding utf8 -Value @'
package org.jetbrains.annotations;
public @interface Nullable {}
'@
& javac -encoding UTF-8 -d $stubClasses (Join-Path $annotationDir 'NotNull.java') (Join-Path $annotationDir 'Nullable.java')
& jar --create --file $stubJar -C $stubClasses .

$sourcesFile = Join-Path $build 'sources.txt'
Get-ChildItem $source -Recurse -Filter *.java | ForEach-Object { $_.FullName } | Set-Content -Path $sourcesFile -Encoding utf8

& javac --release 8 -Xlint:-options -encoding UTF-8 -proc:none -cp $stubJar -d $classes "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Copy-Item (Join-Path $source 'messages*.properties') $classes -Force
$guiDir = Join-Path $classes 'top\weixiansen574\hybridfilexfer\gui'
New-Item -ItemType Directory -Force $guiDir | Out-Null
Copy-Item (Join-Path $source 'top\weixiansen574\hybridfilexfer\gui\messages_gui*.properties') $guiDir -Force

& jar --create --file $Output --main-class top.weixiansen574.hybridfilexfer.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }

$item = Get-Item $Output
Write-Output ("BUILT {0} ({1} bytes)" -f $item.FullName, $item.Length)
