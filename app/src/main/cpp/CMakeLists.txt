cmake_minimum_required(VERSION 3.22.1)
project("image_optimizer")

add_library(
        image_optimizer
        SHARED
        native-lib.cpp)

# Tìm các thư viện lõi của Android
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# Liên kết thư viện C++ của bạn với log và jnigraphics
target_link_libraries(
        image_optimizer
        ${log-lib}
        ${jnigraphics-lib})