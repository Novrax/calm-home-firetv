# Builds launcher\out\calm-home.apk without Gradle. Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File launcher\build.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$sdk  = if (Test-Path "$env:LOCALAPPDATA\Android\Sdk") { "$env:LOCALAPPDATA\Android\Sdk" } else { (Get-Item "$env:LOCALAPPDATA\Packages\Claude_*\LocalCache\Local\Android\Sdk" | Select-Object -First 1).FullName }
$bt   = "$sdk\build-tools\34.0.0"
$jar  = "$sdk\platforms\android-34\android.jar"
$jdk  = (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
$out  = "$here\out"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -I $jar --manifest "$here\AndroidManifest.xml" --java "$out\gen" `
    --min-sdk-version 26 --target-sdk-version 30 -o "$out\base.apk" "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$sources = Get-ChildItem -Recurse "$here\src", "$out\gen" -Filter *.java | ForEach-Object FullName
& javac -nowarn -Xlint:-options -source 11 -target 11 -encoding UTF-8 -classpath $jar -d "$out\classes" @sources
if ($LASTEXITCODE) { throw 'javac failed' }
$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 26 --lib $jar --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Copy-Item "$out\base.apk" "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

$ks = "$here\signing.jks"
if (-not (Test-Path $ks)) {
    cmd /c "keytool -genkeypair -keystore `"$ks`" -storepass calmhome -keypass calmhome -alias calm -keyalg RSA -keysize 2048 -validity 36500 -dname `"CN=Calm Home`" 2>nul"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:calmhome --key-pass pass:calmhome `
    --out "$out\calm-home.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Get-Item "$out\calm-home.apk" | Select-Object Name, Length
