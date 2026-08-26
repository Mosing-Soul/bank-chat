$ErrorActionPreference = 'Stop'
$xlsx = (Get-ChildItem -LiteralPath 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_v1' -Filter '*.xlsx' | Select-Object -First 1).FullName
$outDir = 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_work\previews'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $true
$excel.DisplayAlerts = $false
$excel.WindowState = -4140
try {
    $book = $excel.Workbooks.Open($xlsx)
    $index = 1
    foreach ($sheet in $book.Worksheets) {
        $used = $sheet.UsedRange
        $lastRow = [Math]::Min($used.Rows.Count, 18)
        $lastCol = $used.Columns.Count
        $range = $sheet.Range($sheet.Cells(1, 1), $sheet.Cells($lastRow, $lastCol))
        $sheet.Activate()
        $range.Select() | Out-Null
        $range.CopyPicture(1, 2)
        Start-Sleep -Milliseconds 400
        $chartObject = $sheet.ChartObjects().Add(0, 0, [Math]::Max(900, $range.Width), [Math]::Max(400, $range.Height))
        $chartObject.Activate() | Out-Null
        $chartObject.Chart.Paste() | Out-Null
        Start-Sleep -Milliseconds 400
        $png = Join-Path $outDir (('sheet_{0}.png' -f $index))
        $chartObject.Chart.Export($png, 'PNG') | Out-Null
        $chartObject.Delete()
        $index++
    }
    $book.Close($false)
    Get-ChildItem -LiteralPath $outDir -Filter '*.png' | Select-Object FullName,Length
}
finally {
    $excel.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null
}
