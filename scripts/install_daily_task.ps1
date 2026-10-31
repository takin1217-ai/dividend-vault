$ErrorActionPreference = "Stop"
$python = (Get-Command python).Source
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$action = New-ScheduledTaskAction -Execute $python -Argument "-m dividend_vault sync" -WorkingDirectory $root
$morning = New-ScheduledTaskTrigger -Daily -At 7:20am
$evening = New-ScheduledTaskTrigger -Daily -At 5:10pm
Register-ScheduledTask -TaskName "DividendVaultDailySync" -Action $action -Trigger @($morning, $evening) -Description "배당금고 일봉을 매일 두 번 이어서 받습니다." -Force
Write-Output "DividendVaultDailySync 작업을 등록했습니다."
