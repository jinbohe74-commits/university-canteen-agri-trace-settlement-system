@echo off
chcp 936 >nul
title 农校直供 - 农产品直供溯源与结算管理系统 启动器
cd /d "%~dp0"

echo ============================================================
echo   农校直供 - 面向高校食堂的农产品直供溯源与结算管理系统
echo ============================================================
echo.

set "ROOT=%~dp0"
set "TMPDIR=%ROOT%.work\tmp"
if not exist "%TMPDIR%" mkdir "%TMPDIR%"

rem ---------- 1. 后端 ----------
echo [1/3] 检查后端服务 8080 ...
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo       8080 已在运行，跳过启动
  goto front
)

if not exist "backend\target\canteen-trace.jar" (
  echo       未找到 Jar 包，正在打包，首次需要几分钟 ...
  pushd backend
  call mvn -q -DskipTests package
  popd
  if not exist "backend\target\canteen-trace.jar" (
    echo       [错误] 打包失败，请检查 JDK 17 与 Maven 是否可用
    goto end
  )
)

echo       启动后端 ...
start "canteen-backend-8080" /min /D "%ROOT%backend" cmd /k "chcp 65001 >nul && set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && java -jar target\canteen-trace.jar --server.port=8080"

echo [2/3] 等待后端就绪 ...
set "READY="
for /l %%i in (1,1,60) do (
  if not defined READY (
    curl -s -o nul -m 2 http://127.0.0.1:8080/api/common/health
    if not errorlevel 1 set "READY=1"
    if not defined READY ping -n 2 127.0.0.1 >nul
  )
)
if defined READY (
  echo       后端已就绪： http://127.0.0.1:8080/api
) else (
  echo       [警告] 后端 120 秒内未就绪，请查看 canteen-backend-8080 窗口的报错
)

:front
rem ---------- 2. 前端 ----------
echo [3/3] 检查前端服务 5173 ...
netstat -ano | findstr ":5173" | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo       5173 已在运行，跳过启动
  goto open
)

if not exist "frontend\node_modules" (
  echo       首次运行，安装前端依赖，请稍候 ...
  pushd frontend
  call npm install --no-audit --no-fund
  popd
)

echo       启动前端 ...
start "canteen-frontend-5173" /min /D "%ROOT%frontend" cmd /k "set TEMP=%TMPDIR%&& set TMP=%TMPDIR%&& npm run dev"

echo       等待前端就绪 ...
set "FREADY="
for /l %%i in (1,1,45) do (
  if not defined FREADY (
    curl -s -o nul -m 2 http://127.0.0.1:5173/
    if not errorlevel 1 set "FREADY=1"
    if not defined FREADY ping -n 2 127.0.0.1 >nul
  )
)
if defined FREADY (
  echo       前端已就绪： http://127.0.0.1:5173
) else (
  echo       [警告] 前端 90 秒内未就绪，请查看 canteen-frontend-5173 窗口的报错
)

:open
echo.
echo       正在打开浏览器 ...
if not "%NOBROWSER%"=="1" start "" http://127.0.0.1:5173

echo.
echo ============================================================
echo   访问地址 : http://127.0.0.1:5173
echo   后端接口 : http://127.0.0.1:8080/api
echo.
echo   演示账号 : 见 README（仅限本地演示）
echo.
echo   注意：请勿关闭 canteen-backend-8080 与 canteen-frontend-5173
echo         这两个黑窗口（可最小化），关闭即等于停止服务。
echo   关闭系统 : 双击 stop.bat
echo ============================================================
echo.

:end
pause
