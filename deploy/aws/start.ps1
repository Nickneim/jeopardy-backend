# Starts the instance, points CloudFront at its new address and waits until
# the game answers through CloudFront.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"
. "$PSScriptRoot\common.ps1"

Connect-Aws

Write-Host "Starting the instance..."
Invoke-Aws ec2 start-instances --instance-ids $InstanceId --output text | Out-Null
Invoke-Aws ec2 wait instance-running --instance-ids $InstanceId
$dns = Invoke-Aws ec2 describe-instances --instance-ids $InstanceId `
    --query "Reservations[0].Instances[0].PublicDnsName" --output text

# Every start gives the instance a new public address.
Set-BackendOrigin $dns

# The backend needs a minute to start, and a CloudFront change a few minutes
# to reach every edge location.
Write-Host "Waiting for the game to answer (usually 2-5 minutes)..."
$deadline = (Get-Date).AddMinutes(15)
while ($true) {
    try {
        $response = Invoke-WebRequest "https://$Domain/api/games?size=1" -TimeoutSec 10
        if ($response.StatusCode -eq 200) { break }
    } catch { }
    if ((Get-Date) -gt $deadline) { throw "No answer from https://$Domain after 15 minutes" }
    Start-Sleep -Seconds 10
}

Write-Host "Ready: https://$Domain"
Write-Host "It stops itself 20 minutes after the last game ends; run stop.ps1 afterwards"
Write-Host "to take its old address out of CloudFront (or to stop it right away)."
