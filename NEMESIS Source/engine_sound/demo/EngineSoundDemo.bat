@echo off
cd /d "%~dp0"
py -3 EngineSoundDemo.py
if errorlevel 1 python EngineSoundDemo.py
