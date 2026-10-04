#!/bin/sh
# Plain build without Gradle: needs only a JDK (17+), curl and unzip.
# Downloads protoc + the two runtime jars once into ./lib, generates the protobuf
# classes, compiles, and produces ./dist/meshconsole.jar plus run scripts.
set -e
cd "$(dirname "$0")"
PB=31.1            # protoc release; protobuf-java 4.$PB must match
JSC=2.11.4
mkdir -p lib build/gen build/classes dist
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)  PA=linux-x86_64 ;;
  Linux-aarch64) PA=linux-aarch_64 ;;
  Darwin-arm64)  PA=osx-aarch_64 ;;
  Darwin-x86_64) PA=osx-x86_64 ;;
  *) echo "unsupported platform for protoc download; install protoc manually" >&2; exit 1 ;;
esac
[ -x lib/protoc/bin/protoc ] || { curl -sSL -o lib/protoc.zip "https://github.com/protocolbuffers/protobuf/releases/download/v$PB/protoc-$PB-$PA.zip"; unzip -qo lib/protoc.zip -d lib/protoc; }
[ -f lib/protobuf-java.jar ] || curl -sSL -o lib/protobuf-java.jar "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.$PB/protobuf-java-4.$PB.jar"
[ -f lib/jSerialComm.jar ]  || curl -sSL -o lib/jSerialComm.jar  "https://github.com/Fazecast/jSerialComm/releases/download/v$JSC/jSerialComm-$JSC.jar"
[ -d protobufs ] || ./fetch-protos.sh
echo "generating protobuf classes..."
lib/protoc/bin/protoc --java_out=build/gen --proto_path=protobufs --proto_path=lib/protoc/include protobufs/meshtastic/*.proto protobufs/nanopb.proto
echo "compiling..."
find src/main/java build/gen -name '*.java' > build/sources.txt
javac -nowarn --release 17 -encoding UTF-8 -cp lib/protobuf-java.jar:lib/jSerialComm.jar -d build/classes @build/sources.txt
printf 'Main-Class: meshconsole.Main\nClass-Path: protobuf-java.jar jSerialComm.jar\n' > build/MANIFEST.MF
jar cfm dist/meshconsole.jar build/MANIFEST.MF -C build/classes .
cp lib/protobuf-java.jar lib/jSerialComm.jar dist/
printf '#!/bin/sh\ncd "$(dirname "$0")" && exec java -jar meshconsole.jar "$@"\n' > dist/meshconsole.sh && chmod +x dist/meshconsole.sh
printf '@echo off\r\ncd /d %%~dp0\r\njava -jar meshconsole.jar %%*\r\n' > dist/meshconsole.bat
echo "OK: run dist/meshconsole.sh (or dist\\meshconsole.bat on Windows)"
