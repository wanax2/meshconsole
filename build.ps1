# Plain build without Gradle for Windows: needs only a JDK (17+) and git.
# Downloads protoc + the two runtime jars once into .\lib, generates the protobuf
# classes, compiles, and produces .\dist\meshconsole.jar plus meshconsole.bat.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$PB = "31.1"; $JSC = "2.11.4"
New-Item -ItemType Directory -Force lib, build\gen, build\classes, dist | Out-Null
if (-not (Test-Path lib\protoc\bin\protoc.exe)) {
  Invoke-WebRequest "https://github.com/protocolbuffers/protobuf/releases/download/v$PB/protoc-$PB-win64.zip" -OutFile lib\protoc.zip
  Expand-Archive lib\protoc.zip -DestinationPath lib\protoc -Force
}
if (-not (Test-Path lib\protobuf-java.jar)) { Invoke-WebRequest "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.$PB/protobuf-java-4.$PB.jar" -OutFile lib\protobuf-java.jar }
if (-not (Test-Path lib\jSerialComm.jar))  { Invoke-WebRequest "https://github.com/Fazecast/jSerialComm/releases/download/v$JSC/jSerialComm-$JSC.jar" -OutFile lib\jSerialComm.jar }
if (-not (Test-Path protobufs)) { .\fetch-protos.ps1 }
Write-Host "generating protobuf classes..."
$protos = Get-ChildItem protobufs\meshtastic\*.proto | ForEach-Object { "protobufs/meshtastic/" + $_.Name }
& lib\protoc\bin\protoc.exe --java_out=build\gen --proto_path=protobufs --proto_path=lib\protoc\include @protos protobufs\nanopb.proto
if ($LASTEXITCODE -ne 0) { throw "protoc failed" }
Write-Host "compiling..."
Get-ChildItem -Recurse src\main\java, build\gen -Filter *.java | ForEach-Object { $_.FullName } | Set-Content build\sources.txt
& javac -nowarn --release 17 -encoding UTF-8 -cp "lib\protobuf-java.jar;lib\jSerialComm.jar" -d build\classes "@build\sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
# jar.exe is sometimes not on PATH even when javac is (JDK launcher shims); look next to javac, then JAVA_HOME.
# If it is nowhere, ship the classes directory instead of a jar - works just as well.
$jar = Join-Path (Split-Path (Get-Command javac).Source) "jar.exe"
if (-not (Test-Path $jar) -and $env:JAVA_HOME) { $jar = Join-Path $env:JAVA_HOME "bin\jar.exe" }
Copy-Item lib\protobuf-java.jar, lib\jSerialComm.jar dist\
if (Test-Path $jar) {
  "Main-Class: meshconsole.Main`nClass-Path: protobuf-java.jar jSerialComm.jar`n" | Set-Content build\MANIFEST.MF -Encoding ascii
  & $jar cfm dist\meshconsole.jar build\MANIFEST.MF -C build\classes .
  if ($LASTEXITCODE -ne 0) { throw "jar failed" }
  "@echo off`r`ncd /d %~dp0`r`njava -jar meshconsole.jar %*`r`n" | Set-Content dist\meshconsole.bat -Encoding ascii
} else {
  Write-Host "jar.exe not found; packaging the classes directory instead"
  if (Test-Path dist\classes) { Remove-Item -Recurse -Force dist\classes }
  Copy-Item -Recurse build\classes dist\classes
  "@echo off`r`ncd /d %~dp0`r`njava -cp classes;protobuf-java.jar;jSerialComm.jar meshconsole.Main %*`r`n" | Set-Content dist\meshconsole.bat -Encoding ascii
}
Write-Host "OK: run dist\meshconsole.bat"
