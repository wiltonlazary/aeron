@if "%DEBUG%" == "" @echo off
setlocal EnableDelayedExpansion

set "DIR=%~dp0"
set "SOURCE_DIR=%DIR%\.."
set "BUILD_DIR=%DIR%\Release"
set "BUILD_CONFIG=Release"
set "ZLIB_ZIP=%DIR%\zlib1211.zip"
set "EXTRA_CMAKE_ARGS="
set "AERON_SKIP_RMDIR="

for %%o in (%*) do (

    if "%%o"=="--help" (
        echo %0 [--c-warnings-as-errors] [--cxx-warnings-as-errors] [--build-aeron-driver] [--link-samples-client-shared] [--build-archive-api] [--skip-rmdir] [--slow-system-tests] [--no-system-tests] [--debug-build] [--help]
        exit /b
    )

    if "%%o"=="--c-warnings-as-errors" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DC_WARNINGS_AS_ERRORS=ON"
    )

    if "%%o"=="--cxx-warnings-as-errors" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DCXX_WARNINGS_AS_ERRORS=ON"
    )

    if "%%o"=="--build-aeron-driver" (
        echo "Enabling building of aeron driver is now the default"
    )

    if "%%o"=="--link-samples-client-shared" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DLINK_SAMPLES_CLIENT_SHARED=ON"
    )

    if "%%o"=="--build-archive-api" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DBUILD_AERON_ARCHIVE_API=ON"
    )

    if "%%o"=="--skip-rmdir" (
        set "AERON_SKIP_RMDIR=yes"
    )

    if "%%o"=="--slow-system-tests" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DAERON_SLOW_SYSTEM_TESTS=ON -DAERON_SYSTEM_TESTS=OFF"
    )

    if "%%o"=="--no-system-tests" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DAERON_SYSTEM_TESTS=OFF"
    )

    if "%%o"=="--debug-build" (
        set "EXTRA_CMAKE_ARGS=!EXTRA_CMAKE_ARGS! -DCMAKE_BUILD_TYPE=Debug"
        set "BUILD_DIR=%DIR%\Debug"
        set "BUILD_CONFIG=Debug"
    )
)

call "%DIR%\vs-helper.cmd"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

if "%AERON_SKIP_RMDIR%" equ "yes" goto :start_build
if EXIST %BUILD_DIR% rd /S /Q %BUILD_DIR%
:start_build

set "ZLIB_BUILD_DIR=%BUILD_DIR%\zlib-build"
set "ZLIB_INSTALL_DIR=%BUILD_DIR%\zlib64"

md %BUILD_DIR%
pushd %BUILD_DIR%
md %ZLIB_BUILD_DIR%
pushd %ZLIB_BUILD_DIR%
7z x %ZLIB_ZIP%
pushd zlib-1.2.11
md build
pushd build

cmake -DCMAKE_INSTALL_PREFIX=%ZLIB_INSTALL_DIR% ..
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

cmake --build . --config Release --target install
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

pushd %BUILD_DIR%
cmake %EXTRA_CMAKE_ARGS% %SOURCE_DIR%
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

cmake --build . --config %BUILD_CONFIG%
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

ctest -C %BUILD_CONFIG% --output-on-failure
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
