param(
    [ValidateSet('Install', 'Run', 'Show', 'Restart', 'Stop', 'Uninstall', 'Check')]
    [string]$Action = 'Show',
    [string]$Address,
    [int]$Port = 43823
)
$ErrorActionPreference = 'Stop'
$runtime = Join-Path $env:LOCALAPPDATA 'CodexBar\ClaudeCompanion'
$settingsPath = Join-Path $runtime 'windows-resident.json'
$statusPath = Join-Path $runtime 'resident-status.json'
$controlPath = Join-Path $runtime 'resident-control.json'
$pausePath = Join-Path $runtime 'resident-paused'
$scriptPath = Join-Path $runtime 'scripts\windows-resident.ps1'
$taskName = 'CodexBar Claude Companion'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$startupLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'CodexBar Claude Companion.lnk'
$menuDirectory = Join-Path ([Environment]::GetFolderPath('Programs')) 'CodexBar Claude Companion'
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value

function New-ResidentShortcut($path, $mode) {
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $powershell
    $shortcut.Arguments = '-NoProfile -NonInteractive -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $scriptPath + '" -Action ' + $mode
    $shortcut.WorkingDirectory = $runtime
    $shortcut.WindowStyle = 7
    $shortcut.Description = 'CodexBar Claude companion'
    $shortcut.Save()
}
function Start-Resident {
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile','-NonInteractive','-STA','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',('"' + $scriptPath + '"'),'-Action','Run') -WindowStyle Hidden | Out-Null
}
function Send-Control($command) {
    $temporary = "$controlPath.$PID.tmp"
    @{ command = $command; createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() } | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $controlPath -Force
}

if ($Action -eq 'Install') {
    $source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    if (Test-Path -LiteralPath $settingsPath) {
        $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($existingTask -and ($existingTask.Actions.Arguments -like ('*' + $scriptPath + '*'))) {
            Disable-ScheduledTask -TaskName $taskName | Out-Null
        }
        Send-Control 'exit'
        # Wait for the old resident to close its owned process tree before copying.
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            $running = $null
            if (![Threading.Mutex]::TryOpenExisting("Local\CodexBarClaudeResident-$sid", [ref]$running)) { break }
            $running.Dispose()
            Start-Sleep -Milliseconds 500
        }
        $running = $null
        if ([Threading.Mutex]::TryOpenExisting("Local\CodexBarClaudeResident-$sid", [ref]$running)) {
            $running.Dispose()
            throw 'The resident is still running. Exit it from its tray menu before installing.'
        }
        if (Test-Path -LiteralPath $controlPath) { Remove-Item -LiteralPath $controlPath }
    }
    if ($source.TrimEnd('\') -ne $runtime.TrimEnd('\')) {
        foreach ($name in @('src','scripts','test','package.json','package-lock.json','README.md','start-windows.cmd','start-macos-linux.sh')) {
            Copy-Item -LiteralPath (Join-Path $source $name) -Destination $runtime -Recurse -Force
        }
    }
    $node = (Get-Command node.exe).Source
    $claude = (Get-Command claude.exe).Source
    Push-Location $runtime
    try {
        & $node -e "require.resolve('node-pty'); require.resolve('@xterm/headless'); require.resolve('qrcode-terminal')" 2>$null
        if ($LASTEXITCODE -ne 0) {
            & (Get-Command npm.cmd).Source ci --omit=dev
            if ($LASTEXITCODE -ne 0) { throw 'Installing companion dependencies failed.' }
        }
    } finally { Pop-Location }
    if (!$Address -and (Test-Path -LiteralPath $settingsPath)) {
        $Address = (Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json).address
    }
    $parsedAddress = $null
    if (![Net.IPAddress]::TryParse($Address, [ref]$parsedAddress) -or $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $Port -lt 1024 -or $Port -gt 65535) {
        throw 'Install requires -Address with the already-paired private IPv4 address.'
    }
    @{ node = $node; claude = $claude; address = $Address; port = $Port } | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding UTF8
    $arguments = '-NoProfile -NonInteractive -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $scriptPath + '" -Action Run'
    $startupMode = 'Task Scheduler'
    try {
        $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($existing -and $existing.Actions.Arguments -notcontains $arguments) { throw 'An unrelated task uses this name.' }
        $taskAction = New-ScheduledTaskAction -Execute $powershell -Argument $arguments -WorkingDirectory $runtime
        $logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User $sid
        $watchdogTrigger = New-ScheduledTaskTrigger -Once -At ([DateTime]::Now.AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1)
        $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
        $taskSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
        Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger @($logonTrigger, $watchdogTrigger) -Principal $principal -Settings $taskSettings -Force | Out-Null
    } catch {
        # A standard user may not be allowed to register tasks. Logon still works.
        $startupMode = 'Startup folder (Task Scheduler unavailable)'
        New-ResidentShortcut $startupLink 'Run'
    }
    New-Item -ItemType Directory -Path $menuDirectory -Force | Out-Null
    New-ResidentShortcut (Join-Path $menuDirectory 'Claude companion status.lnk') 'Show'
    New-ResidentShortcut (Join-Path $menuDirectory 'Restart Claude companion.lnk') 'Restart'
    New-ResidentShortcut (Join-Path $menuDirectory 'Pause Claude companion.lnk') 'Stop'
    if ($startupMode -eq 'Task Scheduler') { Start-ScheduledTask -TaskName $taskName }
    else { Start-Resident }
    Write-Output "Installed resident. Startup: $startupMode. Existing pairing and Claude login preserved."
    exit 0
}

