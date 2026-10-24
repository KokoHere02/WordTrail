$ErrorActionPreference='Stop'
foreach($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $parseErrors=$null
    [void][Management.Automation.Language.Parser]::ParseFile($script.FullName,[ref]$null,[ref]$parseErrors)
    if($parseErrors){throw "PowerShell syntax error: $($script.Name)"}
}
$testDirectory=Join-Path $PSScriptRoot ('../target/backup-safety-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testDirectory)
$sqlPath=Join-Path $testDirectory 'database.sql'
$manifestPath=Join-Path $testDirectory 'manifest.json'
function Expect-Rejected([string]$Expected) {
    try{& (Join-Path $PSScriptRoot 'Verify-WordTrailBackup.ps1') -BackupPath $testDirectory -CredentialFile 'not-used.xml';throw 'Verification unexpectedly accepted the invalid backup'}
    catch{if($_.Exception.Message -notlike "$Expected*"){throw}}
}
Expect-Rejected 'Backup is incomplete'
[IO.File]::WriteAllText((Join-Path $testDirectory 'COMPLETE'),'synthetic test only')
[IO.File]::WriteAllText($sqlPath,'SELECT 1;')
[IO.File]::WriteAllText($manifestPath,(@{formatVersion=1;database='word_trail_db';tables=@('synthetic');sqlSha256='invalid-checksum'}|ConvertTo-Json))
Expect-Rejected 'Backup checksum'
foreach($sql in @('USE word_trail_db;','CREATE DATABASE word_trail_db;','DROP DATABASE word_trail_db;')) {
    [IO.File]::WriteAllText($sqlPath,$sql)
    [IO.File]::WriteAllText($manifestPath,(@{formatVersion=1;database='word_trail_db';tables=@('synthetic');sqlSha256=(Get-FileHash -LiteralPath $sqlPath -Algorithm SHA256).Hash}|ConvertTo-Json))
    Expect-Rejected 'Backup may switch databases'
}
Write-Output 'BACKUP_SCRIPT_SYNTAX_AND_REJECTION_TESTS_PASSED=5'
