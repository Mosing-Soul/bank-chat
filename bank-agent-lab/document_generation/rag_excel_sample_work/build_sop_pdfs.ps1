$ErrorActionPreference = 'Stop'
$dataPath = 'D:\JetBrains\project\bank-chat\bank-agent-lab\document_generation\rag_excel_sample_work\sop_documents.json'
$assetDir = 'D:\JetBrains\project\bank-chat\bank-agent-demo\assets'
$data = Get-Content -LiteralPath $dataPath -Raw -Encoding UTF8 | ConvertFrom-Json
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0

function Set-CellText($cell, $text, $bold, $color, $size) {
    $cell.Range.Text = [string]$text
    $cell.Range.Font.NameFarEast = 'Microsoft YaHei'
    $cell.Range.Font.Name = 'Arial'
    $cell.Range.Font.Size = [single]$size
    $cell.Range.Font.Bold = $(if ($bold) { 1 } else { 0 })
    $cell.Range.Font.Color = $color
    $cell.VerticalAlignment = 1
    $cell.Range.ParagraphFormat.SpaceAfter = 0
}

function Add-Paragraph($doc, $text, $style, $spaceAfter) {
    $range = $doc.Content
    $range.Collapse(0)
    $p = $doc.Paragraphs.Add($range)
    $p.Range.Text = [string]$text
    $p.Range.Font.NameFarEast = 'Microsoft YaHei'
    $p.Range.Font.Name = 'Arial'
    $p.Range.Font.Size = 9.5
    if ($style -eq -63) {
        $p.Range.Font.Size = 22
        $p.Range.Font.Bold = 1
        $p.Range.ParagraphFormat.Alignment = 1
    } elseif ($style -eq -75) {
        $p.Range.Font.Size = 12
        $p.Range.Font.Italic = 1
        $p.Range.ParagraphFormat.Alignment = 1
    } elseif ($style -eq -2) {
        $p.Range.Font.Size = 14
        $p.Range.Font.Bold = 1
    }
    $p.Range.ParagraphFormat.SpaceAfter = $spaceAfter
    $p.Range.ParagraphFormat.LineSpacingRule = 1
    return $p
}

function Add-Table($doc, $tableData) {
    $range = $doc.Content
    $range.Collapse(0)
    $rows = 1 + $tableData.rows.Count
    $cols = $tableData.headers.Count
    $table = $doc.Tables.Add($range, $rows, $cols)
    $table.Borders.Enable = 1
    $table.AllowAutoFit = $true
    $table.Rows(1).HeadingFormat = $true
    for ($c = 1; $c -le $cols; $c++) {
        Set-CellText $table.Cell(1, $c) $tableData.headers[$c - 1] $true 16777215 9
        $table.Cell(1, $c).Shading.BackgroundPatternColor = 6108955
    }
    for ($r = 0; $r -lt $tableData.rows.Count; $r++) {
        for ($c = 0; $c -lt $cols; $c++) {
            Set-CellText $table.Cell($r + 2, $c + 1) $tableData.rows[$r][$c] $false 2105376 8.5
            if (($r % 2) -eq 1) { $table.Cell($r + 2, $c + 1).Shading.BackgroundPatternColor = 16185078 }
        }
    }
    $table.Range.ParagraphFormat.SpaceAfter = 2
    $end = $doc.Content
    $end.Collapse(0)
    $end.InsertParagraphAfter()
}

try {
    foreach ($item in $data.documents) {
        $doc = $word.Documents.Add()
        $section = $doc.Sections.Item(1)
        $section.PageSetup.TopMargin = $word.CentimetersToPoints(1.8)
        $section.PageSetup.BottomMargin = $word.CentimetersToPoints(1.7)
        $section.PageSetup.LeftMargin = $word.CentimetersToPoints(1.7)
        $section.PageSetup.RightMargin = $word.CentimetersToPoints(1.7)
        $section.Headers.Item(1).Range.Text = "$($item.code)    $($item.version)"
        $section.Headers.Item(1).Range.Font.NameFarEast = 'Microsoft YaHei'
        $section.Headers.Item(1).Range.Font.Size = 8
        $section.Headers.Item(1).Range.Font.Color = 8421504
        $footer = $section.Footers.Item(1).Range
        $footer.Text = 'Internal training document | Page '
        $footer.Font.Name = 'Arial'
        $footer.Font.Size = 8
        $footer.ParagraphFormat.Alignment = 2
        $footer.Collapse(0)
        $footer.Fields.Add($footer, 33) | Out-Null

        $title = Add-Paragraph $doc $item.title -63 8
        $title.Range.Font.NameFarEast = 'Microsoft YaHei'
        $title.Range.Font.Color = 6108955
        $title.Range.Font.Size = 22
        $title.Range.ParagraphFormat.Alignment = 1
        $sub = Add-Paragraph $doc $item.subtitle -75 12
        $sub.Range.Font.NameFarEast = 'Microsoft YaHei'
        $sub.Range.ParagraphFormat.Alignment = 1

        $meta = [pscustomobject]@{
            headers = $item.metaHeaders
            rows = @(@($item.code, $item.version, $item.effectiveDate, $item.owner))
        }
        Add-Table $doc $meta
        $scope = Add-Paragraph $doc ($item.scopeLabel + $item.scope) -1 6
        $scope.Range.Font.Bold = 1
        $warn = Add-Paragraph $doc $item.warning -1 10
        $warn.Range.Shading.BackgroundPatternColor = 13431551
        $warn.Range.Font.Color = 192
        $warn.Range.Font.Bold = 1

        foreach ($s in $item.sections) {
            if ($s.pageBreakBefore) {
                $breakRange = $doc.Content
                $breakRange.Collapse(0)
                $breakRange.InsertBreak(7)
            }
            $heading = Add-Paragraph $doc $s.heading -2 6
            $heading.Range.Font.NameFarEast = 'Microsoft YaHei'
            $heading.Range.Font.Color = 6108955
            $heading.Range.Font.Size = 14
            if ($s.paragraphs) {
                foreach ($text in $s.paragraphs) { Add-Paragraph $doc $text -1 5 | Out-Null }
            }
            if ($s.bullets) {
                foreach ($text in $s.bullets) {
                    $p = Add-Paragraph $doc $text -1 3
                    $p.Range.ListFormat.ApplyBulletDefault()
                }
                $end = $doc.Content
                $end.Collapse(0)
                $end.InsertParagraphAfter()
            }
            if ($s.table) { Add-Table $doc $s.table }
        }

        $doc.Repaginate()
        $pdfPath = Join-Path $assetDir $item.filename
        $doc.ExportAsFixedFormat($pdfPath, 17)
        Write-Output ("created=" + $pdfPath + ";pages=" + $doc.ComputeStatistics(2) + ";words=" + $doc.ComputeStatistics(0))
        $doc.Close(0)
    }
}
finally {
    $word.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
}
