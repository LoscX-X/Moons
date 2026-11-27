param([Parameter(Mandatory=$true)][string]$Executable,
    [Parameter(Mandatory=$true)][string]$Fixture,
    [Parameter(Mandatory=$true)][int]$TargetPid)
$ErrorActionPreference = 'Stop'
$env:APPDATA = Join-Path $Fixture 'appdata'
$env:TEMPOS_HOME = Join-Path $Fixture 'user-data'
New-Item -ItemType Directory -Path $env:APPDATA -Force | Out-Null
$static = [Reflection.BindingFlags]'Static, NonPublic'
$instance = [Reflection.BindingFlags]'Instance, NonPublic'
$assembly = [Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($Executable))
$catalog = $assembly.GetType('Tempos.WindowsLauncher.VersionCatalog')
$program = $assembly.GetType('Tempos.WindowsLauncher.Program')
$sessions = $assembly.GetType('Tempos.WindowsLauncher.TemporaryPayloadSession')
$portable = $assembly.GetType('Tempos.WindowsLauncher.PortableConfig')
$profile = $catalog.GetMethod('FindArtifact', $static).Invoke($null, @('26.3'))
$package = $sessions.GetMethod('Create', $static).Invoke($null, @($profile))
[string]$root = $sessions.GetField('Root', $instance).GetValue($package)
$utf8 = New-Object Text.UTF8Encoding($false)
try {
    # Explicit import keeps this fixture independent of any existing user configuration.
    $import = Join-Path $Fixture 'portable-import.json'
    [IO.File]::WriteAllText($import, '{"format":2,"values":{"clickgui.theme.rgb.speed":1.25},"modules":{}}', $utf8)
    $prepared = $portable.GetMethod('Prepare', $static).Invoke($null, @([string]$root, [string]$Executable, [string]$import))
    [string]$home = $prepared.GetType().GetField('Home', $instance).GetValue($prepared)
    [string]$profileFile = $prepared.GetType().GetField('ProfilePath', $instance).GetValue($prepared)
    $started = $sessions.GetMethod('RetainForProcess', $instance).Invoke($package, @($TargetPid))
    $context = $sessions.GetField('Context', $instance).GetValue($package)
    $identityRejected = $false
    try {
        $null = $program.GetMethod('LoadBridge', $static).Invoke($null, @('', '', '', $home, '',
            $TargetPid.ToString(), [Action[int,string]]{}, [Func[bool]]{ return $false }, $context, ([long]$started + 1)))
    } catch {
        if ($_.Exception.GetBaseException().Message -notmatch 'selected Minecraft process exited') { throw }
        $identityRejected = $true
    }
    if (-not $identityRejected) { throw 'A different target identity reached native loading.' }
    # Emulate the supported saved-profile file boundary, after the helper has started.
    [IO.File]::WriteAllText($profileFile, '{"format":2,"values":{"clickgui.theme.rgb.speed":3.5},"modules":{}}', $utf8)
    $manifest = $portable.GetMethod('Manifest', $static).Invoke($null, @([string]$root))
    $state = @{ root = $root; helper = $manifest['helperRoot']; expected = 3.5 }
    [IO.File]::WriteAllText((Join-Path $Fixture 'portable-session.json'), ($state | ConvertTo-Json -Compress), $utf8)
    $package.Dispose()
    if (-not (Test-Path -LiteralPath $root)) { throw 'Live process lost its lazy-load libraries.' }
    Write-Output 'PORTABLE_SESSION_ARMED: changed saved parameter, rejected mismatched process identity, retained live target files.'
} finally { $package.Dispose() }
