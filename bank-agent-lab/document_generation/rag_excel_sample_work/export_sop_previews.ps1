$ErrorActionPreference = 'Stop'
$sourceDir = 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_work\sop_sources'
$outDir = 'D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_work\sop_previews'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $true
$excel.DisplayAlerts = $false
$excel.WindowState = -4140
try {
    $index = 1
    foreach ($source in (Get-ChildItem -LiteralPath $sourceDir -Filter 'sop_*.xlsx' | Sort-Object Name)) {
        $book = $excel.Workbooks.Open($source.FullName)
        $sheet = $book.Worksheets.Item(1)
        $range = $sheet.UsedRange
        $sheet.Activate()
        $range.Select() | Out-Null
        $range.CopyPicture(1, 2)
        Start-Sleep -Milliseconds 500
        $chartObject = $sheet.ChartObjects().Add(0, 0, [Math]::Max(1200, $range.Width), [Math]::Max(800, $range.Height))
        $chartObject.Activate() | Out-Null
        $chartObject.Chart.Paste() | Out-Null
        Start-Sleep -Milliseconds 500
        $png = Join-Path $outDir (('sop_{0}.png' -f $index))
        $chartObject.Chart.Export($png, 'PNG') | Out-Null
        $chartObject.Delete()
        $book.Close($false)
        Write-Output $png
        $index++
    }
}
finally {
    $excel.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null
}
