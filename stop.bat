@echo off
chcp 936 >nul
title 农校直供 - 停止服务
cd /d "%~dp0"
echo 正在按进程命令行精确识别并停止本项目服务 ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop_services.ps1"
echo.
echo 完成。
pause
