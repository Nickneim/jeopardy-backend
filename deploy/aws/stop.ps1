# Stops the instance, after asking if games are still running.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"

try {
    $sessions = Invoke-RestMethod "https://$Domain/api/game-sessions?size=1" -TimeoutSec 10
    if ($sessions.totalElements -gt 0) {
        $answer = Read-Host "$($sessions.totalElements) game session(s) still running. Stop anyway? (y/N)"
        if ($answer -ne "y") { exit }
    }
} catch {
    Write-Host "The game isn't answering; stopping anyway."
}

aws ec2 stop-instances --instance-ids $InstanceId --output text | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Stopping the instance failed (signed in? aws sso login)" }
aws ec2 wait instance-stopped --instance-ids $InstanceId
Write-Host "Stopped."
