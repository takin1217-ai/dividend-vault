$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$env:GIT_AUTHOR_NAME = "takin1217-ai"
$env:GIT_AUTHOR_EMAIL = "273512078+takin1217-ai@users.noreply.github.com"
$env:GIT_COMMITTER_NAME = "takin1217-ai"
$env:GIT_COMMITTER_EMAIL = "273512078+takin1217-ai@users.noreply.github.com"

git add -A
git reset -q -- data secrets playstore
$staged = git diff --cached --name-only
$blocked = $staged | Where-Object { $_ -match '(^|/)(secrets/|\.env$|\.jks$|\.keystore$|\.aab$|\.apk$|\.sqlite$)' }
if ($blocked) {
    git reset -q -- $blocked
}
$staged = git diff --cached --name-only
if (-not $staged) {
    Write-Output "백업할 소스 변경이 없습니다."
    exit 0
}
git commit -m "작업 내용을 깃허브에 백업한다."
git pull --rebase origin main
if ($LASTEXITCODE -ne 0) {
    Write-Error "다른 곳의 수정과 겹쳤습니다. 강제 푸시는 하지 않습니다."
}
git push origin main
Write-Output "깃허브 main에 백업했습니다."
