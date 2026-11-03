@echo off
setlocal
call mvnw.cmd clean verify
if errorlevel 1 (echo Build failed & exit /b 1)
if not exist dist mkdir dist
copy /Y auction-server\target\auction-server-1.0.0-all.jar dist\auction-server.jar
copy /Y auction-client\target\auction-client-1.0.0-all.jar dist\auction-client.jar
echo Build OK. Files are in dist\