if ($Action -eq 'Uninstall') {
    Send-Control 'exit'
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing -and ($existing.Actions.Arguments -like ('*' + $scriptPath + '*'))) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    }
    foreach ($link in @($startupLink, (Join-Path $menuDirectory 'Claude companion status.lnk'), (Join-Path $menuDirectory 'Restart Claude companion.lnk'), (Join-Path $menuDirectory 'Pause Claude companion.lnk'))) {
        if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link }
    }
    Write-Output 'Automatic startup removed. Pairing, sign-in and companion files preserved.'
    exit 0
}

if (!(Test-Path -LiteralPath $settingsPath) -and $Action -ne 'Check') { throw 'Install the resident first.' }
if ($Action -in @('Show','Restart','Stop')) {
    Send-Control $Action.ToLowerInvariant()
    Start-Resident
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot 'WindowsChildJob.cs')
if ($Action -eq 'Check') {
    $jobTest = New-Object CodexBarChildJob
    $jobTest.Dispose()
    Write-Output 'PASS WinForms and Windows child-job initialization'
    exit 0
}
$created = $false
$mutex = New-Object Threading.Mutex($true, "Local\CodexBarClaudeResident-$sid", [ref]$created)
if (!$created) { $mutex.Dispose(); exit 0 }
$config = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
$script:child = $null
$script:job = $null
$script:paused = Test-Path -LiteralPath $pausePath
$script:attempts = 0
$script:nextStart = [DateTime]::UtcNow
$script:message = 'Starting'
$script:lastSuccess = $null
$script:startedAt = [DateTime]::MinValue
$script:quitting = $false

