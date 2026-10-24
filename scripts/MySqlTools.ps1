$ErrorActionPreference='Stop'
function Resolve-MySqlTool([string]$Name,[string]$BinDirectory) {
    if($BinDirectory){$candidate=Join-Path $BinDirectory "$Name.exe"}
    else {
        $command=Get-Command "$Name.exe" -ErrorAction SilentlyContinue
        if($command){$candidate=$command.Source}else{$candidate=Join-Path $env:ProgramFiles "MySQL/MySQL Server 8.0/bin/$Name.exe"}
    }
    if(-not(Test-Path -LiteralPath $candidate -PathType Leaf)){throw "MySQL tool not found: $Name; configure -MySqlBin"}
    return [IO.Path]::GetFullPath($candidate)
}
function Protect-BackupPath([string]$Path,[bool]$Directory) {
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=Get-Acl -LiteralPath $Path
    $allowed=@($acl.Access|Where-Object {$_.AccessControlType -eq 'Allow'})
    $foreign=@($allowed|Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value})
    $full=[Security.AccessControl.FileSystemRights]::FullControl
    $ownFull=@($allowed|Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $sid.Value -and ($_.FileSystemRights -band $full) -eq $full})
    # Existing private directories and inherited private files need no permission rewrite.
    if($foreign.Count -eq 0 -and $ownFull.Count -gt 0){return}
    # icacls modifies only the DACL, preserving ownership and auditing settings.
    $icacls=Join-Path $env:SystemRoot 'System32/icacls.exe'
    $grant='*'+$sid.Value+':F';if($Directory){$grant='*'+$sid.Value+':(OI)(CI)F'}
    & $icacls $Path '/inheritance:r' '/grant:r' $grant *> $null
    if($LASTEXITCODE -ne 0){throw 'Cannot restrict backup path access'}
    foreach($identity in @($foreign|ForEach-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value}|Select-Object -Unique)){
        & $icacls $Path '/remove:g' ('*'+$identity) *> $null
        if($LASTEXITCODE -ne 0){throw 'Cannot remove inherited backup path access'}
    }
    $after=Get-Acl -LiteralPath $Path
    if(@($after.Access|Where-Object {$_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value}).Count -ne 0){throw 'Backup path is accessible by another identity'}
}
function New-MySqlOptionFile([string]$CredentialFile,[string]$Directory) {
    $credential=Import-Clixml -LiteralPath $CredentialFile
    if($credential -isnot [Management.Automation.PSCredential]){throw 'Expected a DPAPI-protected PSCredential file'}
    $path=Join-Path $Directory ('.mysql-'+[Guid]::NewGuid().ToString('N')+'.cnf')
    [IO.File]::WriteAllText($path,'')
    try {
        Protect-BackupPath $path $false
        $password=$credential.GetNetworkCredential().Password
        $escapedPassword=$password.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')
        $escapedUser=$credential.UserName.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')
        [IO.File]::WriteAllText($path,"[client]`nuser=`"$escapedUser`"`npassword=`"$escapedPassword`"`n",[Text.UTF8Encoding]::new($false))
        return $path
    }catch{Remove-Item -LiteralPath $path -Force;throw}
}
function Invoke-MySqlTool([string]$Executable,[string[]]$Arguments,[string]$InputFile) {
    $start=New-Object Diagnostics.ProcessStartInfo
    $start.FileName=$Executable
    $start.Arguments=($Arguments|ForEach-Object {'"'+$_.Replace('"','\"')+'"'}) -join ' '
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    $start.RedirectStandardInput=[bool]$InputFile
    $process=New-Object Diagnostics.Process
    $process.StartInfo=$start
    try {
        [void]$process.Start()
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if($InputFile){$inputStream=[IO.File]::OpenRead($InputFile);try{$inputStream.CopyTo($process.StandardInput.BaseStream)}finally{$inputStream.Dispose();$process.StandardInput.Close()}}
        $process.WaitForExit()
        $output=$stdout.GetAwaiter().GetResult()
        [void]$stderr.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw "MySQL operation failed with exit code $($process.ExitCode); sensitive native diagnostics were suppressed"}
        return $output
    }finally{$process.Dispose()}
}
function Remove-MySqlOptionFile([string]$Path,[string]$Directory) {
    if($Path){$resolved=[IO.Path]::GetFullPath($Path);$root=[IO.Path]::GetFullPath($Directory).TrimEnd('\')+'\';if(-not $resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'Credential cleanup target is outside the backup directory'};Remove-Item -LiteralPath $resolved -Force}
}
