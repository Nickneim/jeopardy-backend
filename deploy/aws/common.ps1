# Helpers for the scripts next to this file, loaded after settings.ps1.

# Runs the AWS CLI and stops the script if it fails.
function Invoke-Aws {
    $output = aws @args
    if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') failed" }
    $output
}

# Signs in to AWS with SSO unless the current sign-in still works.
function Connect-Aws {
    aws sts get-caller-identity --output text *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Signing in to AWS..."
        Invoke-Aws sso login | Out-Null
    }
}

# Points CloudFront's backend origin (/api and /ws) at $DomainName, unless it
# already does. The change takes a few minutes to reach every edge location.
function Set-BackendOrigin([string]$DomainName) {
    $etag = Invoke-Aws cloudfront get-distribution-config --id $DistributionId --query ETag --output text
    $current = Invoke-Aws cloudfront get-distribution-config --id $DistributionId `
        --query "DistributionConfig.Origins.Items[?Id=='backend'].DomainName | [0]" --output text
    if ($current -eq $DomainName) { return }

    Write-Host "Pointing CloudFront's backend origin at $DomainName..."
    $config = (Invoke-Aws cloudfront get-distribution-config --id $DistributionId `
        --query DistributionConfig --output json) -join "`n"
    # Only the backend origin's DomainName, which follows its Id.
    $pattern = '("Id":\s*"backend",\s*"DomainName":\s*")[^"]*(")'
    if ([regex]::Matches($config, $pattern).Count -ne 1) { throw "Can't find the backend origin in the distribution" }
    $file = Join-Path $env:TEMP "jeopardy-distribution-update.json"
    [regex]::Replace($config, $pattern, "`${1}$DomainName`${2}") | Set-Content $file
    Invoke-Aws cloudfront update-distribution --id $DistributionId --if-match $etag `
        --distribution-config "file://$file" --query Distribution.Status --output text | Out-Null
}
