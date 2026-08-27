$ErrorActionPreference = 'Stop'
$assetDir = 'D:\JetBrains\project\bank-chat\bank-agent-demo\assets'
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    foreach ($pdf in (Get-ChildItem -LiteralPath $assetDir -Filter '*内部参考_V1.0.pdf' | Sort-Object Name)) {
        $doc = $word.Documents.Open($pdf.FullName, $false, $true)
        $pages = $doc.ComputeStatistics(2)
        $words = $doc.ComputeStatistics(0)
        $chars = $doc.Content.Text.Length
        Write-Output ("pdf=" + $pdf.Name + ";pages=" + $pages + ";words=" + $words + ";chars=" + $chars)
        $doc.Close(0)
    }
}
finally {
    $word.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
}
