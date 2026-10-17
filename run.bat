@echo off
setlocal
cd /d "%~dp0"

if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
        if /i "%%A"=="DB_URL" if not defined DB_URL set "DB_URL=%%B"
        if /i "%%A"=="DB_USER" if not defined DB_USER set "DB_USER=%%B"
        if /i "%%A"=="DB_PASSWORD" if not defined DB_PASSWORD set "DB_PASSWORD=%%B"
    )
)

if not exist build mkdir build
javac -cp postgresql-42.2.8.jar -d build GUI\*.java
if errorlevel 1 exit /b %errorlevel%
java -cp "build;postgresql-42.2.8.jar" GUI
