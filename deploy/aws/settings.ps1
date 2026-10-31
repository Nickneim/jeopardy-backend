# Ids of the AWS resources, loaded by the scripts next to this file. Nothing
# secret goes here: these only name things, and using them needs your AWS sign-in.
$env:AWS_PROFILE = "jeopardy"
$Region = "us-east-2"
$InstanceId = "i-0bb3a26a2c97e3c2c"
$DistributionId = "E37PH8DQYVKMWP"
$Domain = "d14x3ygmehrd5x.cloudfront.net"
$Registry = "241200392621.dkr.ecr.us-east-2.amazonaws.com"
$FrontendBucket = "jeopardy-frontend-241200392621"
# Where stop.ps1 points CloudFront's backend origin while the instance is
# stopped, instead of the instance's old public address, which AWS may give to
# someone else. Names under .invalid never exist (RFC 6761), so CloudFront
# answers 502 at once, which the frontend shows as "offline".
$OfflineOrigin = "jeopardy-backend-offline.invalid"
# The frontend repository, built and uploaded by deploy-frontend.ps1: a
# checkout named frontend next to this one, unless FRONTEND_DIR says otherwise.
$FrontendDir = if ($env:FRONTEND_DIR) { $env:FRONTEND_DIR } else { Join-Path $PSScriptRoot "..\..\..\frontend" }
