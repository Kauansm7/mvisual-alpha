@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul || (echo ERRO: instale o JDK 17 e configure JAVA_HOME. & pause & exit /b 1)
where mvn >nul 2>nul || (echo ERRO: instale Maven 3.9+ e adicione mvn ao PATH. & pause & exit /b 1)
set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
java -version 2>&1 | findstr /C:"17." >nul
if errorlevel 1 echo AVISO: o build recomendado usa JDK 17.
call mvn clean package
if errorlevel 1 (echo Falha na compilacao Maven. & pause & exit /b 1)
if exist "%ISCC%" (
  "%ISCC%" installer.iss
  if errorlevel 1 (echo Falha ao gerar o instalador. & pause & exit /b 1)
  echo Instalador gerado em target\installer\Mvisual-Setup-v1.0.0.exe
) else (
  echo Aplicacao gerada em target\dist\Mvisual
  echo Para gerar o instalador, instale Inno Setup 6 e execute ISCC.exe installer.iss
)
pause
