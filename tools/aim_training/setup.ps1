param([ValidateSet('cuda','cpu')][string]$Device = 'cuda', [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & $Python -c "import sys; assert (3,11) <= sys.version_info[:2] <= (3,13), 'Use Python 3.11-3.13, preferably 3.12, 64-bit'; assert sys.maxsize > 2**32, '64-bit Python required'"
    if ($LASTEXITCODE -ne 0) { throw 'Python check failed' }
    if (-not (Test-Path -LiteralPath '.venv\Scripts\python.exe')) {
        & $Python -m venv .venv
        if ($LASTEXITCODE -ne 0) { throw 'venv creation failed' }
    }
    $AimPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
    & $AimPython -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Data dependency installation failed' }
    $AimIndex = if ($Device -eq 'cuda') { 'https://download.pytorch.org/whl/cu130' } else { 'https://download.pytorch.org/whl/cpu' }
    $AimTorch = if ($Device -eq 'cuda') { 'torch==2.13.0+cu130' } else { 'torch==2.13.0+cpu' }
    & $AimPython -m pip install $AimTorch --index-url $AimIndex
    if ($LASTEXITCODE -ne 0) { throw 'PyTorch installation failed' }
    & $AimPython check_env.py
    if ($LASTEXITCODE -ne 0) { throw 'Hardware GRU check failed' }
    if ($Device -eq 'cuda') {
        & $AimPython -c "import torch; assert torch.cuda.is_available(), 'CUDA unavailable: check NVIDIA driver, or explicitly choose CPU'"
        if ($LASTEXITCODE -ne 0) { throw 'CUDA requested but unavailable' }
    }
    & $AimPython -m pip freeze | Out-File -FilePath installed-versions.txt -Encoding utf8
    Write-Host 'Ready. Follow README.md for preprocessing and training.'
} finally {
    Pop-Location
}
