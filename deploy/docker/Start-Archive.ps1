[CmdletBinding()]
param([switch]$PrepareOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$environmentPath = Join-Path $projectRoot '.env.docker'

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

if (-not (Test-Path -LiteralPath $environmentPath)) {
    $template = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.docker.example'))
    $values = @{
        POSTGRES_PASSWORD = New-RandomSecret
        MINIO_PASSWORD = New-RandomSecret
        JWT_SECRET = New-RandomSecret
        SETTINGS_ENCRYPTION_KEY = New-RandomSecret
        ARCHIVE_INSTANCE_UUID = [guid]::NewGuid().ToString()
        ADMIN_PASSWORD = New-RandomSecret
    }
    foreach ($key in $values.Keys) {
        $template = $template.Replace("__${key}__", $values[$key])
    }
    [IO.File]::WriteAllText($environmentPath, $template, (New-Object Text.UTF8Encoding $false))
    Write-Host 'Created .env.docker with unique passwords and installation keys.'
} else {
    Write-Host 'Using existing .env.docker; passwords and installation keys were preserved.'
}

if ($PrepareOnly) { return }

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $dockerPath = $dockerCommand.Source
} else {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe')
    )
    $dockerPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $dockerPath) { throw 'Docker CLI was not found. Install/open Docker Desktop with Linux containers.' }

Push-Location $projectRoot
try {
    $engineType = & $dockerPath info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable. Open Docker Desktop and wait for it to start.' }
    if ($engineType -ne 'linux') { throw 'Switch Docker Desktop to Linux containers before starting this project.' }
    & $dockerPath compose --env-file .env.docker config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration is invalid.' }
    & $dockerPath compose --env-file .env.docker up --build --detach --wait --wait-timeout 360
    if ($LASTEXITCODE -ne 0) {
        throw 'Startup did not complete. Run: docker compose --env-file .env.docker logs --tail 100'
    }
    & $dockerPath compose --env-file .env.docker ps
    Write-Host 'Open http://localhost:3100 (unless you changed DOCKER_FRONTEND_PORT).'
    Write-Host 'Username: admin. Find DOCKER_ADMIN_PASSWORD in .env.docker.'
} finally {
    Pop-Location
}
