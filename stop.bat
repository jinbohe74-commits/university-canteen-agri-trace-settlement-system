@echo off
chcp 936 >nul
title 农校直供 - 停止服务
cd /d "%~dp0"
echo 正在停止 8080（后端）与 5173（前端）端口上的进程 ...

for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
  taskkill /f /pid %%p >nul 2>nul
  echo   已停止后端进程 PID %%p
)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5173" ^| findstr "LISTENING"') do (
  taskkill /f /pid %%p >nul 2>nul
  echo   已停止前端进程 PID %%p
)

echo.
echo 已停止。若窗口仍然存在，可直接关闭它们。
ping -n 3 127.0.0.1 >nul
