$JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $JAVA_HOME

$output = & .\gradlew.bat :app:assembleDebug 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output $output
    exit $LASTEXITCODE
}

$versionLine = Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"'
$version = $versionLine.Matches.Groups[1].Value

Copy-Item -Path "app\build\outputs\apk\debug\app-debug.apk" -Destination "Dummy build\BlueMesh-v$version.apk" -Force
Write-Output "Copied APK to Dummy build\BlueMesh-v$version.apk"
