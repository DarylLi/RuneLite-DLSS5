[CmdletBinding()]
param(
	[string] $RuneLiteInstall = (Join-Path $env:LOCALAPPDATA 'RuneLite'),
	[switch] $ValidateOnly
)

$ErrorActionPreference = 'Stop'
$RuneLiteInstall = [System.IO.Path]::GetFullPath($RuneLiteInstall)
$configPath = Join-Path $RuneLiteInstall 'config.json'
$backupRoot = Join-Path $RuneLiteInstall 'dlss5-backup'
$backupConfig = Join-Path $backupRoot 'config.official.json'
$runeLiteLauncherPath = Join-Path $RuneLiteInstall 'RuneLite.exe'
$officialJar = Join-Path $RuneLiteInstall 'RuneLite.jar'
$customMainClass = 'rs117.hd.WebGpuRuneLite'

function Get-Sha256([string] $Path)
{
	return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Assert-File([string] $Path, [string] $Description)
{
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -le 0)
	{
		throw "$Description was not found or is empty: $Path"
	}
}

function Read-JsonFile([string] $Path, [string] $Description)
{
	try
	{
		return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
	}
	catch
	{
		throw "$Description is not valid JSON: $Path`n$($_.Exception.Message)"
	}
}

function Copy-Verified([string] $Source, [string] $Destination)
{
	Copy-Item -LiteralPath $Source -Destination $Destination -Force
	if ((Get-Sha256 $Source) -ne (Get-Sha256 $Destination))
	{
		throw "Staged restore configuration hash mismatch: $Destination"
	}
}

function Install-Atomic([string] $Source, [string] $Destination)
{
	$parent = Split-Path -Parent $Destination
	$temporary = Join-Path $parent ('.dlss5-restore-' + [System.Guid]::NewGuid().ToString('N') + '.tmp')
	try
	{
		Copy-Verified $Source $temporary
		[System.IO.File]::Move($temporary, $Destination, $true)
		if ((Get-Sha256 $Source) -ne (Get-Sha256 $Destination))
		{
			throw "Restored configuration hash mismatch: $Destination"
		}
	}
	finally
	{
		if (Test-Path -LiteralPath $temporary -PathType Leaf)
		{
			Remove-Item -LiteralPath $temporary -Force
		}
	}
}

function Stop-InstalledRuneLite
{
	$expectedPath = [System.IO.Path]::GetFullPath($runeLiteLauncherPath)
	Get-CimInstance Win32_Process |
		Where-Object {
			-not [string]::IsNullOrWhiteSpace($_.ExecutablePath) -and
			[string]::Equals(
				[System.IO.Path]::GetFullPath($_.ExecutablePath),
				$expectedPath,
				[System.StringComparison]::OrdinalIgnoreCase)
		} |
		ForEach-Object {
			Stop-Process -Id $_.ProcessId -Force
			Wait-Process -Id $_.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
		}
}

Assert-File $runeLiteLauncherPath 'RuneLite launcher executable'
Assert-File $officialJar 'Official RuneLite jar'
Assert-File $backupConfig 'Official RuneLite configuration backup'
$officialConfig = Read-JsonFile $backupConfig 'Official RuneLite configuration backup'
if ($officialConfig.mainClass -eq $customMainClass -or -not (@($officialConfig.classPath) -contains 'RuneLite.jar'))
{
	throw "Backup does not select the official RuneLite.jar: $backupConfig"
}

if ($ValidateOnly)
{
	Write-Output "Validated official RuneLite restore point: $backupConfig"
	Write-Output "Backup SHA-256: $(Get-Sha256 $backupConfig)"
	Write-Output 'No processes were stopped and no files were changed.'
	return
}

Stop-InstalledRuneLite
New-Item -ItemType Directory -Path (Join-Path $backupRoot 'restore-snapshots') -Force | Out-Null
if (Test-Path -LiteralPath $configPath -PathType Leaf)
{
	$restoreSnapshot = Join-Path $backupRoot ("restore-snapshots\config.before-restore.$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').json")
	Copy-Verified $configPath $restoreSnapshot
}
Install-Atomic $backupConfig $configPath
$restoredConfig = Read-JsonFile $configPath 'Restored RuneLite configuration'
if ($restoredConfig.mainClass -eq $customMainClass -or -not (@($restoredConfig.classPath) -contains 'RuneLite.jar'))
{
	throw 'Restored RuneLite configuration failed final verification.'
}

Write-Output "Restored and hash-verified the official RuneLite launch configuration at $configPath"
Write-Output 'Custom jar/runtime files were retained but are inactive and recoverable.'
Write-Output 'The Jagex Launcher was left running and its session data was not accessed.'
