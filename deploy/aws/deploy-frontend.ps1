# Builds the frontend and uploads it to S3. The instance can stay stopped:
# CloudFront serves the frontend from the bucket.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\settings.ps1"

Push-Location $FrontendDir
try {
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw "pnpm install failed" }
    pnpm run build
    if ($LASTEXITCODE -ne 0) { throw "The frontend build failed" }

    # Hashed file names: a changed file gets a new name, so these can be cached
    # for a year. Old ones stay for pages loaded before this upload.
    aws s3 sync dist/assets "s3://$FrontendBucket/assets" --cache-control "public,max-age=31536000,immutable"
    if ($LASTEXITCODE -ne 0) { throw "Uploading the assets failed" }
    # index.html and the rest: checked again on every load, so the new version
    # shows up right away without a CloudFront invalidation.
    aws s3 sync dist "s3://$FrontendBucket" --exclude "assets/*" --cache-control "no-cache" --delete
    if ($LASTEXITCODE -ne 0) { throw "Uploading index.html failed" }
} finally {
    Pop-Location
}

Write-Host "Deployed: https://$Domain (reload the page to get it)"
