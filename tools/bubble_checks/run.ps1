param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$GradleCache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jars = Get-ChildItem $GradleCache -Recurse -Include 'kotlin-compiler-embeddable-1.9.24.jar','trove4j-1.0.20200330.jar','kotlin-stdlib-1.9.24.jar','annotations-13.0.jar','kotlin-script-runtime-1.9.24.jar','kotlin-reflect-1.6.10.jar'
if ($jars.Count -lt 6) { throw 'Required Kotlin compiler dependencies are missing from the Gradle cache. Sync the project first.' }
$classpath = $jars.FullName -join ';'
$destination = Join-Path $repo 'app/build/bubble-checks.jar'
New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$sources = @(
    (Join-Path $repo 'app/src/main/java/com/comicreader/app/data/bubble/MaskBoundary.kt'),
    (Join-Path $repo 'app/src/main/java/com/comicreader/app/ui/reader/BubbleSizing.kt'),
    (Join-Path $PSScriptRoot 'Checks.kt')
)
& $java -cp $classpath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $destination @sources
if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed to compile.' }
& $java -cp "$destination;$classpath" ChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed.' }
