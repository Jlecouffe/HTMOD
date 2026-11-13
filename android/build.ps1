# Construit HTMod pour Android (appli native en Kotlin) sans Gradle, puis l'installe.
#   powershell -ExecutionPolicy Bypass -File android\build.ps1 [-NoInstall] [-Device 192.168.1.137:36579] [-Bsht E:\firmware\bsht]
# Sources : appli Android en Kotlin (android\kotlin) + coeur Java de HTMod partage avec Windows
#           (decodeurs, protocole du poste : src\com\htmod et <Bsht>\mod\src), copie a l'identique dans
#           build\gen avec les classes rendues publiques (Kotlin refuse d'exposer les types Â« package Â»).
# Outils : JDK 17 et SDK Android de <Bsht>\tools, compilateur Kotlin d'Android Studio.
# Resultat : android\build\HTMod-android.apk
param([switch]$NoInstall, [string]$Device = "", [string]$Bsht = "E:\firmware\bsht",
      [string]$Kotlin = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc")

$a   = $PSScriptRoot
$w   = Split-Path $a -Parent
$t   = "$Bsht\tools"
$jh  = "$t\jdk\jdk-17.0.20.1+1"
$bt  = "$t\sdk\build-tools\35.0.0"
$aj  = "$t\sdk\platforms\android-35\android.jar"
$out = "$a\build"
$ks  = "$a\keystore\htmod-android.keystore"
$std = "$Kotlin\lib\kotlin-stdlib.jar"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "C:\adb\adb.exe" }
$env:JAVA_HOME = $jh
$env:PATH = "$jh\bin;$env:PATH"

function Fail($msg) { Write-Host "ECHEC : $msg" -ForegroundColor Red; exit 1 }
if (-not (Test-Path "$Kotlin\bin\kotlinc.bat")) { Fail "compilateur Kotlin introuvable ($Kotlin)" }
$utf8 = New-Object Text.UTF8Encoding($false)

$m = Select-String -Path "$a\kotlin\com\htmod\Engine.kt" -Pattern 'const val VERSION = "([0-9.]+)"'
if (-not $m) { Fail "VERSION introuvable dans Engine.kt" }
$Version = $m.Matches[0].Groups[1].Value
Write-Host "HTMod Android $Version (Kotlin)"

foreach ($d in "tools", "res", "flat", "gen", "rgen", "kclasses", "classes", "dex", "apk") {
    Remove-Item -Recurse -Force "$out\$d" -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force "$out\$d" | Out-Null
}

# 1. Icones (logo de HTMod Windows dessine par Logo.java) + ressources Android.
& javac -nowarn -encoding UTF-8 -sourcepath "$w\src" -d "$out\tools" "$w\src\com\htmod\Logo.java" "$a\tools\com\htmod\IconGen.java"
if ($LASTEXITCODE -ne 0) { Fail "javac icones" }
Copy-Item -Recurse "$a\resapp\*" "$out\res"
& java "-Djava.awt.headless=true" -cp "$out\tools" com.htmod.IconGen "$out\res"
if ($LASTEXITCODE -ne 0) { Fail "icones" }
& "$bt\aapt2.exe" compile --dir "$out\res" -o "$out\flat\res.zip"
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile" }
$vc = [int]($Version.Replace('.', '')) * 10
& "$bt\aapt2.exe" link -I $aj --manifest "$a\AndroidManifest.xml" --version-code $vc --version-name $Version --replace-version --custom-package com.htmod --java "$out\rgen" -o "$out\apk\base.apk" "$out\flat\res.zip"
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link" }

# 2. Coeur Java partage : copies dans build\gen, classes et types imbriques rendus publics.
function Publish([string]$s) {
    $s = [regex]::Replace($s, '(?m)^final class ', 'public final class ')
    $s = [regex]::Replace($s, '(?m)^class ', 'public class ')
    $s = [regex]::Replace($s, '(?m)^    (static final class|static class|interface|final class|static abstract class|abstract static class) ', '    public $1 ')
    return $s
}
$core = "Afsk1200", "Aprs", "AprsMute", "Ax25", "Decoders", "Pocsag", "PosteDetector", "Radio", "Sbc", "SbcEncoder", "Wav", "Sgp4", "SatTrack" |
        ForEach-Object { "$w\src\com\htmod\$_.java" }
