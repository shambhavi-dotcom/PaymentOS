param(
    [string]$MavenCommand = "mvn.cmd"
)

$ErrorActionPreference = "Stop"
$backendDirectory = Split-Path -Parent $PSScriptRoot
$frontendDirectory = Join-Path (Split-Path -Parent $backendDirectory) "frontend"

Push-Location $backendDirectory
try {
    & $MavenCommand "-Dtest=RoutingBenchmarkTest" "-Dpaymentos.benchmark.run=true" test
    if ($LASTEXITCODE -ne 0) {
        throw "Routing benchmark failed with Maven exit code $LASTEXITCODE"
    }

    $results = Join-Path $PSScriptRoot "results\routing-benchmark.json"
    $publicDirectory = Join-Path $frontendDirectory "public"
    New-Item -ItemType Directory -Path $publicDirectory -Force | Out-Null
    Copy-Item $results (Join-Path $publicDirectory "routing-benchmark.json") -Force
    Write-Output "Benchmark JSON and Markdown report written to $PSScriptRoot\results"
    Write-Output "Dashboard benchmark snapshot copied to $publicDirectory"
}
finally {
    Pop-Location
}
