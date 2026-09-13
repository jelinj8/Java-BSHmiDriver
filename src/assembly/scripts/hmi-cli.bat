@echo off
set "DIR=%~dp0"
java -cp "%DIR%bshmidriver-cli.jar;%DIR%lib\*" cz.bliksoft.hmieink.Cli %*
