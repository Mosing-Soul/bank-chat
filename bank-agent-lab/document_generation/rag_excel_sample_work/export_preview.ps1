$ErrorActionPreference = 'Stop'
$xlsx = (Get-ChildItem -LiteralPath 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_v1' -Filter '*.xlsx' | Select-Object -First 1).FullName
$pdf = 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_work\workbook_preview.pdf'
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false
try {
    $book = $excel.Workbooks.Open($xlsx)
    foreach ($sheet in $book.Worksheets) {
        $sheet.PageSetup.Zoom = $false
        $sheet.PageSetup.FitToPagesWide = 1
        $sheet.PageSetup.FitToPagesTall = $false
        $sheet.PageSetup.Orientation = 2
    }
    $book.ExportAsFixedFormat(0, $pdf)
    $book.Close($false)
    Write-Output $pdf
}
finally {
    $excel.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null
}
