function Copy-BoundedStream {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream]$Source,

        [Parameter(Mandatory = $true)]
        [System.IO.Stream]$Destination,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 200000000)]
        [int64]$ExpectedSize,

        [int64]$ContentLength = -1
    )

    if ($ContentLength -ge 0 -and $ContentLength -ne $ExpectedSize) {
        throw "Download Content-Length mismatch: expected $ExpectedSize, got $ContentLength."
    }
    $buffer = [byte[]]::new(1048576)
    $written = [int64]0
    while (($count = $Source.Read($buffer, 0, $buffer.Length)) -gt 0) {
        $written += $count
        if ($written -gt $ExpectedSize) {
            throw "Download exceeded its locked size of $ExpectedSize bytes."
        }
        $Destination.Write($buffer, 0, $count)
    }
    if ($written -ne $ExpectedSize) {
        throw "Download size mismatch: expected $ExpectedSize, got $written."
    }
}
