$ErrorActionPreference = 'Stop'
$xlsx = (Get-ChildItem -LiteralPath 'D:\JetBrains\project\bank-chat\bank-agent-demo\assets' -Filter '*_Mock_V2.0.xlsx' | Select-Object -First 1).FullName
$outDir = 'D:\JetBrains\project\bank-chat\outputs\rag_expansion_v2\excel_previews'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false
try {
  $book = $excel.Workbooks.Open($xlsx)
  $index = 1
  foreach ($sheet in $book.Worksheets) {
    $used = $sheet.UsedRange
    $lastRow = [Math]::Min($used.Rows.Count, 20)
    $lastCol = $used.Columns.Count
    $range = $sheet.Range($sheet.Cells(1,1),$sheet.Cells($lastRow,$lastCol))
    $sheet.Activate(); $range.Select() | Out-Null; $range.CopyPicture(1,2)
    Start-Sleep -Milliseconds 250
    $chart = $sheet.ChartObjects().Add(0,0,[Math]::Max(900,$range.Width),[Math]::Max(400,$range.Height))
    $chart.Chart.Paste() | Out-Null; Start-Sleep -Milliseconds 250
    $chart.Chart.Export((Join-Path $outDir ("sheet_$index.png")),'PNG') | Out-Null
    $chart.Delete(); $index++
  }
  $book.Close($false)
} finally { $excel.Quit(); [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null }
Get-ChildItem $outDir -Filter '*.png' | Select-Object Name,Length
