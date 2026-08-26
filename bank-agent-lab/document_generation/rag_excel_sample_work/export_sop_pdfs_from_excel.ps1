$ErrorActionPreference = 'Stop'
$mappingPath = 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_work\sop_sources\mapping.json'
$assetDir = 'D:\JetBrains\project\bank-chat\bank-agent-demo\assets'
$items = Get-Content -LiteralPath $mappingPath -Raw -Encoding UTF8 | ConvertFrom-Json
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false
try {
    foreach ($item in $items) {
        $book = $excel.Workbooks.Open($item.source)
        $pdf = Join-Path $assetDir $item.pdf
        $book.ExportAsFixedFormat(0, $pdf)
        $sheet = $book.Worksheets.Item(1)
        $used = $sheet.UsedRange
        Write-Output ("created=" + $pdf + ";rows=" + $used.Rows.Count + ";cols=" + $used.Columns.Count)
        $book.Close($false)
    }
}
finally {
    $excel.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null
}
