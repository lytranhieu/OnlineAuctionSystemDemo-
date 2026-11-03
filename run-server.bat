@echo off
setlocal
java -jar auction-server\target\auction-server-1.0.0-all.jar 5050 data\state.json
pause
