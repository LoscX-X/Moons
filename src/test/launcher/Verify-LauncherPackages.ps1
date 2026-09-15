param([Parameter(Mandatory=$true)][string]$Distribution, [Parameter(Mandatory=$true)][string]$FixtureRoot)
$ErrorActionPreference = 'Stop'
$Distribution = [IO.Path]::GetFullPath($Distribution)
$fixture = Join-Path ([IO.Path]::GetFullPath($FixtureRoot)) ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$previousHome = $env:MOONS_HOME
$env:MOONS_HOME = Join-Path $fixture 'home'
# Run the installer alone, away from build outputs and standalone runtime JARs.
$installer = Join-Path $fixture 'moon-install.exe'
Copy-Item -LiteralPath (Join-Path $Distribution 'moon-install.exe') -Destination $installer
$loader = Join-Path $Distribution 'moon.exe'
$script:attempt = 0

function Run-Tool([string]$Executable, [string]$Argument, [int]$Expected) {
    $script:attempt++
    $log = Join-Path $fixture ("run-" + $script:attempt)
    $process = Start-Process -FilePath $Executable -ArgumentList $Argument -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput ($log + '.out') -RedirectStandardError ($log + '.err')
    if ($process.ExitCode -ne $Expected) {
        throw "Unexpected exit code $($process.ExitCode), expected ${Expected}: $(Get-Content ($log + '.err') -Raw)"
    }
}

function Installed-State {
    $state = @{}
    Get-ChildItem -LiteralPath $env:MOONS_HOME -File -Recurse | ForEach-Object {
        $hash = [Security.Cryptography.SHA256]::Create()
        $stream = [IO.File]::OpenRead($_.FullName)
        try { $digest = [BitConverter]::ToString($hash.ComputeHash($stream)) }
        finally { $stream.Dispose(); $hash.Dispose() }
        $state[$_.FullName] = "$($_.Length):$($_.LastWriteTimeUtc.Ticks):$digest"
    }
    return $state
}

try {
    Run-Tool $loader '--self-test-version-detection' 0
    Run-Tool $installer '--version' 0
    $installerVersion = Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw
    Run-Tool $loader '--version' 0
    $loaderVersion = Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw
    if ($installerVersion -ne $loaderVersion -or $loaderVersion -notmatch 'client.version=\d+\.\d+\.\d+' -or
        $loaderVersion -notmatch 'dependencies.version=[0-9a-f]{12}') { throw 'Mismatched package version metadata.' }
    $installerResources = [Reflection.Assembly]::LoadFile($installer).GetManifestResourceNames()
    $loaderResources = [Reflection.Assembly]::LoadFile($loader).GetManifestResourceNames()
    if ($installerResources -notcontains 'Moons.Ysm.zip' -or
        $installerResources -contains 'Moons.Bridge.dll' -or
        $loaderResources -contains 'Moons.Ysm.zip' -or
        $loaderResources -notcontains 'Moons.Bridge.dll' -or
        $installerResources -notcontains 'Moons.UiRuntime.jar' -or
        $loaderResources -contains 'Moons.UiRuntime.jar') { throw 'Incorrect installer/loader resource split.' }

    # A clean loader must report missing dependencies without creating the installation.
    Run-Tool $loader '--verify-dependencies' 1
    if (Test-Path -LiteralPath $env:MOONS_HOME) { throw 'Loader modified a missing installation.' }
    Run-Tool $installer '--install-only' 0
    Run-Tool $loader '--verify-dependencies' 0
    $before = Installed-State
    Run-Tool $loader '--verify-dependencies' 0
    Run-Tool $installer '--install-only' 0
    $after = Installed-State
    if ($before.Count -ne $after.Count) { throw 'Repeat installation changed the file set.' }
    foreach ($name in $before.Keys) {
        # The small current-runtime pointer may be atomically republished by the installer.
        if ($name.EndsWith('moons-ui-runtime.current')) { continue }
        if ($before[$name] -ne $after[$name]) { throw "Unchanged dependency was rewritten: $name" }
    }

    $pointer = Join-Path $env:MOONS_HOME 'libraries/moons-ui-runtime.current'
    $ui = Join-Path (Join-Path $env:MOONS_HOME 'libraries') (Get-Content -LiteralPath $pointer -Raw).Trim()
    [IO.File]::WriteAllText($ui, 'damaged UI')
    $damaged = Installed-State
    Run-Tool $loader '--verify-dependencies' 1
    $after = Installed-State
    if ($damaged.Count -ne $after.Count) { throw 'Loader changed the dependency file set.' }
    foreach ($name in $damaged.Keys) {
        if ($damaged[$name] -ne $after[$name]) { throw "Loader changed damaged installation: $name" }
    }

    # A locked damaged runtime must leave the installation intact and clean up staging.
    $locked = [IO.File]::Open($ui, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try { Run-Tool $installer '--install-only' 1 }
    finally { $locked.Dispose() }
    $after = Installed-State
    if ($damaged.Count -ne $after.Count) { throw 'Failed extraction changed the file set.' }
    foreach ($name in $damaged.Keys) {
        if ($damaged[$name] -ne $after[$name]) { throw "Failed extraction changed installation: $name" }
    }
    if (Get-ChildItem -LiteralPath $env:MOONS_HOME -Filter '*.tmp*' -Recurse) { throw 'Failed extraction left staging files.' }
    # Repair using only the embedded resource, even when an unrelated local JAR is present.
    [IO.File]::WriteAllText((Join-Path $fixture 'moons-ui-runtime.jar'), 'unrelated runtime')
    Run-Tool $installer '--install-only' 0
    $ysm = Join-Path $env:MOONS_HOME 'libraries/moons-ysm-core.jar'
    [IO.File]::WriteAllText($ysm, 'old YSM')
    Run-Tool $loader '--verify-dependencies' 1
    Run-Tool $installer '--install-only' 0
    Run-Tool $loader '--verify-dependencies' 0
    Write-Output 'LAUNCHER_PACKAGES_VERIFIED: isolated roles, read-only loader, offline install, idempotence, failed extraction preservation and UI/YSM repair.'
}
finally { $env:MOONS_HOME = $previousHome }
