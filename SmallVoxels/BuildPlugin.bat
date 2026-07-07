@echo off
setlocal

call mvn clean package -q
if errorlevel 1 exit /b 1

if not exist "built-plugins" mkdir "built-plugins"
copy /y "target\SmallVoxels-1.0-SNAPSHOT.jar" "built-plugins\SmallVoxels.jar" >nul
if errorlevel 1 exit /b 1

echo Built: built-plugins\SmallVoxels.jar
