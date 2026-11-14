# Publica una version nueva de la extension CSV:
#   1. Pone el numero en MANIFEST.MF, UpdateChecker.java y README.md.
#   2. Compila el JAR con build.bat.
#   3. Commitea, crea el tag vX.Y.Z y hace push.
#   4. Crea la Release en GitHub con com.csv.download.jar adjunto.
# Uso: release.bat X.Y.Z ["notas de la version"]
# Requiere: SQLDEV_HOME, git y GitHub CLI (gh) con sesion iniciada.
param(
    [Parameter(Mandatory = $true)] [string] $Version,
    [string] $Notes
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version invalida: '$Version' (formato esperado X.Y.Z)"
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "Falta GitHub CLI: instala con 'winget install GitHub.cli' y luego 'gh auth login'"
}
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Sin sesion en GitHub CLI: ejecuta 'gh auth login'" }
if (git status --porcelain) {
    throw "Hay cambios sin commitear; commitea o descarta antes de publicar"
}
if (git tag -l "v$Version") {
    throw "El tag v$Version ya existe; sube el numero de version"
}

$visibility = (gh repo view --json visibility --jq .visibility) 2>$null
if ($visibility -ne 'PUBLIC') {
    Write-Warning "El repositorio no es publico ($visibility): la descarga anonima del JAR y el aviso de version nueva no funcionaran. Hazlo publico en GitHub o con 'gh repo edit --visibility public --accept-visibility-change-consequences'."
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Set-Version($path, $pattern, $replacement) {
    $text = [IO.File]::ReadAllText($path)
    if ($text -notmatch $pattern) {
        throw "No se encontro el numero de version en $path"
    }
    $new = [regex]::Replace($text, $pattern, $replacement)
    if ($new -ne $text) { [IO.File]::WriteAllText($path, $new, $utf8) }
}
Set-Version 'META-INF/MANIFEST.MF' 'Bundle-Version: [\d.]+' "Bundle-Version: $Version"
Set-Version 'src/com/csv/download/UpdateChecker.java' '(?<=CURRENT_VERSION = ")[\d.]+' $Version
Set-Version 'README.md' '(?<=Version actual: \*\*)[\d.]+' $Version
Write-Host "Version puesta a $Version en MANIFEST, UpdateChecker y README"

& "$PSScriptRoot\build.bat"
if ($LASTEXITCODE -ne 0) { throw "Fallo la compilacion" }

git add META-INF/MANIFEST.MF src/com/csv/download/UpdateChecker.java README.md
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -m "v$Version" }
git tag -a "v$Version" -m "v$Version"
git push origin HEAD --follow-tags
if ($LASTEXITCODE -ne 0) { throw "Fallo el push" }

$jar = 'dist/com.csv.download.jar'
if ($Notes) {
    gh release create "v$Version" $jar --title "v$Version" --notes $Notes
} else {
    gh release create "v$Version" $jar --title "v$Version" --generate-notes
}
if ($LASTEXITCODE -ne 0) { throw "Fallo la creacion de la release" }

Write-Host ""
Write-Host "Release v$Version publicada."
Write-Host "Descarga fija del ultimo JAR: https://github.com/miguelacg74/csv-download/releases/latest/download/com.csv.download.jar"
