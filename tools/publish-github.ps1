# Публикация публичной копии репозитория на GitHub.
#
# Публичная история пересобирается из рабочего репозитория без аварийного архива
# моделей models/files/ (GitHub отклоняет файлы >100 МБ в обычном git) и без
# внутреннего файла инструкций агента AGENTS.md.
# Токен и адрес репозитория берутся из .env (GitHubRepo, GitHubRepoToken).
#
# Использование:
#   tools/publish-github.ps1                         — только main и теги
#   tools/publish-github.ps1 -Tag v0.5.8 `
#        -Apk app\build\outputs\apk\release\app-release.apk
#                                                    — + GitHub Release с подписанным APK
#
# На GitHub публикуются только подписанные release-сборки (debug-APK не выкладывать).

[CmdletBinding()]
param(
    [string]$Tag,
    [string]$Apk
)

# git пишет прогресс в stderr — при Stop PowerShell 5.1 считал бы его ошибкой;
# ошибки git контролируются по $LASTEXITCODE ниже.
$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$mirrorDir = Join-Path $env:LOCALAPPDATA 'LLM-Translator-github-mirror'

# --- параметры из .env ---
$envPath = Join-Path $repoRoot '.env'
if (-not (Test-Path $envPath)) { throw "Не найден .env: $envPath" }
$envLines = Get-Content $envPath -Encoding UTF8
$repoUrl = ($envLines | Where-Object { $_ -match '^GitHubRepo=' }) -replace '^GitHubRepo=', ''
$token = ($envLines | Where-Object { $_ -match '^GitHubRepoToken=' }) -replace '^GitHubRepoToken=', ''
if (-not $repoUrl -or -not $token) { throw 'В .env не заданы GitHubRepo / GitHubRepoToken' }
if ($repoUrl -notmatch '^https://github\.com/([^/]+)/([^/.]+)') { throw "Неожиданный GitHubRepo: $repoUrl" }
$owner = $Matches[1]; $repo = $Matches[2]
$authUrl = $repoUrl -replace 'https://', "https://x-access-token:$token@"

# --- зеркало без models/files/ и без внутреннего AGENTS.md ---
if (Test-Path $mirrorDir) { Remove-Item -Recurse -Force $mirrorDir }
git clone --no-hardlinks --mirror $repoRoot $mirrorDir
if ($LASTEXITCODE -ne 0) { throw 'git clone зеркала не удался' }
git -C $mirrorDir filter-repo --path models/files --path AGENTS.md --invert-paths --force
if ($LASTEXITCODE -ne 0) { throw 'git filter-repo не удался (нужен: pip install git-filter-repo)' }

# --- пуш main и тегов ---
$refs = @('refs/heads/main') + (git -C $mirrorDir for-each-ref --format='%(refname)' refs/tags)
git -C $mirrorDir push --force $authUrl @refs
if ($LASTEXITCODE -ne 0) { throw 'git push в GitHub не удался' }
Write-Host "Опубликовано в $repoUrl`: main + $($refs.Count - 1) тегов (история без models/files/)."

# --- GitHub Release с подписанным APK ---
if ($Tag) {
    if (-not $Apk) { throw 'Для -Tag обязателен -Apk (путь к app-release.apk)' }
    $apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
    if (-not (Test-Path $apkPath)) { throw "APK не найден: $apkPath" }

    $headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'publish-github' }
    $ErrorActionPreference = 'Stop'
    $payload = @{ tag_name = $Tag; name = "LLM-Translator $Tag"; draft = $false; prerelease = $false } | ConvertTo-Json
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases" `
        -Method Post -Headers $headers -Body $payload -ContentType 'application/json; charset=utf-8'
    # Имя ассета без версии: сохраняет ссылку
    # .../releases/latest/download/LLM-Translator-release.apk актуальной для любого релиза.
    $assetName = "LLM-Translator-release.apk"
    $uploaded = Invoke-RestMethod -Uri "https://uploads.github.com/repos/$owner/$repo/releases/$($release.id)/assets?name=$assetName" `
        -Method Post -Headers $headers -ContentType 'application/vnd.android.package-archive' -InFile $apkPath
    Write-Host "Release ${Tag}: $($uploaded.browser_download_url)"
}
