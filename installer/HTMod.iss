; Installateur HTMod (Inno Setup 6), appele par build.ps1 :
;   ISCC.exe /DAppDir=<dist\HTMod> /DOutDir=<dossier de sortie> /DAppVersion=1.1 installer\HTMod.iss
; Resultat : HTMod-Setup.exe (propose l'icone sur le Bureau)

#ifndef AppVersion
  #define AppVersion "1.1"
#endif

[Setup]
AppId={{6F1C2A7E-4B8D-4E21-9C3A-7D5E0B1A4C62}
AppName=HTMod
AppVersion={#AppVersion}
AppVerName=HTMod {#AppVersion}
AppPublisher=HTMod
DefaultDirName={autopf}\HTMod
DefaultGroupName=HTMod
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutDir}
OutputBaseFilename=HTMod-Setup
UninstallDisplayIcon={app}\HTMod.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\HTMod"; Filename: "{app}\HTMod.exe"
Name: "{group}\{cm:UninstallProgram,HTMod}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\HTMod"; Filename: "{app}\HTMod.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\HTMod.exe"; Description: "{cm:LaunchProgram,HTMod}"; Flags: nowait postinstall skipifsilent
