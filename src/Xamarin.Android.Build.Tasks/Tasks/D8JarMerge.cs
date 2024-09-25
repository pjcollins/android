using System;
using System.IO;

using Microsoft.Build.Framework;
using Microsoft.Build.Utilities;
using Xamarin.Android.Tools;
using Microsoft.Android.Build.Tasks;

namespace Xamarin.Android.Tasks
{
	public class D8JarMerge : JavaToolTask
	{
		public override string TaskPrefix => "JRMRG";

		[Required]
		public string InputDirectory { get; set; }

		[Required]
		public string OutputJar { get; set; }

		protected override string ToolName {
			get { return OS.IsWindows ? "jar.exe" : "jar"; }
		}

		protected override string GenerateCommandLineCommands ()
		{
			return GetCommandLineBuilder ().ToString ();
		}

		protected virtual CommandLineBuilder GetCommandLineBuilder ()
		{
			var cmd = new CommandLineBuilder ();
			cmd.AppendSwitch ("--create");
			cmd.AppendSwitchIfNotNull ("--file ", Path.GetFullPath (OutputJar));
			cmd.AppendSwitchIfNotNull ("-C ", Path.GetFullPath (InputDirectory));
			cmd.AppendSwitch (".");
			return cmd;
		}

		public override bool RunTask ()
		{
			var didTaskSucceed = base.RunTask ();
			//Directory.Delete (WorkingDirectory, true);
			return didTaskSucceed;
		}

		// Note: We do not want to call the base.LogEventsFromTextOutput as it will incorrectly identify
		// Warnings and Info messages as errors.
		//protected override void LogEventsFromTextOutput (string singleLine, MessageImportance messageImportance)
		//{
		//	CheckForError (singleLine);
		//	Log.LogMessage (messageImportance, singleLine);
		//}
	}
}
