# 校验 PC 与 Android 两份 core（含 nio）逐文件一致。
# 用法：pwsh -File script/check-core-sync.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pcRoot = Join-Path $root 'HybridFileXfer-PC\src\top\weixiansen574'
$androidRoot = Join-Path $root 'HybridFileXfer-Android\app\src\main\java\top\weixiansen574'

$pairs = @(
    @{ name = 'core'; pc = Join-Path $pcRoot 'hybridfilexfer\core'; android = Join-Path $androidRoot 'hybridfilexfer\core' },
    @{ name = 'nio';  pc = Join-Path $pcRoot 'nio';                  android = Join-Path $androidRoot 'nio' }
)

$problems = 0
foreach ($pair in $pairs) {
    $pcFiles = Get-ChildItem $pair.pc -Recurse -File -Filter *.java
    foreach ($file in $pcFiles) {
        $relative = $file.FullName.Substring($pair.pc.Length).TrimStart('\', '/')
        $androidFile = Join-Path $pair.android $relative
        if (-not (Test-Path $androidFile)) {
            Write-Output "MISSING $($pair.name)/$relative"
            $problems++
            continue
        }
        $pcHash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash
        $androidHash = (Get-FileHash $androidFile -Algorithm SHA256).Hash
        if ($pcHash -ne $androidHash) {
            Write-Output "DIFF    $($pair.name)/$relative"
            $problems++
        }
    }
    $androidFiles = Get-ChildItem $pair.android -Recurse -File -Filter *.java
    foreach ($file in $androidFiles) {
        $relative = $file.FullName.Substring($pair.android.Length).TrimStart('\', '/')
        $pcFile = Join-Path $pair.pc $relative
        if (-not (Test-Path $pcFile)) {
            Write-Output "EXTRA   $($pair.name)/$relative"
            $problems++
        }
    }
    Write-Output ("CHECKED {0}: {1} files" -f $pair.name, $pcFiles.Count)
}

if ($problems -gt 0) {
    Write-Output "CORE SYNC FAILED: $problems problem(s)"
    exit 1
}
Write-Output 'CORE SYNC OK'
