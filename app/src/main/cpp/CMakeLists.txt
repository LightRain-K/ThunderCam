cmake_minimum_required(VERSION 3.22.1)
project(processor)

add_library(processor SHARED processor.cpp)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(processor ${log-lib} ${jnigraphics-lib})
target_compile_options(processor PRIVATE -O3 -ffast-math)
