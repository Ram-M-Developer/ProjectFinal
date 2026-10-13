@echo off
set "JAVA_HOME=C:\Users\ELCOT\.jdks\temurin-21.0.11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAVEN_OPTS=-Xmx256m"
"C:\Users\ELCOT\AppData\Local\Programs\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
