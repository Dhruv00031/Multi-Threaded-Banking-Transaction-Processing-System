[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [ValidateLength(1, 36)]
    [string]$AccountId,

    [ValidateRange(1, 10000)]
    [int]$TransactionCount = 100,

    [ValidateRange(1, 128)]
    [int]$Concurrency = 10,

    [ValidateScript({ $_ -gt 0 })]
    [decimal]$Amount = 1.00,

    [string]$BaseUrl = 'http://localhost:8080',

    [string]$ResultsPath,

    [switch]$ConfirmWrites
)

$ErrorActionPreference = 'Stop'

if (-not $ConfirmWrites) {
    throw 'This script changes an account balance. Re-run with -ConfirmWrites after selecting a test account.'
}

$base = $BaseUrl.TrimEnd('/')
$statsUrl = "$base/api/transactions/executor/stats"
$depositUrl = "$base/api/transactions/deposit"
$runToken = [guid]::NewGuid().ToString('N')

Write-Host "Reading executor statistics before the run..."
$statsBefore = Invoke-RestMethod `
    -Uri $statsUrl `
    -OperationTimeoutSeconds 30

$results = 1..$TransactionCount | ForEach-Object -Parallel {
    $requestNumber = $_
    $runToken = $using:runToken
    $accountId = $using:AccountId
    $amount = $using:Amount
    $requestUri = $using:depositUrl
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = 0

    $body = @{
        transactionId = "$runToken-$requestNumber"
        accountId = $accountId
        amount = $amount
        description = 'Opt-in concurrent API load test'
    } | ConvertTo-Json -Compress

    try {
        $response = Invoke-WebRequest `
            -Uri $requestUri `
            -Method Post `
            -ContentType 'application/json' `
            -Body $body `
            -SkipHttpErrorCheck `
            -OperationTimeoutSeconds 60
        $statusCode = [int]$response.StatusCode
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response -and $null -ne $response.StatusCode) {
            $statusCode = [int]$response.StatusCode
        }
    } finally {
        $timer.Stop()
    }

    [pscustomobject]@{
        RequestNumber = $requestNumber
        StatusCode = $statusCode
        LatencyMs = $timer.Elapsed.TotalMilliseconds
    }
} -ThrottleLimit $Concurrency

Write-Host "Reading executor statistics after the run..."
$statsAfter = Invoke-RestMethod `
    -Uri $statsUrl `
    -OperationTimeoutSeconds 30

$successCount = @($results | Where-Object { $_.StatusCode -eq 201 }).Count
$rejectedCount = @($results | Where-Object {
    ($_.StatusCode -ge 400 -and $_.StatusCode -lt 500) -or $_.StatusCode -eq 503
}).Count
$failedCount = $TransactionCount - $successCount - $rejectedCount
$latencies = @($results | ForEach-Object { [double]$_.LatencyMs } | Sort-Object)

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    [int]$index = [Math]::Max(0, [Math]::Ceiling($Percentile * $Values.Count) - 1)
    return $Values[$index]
}

$averageLatency = ($latencies | Measure-Object -Average).Average

if ($ResultsPath) {
    $results | Export-Csv -Path $ResultsPath -NoTypeInformation
    Write-Host "Per-request statuses and latencies saved to $ResultsPath"
}

Write-Host ''
Write-Host 'Load test summary'
[pscustomobject]@{
    Submitted = $TransactionCount
    Successful = $successCount
    Rejected = $rejectedCount
    Failed = $failedCount
    Concurrency = $Concurrency
    AverageLatencyMs = [Math]::Round($averageLatency, 2)
    MinimumLatencyMs = [Math]::Round($latencies[0], 2)
    MaximumLatencyMs = [Math]::Round($latencies[$latencies.Count - 1], 2)
    P50LatencyMs = [Math]::Round((Get-Percentile -Values $latencies -Percentile 0.50), 2)
    P95LatencyMs = [Math]::Round((Get-Percentile -Values $latencies -Percentile 0.95), 2)
    P99LatencyMs = [Math]::Round((Get-Percentile -Values $latencies -Percentile 0.99), 2)
} | Format-List

Write-Host 'Executor statistics before:'
$statsBefore | Format-List
Write-Host 'Executor statistics after:'
$statsAfter | Format-List
