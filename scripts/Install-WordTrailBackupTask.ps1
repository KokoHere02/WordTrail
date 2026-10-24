[CmdletBinding()]
param(
    [string]$CredentialFile=(Join-Path $PSScriptRoot '../.local/backup-credential.xml'),
    [string]$BackupDirectory=(Join-Path $PSScriptRoot '../backups'),
    [string]$MySqlBin,
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DbHost='127.0.0.1',
    [ValidateRange(1,65535)][int]$DbPort=3306,
    [ValidatePattern('^[A-Za-z0-9_]+$')][string]$Database='word_trail_db',
    [ValidatePattern('^([01][0-9]|2[0-3]):[0-5][0-9]$')][string]$DailyAt='02:00'
)
$ErrorActionPreference='Stop'
$scriptPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'Backup-WordTrail.ps1'))
$credentialPath=[IO.Path]::GetFullPath($CredentialFile)
if(-not(Test-Path -LiteralPath $credentialPath)){throw 'Create the DPAPI-protected credential file before scheduling backups'}
$projectHash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($scriptPath))).Replace('-','').Substring(0,8)
$taskName="WordTrail-DailyBackup-$projectHash"
$arguments="-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$scriptPath`" -CredentialFile `"$credentialPath`" -BackupDirectory `"$([IO.Path]::GetFullPath($BackupDirectory))`""
if($MySqlBin){$arguments+=" -MySqlBin `"$([IO.Path]::GetFullPath($MySqlBin))`""}
$arguments+=" -DbHost `"$DbHost`" -DbPort $DbPort -Database `"$Database`""
$action=New-ScheduledTaskAction -Execute (Join-Path $PSHOME 'powershell.exe') -Argument $arguments
$trigger=New-ScheduledTaskTrigger -Daily -At ([DateTime]::ParseExact($DailyAt,'HH:mm',[Globalization.CultureInfo]::InvariantCulture))
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Daily WordTrail MySQL backup; credentials remain DPAPI-protected; backups are retained.' -Force|Out-Null
Write-Output "BACKUP_TASK_NAME=$taskName"
Write-Output "DAILY_LOCAL_TIME=$DailyAt"
