param([Parameter(Mandatory=$true)][string]$Distribution,
    [Parameter(Mandatory=$true)][string]$FixtureRoot,
    [string]$JavaVerifierClasspath, [string]$JavaExecutable = 'java.exe')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Distribution = [IO.Path]::GetFullPath($Distribution)
$fixture = Join-Path ([IO.Path]::GetFullPath($FixtureRoot)) ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$previousHome = $env:TEMPOS_HOME
$previousAppData = $env:APPDATA
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
$env:TEMPOS_HOME = Join-Path $fixture 'user-data'
$env:APPDATA = Join-Path $fixture 'appdata'
$env:TEMP = Join-Path $fixture 'temp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:APPDATA -Force | Out-Null
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$loader = Join-Path $fixture 'tempos.exe'
Copy-Item -LiteralPath (Join-Path $Distribution 'tempos.exe') -Destination $loader
$script:attempt = 0

function Run-Tool([string]$Argument, [int]$Expected) {
    $script:attempt++
    $log = Join-Path $fixture ('run-' + $script:attempt)
    $process = Start-Process -FilePath $loader -ArgumentList $Argument -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput ($log + '.out') -RedirectStandardError ($log + '.err')
    if ($process.ExitCode -ne $Expected) { throw "Unexpected exit $($process.ExitCode), expected ${Expected}: $Argument`n$(Get-Content ($log + '.err') -Raw)" }
    return (Get-Content ($log + '.out') -Raw)
}
function Hash-Bytes([byte[]]$Bytes) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($Bytes)) } finally { $hash.Dispose() }
}
function Read-Entry($Entry) {
    $stream = $Entry.Open(); $buffer = New-Object IO.MemoryStream
    try { $stream.CopyTo($buffer); return ,$buffer.ToArray() } finally { $buffer.Dispose(); $stream.Dispose() }
}
function Check-Archive([byte[]]$Expected, [byte[]]$Actual, [string]$Label) {
    $expectedStream = New-Object IO.MemoryStream(,$Expected)
    $actualStream = New-Object IO.MemoryStream(,$Actual)
    $expectedZip = New-Object IO.Compression.ZipArchive($expectedStream, [IO.Compression.ZipArchiveMode]::Read)
    $actualZip = New-Object IO.Compression.ZipArchive($actualStream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        $entries = @($expectedZip.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        if ($entries.Count -ne @($actualZip.Entries | Where-Object { -not $_.FullName.EndsWith('/') }).Count) { throw "Entry count mismatch: $Label" }
        foreach ($entry in $entries) {
            if ($entry.FullName -match 'localfabric|fabric\.mod\.json|tempos-local|^org/jetbrains/(skia|skiko)/|^androidx/|(^|/)icudtl\.dat$|skiko-.*\.dll$') { throw "Removed dependency or Mod entry remains: $Label/$($entry.FullName)" }
            $found = $actualZip.GetEntry($entry.FullName)
            if ($null -eq $found) { throw "Missing entry: $Label/$($entry.FullName)" }
            $a = Read-Entry $entry; $b = Read-Entry $found
            if ($entry.FullName.EndsWith('.jar')) { Check-Archive $a $b ($Label + '/' + $entry.FullName) }
            elseif ((Hash-Bytes $a) -ne (Hash-Bytes $b)) { throw "Changed entry: $Label/$($entry.FullName)" }
        }
    } finally { $expectedZip.Dispose(); $actualZip.Dispose(); $expectedStream.Dispose(); $actualStream.Dispose() }
}
try {
    $null = Run-Tool '--self-test-version-detection' 0
    $version = Run-Tool '--version' 0
    if ($version -cnotmatch 'client.version=[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-(Experiment|Release)(\r?\n|$)') { throw 'Invalid client version metadata.' }
    $assembly = [Reflection.Assembly]::LoadFile($loader)
    $resources = $assembly.GetManifestResourceNames()
    foreach ($name in 'Tempos.UiRuntime.jar','Tempos.Ysm.zip','Tempos.Api.jar','Tempos.Bridge.dll','Tempos.Finalizer.exe','Tempos.Payload.26_1.jar','Tempos.Payload.26_4.patch') {
        if ($resources -notcontains $name) { throw "Missing embedded resource: $name" }
    }
    $static = [Reflection.BindingFlags]'Static, NonPublic'; $instance = [Reflection.BindingFlags]'Instance, NonPublic'
    $catalog = $assembly.GetType('Tempos.WindowsLauncher.VersionCatalog')
    $program = $assembly.GetType('Tempos.WindowsLauncher.Program')
    $sessions = $assembly.GetType('Tempos.WindowsLauncher.TemporaryPayloadSession')
    $profiles = Get-Content (Join-Path $PSScriptRoot '../../../config/version-catalog.json') -Raw | ConvertFrom-Json
    foreach ($item in @($profiles.profiles | Where-Object { $_.available -and $_.payloadFamily -eq 'modern' })) {
        $profile = $catalog.GetMethod('FindArtifact', $static).Invoke($null, @($item.artifactId))
        $package = $sessions.GetMethod('Create', $static).Invoke($null, @($profile))
        $root = $sessions.GetField('Root', $instance).GetValue($package)
        try {
            $payload = $program.GetMethod('ExtractPayload', $static).Invoke($null, @($root, $item.artifactId))
            $null = $program.GetMethod('ExtractBootstrapApi', $static).Invoke($null, @($root))
            $null = $program.GetMethod('ExtractBridge', $static).Invoke($null, @($root))
            $original = Join-Path (Split-Path $Distribution -Parent) ('universal/' + $item.artifactId + '/tempos.jar')
            Check-Archive ([IO.File]::ReadAllBytes($original)) ([IO.File]::ReadAllBytes($payload)) $item.gameId
            $jars = @(Get-ChildItem -LiteralPath $root -Filter '*.jar' -Recurse)
            if ($jars.Count -ne 7) { throw "Unexpected payload/library set: $($item.gameId)" }
            foreach ($jar in $jars) { $bytes = [IO.File]::ReadAllBytes($jar.FullName); Check-Archive $bytes $bytes $jar.Name }
            $context = $sessions.GetField('Context', $instance).GetValue($package)
            $manifest = $context.GetType().GetField('Path', $instance).GetValue($context)
            $hash = $context.GetType().GetField('Hash', $instance).GetValue($context)
            if ([IO.File]::ReadAllText($manifest) -notmatch '(?m)^format=4$') { throw 'Wrong temporary context format.' }
            if ($JavaVerifierClasspath) {
                $data = "home=$env:TEMPOS_HOME`npayload=$payload`nmanifest=$manifest`nhash=$hash`nprofile=$($item.gameId)`n"
                [IO.File]::WriteAllText((Join-Path $fixture 'dependency-fixture.properties'), $data, (New-Object Text.UTF8Encoding($false)))
                & $JavaExecutable -cp $JavaVerifierClasspath com.blanoir.tempos.agent.core.DependencyContextVerification $fixture
                if ($LASTEXITCODE -ne 0) { throw 'Java rejected the real temporary package.' }
            }
        } finally { $package.Dispose() }
        if (Test-Path -LiteralPath $root) { throw 'Unretained session was not removed.' }
        $extracted = Run-Tool ('--extract-only --minecraft-version ' + $item.gameId) 0
        if ($extracted -notmatch 'TEMPORARY_PACKAGE_VERIFIED') { throw 'Real EXE extraction did not complete.' }
        $null = Run-Tool ('--verify-dependencies --minecraft-version ' + $item.gameId) 0
        Write-Output "SINGLE_EXE_PAYLOAD_VERIFIED: $($item.gameId), full entry comparison, libraries and temporary cleanup."
    }
    $null = Run-Tool '--verify-dependencies --minecraft-version unsupported' 1
    if (Test-Path -LiteralPath $env:TEMPOS_HOME) { throw 'Single-file extraction wrote a persistent home.' }
    $lifetimeSource = Join-Path $fixture 'PackageLifetime.java'
    [IO.File]::WriteAllText($lifetimeSource, 'class PackageLifetime { public static void main(String[] args) throws Exception { Thread.sleep(60000); } }')
    $target = Start-Process $JavaExecutable -ArgumentList ('"' + $lifetimeSource + '"') -WindowStyle Hidden -PassThru
    try {
        $driverScript = Join-Path $PSScriptRoot 'Verify-PortableSession.ps1'
        $driverArguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $driverScript + '" -Executable "' + $loader + '" -Fixture "' + $fixture + '" -TargetPid ' + $target.Id
        $driver = Start-Process powershell.exe -ArgumentList $driverArguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $fixture 'portable-driver.out') -RedirectStandardError (Join-Path $fixture 'portable-driver.err')
        try {
            # Retain the native handle before exit so Windows PowerShell can read its exit code.
            $null = $driver.Handle
            # Wait for this driver only: Start-Process -Wait also waits its finalizer descendant.
            if (-not $driver.WaitForExit(30000)) { throw 'Portable lifecycle driver did not exit.' }
            $driver.Refresh()
            if ($driver.ExitCode -ne 0) { throw "Portable lifecycle driver failed: $(Get-Content (Join-Path $fixture 'portable-driver.err') -Raw)" }
        } finally { if (-not $driver.HasExited) { Stop-Process -Id $driver.Id }; $driver.Dispose() }
        $state = Get-Content (Join-Path $fixture 'portable-session.json') -Raw | ConvertFrom-Json
        $target.Refresh()
        if ($target.HasExited) { throw 'Disposable Java exited before the retained-session assertion.' }
        if (-not (Test-Path -LiteralPath $state.root)) { throw 'Live process lost its lazy-load libraries.' }
        Stop-Process -Id $target.Id; $target.WaitForExit()
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while (((Test-Path -LiteralPath $state.root) -or (Test-Path -LiteralPath $state.helper)) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
        if (Test-Path -LiteralPath $state.root) { throw 'Parameter finalizer left the completed session on disk.' }
        if (Test-Path -LiteralPath $state.helper) { throw 'Parameter finalizer did not remove its helper directory.' }
        $portable = $assembly.GetType('Tempos.WindowsLauncher.PortableConfig')
        $cached = $portable.GetMethod('Read', $static).Invoke($null, @([string]$loader))
        if ($null -eq $cached) { throw 'Parameter finalizer did not save the EXE cache.' }
        $saved = [Text.Encoding]::UTF8.GetString($cached) | ConvertFrom-Json
        if ($saved.profiles.default.values.'clickgui.theme.rgb.speed' -ne $state.expected) { throw 'EXE cache did not retain the latest saved parameter.' }
        if (Test-Path -LiteralPath $env:TEMPOS_HOME) { throw 'Portable lifecycle wrote a persistent home.' }
        Write-Output 'PORTABLE_EXE_PARAMETERS_VERIFIED: independent launcher exit, live Java retention, changed parameter writeback and session/helper cleanup.'
    } finally { if (-not $target.HasExited) { Stop-Process -Id $target.Id }; $target.Dispose() }
    Write-Output 'LAUNCHER_PACKAGES_VERIFIED: one offline EXE, full four-version payloads, no installed libs, no Skia/Skiko/ICU/Compose/Mod entry, process identity rejection, parameter writeback and cleanup after target exit.'
} finally { $env:TEMPOS_HOME = $previousHome; $env:APPDATA = $previousAppData; $env:TEMP = $previousTemp; $env:TMP = $previousTmp }
