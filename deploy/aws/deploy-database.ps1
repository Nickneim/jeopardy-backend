# Replaces the game data on the instance with your local database: dumps the
# local Compose Postgres, keeps the dump in S3 (also your backup) and restores
# it on the instance through Run Command. Restarting the backend ends the games
# in progress.
#   .\deploy\aws\deploy-database.ps1                       dump the local database
#   .\deploy\aws\deploy-database.ps1 -Key jeopardy-2026-10-24.dump
#                                                          restore a dump already in S3
param([string]$Key)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"
. "$PSScriptRoot\common.ps1"

$bucket = "jeopardy-deploy-241200392621"
$localDb = "jeopardy-db-1"

# Runs a shell script on the instance as root, waits for it and returns its
# output. Stops this script if it fails.
function Invoke-OnInstance([string]$Comment, [string[]]$Commands) {
    $parameters = Join-Path $env:TEMP "jeopardy-run-command.json"
    @{ commands = @("set -euo pipefail") + $Commands } | ConvertTo-Json | Set-Content $parameters
    $commandId = Invoke-Aws ssm send-command --instance-ids $InstanceId --document-name AWS-RunShellScript `
        --comment $Comment --parameters "file://$parameters" --query Command.CommandId --output text
    aws ssm wait command-executed --command-id $commandId --instance-id $InstanceId
    $failed = $LASTEXITCODE -ne 0
    $result = Invoke-Aws ssm get-command-invocation --command-id $commandId --instance-id $InstanceId --output json |
        Out-String | ConvertFrom-Json
    if ($failed) {
        Write-Host $result.StandardOutputContent
        Write-Host $result.StandardErrorContent
        throw "$Comment failed on the instance"
    }
    $result.StandardOutputContent.Trim()
}

# The newest migration a database has, so a dump only goes to a backend on the
# same schema (Flyway and Hibernate's validation would refuse anything else).
$schemaQuery = "select version from flyway_schema_history where success order by installed_rank desc limit 1"

Connect-Aws

$state = Invoke-Aws ec2 describe-instances --instance-ids $InstanceId `
    --query "Reservations[0].Instances[0].State.Name" --output text
if ($state -ne "running") { throw "The instance is $state. Run start.ps1 first." }

# A query on the local database, as the user from your .env (the container's
# POSTGRES_USER), with the query passed to sh as $0.
function Invoke-LocalQuery([string]$Query) {
    $output = docker exec $localDb sh -c 'psql -U "$POSTGRES_USER" -d jeopardy -Atc "$0"' $Query
    if ($LASTEXITCODE -ne 0) { throw "Can't read the local database: is the Compose Postgres ($localDb) running?" }
    $output
}

if (-not $Key) {
    $localSchema = Invoke-LocalQuery $schemaQuery
    $localGames = Invoke-LocalQuery "select count(*) from game"

    $Key = "jeopardy-$(Get-Date -Format 'yyyy-MM-dd-HHmm').dump"
    $backups = Join-Path $HOME "jeopardy-backups"
    New-Item -ItemType Directory -Force $backups | Out-Null
    $dump = Join-Path $backups $Key
    Write-Host "Dumping the local database ($localGames games, schema version $localSchema)..."
    docker exec $localDb sh -c 'pg_dump -U "$POSTGRES_USER" -d jeopardy -Fc --no-owner --no-privileges -f /tmp/jeopardy.dump'
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed" }
    docker cp "$($localDb):/tmp/jeopardy.dump" $dump
    if ($LASTEXITCODE -ne 0) { throw "Copying the dump out of the container failed" }
    docker exec $localDb rm /tmp/jeopardy.dump

    Invoke-Aws s3 cp $dump "s3://$bucket/data/$Key" | Out-Null
    Write-Host "Uploaded s3://$bucket/data/$Key (local copy: $dump)"

    $remoteSchema = Invoke-OnInstance "Read the schema version" @(
        "docker exec jeopardy-db-1 psql -U jeopardy -d jeopardy -Atc '$schemaQuery'"
    )
    if ($remoteSchema -ne $localSchema) {
        throw "The instance's database is on schema version $remoteSchema, yours on $localSchema. Deploy the matching backend first."
    }
}

try {
    $sessions = Invoke-RestMethod "https://$Domain/api/game-sessions?size=1" -TimeoutSec 10
    if ($sessions.totalElements -gt 0) {
        $answer = Read-Host "$($sessions.totalElements) game session(s) running; restoring ends them. Continue? (y/N)"
        if ($answer -ne "y") { exit }
    }
} catch {
    Write-Host "The game isn't reachable through CloudFront, so nobody is playing."
}

# With the backend stopped, nothing holds a connection to the database. If a
# step fails, the backend stays stopped rather than starting on half the data.
Write-Host "Restoring $Key on the instance..."
$compose = "docker compose --project-directory /opt/jeopardy --env-file /opt/jeopardy/app.env --env-file /run/jeopardy/backend-tag.env"
$games = Invoke-OnInstance "Restore $Key" @(
    "aws s3 cp s3://$bucket/data/$Key /tmp/jeopardy.dump --region $Region --only-show-errors",
    "$compose stop backend > /dev/null 2>&1",
    "docker exec jeopardy-db-1 dropdb -U jeopardy --force jeopardy",
    "docker exec jeopardy-db-1 createdb -U jeopardy jeopardy",
    "docker cp /tmp/jeopardy.dump jeopardy-db-1:/tmp/jeopardy.dump > /dev/null",
    "docker exec jeopardy-db-1 pg_restore -U jeopardy -d jeopardy --no-owner --no-privileges /tmp/jeopardy.dump",
    "docker exec jeopardy-db-1 rm /tmp/jeopardy.dump",
    "rm /tmp/jeopardy.dump",
    "docker exec jeopardy-db-1 psql -U jeopardy -d jeopardy -Atc 'select count(*) from game'",
    "$compose start backend > /dev/null 2>&1"
)
Write-Host "Restored: $games games. Waiting for the backend to answer..."

$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    try {
        $response = Invoke-WebRequest "https://$Domain/api/games?size=1" -TimeoutSec 10
        if ($response.StatusCode -eq 200) { break }
    } catch { }
    if ((Get-Date) -gt $deadline) { throw "No answer from https://$Domain after 5 minutes; check the logs in CloudWatch" }
    Start-Sleep -Seconds 5
}
Write-Host "Done: https://$Domain"
