# Stop only processes whose command line identifies them as this project's services.
# Never kill an arbitrary process just because it happens to listen on 8080/5173.
$targets = @(
    @{ Port = 8080; Pattern = 'canteen-trace\.jar' },
    @{ Port = 5173; Pattern = 'vite|npm run dev|node.*vite' }
)
foreach ($t in $targets) {
    $conns = Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) { Write-Host "Port $($t.Port): no listener"; continue }
    foreach ($c in $conns) {
        $processId = $c.OwningProcess
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if ($null -eq $proc) { continue }
        if ($proc.CommandLine -and $proc.CommandLine -match $t.Pattern) {
            Write-Host "Port $($t.Port): stopping project process PID $processId"
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        } else {
            Write-Host "Port $($t.Port): SKIP PID $processId (not a project process)"
        }
    }
}
