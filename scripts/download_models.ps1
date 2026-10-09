#requires -version 5.1
<#
.DESCRIPTION
    Загрузка моделей LLM-Translator из models/manifest.json в models/<id>/<file>.
    - Скачивание с докачкой (Range) — как BaseDownloader в приложении.
    - Проверка SHA-256 (если задан в манифесте).
    - Печатает SHA-256 всех скачанных файлов (для фиксации в манифесте/каталоге).
    Использование:
        .\scripts\download_models.ps1                 # скачать всё
        .\scripts\download_models.ps1 -Ids opus-mt-tiny-ru-en-int8
        .\scripts\download_models.ps1 -PrintShaOnly   # только посчитать SHA уже скачанных
#>
[CmdletBinding()]
param(
    [string[]]$Ids = @(),
    [switch]$PrintShaOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $root "models\manifest.json"
$modelsRoot = Join-Path $root "models"

if (-not (Test-Path $manifestPath)) {
    Write-Error "Манифест не найден: $manifestPath"
    exit 1
}

$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json

function Get-Sha256([string]$path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
}

function Download-File([string]$url, [string]$destPath, [string]$expectedSha) {
    $partPath = "$destPath.part"
    $uri = [uri]$url
    $attempt = 0
    $maxAttempts = 5

    while ($attempt -lt $maxAttempts) {
        $attempt++
        $resumeFrom = 0
        if (Test-Path $partPath) {
            $resumeFrom = (Get-Item -LiteralPath $partPath).Length
        }

        try {
            $request = [Net.HttpWebRequest]::Create($uri)
            $request.Timeout = 30000
            if ($resumeFrom -gt 0) {
                $request.AddRange($resumeFrom, [int64]::MaxValue)
            }
            $response = $request.GetResponse()
            $isPartial = ($response.StatusCode -eq [Net.HttpStatusCode]::PartialContent)
            $total = $resumeFrom + $response.ContentLength

            $fs = if ($isPartial) {
                [IO.FileStream]::new($partPath, [IO.FileMode]::Append)
            } else {
                [IO.FileStream]::new($partPath, [IO.FileMode]::Create)
            }

            try {
                $buffer = New-Object byte[] 65536
                $received = 0
                $lastPct = -1
                while (($read = $response.GetResponseStream().Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $fs.Write($buffer, 0, $read)
                    $received += $read
                    if ($total -gt 0) {
                        $pct = [int](($resumeFrom + $received) * 100 / $total)
                        if ($pct -ne $lastPct) {
                            $lastPct = $pct
                            Write-Verbose "  $([System.IO.Path]::GetFileName($destPath)): ${pct}%"
                        }
                    }
                }
            } finally {
                $fs.Dispose()
                $response.Close()
            }

            # Финальный rename.
            if (Test-Path $destPath) { Remove-Item -LiteralPath $destPath -Force }
            Move-Item -LiteralPath $partPath -Destination $destPath -Force
            return
        } catch {
            Write-Warning "Попытка $attempt/$maxAttempts не удалась ($url): $($_.Exception.Message)"
            Start-Sleep -Seconds 2
        }
    }
    throw "Не удалось скачать: $url"
}

$models = $manifest.models | Where-Object {
    ($_.files.Count -gt 0) -and
    ($Ids.Count -eq 0 -or ($Ids -contains $_.id))
}

foreach ($model in $models) {
    $modelDir = Join-Path $modelsRoot $model.id
    New-Item -ItemType Directory -Force -Path $modelDir | Out-Null
    Write-Host "`n=== $model.id ===" -ForegroundColor Cyan

    foreach ($f in $model.files) {
        $destPath = Join-Path $modelDir $f.name
        if (-not $PrintShaOnly) {
            if ((Test-Path $destPath) -and $f.sha256) {
                $existingSha = Get-Sha256 $destPath
                if ($existingSha -eq $f.sha256) {
                    Write-Host "  [пропущено, SHA совпадает] $($f.name)" -ForegroundColor DarkGray
                    continue
                }
            }
            Write-Host "  [скачиваю] $($f.name)" -ForegroundColor Yellow
            Download-File $f.url $destPath $f.sha256
        }

        # Проверка / печать SHA-256.
        if (Test-Path $destPath) {
            $actualSha = Get-Sha256 $destPath
            if ($f.sha256) {
                if ($actualSha -ne $f.sha256) {
                    Write-Error "SHA-256 НЕ СОВПАДАЕТ для $($f.name): expected=$($f.sha256) actual=$actualSha"
                    exit 1
                } else {
                    Write-Host "  [OK] $($f.name): $actualSha" -ForegroundColor Green
                }
            } else {
                Write-Host "  [SHA] $($f.name): $actualSha" -ForegroundColor Magenta
            }
        }
    }
}

Write-Host "`nГотово. SHA-256 выше можно зафиксировать в models/manifest.json." -ForegroundColor Green
