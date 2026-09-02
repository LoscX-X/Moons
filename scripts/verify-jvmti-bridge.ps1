[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Java,
    [Parameter(Mandatory = $true)][string]$Fixture,
    [Parameter(Mandatory = $true)][string]$Dll,
    [Parameter(Mandatory = $true)][string]$Payload,
    [switch]$SystemLoader,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$javaFull = (Resolve-Path -LiteralPath $Java).Path
$fixtureFull = (Resolve-Path -LiteralPath $Fixture).Path
$dllFull = (Resolve-Path -LiteralPath $Dll).Path
$payloadFull = (Resolve-Path -LiteralPath $Payload).Path
$smokeRoot = Join-Path (Join-Path $root "build") ("bridge-smoke-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $smokeRoot | Out-Null
$stdout = Join-Path $smokeRoot "fixture.out.log"
$stderr = Join-Path $smokeRoot "fixture.err.log"

$fixtureArguments = @("-jar", $fixtureFull)
if ($SystemLoader) { $fixtureArguments += "--system-loader" }

$process = Start-Process `
    -FilePath $javaFull `
    -ArgumentList $fixtureArguments `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

try {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Fixture exited early with code $($process.ExitCode)" }
        if ((Test-Path -LiteralPath $stdout) -and
                (Select-String -LiteralPath $stdout -Pattern "MOONS_FIXTURE_READY" -Quiet)) {
            break
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $stdout) -or
            -not (Select-String -LiteralPath $stdout -Pattern "MOONS_FIXTURE_READY" -Quiet)) {
        throw "Fixture readiness timeout"
    }

    & (Join-Path $PSScriptRoot "moons-inject.ps1") `
        -ProcessId $process.Id `
        -Dll $dllFull `
        -Payload $payloadFull `
        -DataHome $smokeRoot

    $bridgeLog = Join-Path $smokeRoot "bridge.log"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $active = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Fixture exited during bridge startup with code $($process.ExitCode)" }
        $javaActive = (Test-Path -LiteralPath $bridgeLog) -and
                (Select-String -LiteralPath $bridgeLog -Pattern "active: mode=JVMTI" -Quiet)
        $hookActive = (Test-Path -LiteralPath $stdout) -and
                (Select-String -LiteralPath $stdout -Pattern "First client tick received" -Quiet)
        if ($javaActive -and $hookActive) {
            $active = $true
            break
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $active) { throw "JVMTI runtime/hook activation timeout; logs: $smokeRoot" }

    Write-Host "[Moons] JVMTI bridge smoke passed: $smokeRoot"
    Get-Content -LiteralPath $stdout -Tail 30
    if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Tail 30 }
    Get-Content -LiteralPath $bridgeLog -Tail 20
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    if ($process) { $process.WaitForExit() }
}
