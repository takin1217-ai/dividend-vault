$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$script = Join-Path $root "scripts\github_backup.ps1"
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`"" -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -Daily -At 9:40pm
Register-ScheduledTask -TaskName "DividendVaultGitHubBackup" -Action $action -Trigger $trigger -Description "배당금고 소스를 매일 깃허브 main에 백업합니다. 시세 데이터베이스와 키는 올리지 않습니다." -Force
Write-Output "DividendVaultGitHubBackup 작업을 매일 21:40에 등록했습니다."
