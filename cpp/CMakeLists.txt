cmake_minimum_required(VERSION 3.16)
project(meshcli CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Linux: sudo apt install libprotobuf-dev protobuf-compiler cmake g++
# Windows: vcpkg install protobuf  then  cmake -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake ..
find_package(Protobuf REQUIRED)
find_package(Threads REQUIRED)

# Meshtastic .proto files live in ../protobufs (run ../fetch-protos.sh once)
set(PROTO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../protobufs)
file(GLOB MESH_PROTOS ${PROTO_ROOT}/meshtastic/*.proto)
set(GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GEN_DIR})

set(GEN_SRCS)
foreach(p ${MESH_PROTOS} ${PROTO_ROOT}/nanopb.proto)
    file(RELATIVE_PATH rel ${PROTO_ROOT} ${p})
    string(REGEX REPLACE "\\.proto$" ".pb.cc" cc ${rel})
    string(REGEX REPLACE "\\.proto$" ".pb.h" h ${rel})
    list(APPEND GEN_SRCS ${GEN_DIR}/${cc} ${GEN_DIR}/${h})
endforeach()

add_custom_command(
    OUTPUT ${GEN_SRCS}
    COMMAND protobuf::protoc --cpp_out=${GEN_DIR} -I${PROTO_ROOT} ${MESH_PROTOS} ${PROTO_ROOT}/nanopb.proto
    DEPENDS ${MESH_PROTOS} ${PROTO_ROOT}/nanopb.proto
    COMMENT "Generating Meshtastic protobuf C++ sources")

add_library(meshproto STATIC ${GEN_SRCS})
target_include_directories(meshproto PUBLIC ${GEN_DIR})
target_link_libraries(meshproto PUBLIC protobuf::libprotobuf)
# generated code is noisy under -Wall
target_compile_options(meshproto PRIVATE $<$<CXX_COMPILER_ID:GNU,Clang>:-w>)

add_executable(meshcli
    src/main.cpp src/serial.cpp src/mesh_stream.cpp src/mesh_state.cpp)
target_include_directories(meshcli PRIVATE src)
target_link_libraries(meshcli PRIVATE meshproto Threads::Threads)
if(WIN32)
    target_link_libraries(meshcli PRIVATE ws2_32)
endif()