function Stop-OwnedChild {
    if ($script:job) { $script:job.Dispose(); $script:job = $null }
    if ($script:child) { $script:child.Dispose(); $script:child = $null }
}
function Retry-Later {
    Stop-OwnedChild
    $script:attempts++
    $seconds = [Math]::Min(300, 5 * [Math]::Pow(2, [Math]::Min($script:attempts - 1, 6)))
    $script:nextStart = [DateTime]::UtcNow.AddSeconds($seconds)
    $script:message = "Waiting for connection; retry in $seconds seconds"
}
function Show-Status {
    $last = if ($script:lastSuccess) { $script:lastSuccess.ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss') } else { 'Not collected yet' }
    $body = $script:message + "`r`n`r`nLast successful refresh: " + $last + "`r`nAddress: " + $config.address + ':' + $config.port + "`r`n`r`nUpdates every 5 minutes. Keep this PC awake and Tailscale connected.`r`nIf refresh keeps failing, check Claude sign-in in .codexbar\claude-workspace."
    [Windows.Forms.MessageBox]::Show($body, 'CodexBar Claude Companion', 'OK', 'Information') | Out-Null
}
function Handle-Control($command) {
    switch ($command) {
        'show' { Show-Status }
        'restart' { Stop-OwnedChild; $script:config = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json; if (Test-Path -LiteralPath $pausePath) { Remove-Item -LiteralPath $pausePath }; $script:paused = $false; $script:attempts = 0; $script:nextStart = [DateTime]::UtcNow }
        'stop' { Set-Content -LiteralPath $pausePath -Value 'paused' -Encoding ASCII; $script:paused = $true; Stop-OwnedChild; $script:message = 'Paused until Restart' }
        'exit' { $script:quitting = $true; [Windows.Forms.Application]::Exit() }
    }
}
function Resident-Tick {
    if (Test-Path -LiteralPath $controlPath) {
        try {
            $control = Get-Content -LiteralPath $controlPath -Raw | ConvertFrom-Json
            Remove-Item -LiteralPath $controlPath
            if ([Math]::Abs([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $control.createdAt) -lt 60) { Handle-Control $control.command }
        } catch { }
    }
    if ($script:paused) { $script:message = 'Paused until Restart'; $tray.Text = 'CodexBar Claude - paused'; return }
    if ($script:quitting) { return }
    if ($script:child -and $script:child.HasExited) { Retry-Later }
    if (!$script:child -and [DateTime]::UtcNow -ge $script:nextStart) {
        try {
            $startInfo = New-Object Diagnostics.ProcessStartInfo
            $startInfo.FileName = $config.node
            $startInfo.WorkingDirectory = $runtime
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            $startInfo.RedirectStandardInput = $true
            $startInfo.Arguments = '"' + (Join-Path $runtime 'scripts\windows-child.js') + '" --background --address ' + $config.address + ' --port ' + $config.port + ' --claude-command "' + $config.claude + '" --status-file "' + $statusPath + '"'
            $script:job = New-Object CodexBarChildJob
            $script:child = [Diagnostics.Process]::Start($startInfo)
            $script:job.Add($script:child.Handle)
            # Drain without storing potentially sensitive startup output.
            $script:child.BeginOutputReadLine()
            $script:child.BeginErrorReadLine()
            $script:child.StandardInput.WriteLine('start')
            $script:child.StandardInput.Close()
            $script:startedAt = [DateTime]::UtcNow
            $script:message = 'Collecting Claude usage'
        } catch {
            if ($script:child -and !$script:child.HasExited) { $script:child.Kill() }
            Retry-Later
        }
    }
    if ($script:child -and !$script:child.HasExited) {
        try {
            $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
            if ($status.pid -eq $script:child.Id) {
                if ($status.lastSuccessEpochSeconds) {
                    $script:lastSuccess = [DateTimeOffset]::FromUnixTimeSeconds($status.lastSuccessEpochSeconds).UtcDateTime
                    $script:attempts = 0
                }
                $script:message = if ($status.consecutiveFailures -gt 0) { 'Refresh failed; retrying automatically. Check Claude sign-in or Tailscale.' } elseif ($status.lastSuccessEpochSeconds) { 'Running - usage connection ready' } else { 'Collecting Claude usage' }
                $heartbeat = [DateTimeOffset]::FromUnixTimeSeconds($status.updatedAtEpochSeconds).UtcDateTime
                if (([DateTime]::UtcNow - $heartbeat).TotalMinutes -gt 8) { Retry-Later }
            } elseif (([DateTime]::UtcNow - $script:startedAt).TotalMinutes -gt 2) { Retry-Later }
        } catch {
            if (([DateTime]::UtcNow - $script:startedAt).TotalMinutes -gt 2) { Retry-Later }
        }
    }
    $tray.Text = if ($script:paused) { 'CodexBar Claude - paused' } else { 'CodexBar Claude - ' + $script:message.Substring(0, [Math]::Min(44, $script:message.Length)) }
}

$tray = New-Object Windows.Forms.NotifyIcon
$tray.Icon = [Drawing.SystemIcons]::Information
$tray.Text = 'CodexBar Claude companion'
$menu = New-Object Windows.Forms.ContextMenuStrip
$menu.Items.Add('Status').add_Click({ Show-Status })
$menu.Items.Add('Restart').add_Click({ Handle-Control 'restart' })
$menu.Items.Add('Pause').add_Click({ Handle-Control 'stop' })
$menu.Items.Add('Disable auto-start and exit').add_Click({
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',('"' + $scriptPath + '"'),'-Action','Uninstall') -WindowStyle Hidden | Out-Null
})
$tray.ContextMenuStrip = $menu
$tray.add_DoubleClick({ Show-Status })
$timer = New-Object Windows.Forms.Timer
$timer.Interval = 5000
$timer.add_Tick({ try { Resident-Tick } catch { Retry-Later } })
try {
    $tray.Visible = $true
    Resident-Tick
    $timer.Start()
    [Windows.Forms.Application]::Run()
} finally {
    $timer.Stop()
    Stop-OwnedChild
    $tray.Visible = $false
    $tray.Dispose()
    $menu.Dispose()
    $timer.Dispose()
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
