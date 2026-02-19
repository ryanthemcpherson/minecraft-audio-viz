@echo off
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "C:\Users\Ryan\Desktop\minecraft-audio-viz\minecraft_plugin"
call "C:\Users\Ryan\Desktop\minecraft-audio-viz\minecraft_plugin\mvnw.cmd" package -DskipTests
