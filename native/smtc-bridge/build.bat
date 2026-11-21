@echo off
setlocal
set "OUT_DIR=%~1"
if not defined OUT_DIR exit /b 2
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
for /f "usebackq tokens=*" %%i in (`"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_ROOT=%%i"
if not defined VS_ROOT (
  echo Visual Studio C++ build tools are required for Windows SMTC support. 1>&2
  exit /b 3
)
call "%VS_ROOT%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /std:c++17 /EHsc /MD /LD /O2 /DWIN32_LEAN_AND_MEAN /DUNICODE /D_UNICODE /Fo"%OUT_DIR%\SoloSmtcBridge.obj" ^
  /I"%WindowsSdkDir%Include\%WindowsSDKVersion%cppwinrt" ^
  /I"%WindowsSdkDir%Include\%WindowsSDKVersion%um" ^
  /I"%WindowsSdkDir%Include\%WindowsSDKVersion%shared" ^
  "%~dp0SoloSmtcBridge.cpp" /link ^
  /LIBPATH:"%WindowsSdkDir%Lib\%WindowsSDKVersion%um\x64" ^
  windowsapp.lib runtimeobject.lib user32.lib /IMPLIB:"%OUT_DIR%\SoloSmtcBridge.lib" /OUT:"%OUT_DIR%\SoloSmtcBridge.dll"
exit /b %errorlevel%
