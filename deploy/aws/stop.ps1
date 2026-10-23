# Stops the instance (after asking if games are still running) and takes its
# address out of CloudFront. Also run it after the instance stopped by itself:
# then it only does the CloudFront part.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"
. "$PSScriptRoot\common.ps1"

Connect-Aws

$state = Invoke-Aws ec2 describe-instances --instance-ids $InstanceId `
    --query "Reservations[0].Instances[0].State.Name" --output text
if ($state -ne "stopped") {
    try {
        $sessions = Invoke-RestMethod "https://$Domain/api/game-sessions?size=1" -TimeoutSec 10
        if ($sessions.totalElements -gt 0) {
            $answer = Read-Host "$($sessions.totalElements) game session(s) still running. Stop anyway? (y/N)"
            if ($answer -ne "y") { exit }
        }
    } catch {
        Write-Host "The game isn't answering; stopping anyway."
    }

    Write-Host "Stopping the instance..."
    Invoke-Aws ec2 stop-instances --instance-ids $InstanceId --output text | Out-Null
    Invoke-Aws ec2 wait instance-stopped --instance-ids $InstanceId
}

# The stopped instance's public address goes back to AWS, which may give it to
# someone else: send /api and /ws somewhere of ours instead.
Set-BackendOrigin $OfflineOrigin
Write-Host "Stopped."
