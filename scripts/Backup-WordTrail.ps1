[CmdletBinding()]
param(
    [string]$CredentialFile=(Join-Path $PSScriptRoot '../.local/backup-credential.xml'),
    [string]$BackupDirectory=(Join-Path $PSScriptRoot '../backups'),
    [string]$MySqlBin,
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DbHost='127.0.0.1',
    [ValidateRange(1,65535)][int]$DbPort=3306,
    [ValidatePattern('^[A-Za-z0-9_]+$')][string]$Database='word_trail_db'
)
. (Join-Path $PSScriptRoot 'MySqlTools.ps1')
$mysql=Resolve-MySqlTool 'mysql' $MySqlBin
$dump=Resolve-MySqlTool 'mysqldump' $MySqlBin
$root=[IO.Path]::GetFullPath($BackupDirectory)
[void][IO.Directory]::CreateDirectory($root)
Protect-BackupPath $root $true
$destination=Join-Path $root ('wordtrail-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($destination)
$optionFile=$null
try {
    $optionFile=New-MySqlOptionFile $CredentialFile $destination
    $connection=@("--defaults-extra-file=$optionFile","--host=$DbHost","--port=$DbPort",'--default-character-set=utf8mb4')
    $tableText=Invoke-MySqlTool $mysql ($connection+@('--batch','--skip-column-names',"--database=$Database",'--execute=SHOW FULL TABLES'))
    $tables=@($tableText -split '\r?\n'|Where-Object {$_}|ForEach-Object {($_ -split "`t")[0]})
    if($tables.Count -eq 0){throw 'Refusing an empty database backup'}
    $sqlFile=Join-Path $destination 'database.sql'
    [void](Invoke-MySqlTool $dump ($connection+@('--single-transaction','--routines','--events','--triggers','--hex-blob','--set-gtid-purged=OFF','--no-tablespaces','--column-statistics=0',"--result-file=$sqlFile",$Database)))
    if((Get-Item -LiteralPath $sqlFile).Length -eq 0){throw 'Empty SQL backup'}
    $manifest=@{formatVersion=1;database=$Database;createdAtUtc=[DateTime]::UtcNow.ToString('o');tables=$tables;sqlSha256=(Get-FileHash -LiteralPath $sqlFile -Algorithm SHA256).Hash}
    [IO.File]::WriteAllText((Join-Path $destination 'manifest.json'),($manifest|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $destination 'COMPLETE'),'Backup and checksum completed.')
    Write-Output "BACKUP_DIRECTORY=$destination"
    Write-Output "BACKUP_TABLE_COUNT=$($tables.Count)"
    Write-Output "BACKUP_SHA256=$($manifest.sqlSha256)"
}finally{Remove-MySqlOptionFile $optionFile $destination}
