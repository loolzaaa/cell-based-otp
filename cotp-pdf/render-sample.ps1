#Requires -Version 5.1
<#
.SYNOPSIS
    Builds cotp-pdf and renders a real, openable COTP sample card PDF.

.DESCRIPTION
    Installs the cotp-java core into the local Maven repository (if needed), compiles the
    cotp-pdf module, resolves its runtime classpath and runs com.cotp.pdf.SampleCardPdf.
    The result is a deterministic, single-page PDF you can open in any PDF reader.

.PARAMETER Layout
    SINGLE (default) or WORD_PAIRS.

.PARAMETER CardId
    Public card id printed on the card. Default: COTP-DEMO-0001.

.PARAMETER Output
    Output PDF path. Default: cotp-pdf\build\sample-card.pdf.

.PARAMETER PinNote
    Optional note printed as the card title. Must NOT be the real PIN.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1
    powershell -ExecutionPolicy Bypass -File cotp-pdf\render-sample.ps1 -Layout WORD_PAIRS
#>
[CmdletBinding()]
param(
    [ValidateSet('SINGLE', 'WORD_PAIRS')]
    [string]$Layout = 'SINGLE',

    [string]$CardId = 'COTP-DEMO-0001',

    [string]$Output = '',

    [string]$PinNote = ''
)

$ErrorActionPreference = 'Stop'

$moduleDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleDir
$mvnw = Join-Path $repoRoot 'cotp-java\mvnw.cmd'
$corePom = Join-Path $repoRoot 'cotp-java\pom.xml'
$modulePom = Join-Path $moduleDir 'pom.xml'
$buildDir = Join-Path $moduleDir 'build'

if (-not (Test-Path -LiteralPath $mvnw)) {
    throw "Maven wrapper not found at $mvnw"
}

if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $buildDir 'sample-card.pdf'
}
$Output = [System.IO.Path]::GetFullPath($Output)

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

function Invoke-Maven {
    param([string[]]$MavenArgs)
    & $mvnw @MavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed (exit $LASTEXITCODE): mvnw $($MavenArgs -join ' ')"
    }
}

$coreJar = Join-Path $env:USERPROFILE '.m2\repository\com\cotp\cotp-java\1.0.0\cotp-java-1.0.0.jar'
if (-not (Test-Path -LiteralPath $coreJar)) {
    Write-Host 'Installing cotp-java core into the local Maven repository...'
    Invoke-Maven @('-B', '-q', '-DskipTests', '-f', $corePom, 'install')
}
else {
    Write-Host "cotp-java core already installed: $coreJar"
}

Write-Host 'Compiling cotp-pdf module...'
Invoke-Maven @('-B', '-q', '-f', $modulePom, 'compile')

Write-Host 'Resolving runtime classpath...'
$cpFile = Join-Path $buildDir 'cp.txt'
Invoke-Maven @('-B', '-q', '-f', $modulePom, 'dependency:build-classpath', "-Dmdep.outputFile=$cpFile")

$deps = (Get-Content -LiteralPath $cpFile -Raw).Trim()
$classesDir = Join-Path $moduleDir 'target\classes'
$classpath = "$classesDir;$deps"

$javaArgs = @('-Djava.awt.headless=true', '-cp', $classpath, 'com.cotp.pdf.SampleCardPdf',
    $Output, $Layout, $CardId)
if (-not [string]::IsNullOrWhiteSpace($PinNote)) {
    $javaArgs += $PinNote
}

Write-Host 'Rendering sample card...'
& java @javaArgs
if ($LASTEXITCODE -ne 0) {
    throw "SampleCardPdf failed (exit $LASTEXITCODE)"
}

if (-not (Test-Path -LiteralPath $Output)) {
    throw "Expected PDF was not produced: $Output"
}

$file = Get-Item -LiteralPath $Output
$bytes = [System.IO.File]::ReadAllBytes($Output)
$header = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(5, $bytes.Length))
Write-Host ''
Write-Host "Generated PDF : $($file.FullName)"
Write-Host "Size (bytes)  : $($file.Length)"
Write-Host "Header        : $header"
