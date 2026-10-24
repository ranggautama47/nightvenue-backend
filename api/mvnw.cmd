@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Apache Maven Wrapper startup batch script, version 3.3.2

@IF "%__MVNW_ARG0_NAME__%"=="" (SET "__MVNW_ARG0_NAME__=%~nx0")

@SET "MAVEN_PROJECTBASEDIR=%~dp0"
@SET "PROPERTIES_FILE=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"

@FOR /F "usebackq tokens=2 delims==" %%G IN (`findstr /r "^distributionUrl=" "%PROPERTIES_FILE%"`) DO SET "DISTRIBUTION_URL=%%G"

@FOR %%G IN ("%DISTRIBUTION_URL%") DO SET "MAVEN_DIST_NAME=%%~nxG"

@SET "MAVEN_HOME_CANDIDATE=%USERPROFILE%\.m2\wrapper\dists\%MAVEN_DIST_NAME:.zip=%"

@IF NOT EXIST "%MAVEN_HOME_CANDIDATE%" (
    echo Downloading Maven: %DISTRIBUTION_URL%
    mkdir "%MAVEN_HOME_CANDIDATE%"
    powershell -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%MAVEN_HOME_CANDIDATE%\%MAVEN_DIST_NAME%'"
    powershell -Command "Expand-Archive -Path '%MAVEN_HOME_CANDIDATE%\%MAVEN_DIST_NAME%' -DestinationPath '%MAVEN_HOME_CANDIDATE%'"
    del "%MAVEN_HOME_CANDIDATE%\%MAVEN_DIST_NAME%"
)

@FOR /D %%G IN ("%MAVEN_HOME_CANDIDATE%\*") DO SET "MAVEN_HOME=%%G"

@"%MAVEN_HOME%\bin\mvn.cmd" %*
