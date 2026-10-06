#requires -version 5.1
<#
    Dependency-free build + launch of the COTP web demo.

    Compiles the REAL core library (cotp-java/src/main/java) together with the
    demo server, copies the bundled BIP-39 dictionary and the static frontend
    into cotp-demo/build/classes, then starts the JDK HttpServer.

    Requires only a JDK (21+) on PATH. No Maven, no Gradle, no external jars.

    Usage:
        powershell -ExecutionPolicy Bypass -File .\cotp-demo\run-demo.ps1
        powershell -ExecutionPolicy Bypass -File .\cotp-demo\run-demo.ps1 -Port 9090 -NoStop
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$NoStop
)

$ErrorActionPreference = 'Stop'

foreach ($tool in 'java', 'javac') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' was not found on PATH. Install a JDK 21+ and reopen the shell."
    }
}

$base      = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $base
$coreJava  = Join-Path $repoRoot 'cotp-java\src\main\java'
$coreRes   = Join-Path $repoRoot 'cotp-java\src\main\resources\com\cotp\cotp-english.txt'
$demoJava  = Join-Path $base 'src'
$webSrc    = Join-Path $base 'web'
$build     = Join-Path $base 'build'
$classes   = [System.IO.Path]::GetFullPath((Join-Path $build 'classes'))

Write-Host "== COTP web demo build ==" -ForegroundColor Cyan

if (-not (Test-Path -LiteralPath $coreJava)) { throw "Core source not found: $coreJava" }
if (-not (Test-Path -LiteralPath $coreRes))  { throw "Dictionary resource not found: $coreRes" }

# Fresh, isolated build dir. cotp-java/build is never touched.
if (Test-Path -LiteralPath $build) { Remove-Item -Recurse -Force -LiteralPath $build }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Compile on the classpath (unnamed module): collect the FULL source set — every core source
# (minus module-info.java, which would turn this into a JPMS build and hide jdk.httpserver)
# plus every demo source. Keep the FileInfo objects so each maps back to the .class javac emits.
$sourceFiles = @()
$sourceFiles += Get-ChildItem -Recurse -File -Filter *.java -LiteralPath $coreJava |
    Where-Object { $_.Name -ne 'module-info.java' }
$sourceFiles += Get-ChildItem -Recurse -File -Filter *.java -LiteralPath $demoJava
if ($sourceFiles.Count -eq 0) { throw "No .java sources found under $coreJava or $demoJava" }

Write-Host "Compiling $($sourceFiles.Count) source files (core + demo) with UTF-8..." -ForegroundColor Yellow

# Feed the file list through a javac @argfile rather than the command line: it stays
# well under the Windows command-line length limit even as the library grows, and no
# argument can be silently split/misquoted. UTF-8 without BOM (a BOM corrupts the first arg).
# NOTE: inside a javac @argfile a backslash is an escape character, so Windows paths are
# written with forward slashes (accepted by javac) to avoid mangling, e.g. C:\a -> C:a.
$argFile = Join-Path $build 'sources.txt'
$argLines = $sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[System.IO.File]::WriteAllText($argFile, ($argLines -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
& javac -encoding UTF-8 -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# Exhaustive completeness gate: a hardcoded subset (the earlier Json$Parser fix) cannot prove
# the runtime classpath is whole, so a stale/partial build dir could still ship without a core
# class and only fail at runtime, e.g. ClassNotFoundException: com.cotp.ConstantTime from
# CotpVerifier.wordsMatch() during /api/verify. Instead, require that EVERY source file produced
# its top-level .class, then cross-check the whole output dir against the source set.
$missing = @()
$expected = @()
foreach ($src in $sourceFiles) {
    $root = if ($src.FullName.StartsWith($coreJava, [System.StringComparison]::OrdinalIgnoreCase)) {
        $coreJava
    } else {
        $demoJava
    }
    $rel = $src.FullName.Substring($root.Length).TrimStart('\')
    $classRel = [System.IO.Path]::ChangeExtension($rel, '.class')
    $expected += $classRel
    if (-not (Test-Path -LiteralPath (Join-Path $classes $classRel))) { $missing += $classRel }
}
if ($missing.Count -gt 0) {
    throw ("Build is incomplete: $($missing.Count) of $($expected.Count) source files produced no class file." +
        "`nMissing from the runtime classpath ($classes):`n  " + ($missing -join "`n  ") +
        "`nRe-run the script (it performs a clean build) and read the javac output above.")
}

# Walk the produced output and make sure there are at least as many .class files as sources
# (nested/anon classes only add to this count), and that none of them escaped $classes.
$produced = @(Get-ChildItem -Recurse -File -Filter *.class -LiteralPath $classes)
if ($produced.Count -lt $sourceFiles.Count) {
    throw ("Build is incomplete: $($produced.Count) .class files under $classes for " +
        "$($sourceFiles.Count) sources. Re-run the script for a clean rebuild.")
}
Write-Host ("Verified all $($expected.Count) source files produced classes " +
    "($($produced.Count) .class files incl. nested; runtime classpath = $classes).") -ForegroundColor Green

# Bundle the dictionary at the classpath location the library expects.
$resOut = Join-Path $classes 'com\cotp'
New-Item -ItemType Directory -Force -Path $resOut | Out-Null
Copy-Item -LiteralPath $coreRes -Destination (Join-Path $resOut 'cotp-english.txt') -Force

# Bundle the static frontend as a classpath resource (/web/index.html).
$webOut = Join-Path $classes 'web'
New-Item -ItemType Directory -Force -Path $webOut | Out-Null
Copy-Item -Path (Join-Path $webSrc '*') -Destination $webOut -Recurse -Force

# The library resolves the BIP-39 dictionary from the classpath; fail fast if it is not
# exactly where WordDictionary expects it, instead of at startup with an empty dictionary.
if (-not (Test-Path -LiteralPath (Join-Path $resOut 'cotp-english.txt'))) {
    throw "Dictionary resource missing from the runtime classpath: $resOut\cotp-english.txt"
}

Write-Host ""
Write-Host "Starting server on port $Port ..." -ForegroundColor Yellow
Write-Host "(the server prints the listening URL below)" -ForegroundColor DarkGray
Write-Host ""

if ($NoStop) {
    & java "-Dfile.encoding=UTF-8" -cp $classes com.cotp.demo.DemoServer $Port
} else {
    try {
        & java "-Dfile.encoding=UTF-8" -cp $classes com.cotp.demo.DemoServer $Port
    } finally {
        Write-Host "Demo stopped." -ForegroundColor Green
    }
}

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "The COTP demo server failed to start or exited unexpectedly (exit code $LASTEXITCODE)." -ForegroundColor Red
    Write-Host "Common causes:" -ForegroundColor Red
    Write-Host "  - Port $Port is already in use; try running with -Port 9090." -ForegroundColor Red
    Write-Host "  - A compiled class is missing from: $classes" -ForegroundColor Red
    Write-Host "Re-run the script (it performs a clean build) and read the messages above." -ForegroundColor Red
    throw "demo server exited with code $LASTEXITCODE"
}
