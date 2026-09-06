[CmdletBinding()]
param(
	[string] $RuneLiteInstall = (Join-Path $env:LOCALAPPDATA 'RuneLite'),
	[string] $JdkHome,
	[switch] $SkipBuild,
	[switch] $EnableNeuralRendering,
	[switch] $ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$RuneLiteInstall = [System.IO.Path]::GetFullPath($RuneLiteInstall)
$pluginRoot = Join-Path $projectRoot 'plugins\117-hd'
$nativeRoot = Join-Path $projectRoot '.runtime\runelite-webgpu'
$builtJar = Join-Path $pluginRoot 'build\libs\RuneLite-DLSS5.jar'
$installedJar = Join-Path $RuneLiteInstall 'RuneLite-DLSS5.jar'
$installedNative = Join-Path $RuneLiteInstall 'runelite-webgpu'
$configPath = Join-Path $RuneLiteInstall 'config.json'
$officialJar = Join-Path $RuneLiteInstall 'RuneLite.jar'
$runeLiteLauncherPath = Join-Path $RuneLiteInstall 'RuneLite.exe'
$gradleWrapperJar = Join-Path $pluginRoot 'gradle\wrapper\gradle-wrapper.jar'
$backupRoot = Join-Path $RuneLiteInstall 'dlss5-backup'
$backupConfig = Join-Path $backupRoot 'config.official.json'
$customMainClass = 'rs117.hd.WebGpuRuneLite'
$customJarName = 'RuneLite-DLSS5.jar'

# Keep this explicit. A successful install must not silently omit a Streamline,
# NGX, low-latency, patched WebGPU, or project bridge dependency.
$nativeArtifacts = @(
	'runelite_webgpu.dll',
	'wgpu_native.dll',
	'NvLowLatencyVk.dll',
	'sl.interposer.dll',
	'sl.common.dll',
	'sl.dlss.dll',
	'sl.dlss_d.dll',
	'sl.dlss_g.dll',
	'sl.pcl.dll',
	'sl.reflex.dll',
	'nvngx_dlss.dll',
	'nvngx_dlssd.dll',
	'nvngx_dlssg.dll'
)

if ($EnableNeuralRendering) {
	$nativeArtifacts += @('runelite_nvngx.dll', 'sl.dlss_nr.dll', 'nvngx_dlssnr.dll')
}

function Get-Sha256([string] $Path)
{
	return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Assert-File([string] $Path, [string] $Description)
{
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf))
	{
		throw "$Description was not found: $Path"
	}
	if ((Get-Item -LiteralPath $Path).Length -le 0)
	{
		throw "$Description is empty: $Path"
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

function Set-JsonProperty($Object, [string] $Name, $Value)
{
	if ($null -ne $Object.PSObject.Properties[$Name])
	{
		$Object.$Name = $Value
	}
	else
	{
		$Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
	}
}

function Assert-DistributionJar([string] $Path)
{
	$archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
	try
	{
		$mainClassEntry = $archive.GetEntry('rs117/hd/WebGpuRuneLite.class')
		$manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
		if ($null -eq $mainClassEntry -or $null -eq $manifestEntry)
		{
			throw 'the WebGPU entry point or manifest is missing'
		}

		$reader = [System.IO.StreamReader]::new($manifestEntry.Open())
		try
		{
			$manifest = $reader.ReadToEnd()
		}
		finally
		{
			$reader.Dispose()
		}
		if ($manifest -notmatch '(?m)^Main-Class:\s*rs117\.hd\.WebGpuRuneLite\s*$')
		{
			throw 'the manifest does not select rs117.hd.WebGpuRuneLite'
		}
	}
	catch
	{
		throw "RuneLite DLSS5 distribution jar validation failed: $($_.Exception.Message)"
	}
	finally
	{
		$archive.Dispose()
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

function Copy-Verified([string] $Source, [string] $Destination)
{
	$parent = Split-Path -Parent $Destination
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	Copy-Item -LiteralPath $Source -Destination $Destination -Force
	$sourceHash = Get-Sha256 $Source
	$destinationHash = Get-Sha256 $Destination
	if ($sourceHash -ne $destinationHash)
	{
		throw "Staged artifact hash mismatch: $Destination"
	}
}

function Install-Atomic([string] $Source, [string] $Destination)
{
	$sourceHash = Get-Sha256 $Source
	if ((Test-Path -LiteralPath $Destination -PathType Leaf) -and
		(Get-Sha256 $Destination) -eq $sourceHash)
	{
		return
	}

	$parent = Split-Path -Parent $Destination
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	$temporary = Join-Path $parent ('.dlss5-replace-' + [System.Guid]::NewGuid().ToString('N') + '.tmp')
	try
	{
		Copy-Verified $Source $temporary
		for ($attempt = 1; $attempt -le 10; $attempt++)
		{
			try
			{
				# File.Replace's nullable backup argument is bound by PowerShell as
				# an empty string on this .NET runtime and fails with "path is
				# empty". Move(..., overwrite: true) performs the same same-volume
				# atomic rename while the recoverable snapshot is kept separately.
				[System.IO.File]::Move($temporary, $Destination, $true)
				break
			}
			catch [System.IO.IOException]
			{
				if ($attempt -eq 10) { throw }
				Start-Sleep -Milliseconds 500
			}
		}

		if ((Get-Sha256 $Destination) -ne $sourceHash)
		{
			throw "Installed artifact hash mismatch: $Destination"
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

function Backup-File([string] $Source, [string] $Destination)
{
	$parent = Split-Path -Parent $Destination
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	try
	{
		New-Item -ItemType HardLink -Path $Destination -Target $Source -ErrorAction Stop | Out-Null
	}
	catch
	{
		Copy-Verified $Source $Destination
	}
	if ((Get-Sha256 $Source) -ne (Get-Sha256 $Destination))
	{
		throw "Backup hash mismatch: $Destination"
	}
}

function Remove-TransactionDirectory([string] $Path, [string] $RequiredPrefix)
{
	if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return }
	$resolved = [System.IO.Path]::GetFullPath($Path)
	$installPrefix = $RuneLiteInstall.TrimEnd('\') + '\'
	if (-not $resolved.StartsWith($installPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
		-not ([System.IO.Path]::GetFileName($resolved)).StartsWith($RequiredPrefix, [System.StringComparison]::Ordinal))
	{
		throw "Refusing to remove unexpected transaction directory: $resolved"
	}
	Remove-Item -LiteralPath $resolved -Recurse -Force
}

foreach ($requiredPath in @($runeLiteLauncherPath, $officialJar, $configPath, $gradleWrapperJar))
{
	Assert-File $requiredPath 'Required RuneLite installation file'
}

if (-not $SkipBuild -and -not $ValidateOnly)
{
	if ([string]::IsNullOrWhiteSpace($JdkHome))
	{
		$jdkRoots = @(
			'C:\Program Files\Android\openjdk',
			'C:\Program Files\Java',
			'C:\Program Files\Eclipse Adoptium',
			'C:\Program Files\Microsoft'
		)
		$JdkHome = $jdkRoots |
			Where-Object { Test-Path -LiteralPath $_ -PathType Container } |
			ForEach-Object { Get-ChildItem -LiteralPath $_ -Directory -ErrorAction SilentlyContinue } |
			Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') -PathType Leaf } |
			Sort-Object Name -Descending |
			Select-Object -First 1 -ExpandProperty FullName
	}

	$buildJavaPath = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { $null }
	if ([string]::IsNullOrWhiteSpace($buildJavaPath) -or
		-not (Test-Path -LiteralPath $buildJavaPath -PathType Leaf) -or
		-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe') -PathType Leaf))
	{
		throw 'A complete JDK with java.exe and javac.exe is required. Pass its directory with -JdkHome.'
	}

	& (Join-Path $projectRoot 'native\Build-Native.ps1') -Configuration Release -EnableNeuralRendering:$EnableNeuralRendering
	if ($LASTEXITCODE -ne 0)
	{
		throw "Native WebGPU build failed with exit code $LASTEXITCODE"
	}

	Push-Location $pluginRoot
	try
	{
		& $buildJavaPath -classpath $gradleWrapperJar org.gradle.wrapper.GradleWrapperMain webGpuDistributionJar --no-daemon
		$gradleExitCode = $LASTEXITCODE
	}
	finally
	{
		Pop-Location
	}
	if ($gradleExitCode -ne 0)
	{
		throw "RuneLite DLSS5 jar build failed with exit code $gradleExitCode"
	}
}

Assert-File $builtJar 'RuneLite DLSS5 distribution jar'
Assert-DistributionJar $builtJar
foreach ($artifactName in $nativeArtifacts)
{
	Assert-File (Join-Path $nativeRoot $artifactName) "Required native runtime $artifactName"
}

$currentConfig = Read-JsonFile $configPath 'Installed RuneLite configuration'
$isAlreadyOverridden = $currentConfig.mainClass -eq $customMainClass
if ($isAlreadyOverridden)
{
	Assert-File $backupConfig 'Official RuneLite configuration backup'
	$officialConfig = Read-JsonFile $backupConfig 'Official RuneLite configuration backup'
	if ($officialConfig.mainClass -eq $customMainClass)
	{
		throw "Official configuration backup unexpectedly selects the custom client: $backupConfig"
	}
}
else
{
	$officialConfig = $currentConfig
}

if ($ValidateOnly)
{
	Write-Output "Validated RuneLite installation: $RuneLiteInstall"
	Write-Output "Validated distribution jar SHA-256: $(Get-Sha256 $builtJar)"
	Write-Output "Validated $($nativeArtifacts.Count) required native runtime DLLs."
	Write-Output 'No processes were stopped and no files were changed.'
	return
}

$transactionId = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [System.Guid]::NewGuid().ToString('N').Substring(0, 8)
$stagingRoot = Join-Path $RuneLiteInstall ('.dlss5-staging-' + $transactionId)
$snapshotRoot = Join-Path $backupRoot ('snapshots\' + $transactionId)
$stagedJar = Join-Path $stagingRoot $customJarName
$stagedNative = Join-Path $stagingRoot 'runelite-webgpu'
$stagedConfig = Join-Path $stagingRoot 'config.json'
$committedFiles = [System.Collections.Generic.List[object]]::new()

try
{
	# Prepare and verify everything before interrupting the currently installed client.
	Copy-Verified $builtJar $stagedJar
	foreach ($artifactName in $nativeArtifacts)
	{
		Copy-Verified (Join-Path $nativeRoot $artifactName) (Join-Path $stagedNative $artifactName)
	}

	$vmArgs = [System.Collections.Generic.List[string]]::new()
	foreach ($argumentValue in @($officialConfig.vmArgs))
	{
		$argument = [string] $argumentValue
		if (-not $argument.StartsWith('-Drlhd.webgpu.native-dir=', [System.StringComparison]::Ordinal) -and
			-not $argument.StartsWith('-Drunelite.credentials.path=', [System.StringComparison]::Ordinal) -and
			$argument -ne '-ea')
		{
			$vmArgs.Add($argument)
		}
	}
	$vmArgs.Add('-ea')
	$vmArgs.Add("-Drlhd.webgpu.native-dir=$installedNative")
	$customConfig = $officialConfig | ConvertTo-Json -Depth 32 | ConvertFrom-Json
	Set-JsonProperty $customConfig 'classPath' @($customJarName)
	Set-JsonProperty $customConfig 'mainClass' $customMainClass
	Set-JsonProperty $customConfig 'vmArgs' @($vmArgs)
	$configJson = $customConfig | ConvertTo-Json -Depth 32
	New-Item -ItemType Directory -Path (Split-Path -Parent $stagedConfig) -Force | Out-Null
	[System.IO.File]::WriteAllText($stagedConfig, $configJson, [System.Text.UTF8Encoding]::new($false))
	$null = Read-JsonFile $stagedConfig 'Staged RuneLite configuration'

	Stop-InstalledRuneLite
	New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
	New-Item -ItemType Directory -Path $snapshotRoot -Force | Out-Null

	# Retain the exact pre-install state. Hard links keep the large NVIDIA runtime
	# snapshot inexpensive while still surviving atomic replacement of the live files.
	Backup-File $configPath (Join-Path $snapshotRoot 'config.json')
	if (Test-Path -LiteralPath $installedJar -PathType Leaf)
	{
		Backup-File $installedJar (Join-Path $snapshotRoot $customJarName)
	}
	foreach ($artifactName in $nativeArtifacts)
	{
		$installedArtifact = Join-Path $installedNative $artifactName
		if (Test-Path -LiteralPath $installedArtifact -PathType Leaf)
		{
			Backup-File $installedArtifact (Join-Path (Join-Path $snapshotRoot 'runelite-webgpu') $artifactName)
		}
	}

	# Keep an immutable history entry and update the canonical restore point only
	# when config.json is an official configuration.
	if (-not $isAlreadyOverridden)
	{
		$historicalOfficial = Join-Path $backupRoot ("official-configs\config.official.$transactionId.json")
		Backup-File $configPath $historicalOfficial
		Install-Atomic $configPath $backupConfig
	}

	$manifestEntries = [System.Collections.Generic.List[object]]::new()
	$manifestEntries.Add([ordered]@{ path = $customJarName; sha256 = Get-Sha256 $stagedJar })
	foreach ($artifactName in $nativeArtifacts)
	{
		$manifestEntries.Add([ordered]@{
			path = "runelite-webgpu/$artifactName"
			sha256 = Get-Sha256 (Join-Path $stagedNative $artifactName)
		})
	}
	$manifestJson = [ordered]@{
		createdUtc = [DateTime]::UtcNow.ToString('o')
		files = @($manifestEntries)
	} | ConvertTo-Json -Depth 8
	[System.IO.File]::WriteAllText(
		(Join-Path $snapshotRoot 'install-manifest.json'),
		$manifestJson,
		[System.Text.UTF8Encoding]::new($false))

	# Commit binaries first and config last. Until the final replacement, an
	# official config continues to select RuneLite.jar.
	$jarBackup = Join-Path $snapshotRoot $customJarName
	$committedFiles.Add([ordered]@{ destination = $installedJar; backup = if (Test-Path $jarBackup) { $jarBackup } else { $null } })
	Install-Atomic $stagedJar $installedJar
	foreach ($artifactName in $nativeArtifacts)
	{
		$destination = Join-Path $installedNative $artifactName
		$artifactBackup = Join-Path (Join-Path $snapshotRoot 'runelite-webgpu') $artifactName
		$committedFiles.Add([ordered]@{ destination = $destination; backup = if (Test-Path $artifactBackup) { $artifactBackup } else { $null } })
		Install-Atomic (Join-Path $stagedNative $artifactName) $destination
	}
	$committedFiles.Add([ordered]@{ destination = $configPath; backup = (Join-Path $snapshotRoot 'config.json') })
	Install-Atomic $stagedConfig $configPath

	# Final, independent verification of the active install.
	if ((Get-Sha256 $installedJar) -ne (Get-Sha256 $builtJar))
	{
		throw 'Final distribution jar verification failed.'
	}
	foreach ($artifactName in $nativeArtifacts)
	{
		if ((Get-Sha256 (Join-Path $installedNative $artifactName)) -ne
			(Get-Sha256 (Join-Path $nativeRoot $artifactName)))
		{
			throw "Final native runtime verification failed: $artifactName"
		}
	}
	$activeConfig = Read-JsonFile $configPath 'Installed RuneLite configuration'
	if ($activeConfig.mainClass -ne $customMainClass -or
		@($activeConfig.classPath).Count -ne 1 -or
		$activeConfig.classPath[0] -ne $customJarName -or
		-1 -eq [Array]::IndexOf([object[]] @($activeConfig.vmArgs), "-Drlhd.webgpu.native-dir=$installedNative"))
	{
		throw 'Final RuneLite override configuration verification failed.'
	}
}
catch
{
	$installFailure = $_
	# Roll back only files recorded in this exact transaction. Never touch the
	# Jagex Launcher executable, its processes, or any session/account storage.
	for ($index = $committedFiles.Count - 1; $index -ge 0; $index--)
	{
		$entry = $committedFiles[$index]
		try
		{
			if ($null -ne $entry.backup -and (Test-Path -LiteralPath $entry.backup -PathType Leaf))
			{
				Install-Atomic $entry.backup $entry.destination
			}
			elseif (Test-Path -LiteralPath $entry.destination -PathType Leaf)
			{
				Remove-Item -LiteralPath $entry.destination -Force
			}
		}
		catch
		{
			Write-Warning "Rollback failed for $($entry.destination): $($_.Exception.Message)"
		}
	}
	throw $installFailure
}
finally
{
	Remove-TransactionDirectory $stagingRoot '.dlss5-staging-'
}

Write-Output "Installed and hash-verified the RuneLite DLSS5 override at $RuneLiteInstall"
Write-Output "Official configuration backup: $backupConfig"
Write-Output "Recoverable pre-install snapshot: $snapshotRoot"
Write-Output "Deployed $($nativeArtifacts.Count) required native runtime DLLs."
Write-Output 'The Jagex Launcher was left running and its session data was not accessed.'
