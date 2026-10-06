#requires -version 5.1
<#
    Dependency-free build + verification of the COTP library.
    Compiles the library and the standalone self-test, copies the bundled dictionary
    to the class output and runs the determinism/reference-vector harness.
    Requires only a JDK (17+) on PATH.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $base 'build\classes'
$resourceOut = Join-Path $classes 'com\cotp'
$resourceSrc = Join-Path $base 'src\main\resources\com\cotp\cotp-english.txt'

Write-Host "== COTP verify ==" -ForegroundColor Cyan

if (Test-Path (Join-Path $base 'build')) {
    Remove-Item -Recurse -Force (Join-Path $base 'build')
}
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @()
$sources += Get-ChildItem -Recurse -Filter *.java (Join-Path $base 'src\main\java') | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem -Recurse -Filter *.java (Join-Path $base 'tools') | Select-Object -ExpandProperty FullName

Write-Host "Compiling $($sources.Count) source files..." -ForegroundColor Yellow
& javac -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

New-Item -ItemType Directory -Force -Path $resourceOut | Out-Null
Copy-Item -LiteralPath $resourceSrc -Destination (Join-Path $resourceOut 'cotp-english.txt') -Force

Write-Host "Running self-test..." -ForegroundColor Yellow
& java -cp $classes com.cotp.CotpSelfTest
if ($LASTEXITCODE -ne 0) { throw "self-test failed with exit code $LASTEXITCODE" }

Write-Host "OK" -ForegroundColor Green