$core += "Sarsat", "Sondes", "ReedSolomon" | ForEach-Object { "$Bsht\mod\src\com\htmod\$_.java" }
foreach ($f in $core) {
    [IO.File]::WriteAllText("$out\gen\" + (Split-Path $f -Leaf), (Publish ([IO.File]::ReadAllText($f))), $utf8)
}

# Sstv.java : images Img (Android) a la place de BufferedImage, dessins par SstvArt.
$s = [IO.File]::ReadAllText("$w\src\com\htmod\Sstv.java")
$s = [regex]::Replace($s, '(?m)^import java\.awt[^\r\n]*\r?\n', '')
$i0 = $s.IndexOf('    /** Image au format du mode'); $i1 = $s.IndexOf('    /** Signal SSTV complet')
if ($i0 -lt 0 -or $i1 -lt $i0) { Fail "Sstv.java : reperes prepare/testCard introuvables" }
$s = $s.Substring(0, $i0) + "    static Img prepare(Img src, Mode m, String text) { return SstvArt.prepare(src, m, text); }`r`n`r`n" +
     "    static Img testCard(Mode m, String text) { return SstvArt.testCard(m, text); }`r`n`r`n" + $s.Substring($i1)
$s = $s.Replace('BufferedImage', 'Img').Replace('HTMOD Windows : SSTV', 'HTMOD Android (copie generee de Sstv.java) : SSTV')
[IO.File]::WriteAllText("$out\gen\Sstv.java", (Publish $s), $utf8)

# SatCatalog.java : reseau par Net (HttpURLConnection / HTTP/2) a la place de java.net.http, reglages Prefs.
$s = [IO.File]::ReadAllText("$w\src\com\htmod\SatCatalog.java")
$s = [regex]::Replace($s, '(?m)^import java\.(net\.URI|net\.http\.\w+|time\.Duration|util\.prefs\.Preferences);\r?\n', '')
$s = [regex]::Replace($s, '(?m)^    private final Preferences prefs = [^\r\n]*\r?\n', '')
$s = [regex]::Replace($s, '(?s)    private final HttpClient http = .*?\.build\(\);\r?\n', '')
$s = $s.Replace('prefs.get(', 'Prefs.get(').Replace('prefs.put(', 'Prefs.put(').Replace('in.readAllBytes()', 'Net.readAll(in)')
$i0 = $s.IndexOf('    private String get(String url)'); $i1 = $s.IndexOf('    /** GetGPResult')
if ($i0 -lt 0 -or $i1 -lt $i0) { Fail "SatCatalog.java : reperes get/fetchBenshi introuvables" }
$net = @'
    private String get(String url) throws IOException, InterruptedException { return Net.get(url); }

    /** Appel gRPC unaire GetGP (syncAt = 0 : liste complete) ; renvoie le message protobuf de reponse. */
    byte[] fetchBenshi() throws IOException, InterruptedException {
        byte[] b = Net.grpc("https://rpc.benshikj.com:800/satellite.Satellite/GetGP", new byte[]{0, 0, 0, 0, 0});
        if (b.length < 5) throw new IOException(Tr.t("reponse tronquee"));
        if (b[0] != 0) throw new IOException(Tr.t("reponse compressee non geree"));
        int len = ((b[1] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 8) | (b[4] & 0xFF);
        if (len < 0 || 5 + len > b.length) throw new IOException(Tr.t("reponse tronquee"));
        byte[] msg = new byte[len];
        System.arraycopy(b, 5, msg, 0, len);
        return msg;
    }


'@
$s = $s.Substring(0, $i0) + $net + $s.Substring($i1)
$s = $s.Replace('HTMOD Windows : catalogue', 'HTMOD Android (copie generee de SatCatalog.java) : catalogue')
if ($s -match 'HttpClient|HttpRequest|Preferences\.') { Fail "SatCatalog.java : reste du code Windows apres conversion" }
[IO.File]::WriteAllText("$out\gen\SatCatalog.java", (Publish $s), $utf8)

# 3. Compilation : Kotlin (avec les sources Java pour les references), puis Java, puis dex.
$java = @((Get-ChildItem "$out\gen" -Filter *.java).FullName) + @((Get-ChildItem "$out\rgen" -Recurse -Filter *.java).FullName)
$kt = @((Get-ChildItem "$a\kotlin" -Recurse -Filter *.kt).FullName)
$args1 = @("-nowarn", "-jvm-target", "1.8", "-no-reflect", "-no-stdlib", "-classpath", "$aj;$std", "-d", "$out\kclasses") + $kt + $java
$argFile = "$out\kotlinc.args"
[IO.File]::WriteAllLines($argFile, ($args1 | ForEach-Object { if ($_ -match '[\s;]') { '"' + $_.Replace('\', '\\') + '"' } else { $_.Replace('\', '\\') } }), $utf8)
& "$Kotlin\bin\kotlinc.bat" "@$argFile" 2>&1 | ForEach-Object { "$_" }
if ($LASTEXITCODE -ne 0) { Fail "kotlinc" }
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -bootclasspath "$aj;$bt\core-lambda-stubs.jar" -cp "$out\kclasses;$std" -d "$out\classes" $java
if ($LASTEXITCODE -ne 0) { Fail "javac" }
Copy-Item -Recurse -Force "$out\kclasses\*" "$out\classes"
Remove-Item -Recurse -Force "$out\classes\META-INF" -ErrorAction SilentlyContinue
& jar --create --file "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { Fail "jar" }
$d8log = & "$bt\d8.bat" --release --min-api 26 --lib $aj --output "$out\dex" "$out\classes.jar" $std 2>&1
if ($LASTEXITCODE -ne 0) { $d8log | ForEach-Object { "$_" } | Select-Object -Last 30 }
if ($LASTEXITCODE -ne 0) { Fail "d8" }

# 4. APK : dex + ressources Java lues par getResourceAsStream (traductions, modeles du poste, satellites).
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\apk\base.apk", 'Update')
foreach ($d in Get-ChildItem "$out\dex" -Filter *.dex) {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $d.FullName, $d.Name) | Out-Null
}
foreach ($n in "i18n.txt", "poste_rt660_1200.txt", "poste_rt660_2400.txt", "htmod_sats.json") {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$w\res\com\htmod\$n", "com/htmod/$n") | Out-Null
}
$zip.Dispose()

# 5. Alignement + signature (cle propre a l'appli, gardee dans android\keystore : les mises a jour l'exigent).
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    cmd /c "`"$jh\bin\keytool.exe`" -genkeypair -keystore `"$ks`" -alias htmod -keyalg RSA -keysize 2048 -validity 10000 -storepass htmodpass -keypass htmodpass -dname `"CN=HTMod Android`" 2>nul"
}
& "$bt\zipalign.exe" -f -p 4 "$out\apk\base.apk" "$out\apk\aligned.apk"
if ($LASTEXITCODE -ne 0) { Fail "zipalign" }
cmd /c "`"$bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:htmodpass --key-pass pass:htmodpass --out `"$out\HTMod-android.apk`" `"$out\apk\aligned.apk`""
if ($LASTEXITCODE -ne 0) { Fail "signature" }
Write-Host ("OK : $out\HTMod-android.apk ({0:N1} Mo)" -f ((Get-Item "$out\HTMod-android.apk").Length / 1MB)) -ForegroundColor Green
if ($NoInstall) { exit 0 }

# 6. Installation (adb, USB ou debogage sans fil).
$dev = if ($Device) { @("-s", $Device) } else { @() }
& $adb @dev install -r "$out\HTMod-android.apk"
if ($LASTEXITCODE -ne 0) { Fail "installation" }
& $adb @dev shell am start -n com.htmod.android/com.htmod.MainActivity
Write-Host "Installe et lance." -ForegroundColor Green
