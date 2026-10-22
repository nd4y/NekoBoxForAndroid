[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GoRoot,
    [Parameter(Mandatory)][string]$AndroidSdk,
    [Parameter(Mandatory)][string]$AndroidNdk,
    [Parameter(Mandatory)][string]$JavaHome,
    [string]$Target = 'android/arm64,android/amd64'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$goExe = Join-Path (Resolve-Path $GoRoot).Path 'bin/go.exe'
foreach ($required in @($goExe, "$AndroidNdk/source.properties", "$AndroidSdk/platforms/android-35/android.jar", "$JavaHome/bin/javac.exe")) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing build prerequisite: $required" }
}
# The source tree is retained in a locally built Go distribution.
$timerSource = Join-Path $GoRoot 'src/runtime/sys_linux_arm64.s'
if (!(Select-String -LiteralPath $timerSource -SimpleMatch 'CLOCK_BOOTTIME' -Quiet)) {
    throw 'Use a Go 1.26.7 toolchain with the repository CLOCK_BOOTTIME patch applied.'
}
$env:GOROOT = (Resolve-Path $GoRoot).Path
$env:GOTOOLCHAIN = 'local'
$env:GOMAXPROCS = '2'
# gomobile builds target architectures concurrently. Keep their aggregate
# compiler count bounded when the default two architectures are selected.
$env:GOFLAGS = if ($Target.Contains(',') -or $Target -eq 'android') { '-p=1' } else { '-p=2' }
$env:CGO_ENABLED = '1'
$env:ANDROID_HOME = (Resolve-Path $AndroidSdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = (Resolve-Path $AndroidNdk).Path
$env:ANDROID_NDK_ROOT = $env:ANDROID_NDK_HOME
$env:JAVA_HOME = (Resolve-Path $JavaHome).Path
$env:GOBIN = Join-Path $projectRoot 'libcore/.build/bin'
$env:GOMOBILE = Join-Path $projectRoot 'libcore/.build/gomobile'
New-Item -ItemType Directory -Force -Path $env:GOBIN, $env:GOMOBILE | Out-Null
$env:PATH = "$env:GOROOT/bin;$env:GOBIN;$env:JAVA_HOME/bin;$env:PATH"

function Invoke-Checked([string]$Program, [string[]]$ArgumentList) {
    & $Program @ArgumentList
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
function Get-NativeSourceFingerprint {
    $sourceRoots = @((Join-Path $projectRoot 'libcore'))
    $workspace = Split-Path $projectRoot -Parent
    foreach ($dependency in @('sing-box', 'libneko', 'amneziawg-go', 'net', 'MasterDnsVPN-plus', 'utls', 'byedpi', 'netbird')) {
        $sourceRoots += Join-Path $workspace $dependency
    }
    $manifest = foreach ($source in $sourceRoots) {
        $files = & git -C $source ls-files --cached --others --exclude-standard -- '*.go' '*.c' '*.h' 'go.mod' 'go.sum'
        if ($LASTEXITCODE -ne 0) { throw "Cannot fingerprint source: $source" }
        foreach ($relative in ($files | Sort-Object)) {
            if ($relative.EndsWith('_test.go', [StringComparison]::OrdinalIgnoreCase)) { continue }
            $fullPath = Join-Path $source $relative
            if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
                "$(Split-Path $source -Leaf)/$relative $((Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash)"
            }
        }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($manifest -join "`n"))
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha256.Dispose() }
}
Push-Location (Join-Path $projectRoot 'libcore')
try {
    Invoke-Checked $goExe @('version')
    Invoke-Checked $goExe @('install', 'github.com/sagernet/gomobile/cmd/gomobile@v0.1.12')
    Invoke-Checked $goExe @('install', 'github.com/sagernet/gomobile/cmd/gobind@v0.1.12')
    $gomobileExe = Join-Path $env:GOBIN 'gomobile.exe'
    $env:GOBIND = Join-Path $env:GOBIN 'gobind.exe'
    Invoke-Checked $gomobileExe @('init')
    Invoke-Checked $goExe @('run', '-tags=with_gvisor', '../../sing-box/cmd/internal/gvisor_workaround_verify/main.go')
    # gomobile leaves generated hard links behind after interruption.
    $generatedBuild = [IO.Path]::GetFullPath((Join-Path $projectRoot 'libcore/build'))
    $coreDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot 'libcore')) + [IO.Path]::DirectorySeparatorChar
    if (!$generatedBuild.StartsWith($coreDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Generated build directory escaped libcore.'
    }
    if (Test-Path -LiteralPath $generatedBuild) {
        if ((Get-Item -LiteralPath $generatedBuild).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Refusing to clear a redirected build directory.'
        }
        Remove-Item -LiteralPath $generatedBuild -Recurse -Force
    }
    $sourceFingerprint = Get-NativeSourceFingerprint
    # anet's Android interface workaround uses net.zoneCache, as in NetBird's
    # upstream mobile build; Go 1.23+ requires this explicit linker flag.
    Invoke-Checked $gomobileExe @('bind', '-v', "-target=$Target", '-androidapi', '23', '-trimpath', '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -checklinkname=0', '-tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_awg,with_utls,with_clash_api', '.')
    if ($sourceFingerprint -ne (Get-NativeSourceFingerprint)) {
        throw 'Native source changed during compilation. Run the build again before packaging the application.'
    }
    $output = Join-Path $projectRoot 'app/libs'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    Copy-Item -LiteralPath 'libcore.aar' -Destination (Join-Path $output 'libcore.aar') -Force
    @{
        source_sha256 = $sourceFingerprint
        aar_sha256 = (Get-FileHash -LiteralPath 'libcore.aar' -Algorithm SHA256).Hash.ToLowerInvariant()
        target = $Target
        go = (& $goExe version)
        clock_boottime = $true
        android_api = 23
        ldflags = '-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -checklinkname=0'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'libcore.buildinfo.json') -Encoding utf8
    Write-Output "Built $output/libcore.aar for $Target"
} finally {
    Pop-Location
}
