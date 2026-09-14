param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string[]]$RuntimeHomes = @(),
    [string]$BuildDirectory = "build/bootstrap-path-check"
)

$ErrorActionPreference = "Stop"
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not [IO.Path]::IsPathRooted($BuildDirectory)) {
    $BuildDirectory = Join-Path $repository $BuildDirectory
}
$verifier = Join-Path $BuildDirectory "Release/bootstrap-path-verification.exe"
if (-not (Test-Path -LiteralPath $verifier)) {
    throw "Build bootstrap-path-verification with MOONS_BUILD_NATIVE_TESTS=ON first."
}
$fixtures = Join-Path $BuildDirectory "fixtures"
$classes = Join-Path $fixtures "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $JdkHome "bin/javac.exe") --release 17 -d $classes `
    (Join-Path $PSScriptRoot "BootstrapPathProbe.java")
if ($LASTEXITCODE -ne 0) { throw "Could not compile the bootstrap probe" }
$jar = Join-Path $fixtures "probe.jar"
& (Join-Path $JdkHome "bin/jar.exe") --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Could not package the bootstrap probe" }

# Build names from code points so Windows PowerShell 5.1 also reads this script
# correctly without a BOM. The resulting filesystem names are actual Chinese.
$username = -join ([char[]](0x4E2D, 0x6587, 0x7528, 0x6237, 0x540D))
$cache = -join ([char[]](0x7F13, 0x5B58, 0x20, 0x76EE, 0x5F55))
$chineseDirectory = Join-Path $username $cache
foreach ($directory in @("ascii space", $chineseDirectory)) {
    $targetDirectory = Join-Path $fixtures $directory
    New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $targetDirectory "moons-api.jar")
}
[IO.File]::WriteAllBytes((Join-Path $fixtures "empty.jar"), [byte[]]@())
[IO.File]::WriteAllText((Join-Path $fixtures "corrupt.jar"), "not a jar")
$cases = @(
    @("ascii space/moons-api.jar", "ok"),
    @((Join-Path $chineseDirectory "moons-api.jar"), "ok"),
    @(("missing-" + [Guid]::NewGuid().ToString("N") + ".jar"), "rejected"),
    @("empty.jar", "rejected"),
    @("corrupt.jar", "rejected"),
    @($chineseDirectory, "rejected")
)
if ($RuntimeHomes.Count -eq 0) { $RuntimeHomes = @($JdkHome) }
$previousPath = $env:PATH
try {
    foreach ($runtime in $RuntimeHomes) {
        Write-Output ("Runtime: " + $runtime)
        $env:PATH = (Join-Path $runtime "bin/server") + ";" `
            + (Join-Path $runtime "bin") + ";" + $previousPath
        foreach ($case in $cases) {
            & $verifier (Join-Path $fixtures $case[0]) $case[1]
            if ($LASTEXITCODE -ne 0) {
                throw ("Bootstrap path verification failed: " + $runtime + " / " + $case[0])
            }
        }
    }
} finally {
    $env:PATH = $previousPath
}
