[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BackupPath,
    [string]$CredentialFile=(Join-Path $PSScriptRoot '../.local/backup-credential.xml'),
    [string]$MySqlBin,
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DbHost='127.0.0.1',
    [ValidateRange(1,65535)][int]$DbPort=3306
)
. (Join-Path $PSScriptRoot 'MySqlTools.ps1')
$backup=[IO.Path]::GetFullPath($BackupPath)
if(-not(Test-Path -LiteralPath (Join-Path $backup 'COMPLETE'))){throw 'Backup is incomplete'}
$manifest=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
$sqlFile=Join-Path $backup 'database.sql'
if($manifest.formatVersion -ne 1 -or (Get-FileHash -LiteralPath $sqlFile -Algorithm SHA256).Hash -ne $manifest.sqlSha256){throw 'Backup checksum or format verification failed'}
if([regex]::IsMatch([IO.File]::ReadAllText($sqlFile),'(?im)^\s*(USE\s|CREATE\s+DATABASE|DROP\s+DATABASE)')){throw 'Backup may switch databases; refusing restore'}
$mysql=Resolve-MySqlTool 'mysql' $MySqlBin
$schema='wordtrail_backup_verify_'+[Guid]::NewGuid().ToString('N')
$optionFile=$null
try {
    $optionFile=New-MySqlOptionFile $CredentialFile $backup
    $connection=@("--defaults-extra-file=$optionFile","--host=$DbHost","--port=$DbPort",'--default-character-set=utf8mb4','--batch','--skip-column-names')
    [void](Invoke-MySqlTool $mysql ($connection+@("--execute=CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")))
    [void](Invoke-MySqlTool $mysql ($connection+@("--database=$schema")) $sqlFile)
    $actualText=Invoke-MySqlTool $mysql ($connection+@("--database=$schema",'--execute=SHOW FULL TABLES'))
    $actual=@($actualText -split '\r?\n'|Where-Object {$_}|ForEach-Object {($_ -split "`t")[0]})
    if(@(Compare-Object ($manifest.tables|Sort-Object) ($actual|Sort-Object)).Count -ne 0){throw 'Restored table list differs from backup'}
    foreach($table in $actual){if($table -notmatch '^[A-Za-z0-9_]+$'){throw 'Unexpected table name'};$checked=Invoke-MySqlTool $mysql ($connection+@("--database=$schema","--execute=CHECK TABLE ``$table``"));if($checked -notmatch '\tstatus\tOK\s*$'){throw "Restored table check failed: $table"}}
    Write-Output "RESTORE_VERIFICATION_SCHEMA=$schema"
    Write-Output "RESTORED_TABLE_COUNT=$($actual.Count)"
    Write-Output 'BACKUP_RESTORE_VERIFIED=true'
}finally{Remove-MySqlOptionFile $optionFile $backup}
