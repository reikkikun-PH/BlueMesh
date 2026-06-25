$JAVA_HOME = "C:\Users\Ricky\Desktop\BlueMesh\.jdk\jdk-17.0.19+10"
$env:JAVA_HOME = $JAVA_HOME

$versionLine = Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"'
$version = $versionLine.Matches.Groups[1].Value

# Build User edition
Write-Output "Building User edition..."
$output = & .\gradlew.bat :app:assembleUserDebug 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output $output
    exit $LASTEXITCODE
}
Copy-Item -Path "app\build\outputs\apk\user\debug\app-user-debug.apk" -Destination "Dummy build\app-$version-User-debug.apk" -Force
Write-Output "Copied User APK to Dummy build\app-$version-User-debug.apk"

# Build Volunteers edition
Write-Output "Building Volunteers edition..."
$output = & .\gradlew.bat :app:assembleVolunteersDebug 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output $output
    exit $LASTEXITCODE
}
Copy-Item -Path "app\build\outputs\apk\volunteers\debug\app-volunteers-debug.apk" -Destination "Dummy build\app-$version-Volunteers-debug.apk" -Force
Write-Output "Copied Volunteers APK to Dummy build\app-$version-Volunteers-debug.apk"

Write-Output "Done! Both APKs built successfully."
