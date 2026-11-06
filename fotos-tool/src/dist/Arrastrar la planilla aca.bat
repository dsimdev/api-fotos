@echo off
rem Arrastra la planilla (.xlsx o .csv con columnas SKU y EAN) sobre este archivo.
rem Tambien se puede hacer doble clic para ver las instrucciones.
chcp 65001 >nul
setlocal
set "JAVA_OPTS=-Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8"
title Fotos por SKU

if "%~1"=="" (
    call "%~dp0bin\fotos-tool.bat" --help
) else (
    call "%~dp0bin\fotos-tool.bat" %*
)

echo.
echo Podes cerrar esta ventana.
pause >nul
