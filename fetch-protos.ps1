# Downloads the Meshtastic protobuf definitions the build generates code from.
Set-Location $PSScriptRoot
if (Test-Path protobufs\.git) { git -C protobufs pull --ff-only }
else { git clone --depth 1 https://github.com/meshtastic/protobufs.git protobufs }
Write-Host "OK - protobufs\ is ready. Now run: gradle run"
