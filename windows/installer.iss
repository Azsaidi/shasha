; Shasha for Windows installer (Inno Setup). Installs for the current user, no admin needed.
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\out"
#endif
#ifndef OutDir
  #define OutDir "..\dist"
#endif

[Setup]
AppId={{8C6C7E52-3B7B-4E43-9E7B-5A2C2B9D6B11}
AppName=Shasha
AppVersion={#AppVersion}
AppVerName=Shasha {#AppVersion}
AppPublisher=Azsaidi
AppPublisherURL=https://github.com/Azsaidi/shasha
DefaultDirName={localappdata}\Programs\Shasha
DefaultGroupName=Shasha
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir={#OutDir}
OutputBaseFilename=Shasha-Setup-{#AppVersion}
SetupIconFile=Shasha\Assets\shasha.ico
UninstallDisplayIcon={app}\Shasha.exe
UninstallDisplayName=Shasha
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=force
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Shasha"; Filename: "{app}\Shasha.exe"
Name: "{autodesktop}\Shasha"; Filename: "{app}\Shasha.exe"; Tasks: desktopicon

[Run]
; Also runs after a silent update, so the app opens again.
Filename: "{app}\Shasha.exe"; Description: "{cm:LaunchProgram,Shasha}"; Flags: nowait postinstall
