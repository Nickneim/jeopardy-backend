# Starts the instance, points CloudFront at its new address and waits until
# the game answers through CloudFront.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"

# Runs the AWS CLI and stops the script if it fails.
function Invoke-Aws {
    $output = aws @args
    if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') failed" }
    $output
}

aws sts get-caller-identity --output text *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Signing in to AWS..."
    Invoke-Aws sso login | Out-Null
}

Write-Host "Starting the instance..."
Invoke-Aws ec2 start-instances --instance-ids $InstanceId --output text | Out-Null
Invoke-Aws ec2 wait instance-running --instance-ids $InstanceId
$dns = Invoke-Aws ec2 describe-instances --instance-ids $InstanceId `
    --query "Reservations[0].Instances[0].PublicDnsName" --output text

# Every start gives the instance a new public address, and CloudFront's
# backend origin has to follow it.
$etag = Invoke-Aws cloudfront get-distribution-config --id $DistributionId --query ETag --output text
$oldDns = Invoke-Aws cloudfront get-distribution-config --id $DistributionId `
    --query "DistributionConfig.Origins.Items[?Id=='backend'].DomainName | [0]" --output text
if ($oldDns -ne $dns) {
    Write-Host "Pointing CloudFront at $dns..."
    $config = (Invoke-Aws cloudfront get-distribution-config --id $DistributionId `
        --query DistributionConfig --output json) -join "`n"
    $file = Join-Path $env:TEMP "jeopardy-distribution-update.json"
    $config.Replace("""$oldDns""", """$dns""") | Set-Content $file
    Invoke-Aws cloudfront update-distribution --id $DistributionId --if-match $etag `
        --distribution-config "file://$file" --query Distribution.Status --output text | Out-Null
}

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
Write-Host "It stops itself 20 minutes after the last game ends (stop.ps1 stops it now)."
