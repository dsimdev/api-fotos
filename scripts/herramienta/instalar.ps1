<#
.SYNOPSIS
  Instala la herramienta de fotos en esta PC y crea el acceso directo para arrastrar la planilla.

.DESCRIPTION
  Compila (gradlew installDist), copia el programa a una carpeta fija y crea el acceso directo en el escritorio.
  Se puede correr de nuevo para actualizar: reemplaza el programa, NO toca la caché de fotos (vive aparte, en
  %LOCALAPPDATA%\api-fotos\cache) ni las carpetas de entrega ya generadas.

.PARAMETER Carpeta
  Donde queda instalado el programa. Por defecto %LOCALAPPDATA%\api-fotos\herramienta.

.PARAMETER Acceso
  Ruta del acceso directo (.lnk). Por defecto, "Fotos por SKU" en el escritorio.

.PARAMETER SinCompilar
  Usa lo ya compilado en fotos-tool\build\install en vez de volver a compilar.
#>
param(
    [string]$Carpeta = (Join-Path $env:LOCALAPPDATA 'api-fotos\herramienta'),
    [string]$Acceso = (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Fotos por SKU.lnk'),
    [switch]$SinCompilar
)

$ErrorActionPreference = 'Stop'
$lanzador = 'Arrastrar la planilla aca.bat'
$raiz = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

if (-not $SinCompilar) {
    Push-Location $raiz
    try {
        & (Join-Path $raiz 'gradlew.bat') ':fotos-tool:installDist' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw 'La compilación falló: no se instaló nada.' }
    } finally {
        Pop-Location
    }
}

$origen = Join-Path $raiz 'fotos-tool\build\install\fotos-tool'
if (-not (Test-Path (Join-Path $origen $lanzador))) {
    throw "No encuentro el programa compilado en $origen. Corré sin -SinCompilar."
}

if (Test-Path $Carpeta) {
    # Solo se reemplaza una instalación anterior de ESTA herramienta: una carpeta cualquiera no se borra.
    if (-not (Test-Path (Join-Path $Carpeta $lanzador))) {
        throw "La carpeta $Carpeta existe y no parece una instalación de la herramienta. No la toco: elegí otra con -Carpeta."
    }
    Remove-Item $Carpeta -Recurse -Force
}
New-Item -ItemType Directory -Path (Split-Path $Carpeta -Parent) -Force | Out-Null
Copy-Item $origen $Carpeta -Recurse

$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($Acceso)
$link.TargetPath = Join-Path $Carpeta $lanzador
$link.WorkingDirectory = $Carpeta
$link.Description = 'Arrastrá la planilla (.xlsx o .csv con SKU y EAN) sobre este acceso directo'
$link.IconLocation = "$env:SystemRoot\System32\imageres.dll,67"
$link.Save()

Write-Host "Instalado en: $Carpeta"
Write-Host "Acceso directo: $Acceso"
Write-Host 'Para usarlo, arrastrá la planilla sobre el acceso directo.'
