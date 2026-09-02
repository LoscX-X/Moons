using System;
using System.Windows.Forms;
using Moons.Shared;

[assembly: System.Reflection.AssemblyTitle("Moons Hardware ID")]
[assembly: System.Reflection.AssemblyDescription("Out-of-process Moons hardware identifier")]
[assembly: System.Reflection.AssemblyCompany("Moons")]
[assembly: System.Reflection.AssemblyProduct("Moons Hardware ID")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace Moons.HardwareId
{
    internal static class Program
    {
        [STAThread]
        private static int Main()
        {
            string displayName = Environment.GetEnvironmentVariable("MOONS_NAME");
            if (String.IsNullOrWhiteSpace(displayName)) displayName = "Moons";
            displayName = displayName.Trim();
            if (displayName.Length > 32) displayName = displayName.Substring(0, 32);
            try
            {
                string value = HardwareIdGenerator.Generate();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Clipboard.SetText(value);
                MessageBox.Show(
                    "HWID: " + value + "\r\n\r\n已复制到剪贴板。",
                    displayName + " HWID",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return 0;
            }
            catch (Exception error)
            {
                MessageBox.Show(
                    error.Message,
                    displayName + " HWID",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
