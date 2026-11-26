@echo off
cd /d "%~dp0"
if exist C:\Python313\python.exe (
  C:\Python313\python.exe EngineSoundDemo.py
  goto :eof
)
py -3 EngineSoundDemo.py
if errorlevel 1 python EngineSoundDemo.py
