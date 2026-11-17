$ErrorActionPreference = 'Stop'

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

$envPath = Join-Path $PSScriptRoot '.env.local'
if (!(Test-Path -LiteralPath $envPath)) {
    $content = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '.env.local.example'))
    foreach ($name in 'DB_PW', 'LIVEKIT_API_KEY', 'LIVEKIT_API_SECRET', 'AI_INTERNAL_KEY') {
        $content = [regex]::Replace($content, "(?m)^$name=.*$", "$name=$(New-LocalSecret)")
    }
    [IO.File]::WriteAllText($envPath, $content)
    Write-Output 'Created infra/.env.local with random development credentials.'
} else {
    $content = [IO.File]::ReadAllText($envPath)
    if ($content -notmatch '(?m)^AI_INTERNAL_KEY=') {
        [IO.File]::AppendAllText($envPath, "`n# Spring-FastAPI internal authentication`nAI_INTERNAL_KEY=$(New-LocalSecret)`n")
        Write-Output 'Added missing AI_INTERNAL_KEY; preserved all existing values.'
    } else {
        Write-Output 'Preserved existing infra/.env.local.'
    }
}

$frontDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'tutipal_front'
$frontEnv = Join-Path $frontDir '.env'
if (!(Test-Path -LiteralPath $frontEnv)) {
    Copy-Item -LiteralPath (Join-Path $frontDir '.env.example') -Destination $frontEnv
    Write-Output 'Created frontend public development settings.'
} else {
    Write-Output 'Preserved existing frontend .env.'
}
