# Construit HTMod pour Windows (decodeurs SARSAT / radiosondes + waterfall, liaison Bluetooth avec le poste).
#   powershell -ExecutionPolicy Bypass -File build.ps1 [-NoTest] [-Run] [-Bsht E:\firmware\bsht]
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Release -Notes "Nouveautes..."   (publie la Release GitHub)
# Resultat : dist\HTMod\HTMod.exe (runtime Java inclus), dist\HTMod-windows.zip, HTMod-Setup.exe (installateur)
# et HTMod-Setup.exe.sha256 (empreinte verifiee par la mise a jour integree).
# $Bsht : dossier contenant mod\src (decodeurs partages), tools\jdk et htcommander.
# La version est celle de App.VERSION (src\com\htmod\App.java) : la changer la avant une nouvelle Release.
param([switch]$NoTest, [switch]$Run, [string]$Bsht = "E:\firmware\bsht", [switch]$Release, [string]$Notes = "")

$b   = $Bsht
$w   = $PSScriptRoot
$jh  = "$b\tools\jdk\jdk-17.0.20.1+1"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$out = "$w\out"
$env:JAVA_HOME = $jh
$env:PATH = "$jh\bin;$env:PATH"

function Fail($msg) { Write-Host "ECHEC : $msg" -ForegroundColor Red; exit 1 }

$m = Select-String -Path "$w\src\com\htmod\App.java" -Pattern 'static final String VERSION = "([0-9.]+)"'
if (-not $m) { Fail "VERSION introuvable dans App.java" }
$Version = $m.Matches[0].Groups[1].Value
Write-Host "HTMod $Version"

foreach ($d in "classes", "test-classes", "stage") {
    Remove-Item -Recurse -Force "$out\$d" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force "$out\$d" | Out-Null
}

# 1. Pont Bluetooth BLE + RFCOMM (.NET Framework 4 et metadonnees WinRT, presents sur tout Windows 10/11).
$fw = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319"
$wm = "C:\Windows\System32\WinMetadata"
$refs = "$wm\Windows.Devices.winmd", "$wm\Windows.Foundation.winmd", "$wm\Windows.Storage.winmd", "$wm\Windows.Security.winmd",
        "$fw\System.Runtime.dll", "$fw\System.Threading.Tasks.dll", "$fw\System.Runtime.InteropServices.WindowsRuntime.dll" |
        ForEach-Object { "/r:$_" }
& $csc /nologo /optimize+ /target:exe /platform:anycpu "/out:$out\stage\btbridge.exe" $refs "$w\bridge\BtBridge.cs"
if ($LASTEXITCODE -ne 0) { Fail "csc btbridge" }

# 2. Appli Java : sources Windows + decodeurs partages avec la version Android (mod\src).
$shared = "Sarsat.java", "Sondes.java", "ReedSolomon.java" | ForEach-Object { "$b\mod\src\com\htmod\$_" }
$src = @((Get-ChildItem "$w\src\com\htmod\*.java").FullName) + $shared
& javac -encoding UTF-8 --release 17 -Xlint:-options -d "$out\classes" $src
if ($LASTEXITCODE -ne 0) { Fail "javac" }
& jar --create --file "$out\stage\htmod.jar" --main-class com.htmod.App -C "$out\classes" . -C "$w\res" .
if ($LASTEXITCODE -ne 0) { Fail "jar" }

# 3. Tests sans poste (SBC, protocole GAIA, chaine de decodage avec les enregistrements de HTCommander).
if (-not $NoTest) {
    & javac -encoding UTF-8 --release 17 -cp "$out\classes" -d "$out\test-classes" (Get-ChildItem "$w\test\com\htmod\*.java").FullName
    if ($LASTEXITCODE -ne 0) { Fail "javac tests" }
    & java -cp "$out\classes;$out\test-classes;$w\res" com.htmod.WinTest "$b\htcommander\HTCommander-main\src\test" "$w\test\data"
    if ($LASTEXITCODE -ne 0) { Fail "tests" }
}

# 4. Paquet autonome (runtime Java reduit inclus).
Remove-Item -Recurse -Force "$w\dist" -ErrorAction SilentlyContinue
& jpackage --type app-image --name HTMod --app-version $Version --vendor HTMod `
    --input "$out\stage" --main-jar htmod.jar --main-class com.htmod.App `
    --add-modules java.desktop,java.prefs,java.net.http,jdk.crypto.ec,jdk.charsets,java.naming,jdk.localedata,jdk.httpserver `
    --java-options "-Dsun.java2d.uiScale.enabled=true" `
    --dest "$w\dist"
if ($LASTEXITCODE -ne 0) { Fail "jpackage" }
Compress-Archive -Force -Path "$w\dist\HTMod" -DestinationPath "$w\dist\HTMod-windows.zip"

# 5. Installateur (Inno Setup 6 : winget install JRSoftware.InnoSetup) ; propose l'icone sur le Bureau.
$iscc = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe" | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($iscc) {
    & $iscc /Q "/DAppDir=$w\dist\HTMod" "/DOutDir=$w" "/DAppVersion=$Version" "$w\installer\HTMod.iss"
    if ($LASTEXITCODE -ne 0) { Fail "Inno Setup" }
    $sha = (Get-FileHash -Algorithm SHA256 "$w\HTMod-Setup.exe").Hash.ToLower()
    [IO.File]::WriteAllText("$w\HTMod-Setup.exe.sha256", "$sha  HTMod-Setup.exe`n")
    Write-Host "OK : $w\HTMod-Setup.exe (SHA-256 $sha)" -ForegroundColor Green
} else {
    Write-Host "Inno Setup absent : HTMod-Setup.exe non reconstruit" -ForegroundColor Yellow
}

# 6. Release GitHub (gh auth login au prealable) : tag v<version>, installateur et empreinte joints.
#    Les postes installes la voient au demarrage (Options > Rechercher une mise a jour).
if ($Release) {
    if (-not $iscc) { Fail "Inno Setup requis pour publier" }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI absent (winget install GitHub.cli)" }
    if (git -C $w status --porcelain -- src res installer bridge build.ps1) { Fail "modifications non commitees : commitez et poussez avant de publier" }
    if (-not $Notes) { $Notes = "HTMod $Version" }
    & gh release create "v$Version" "$w\HTMod-Setup.exe" "$w\HTMod-Setup.exe.sha256" --repo Jlecouffe/HTMOD `
        --title "HTMod $Version" --notes $Notes --target main
    if ($LASTEXITCODE -ne 0) { Fail "gh release create (version deja publiee ? gh auth login ?)" }
    Write-Host "OK : Release v$Version publiee" -ForegroundColor Green
}

Write-Host "OK : $w\dist\HTMod\HTMod.exe" -ForegroundColor Green
if ($Run) { & "$w\dist\HTMod\HTMod.exe" }
