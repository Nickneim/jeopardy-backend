# Builds the backend at the current commit, pushes it to ECR and makes the
# instance run it: right away if it's running (restarting the backend ends the
# games in progress), otherwise at its next start.
#   .\deploy\aws\deploy-backend.ps1               run the tests first
#   .\deploy\aws\deploy-backend.ps1 -SkipTests
#   .\deploy\aws\deploy-backend.ps1 -Tag 9a4dd64  run an image already in ECR (roll back)
param(
    [switch]$SkipTests,
    [string]$Tag
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"
. "$PSScriptRoot\common.ps1"

Connect-Aws

if (-not $Tag) {
    $repo = git -C $PSScriptRoot rev-parse --show-toplevel
    if (git -C $repo status --porcelain) {
        throw "Commit your changes first: the image is tagged with the commit it's built from."
    }
    $Tag = git -C $repo rev-parse --short HEAD
    $image = "$Registry/jeopardy-backend:$Tag"

    aws ecr describe-images --repository-name jeopardy-backend --image-ids imageTag=$Tag *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "$Tag is already in ECR, not building it again."
    } else {
        # Docker's containerd image store pushes an empty layer that ECR rejects.
        if ((docker info --format '{{json .DriverStatus}}') -match "containerd") {
            throw "Turn off Docker Desktop's 'Use containerd for pulling and storing images' to push."
        }
        Push-Location $repo
        try {
            if (-not $SkipTests) {
                .\gradlew.bat build
                if ($LASTEXITCODE -ne 0) { throw "The build or the tests failed" }
            }
            .\gradlew.bat bootBuildImage --imagePlatform=linux/arm64 --imageName=$image
            if ($LASTEXITCODE -ne 0) { throw "Building the image failed" }
        } finally {
            Pop-Location
        }
        Invoke-Aws ecr get-login-password | docker login --username AWS --password-stdin $Registry
        if ($LASTEXITCODE -ne 0) { throw "Signing Docker in to ECR failed" }
        docker push $image
        if ($LASTEXITCODE -ne 0) { throw "Pushing the image failed" }
    }
}

Invoke-Aws ssm put-parameter --name /jeopardy/backend-tag --type String --value $Tag --overwrite | Out-Null
Write-Host "The instance will run $Tag."

$state = Invoke-Aws ec2 describe-instances --instance-ids $InstanceId `
    --query "Reservations[0].Instances[0].State.Name" --output text
if ($state -ne "running") {
    Write-Host "The instance is $state; it runs $Tag from its next start."
    exit
}

$sessions = Invoke-RestMethod "https://$Domain/api/game-sessions?size=1" -TimeoutSec 10
if ($sessions.totalElements -gt 0) {
    $answer = Read-Host "$($sessions.totalElements) game session(s) running; restarting ends them. Restart now? (y/N)"
    if ($answer -ne "y") {
        Write-Host "Not restarted: $Tag starts with the next start, or run this script again."
        exit
    }
}

# Run Command: runs a command on the instance through the Systems Manager agent,
# without a session.
Write-Host "Restarting the backend on the instance..."
$commandId = Invoke-Aws ssm send-command --instance-ids $InstanceId --document-name AWS-RunShellScript `
    --comment "Deploy backend $Tag" --parameters 'commands=["systemctl restart jeopardy"]' `
    --query Command.CommandId --output text
aws ssm wait command-executed --command-id $commandId --instance-id $InstanceId
if ($LASTEXITCODE -ne 0) {
    Invoke-Aws ssm get-command-invocation --command-id $commandId --instance-id $InstanceId `
        --query "[Status,StandardErrorContent]" --output text
    throw "The restart failed"
}

Write-Host "Waiting for the backend to answer..."
$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    try {
        $response = Invoke-WebRequest "https://$Domain/api/games?size=1" -TimeoutSec 10
        if ($response.StatusCode -eq 200) { break }
    } catch { }
    if ((Get-Date) -gt $deadline) { throw "No answer from https://$Domain after 5 minutes; check the logs in CloudWatch" }
    Start-Sleep -Seconds 5
}
Write-Host "Deployed $($Tag): https://$Domain"
