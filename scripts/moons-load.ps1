[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][Alias("Pid")][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$Dll,
    [Parameter(Mandatory = $true)][string]$Payload,
    [Alias("Home")][string]$DataHome,
    [string]$Name,
    [int]$WaitSeconds = 30
)

$ErrorActionPreference = "Stop"

function Throw-Win32Error([string]$Stage) {
    $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    $message = (New-Object ComponentModel.Win32Exception($code)).Message
    throw "$Stage failed (Win32 $code`: $message)"
}

function Get-PeMachine([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "Not a PE image: $Path" }
        $stream.Position = 0x3C
        $peOffset = $reader.ReadInt32()
        if ($peOffset -lt 0 -or $peOffset -gt ($stream.Length - 6)) {
            throw "Invalid PE header offset in $Path"
        }
        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) { throw "Invalid PE signature: $Path" }
        return $reader.ReadUInt16()
    } finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Get-Sha256Hex([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($stream)
        return ([BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

$dllFull = (Resolve-Path -LiteralPath $Dll).Path
$payloadSource = (Resolve-Path -LiteralPath $Payload).Path
if (-not $DataHome) { $DataHome = Join-Path $env:APPDATA ".moons" }
$homeFull = [IO.Path]::GetFullPath($DataHome)
$displayName = if ($Name) { $Name } elseif ($env:MOONS_NAME) { $env:MOONS_NAME } else { "Moons" }
$displayName = ($displayName -replace '[\x00-\x1F\x7F]', '').Trim()
if (-not $displayName) { $displayName = "Moons" }
if ($displayName.Length -gt 32) { $displayName = $displayName.Substring(0, 32) }
$displayPrefix = "[$displayName]"

if ($ProcessId -eq $PID) { throw "Refusing to load the PowerShell loader itself (PID $PID)" }
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 300) { throw "WaitSeconds must be between 1 and 300" }
if (-not [Environment]::Is64BitProcess) { throw "Run this script from 64-bit PowerShell" }

$target = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if (-not $target) { throw "No live process with PID $ProcessId" }
$targetName = $target.ProcessName
if ($targetName -notin @("java", "javaw")) {
    throw "PID $ProcessId is '$targetName', not java/javaw"
}
$targetStarted = $target.StartTime.ToUniversalTime().ToString("o")

$dataDir = Join-Path $env:APPDATA ".moons"
$payloadDigest = Get-Sha256Hex $payloadSource
$payloadDir = Join-Path (Join-Path $dataDir "payloads") $payloadDigest
$payloadSnapshot = Join-Path $payloadDir "moons.jar"
$bootstrapSnapshot = Join-Path $payloadDir "moons-api.jar"
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null
if (-not (Test-Path -LiteralPath $payloadSnapshot)) {
    Copy-Item -LiteralPath $payloadSource -Destination $payloadSnapshot
}
if (-not (Test-Path -LiteralPath $bootstrapSnapshot)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($payloadSnapshot)
    try {
        $entry = $archive.GetEntry("META-INF/moons/bootstrap/moons-api.jar")
        if (-not $entry) { throw "moons.jar does not contain the bootstrap API payload" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $bootstrapSnapshot, $true)
    } finally {
        $archive.Dispose()
    }
}
New-Item -ItemType Directory -Force -Path $homeFull | Out-Null

$source = @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class MoonsLoad {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern uint GetProcessId(IntPtr process);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder path, ref uint size);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool IsWow64Process2(IntPtr process, out ushort processMachine, out ushort nativeMachine);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr VirtualAllocEx(IntPtr process, IntPtr address, uint size, uint allocationType, uint protect);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool WriteProcessMemory(IntPtr process, IntPtr address, byte[] buffer, uint size, out IntPtr written);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr GetModuleHandle(string name);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr CreateRemoteThread(IntPtr process, IntPtr attributes, uint stackSize, IntPtr start, IntPtr parameter, uint flags, out uint threadId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetExitCodeThread(IntPtr thread, out uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool VirtualFreeEx(IntPtr process, IntPtr address, uint size, uint freeType);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr OpenEvent(uint access, bool inherit, string name);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetEvent(IntPtr handle);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr handle);
}
"@
if (-not ("MoonsLoad" -as [type])) { Add-Type -TypeDefinition $source }

# CREATE_THREAD | QUERY_INFORMATION | VM_OPERATION | VM_WRITE | VM_READ
$processAccess = 0x0002 -bor 0x0400 -bor 0x0008 -bor 0x0020 -bor 0x0010
$handle = [MoonsLoad]::OpenProcess($processAccess, $false, $ProcessId)
if ($handle -eq [IntPtr]::Zero) { Throw-Win32Error "OpenProcess" }

$remote = [IntPtr]::Zero
$thread = [IntPtr]::Zero
$reloadEvent = [IntPtr]::Zero
try {
    if ([MoonsLoad]::GetProcessId($handle) -ne $ProcessId) {
        throw "Target identity changed while opening PID $ProcessId"
    }
    $image = New-Object Text.StringBuilder 32768
    [uint32]$imageLength = $image.Capacity
    if (-not [MoonsLoad]::QueryFullProcessImageName($handle, 0, $image, [ref]$imageLength)) {
        Throw-Win32Error "QueryFullProcessImageName"
    }
    $openedImage = $image.ToString()
    $openedName = [IO.Path]::GetFileNameWithoutExtension($openedImage)
    if ($openedName -notin @("java", "javaw")) {
        throw "Opened PID $ProcessId resolved to '$openedImage', not java/javaw"
    }
    $target.Refresh()
    if ($target.HasExited -or $target.StartTime.ToUniversalTime().ToString("o") -ne $targetStarted) {
        throw "PID $ProcessId exited or was reused during validation"
    }

    [uint16]$processMachine = 0
    [uint16]$nativeMachine = 0
    if (-not [MoonsLoad]::IsWow64Process2($handle, [ref]$processMachine, [ref]$nativeMachine)) {
        Throw-Win32Error "IsWow64Process2"
    }
    [uint16]$targetMachine = if ($processMachine -ne 0) { $processMachine } else { $nativeMachine }
    [uint16]$dllMachine = Get-PeMachine $dllFull
    if ($targetMachine -ne $dllMachine) {
        throw ("Architecture mismatch: target PE machine=0x{0:X4}, bridge PE machine=0x{1:X4}" -f $targetMachine, $dllMachine)
    }
    if ($dllMachine -ne 0x8664) { throw "This bridge distribution requires an x64 JVM" }

    $attempt = [Guid]::NewGuid().ToString("N")
    $confPath = Join-Path $dataDir "bridge-$ProcessId.conf"
    $configuration = @(
        "payload=$payloadSnapshot"
        "bootstrap=$bootstrapSnapshot"
        "home=$homeFull"
        "name=$displayName"
        "attempt=$attempt"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($confPath, $configuration, (New-Object Text.UTF8Encoding($false)))

    $bridgeLoaded = $false
    try {
        if ($target.Modules | Where-Object { $_.ModuleName -ieq "moons-bridge.dll" }) {
            $bridgeLoaded = $true
        }
    } catch {
        Write-Warning "Module list unavailable ($($_.Exception.Message)); continuing after handle-based identity validation"
    }

    $reloadEventName = "Local\MoonsBridgeReload-$ProcessId"
    $reloadEvent = [MoonsLoad]::OpenEvent(0x0002, $false, $reloadEventName)
    if ($reloadEvent -ne [IntPtr]::Zero) {
        $bridgeLoaded = $true
    }

    if ($bridgeLoaded) {
        if ($reloadEvent -eq [IntPtr]::Zero) {
            throw "The loaded bridge does not expose runtime reload support; restart the target once with the updated bridge DLL"
        }
        if (-not [MoonsLoad]::SetEvent($reloadEvent)) {
            Throw-Win32Error "SetEvent($reloadEventName)"
        }
        Write-Host "$displayPrefix runtime reload requested in PID $ProcessId ($openedImage)"
    } else {
        $bytes = [Text.Encoding]::Unicode.GetBytes($dllFull + [char]0)
        $remote = [MoonsLoad]::VirtualAllocEx($handle, [IntPtr]::Zero, [uint32]$bytes.Length, 0x3000, 0x04)
        if ($remote -eq [IntPtr]::Zero) { Throw-Win32Error "VirtualAllocEx" }

        [IntPtr]$written = [IntPtr]::Zero
        if (-not [MoonsLoad]::WriteProcessMemory($handle, $remote, $bytes, [uint32]$bytes.Length, [ref]$written)) {
            Throw-Win32Error "WriteProcessMemory"
        }
        if ($written.ToInt64() -ne $bytes.Length) {
            throw "WriteProcessMemory wrote $($written.ToInt64()) of $($bytes.Length) bytes"
        }

        $kernel32 = [MoonsLoad]::GetModuleHandle("kernel32.dll")
        if ($kernel32 -eq [IntPtr]::Zero) { Throw-Win32Error "GetModuleHandle(kernel32.dll)" }
        $loadLibrary = [MoonsLoad]::GetProcAddress($kernel32, "LoadLibraryW")
        if ($loadLibrary -eq [IntPtr]::Zero) { Throw-Win32Error "GetProcAddress(LoadLibraryW)" }

        [uint32]$threadId = 0
        $thread = [MoonsLoad]::CreateRemoteThread($handle, [IntPtr]::Zero, 0, $loadLibrary, $remote, 0, [ref]$threadId)
        if ($thread -eq [IntPtr]::Zero) { Throw-Win32Error "CreateRemoteThread" }

        $waitResult = [MoonsLoad]::WaitForSingleObject($thread, [uint32]($WaitSeconds * 1000))
        if ($waitResult -eq 0x102) { throw "LoadLibraryW did not return within $WaitSeconds seconds" }
        if ($waitResult -ne 0) { Throw-Win32Error "WaitForSingleObject" }
        [uint32]$exitCode = 0
        if (-not [MoonsLoad]::GetExitCodeThread($thread, [ref]$exitCode)) { Throw-Win32Error "GetExitCodeThread" }
        if ($exitCode -eq 0) { throw "LoadLibraryW returned NULL; the bridge was not loaded" }

        Write-Host "$displayPrefix bridge loaded into PID $ProcessId ($openedImage)"
    }
    Write-Host "$displayPrefix target-created: $targetStarted"
    Write-Host "$displayPrefix attempt: $attempt"
    Write-Host "$displayPrefix payload source: $payloadSource"
    Write-Host "$displayPrefix payload snapshot: $payloadSnapshot"
    Write-Host "$displayPrefix home: $homeFull"
    Write-Host "$displayPrefix dll log: $dataDir\bridge-dll.log"
} finally {
    if ($reloadEvent -ne [IntPtr]::Zero) { [MoonsLoad]::CloseHandle($reloadEvent) | Out-Null }
    if ($thread -ne [IntPtr]::Zero) { [MoonsLoad]::CloseHandle($thread) | Out-Null }
    if ($remote -ne [IntPtr]::Zero) { [MoonsLoad]::VirtualFreeEx($handle, $remote, 0, 0x8000) | Out-Null }
    [MoonsLoad]::CloseHandle($handle) | Out-Null
}

[Threading.Thread]::Sleep(1500)
$log = Join-Path $dataDir "bridge-dll.log"
if (Test-Path -LiteralPath $log) {
    Get-Content -LiteralPath $log | Where-Object {
        $_ -match [Regex]::Escape("[$attempt]")
    } | Select-Object -Last 12
} else {
    Write-Host "$displayPrefix DLL log not created yet; inspect target permissions and JVM compatibility"
}
