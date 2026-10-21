#define MyAppName "Mvisual"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Mvisual Open Source"
#define MyAppExeName "Mvisual.exe"

[Setup]
AppId={{8F59D8AB-23F5-4B85-A2A0-123456789001}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Mvisual
DefaultGroupName=Mvisual
DisableProgramGroupPage=yes
OutputDir=target\installer
OutputBaseFilename=Mvisual-Setup-v1.0.0
SetupIconFile=src\main\resources\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin

[Files]
Source: "target\dist\Mvisual\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Mvisual"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Mvisual"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na Área de Trabalho"; GroupDescription: "Atalhos adicionais:"; Flags: unchecked

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Abrir Mvisual"; Flags: postinstall nowait skipifsilent
