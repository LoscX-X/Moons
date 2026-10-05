param([Parameter(Mandatory=$true)][string]$Distribution, [Parameter(Mandatory=$true)][string]$FixtureRoot, [string]$LegacyDistribution, [string]$Legacy189Distribution)
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
        try { $length = $stream.Length; $digest = [BitConverter]::ToString($hash.ComputeHash($stream)) }
        finally { $stream.Dispose(); $hash.Dispose() }
        # NTFS refreshes per-link directory metadata on read; compare the actual stream bytes.
        $state[$_.FullName] = "${length}:$digest"
    }
    return $state
}

try {
    Run-Tool $loader '--self-test-version-detection' 0
    Run-Tool $installer '--version' 0
    $installerVersion = Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw
    Run-Tool $loader '--version' 0
    $loaderVersion = Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw
    if ($installerVersion -ne $loaderVersion -or $loaderVersion -cnotmatch 'client.version=[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-(Experiment|Release)(\r?\n|$)' -or
        $loaderVersion -notmatch 'dependencies.version=[0-9a-f]{12}') { throw 'Mismatched package version metadata.' }
    $installerResources = [Reflection.Assembly]::LoadFile($installer).GetManifestResourceNames()
    $loaderResources = [Reflection.Assembly]::LoadFile($loader).GetManifestResourceNames()
    if ($installerResources -notcontains 'Moons.Ysm.zip' -or
        $installerResources -contains 'Moons.Bridge.dll' -or
        $loaderResources -contains 'Moons.Ysm.zip' -or
        $loaderResources -notcontains 'Moons.Bridge.dll' -or
        $installerResources -notcontains 'Moons.UiRuntime.jar' -or
        $loaderResources -contains 'Moons.UiRuntime.jar' -or
        $loaderResources -notcontains 'Moons.Payload.26_4.patch') { throw 'Incorrect installer/loader resource split.' }

    # A clean loader must report missing dependencies without creating the installation.
    Run-Tool $loader '--verify-dependencies' 1
    if (Test-Path -LiteralPath $env:MOONS_HOME) { throw 'Loader modified a missing installation.' }
    Run-Tool $installer '--install-only' 0
    Run-Tool $loader '--verify-dependencies' 0
    Run-Tool $loader '--verify-dependencies --minecraft-version 26.2' 1
    if (Test-Path -LiteralPath (Join-Path $env:MOONS_HOME 'modules/moons-ysm-26.2.jar')) { throw 'Installer wrote unselected root adapter.' }
    if (Test-Path -LiteralPath (Join-Path $env:MOONS_HOME 'installations')) { throw 'Installer created a second root installation layout.' }
    Run-Tool $installer '--install-only --minecraft-version 26.2' 0
    Run-Tool $loader '--verify-dependencies --minecraft-version 26.2' 0
    Run-Tool $loader '--dependency-context --minecraft-version 26.2' 0
    $selectedContext = (Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw).Trim()
    if ($selectedContext -notlike '*libraries\latest\26.2\*') { throw 'Selected version is not isolated under libraries/latest.' }
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

    Run-Tool $loader '--dependency-context --minecraft-version 26.1.2' 0
    $baseContext = (Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw).Trim()
    $baseRoot = Split-Path (Split-Path $baseContext -Parent) -Parent
    $ui = Join-Path $baseRoot 'ui/moons-ui-runtime.jar'
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
    $ysm = Join-Path $baseRoot 'libraries/moons-ysm-core.jar'
    [IO.File]::WriteAllText($ysm, 'old YSM')
    Run-Tool $loader '--verify-dependencies' 1
    Run-Tool $installer '--install-only' 0
    Run-Tool $loader '--verify-dependencies' 0
    Run-Tool $installer '--install-only --minecraft-version 26.4-snapshot-1' 0
    Run-Tool $loader '--extract-only --minecraft-version 26.4-snapshot-1' 0
    $snapshotPayload = Get-ChildItem -LiteralPath (Join-Path $env:MOONS_HOME 'cache/launcher') `
        -Recurse -File -Filter 'moons-26.4-snapshot-1.jar' | Select-Object -First 1
    if ($null -eq $snapshotPayload -or $snapshotPayload.Length -eq 0) {
        throw '26.4 snapshot payload was not extracted.'
    }
    if ($LegacyDistribution) {
        $currentHome = $env:MOONS_HOME
        $env:MOONS_HOME = Join-Path $fixture 'old-home'
        $oldInstaller = Join-Path $LegacyDistribution 'moon-install.exe'
        $oldLoader = Join-Path $LegacyDistribution 'moon.exe'
        Run-Tool $oldInstaller '--install-only' 0
        Run-Tool $oldLoader '--verify-dependencies' 0
        $oldUi = (Get-Content -LiteralPath (Join-Path $env:MOONS_HOME 'libraries/moons-ui-runtime.current') -Raw).Trim()
        $oldFiles = @{}
        Get-ChildItem -LiteralPath (Join-Path $env:MOONS_HOME 'libraries') -Filter 'moons-ysm-*.jar' | ForEach-Object {
            $oldFiles['libraries/' + $_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
        Get-ChildItem -LiteralPath (Join-Path $env:MOONS_HOME 'modules') -Filter 'moons-ysm-*.jar' | ForEach-Object {
            $oldFiles['modules/' + $_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
        $sharedData = Join-Path $env:MOONS_HOME 'data/core-features/config'
        New-Item -ItemType Directory -Path $sharedData -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $sharedData 'moons.properties'), 'existing-user-setting=true')
        Run-Tool $installer '--install-only' 0
        foreach ($name in $oldFiles.Keys) {
            $path = Join-Path $env:MOONS_HOME $name
            if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $oldFiles[$name]) { throw 'New installer changed old dependencies.' }
        }
        Run-Tool $oldLoader '--verify-dependencies' 0
        $linkedConfig = Join-Path $env:MOONS_HOME 'data/core-features/config/moons.properties'
        if ([IO.File]::ReadAllText($linkedConfig) -ne 'existing-user-setting=true') { throw 'Old installation view reset user configuration.' }
        $env:MOONS_HOME = $currentHome
        Write-Output 'LEGACY_EXE_VIEW_VERIFIED: actual old installer -> new installer -> actual old loader, unchanged dependencies and shared user configuration.'
    }
    Run-Tool $installer '--retire-version 26.4-snapshot-1' 0
    Run-Tool $loader '--verify-dependencies --minecraft-version 26.4-snapshot-1' 0
    if (Test-Path -LiteralPath (Join-Path $env:MOONS_HOME 'libraries/latest/26.4-snapshot-1')) { throw 'Retirement left the original version directory.' }
    if (-not (Test-Path -LiteralPath (Join-Path $env:MOONS_HOME 'libraries/legacy/26.4-snapshot-1'))) { throw 'Retired version was not moved to legacy.' }
    if ($Legacy189Distribution) {
        $sharedHome = $env:MOONS_HOME
        $frozenInstaller = Join-Path $Legacy189Distribution 'Moons-install.exe'
        $frozenLoader = Join-Path $Legacy189Distribution 'Moons.exe'
        $userFile = Join-Path $sharedHome 'data/storage-verification.txt'
        New-Item -ItemType Directory -Path (Split-Path $userFile -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($userFile, 'shared-config')
        Run-Tool $installer ('--install-only --minecraft-version 1.8.9 --legacy-installer "' + $frozenInstaller + '"') 0
        $result = Get-Content (Join-Path $fixture ('run-' + $script:attempt + '.out')) -Raw
        $legacyHome = ($result -split '\r?\n' | Where-Object { $_.StartsWith('Legacy MOONS_HOME=') } | Select-Object -First 1).Substring(18)
        if ($legacyHome -notlike '*libraries\legacy\1.8.9\*') { throw 'Frozen dependencies were not isolated by game version.' }
        $env:MOONS_HOME = $legacyHome
        Run-Tool $frozenLoader '--verify-dependencies' 0
        if ([IO.File]::ReadAllText((Join-Path $legacyHome 'data/storage-verification.txt')) -ne 'shared-config') { throw 'Legacy home lost shared user data.' }
        $env:MOONS_HOME = $sharedHome
        Run-Tool $loader '--verify-dependencies' 0
        Write-Output 'LEGACY_189_PACKAGE_VERIFIED: actual frozen installer resources, Java8 package, independent libraries/legacy/1.8.9, actual frozen loader verification and shared data.'
    }
    Write-Output 'LAUNCHER_PACKAGES_VERIFIED: isolated roles, read-only loader, selected-version offline install, idempotence, 26.4 extraction, failed extraction preservation, UI/YSM repair, relocatable retirement.'
}
finally { $env:MOONS_HOME = $previousHome }
