<#
.SYNOPSIS
  Instala la herramienta de fotos en C:\Dev\app-fotos.

.DESCRIPTION
  Compila (gradlew installDist) y copia el programa a la carpeta de instalación. Los accesos directos los crea
  cada persona a mano: este script no toca el escritorio.

  Las entregas ("Fotos <fecha>") se crean DENTRO de esta misma carpeta, así que al actualizar se reemplaza solo el
  programa (bin, lib y el lanzador) y nada más: las entregas ya generadas no se tocan. La caché de fotos vive aparte,
  en %LOCALAPPDATA%\api-fotos\cache.

.PARAMETER Carpeta
  Donde queda instalado el programa y donde se crean las entregas. Por defecto C:\Dev\app-fotos.

.PARAMETER SinCompilar
  Usa lo ya compilado en fotos-tool\build\install en vez de volver a compilar.
#>
param(
    [string]$Carpeta = 'C:\Dev\app-fotos',
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
    # Una carpeta que ya existe tiene que estar vacía o ser una instalación anterior de ESTA herramienta: una
    # carpeta cualquiera no se toca.
    $hayAlgo = $null -ne (Get-ChildItem -LiteralPath $Carpeta -Force | Select-Object -First 1)
    if ($hayAlgo -and -not (Test-Path (Join-Path $Carpeta $lanzador))) {
        throw "La carpeta $Carpeta existe, no está vacía y no parece una instalación de la herramienta. No la toco: elegí otra con -Carpeta."
    }
} else {
    New-Item -ItemType Directory -Path $Carpeta -Force | Out-Null
}

# Se reemplaza SOLO lo que es del programa. Todo lo demás de la carpeta (las entregas) queda como está.
foreach ($parte in @('bin', 'lib', $lanzador)) {
    $destino = Join-Path $Carpeta $parte
    if (Test-Path -LiteralPath $destino) { Remove-Item -LiteralPath $destino -Recurse -Force }
    Copy-Item -LiteralPath (Join-Path $origen $parte) -Destination $destino -Recurse
}

Write-Host "Instalado en: $Carpeta"
Write-Host "Las entregas se crean ahí dentro, en carpetas 'Fotos <fecha>'."
Write-Host "Para usarlo, arrastrá la planilla sobre '$lanzador' (o creá un acceso directo a ese archivo)."
