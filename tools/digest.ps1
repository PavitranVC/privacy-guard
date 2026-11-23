param (
    [Parameter(Mandatory=$true)][string]$LastSeen,
    [string[]]$Paths = @()
)

Write-Host "=== Current HEAD ==="
git rev-parse HEAD

Write-Host "`n=== Critical Memory Document Hashes (SHA-256) ==="
$hashFiles = @(
    "CONTEXT.md",
    "GEMINI.md",
    "docs/STATUS.md",
    "docs/DECISIONS.md",
    "implementation_plan.md"
)

foreach ($file in $hashFiles) {
    if (Test-Path $file) {
        $hash = (Get-FileHash -Path $file -Algorithm SHA256).Hash
        Write-Host "$file : $hash"
    } else {
        Write-Host "$file : NOT FOUND"
    }
}

Write-Host "`n=== Git Log ($LastSeen..HEAD) ==="
git log --oneline --stat "$LastSeen..HEAD"

if ($Paths.Count -gt 0) {
    Write-Host "`n=== Git Diff ($LastSeen..HEAD) for Paths: $($Paths -join ', ') ==="
    git diff "$LastSeen..HEAD" -- $Paths
}
