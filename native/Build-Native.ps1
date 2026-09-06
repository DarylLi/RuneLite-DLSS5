[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string] $Configuration = 'Release',
    [switch] $EnableNeuralRendering
)

$nativeRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot '..'))
$buildDirectory = Join-Path $nativeRoot 'build'
$runtimeDirectory = Join-Path $repositoryRoot '.runtime\runelite-webgpu'

$clang = (Get-Command clang.exe -ErrorAction Stop).Source
$clangCxx = (Get-Command clang++.exe -ErrorAction Stop).Source
$make = (Get-Command mingw32-make.exe -ErrorAction Stop).Source

$neuralRendering = if ($EnableNeuralRendering) { 'ON' } else { 'OFF' }
cmake -S $nativeRoot -B $buildDirectory -G 'MinGW Makefiles' `
    "-DCMAKE_BUILD_TYPE=$Configuration" `
    "-DRLW_ENABLE_DLSS5_NEURAL_RENDERING=$neuralRendering" `
    "-DCMAKE_C_COMPILER=$clang" `
    "-DCMAKE_CXX_COMPILER=$clangCxx" `
    "-DCMAKE_MAKE_PROGRAM=$make"
if ($LASTEXITCODE -ne 0) { throw "Native CMake configure failed ($LASTEXITCODE)" }

cmake --build $buildDirectory --config $Configuration --parallel
if ($LASTEXITCODE -ne 0) { throw "Native build failed ($LASTEXITCODE)" }

New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
Get-ChildItem -LiteralPath (Join-Path $buildDirectory 'bin') -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $runtimeDirectory -Force
}
Write-Host "Standalone RuneLite WebGPU runtime built at $runtimeDirectory"
