using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

namespace Xamarin.Android.Prepare
{
	partial class AndroidToolchain : AppObject
	{
		public static readonly Uri AndroidUri = Configurables.Urls.AndroidToolchain_AndroidUri;

		public List<AndroidToolchainComponent> Components { get; }

		public AndroidToolchain ()
		{
			string AndroidNdkVersion       = BuildAndroidPlatforms.AndroidNdkVersion;
			string AndroidPkgRevision      = BuildAndroidPlatforms.AndroidNdkPkgRevision;
			string AndroidNdkDirectory     = GetRequiredProperty (KnownProperties.AndroidNdkDirectory);
			string AndroidCmakeUrlPrefix   = Context.Instance.Properties.GetValue (KnownProperties.AndroidCmakeUrlPrefix) ?? String.Empty;
			string AndroidCmakeVersion     = GetRequiredProperty (KnownProperties.AndroidCmakeVersion);
			string AndroidCmakeVersionPath = GetRequiredProperty (KnownProperties.AndroidCmakeVersionPath);
			string CommandLineToolsVersion = GetRequiredProperty (KnownProperties.CommandLineToolsVersion);
			string CommandLineToolsFolder  = GetRequiredProperty (KnownProperties.CommandLineToolsFolder);
			string EmulatorVersion         = GetRequiredProperty (KnownProperties.EmulatorVersion);
			string EmulatorPkgRevision     = GetRequiredProperty (KnownProperties.EmulatorPkgRevision);
			string XABuildToolsFolder      = GetRequiredProperty (KnownProperties.XABuildToolsFolder);
			string XABuildToolsVersion         = GetRequiredProperty (KnownProperties.XABuildToolsVersion);
			string XABuildToolsPackagePrefix   = Context.Instance.Properties [KnownProperties.XABuildToolsPackagePrefix] ?? String.Empty;
			string XABuildTools30Folder        = GetRequiredProperty (KnownProperties.XABuildTools30Folder);
			string XABuildTools30Version       = GetRequiredProperty (KnownProperties.XABuildTools30Version);
			string XABuildTools30PackagePrefix = Context.Instance.Properties [KnownProperties.XABuildTools30PackagePrefix] ?? String.Empty;
			string XAPlatformToolsVersion  = GetRequiredProperty (KnownProperties.XAPlatformToolsVersion);
			string XAPlatformToolsPackagePrefix = Context.Instance.Properties [KnownProperties.XAPlatformToolsPackagePrefix] ?? String.Empty;
			bool isArm64Apple = Context.Instance.OS.Flavor == "macOS" && RuntimeInformation.OSArchitecture == Architecture.Arm64;
			string emulatorArch = isArm64Apple ? "aarch64" : "x64";
			string systemImageArch = isArm64Apple ? "arm64-v8a" : "x86_64";

			// Upstream manifests with version information:
			//
			//  https://dl-ssl.google.com/android/repository/repository2-1.xml
			//  https://dl-ssl.google.com/android/repository/repository2-3.xml
			//    * platform APIs
			//    * build-tools
			//    * command-line tools
			//    * sdk-tools
			//    * platform-tools
			//
			//  https://dl-ssl.google.com/android/repository/addon2-1.xml
			//    * android_m2repository_r47
			//
			//  https://dl-ssl.google.com/android/repository/sys-img/android/sys-img2-1.xml
			//  https://dl-ssl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml
			//    * system images
			//
			Components = new List<AndroidToolchainComponent> {
				new AndroidPlatformComponent ("platform-33_r02",   apiLevel: "33", pkgRevision: "2"),
				new AndroidPlatformComponent ("platform-UpsideDownCake_r03",   apiLevel: "UpsideDownCake", pkgRevision: "3"),

				new AndroidToolchainComponent ("sources-33_r01",
					destDir: Path.Combine ("sources", "android-33"),
					pkgRevision: "1",
					dependencyType: AndroidToolchainComponentType.BuildDependency,
					buildToolVersion: "33.1"
				),
				new AndroidToolchainComponent ("docs-24_r01",
					destDir: "docs",
					pkgRevision: "1",
					dependencyType: AndroidToolchainComponentType.BuildDependency,
					buildToolVersion: "24.1"
				),
				new AndroidToolchainComponent ("android_m2repository_r47",
					destDir: Path.Combine ("extras", "android", "m2repository"),
					pkgRevision: "47.0.0",
					dependencyType: AndroidToolchainComponentType.BuildDependency,
					buildToolVersion: "47.0.0"
				),
				new AndroidToolchainComponent (isArm64Apple ? $"{systemImageArch}-29_r08" : $"{systemImageArch}-29_r08-{osTag}",
					destDir: Path.Combine ("system-images", "android-29", "default", systemImageArch),
					relativeUrl: new Uri ("sys-img/android/", UriKind.Relative),
					pkgRevision: "8",
					dependencyType: AndroidToolchainComponentType.EmulatorDependency
				),
				new AndroidToolchainComponent ($"android-ndk-r{AndroidNdkVersion}-{osTag}",
					destDir: AndroidNdkDirectory,
					pkgRevision: AndroidPkgRevision,
					buildToolName: $"android-ndk-r{AndroidNdkVersion}",
					buildToolVersion: AndroidPkgRevision
				),
				new AndroidToolchainComponent ($"{XABuildToolsPackagePrefix}build-tools_r{XABuildToolsVersion}-{altOsTag}",
					destDir: Path.Combine ("build-tools", XABuildToolsFolder),
					isMultiVersion: true,
					buildToolName: "android-sdk-build-tools",
					buildToolVersion: $"{XABuildToolsVersion}"
				),
				new AndroidToolchainComponent ($"{XABuildTools30PackagePrefix}build-tools_r{XABuildTools30Version}-{altOsTag}",
					destDir: Path.Combine ("build-tools", XABuildTools30Folder),
					isMultiVersion: true,
					buildToolName: "android-sdk-build-tools",
					buildToolVersion: $"{XABuildTools30Version}"
				),
				new AndroidToolchainComponent ($"commandlinetools-{cltOsTag}-{CommandLineToolsVersion}",
					destDir: Path.Combine ("cmdline-tools", CommandLineToolsFolder),
					isMultiVersion: true,
					buildToolName: "android-sdk-cmdline-tools",
					buildToolVersion: $"{CommandLineToolsFolder}.{CommandLineToolsVersion}"
				),
				new AndroidToolchainComponent ($"{XAPlatformToolsPackagePrefix}platform-tools_r{XAPlatformToolsVersion}-{osTag}",
					destDir: "platform-tools",
					pkgRevision: XAPlatformToolsVersion,
					buildToolName: "android-sdk-platform-tools",
					buildToolVersion: XAPlatformToolsVersion
				),
				new AndroidToolchainComponent ($"emulator-{osTag}_{emulatorArch}-{EmulatorVersion}",
					destDir: "emulator",
					pkgRevision: EmulatorPkgRevision,
					dependencyType: AndroidToolchainComponentType.EmulatorDependency
				),
				new AndroidToolchainComponent ($"{AndroidCmakeUrlPrefix}cmake-{AndroidCmakeVersion}-{osTag}",
					destDir: Path.Combine ("cmake", AndroidCmakeVersionPath),
					isMultiVersion: true,
					noSubdirectory: true,
					pkgRevision: AndroidCmakeVersion,
					buildToolName: "android-sdk-cmake",
					buildToolVersion: AndroidCmakeVersion
				),
			};
		}

		static string GetRequiredProperty (string propertyName)
		{
			string? value = Context.Instance.Properties [propertyName];
			if (String.IsNullOrEmpty (value))
				throw new InvalidOperationException ($"Required property '{propertyName}' not defined");
			return value!;
		}
	}
}
