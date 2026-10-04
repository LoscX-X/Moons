using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace Moons.WindowsLauncher
{
    internal sealed class SupportProfile
    {
        internal readonly string ProfileId, GameId, ArtifactId, Status, PayloadResource;
        internal readonly string[] Aliases;
        internal readonly int JavaVersion;
        private readonly Regex evidence;

        internal SupportProfile(string profileId, string gameId, string artifactId, string status,
            string payloadResource, string evidencePattern, string[] aliases, int javaVersion = 25)
        {
            ProfileId = profileId;
            GameId = gameId;
            ArtifactId = artifactId;
            Status = status;
            PayloadResource = payloadResource;
            Aliases = aliases;
            JavaVersion = javaVersion;
            evidence = new Regex(evidencePattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        }

        internal string PayloadFileName { get { return "moons-" + ArtifactId + ".jar"; } }
        internal string YsmModuleName { get { return "modules/moons-ysm-" + GameId + ".jar"; } }
        internal bool Matches(string value) { return evidence.IsMatch(value); }
    }

    /// <summary>Build-generated support data, with pure input/evidence resolution.</summary>
    internal static partial class VersionCatalog
    {
        internal static SupportProfile BaseProfile { get { return Profiles[0]; } }

        internal static string Normalize(string configured)
        {
            string value = configured == null ? String.Empty : configured.Trim();
            foreach (var profile in Profiles)
                foreach (string alias in profile.Aliases)
                    if (String.Equals(value, alias, StringComparison.OrdinalIgnoreCase)) return profile.ArtifactId;
            return null;
        }

        internal static string MatchEvidence(string evidence)
        {
            if (String.IsNullOrWhiteSpace(evidence)) return null;
            SupportProfile match = null;
            foreach (var profile in Profiles)
            {
                if (!profile.Matches(evidence)) continue;
                if (match != null) return null;
                match = profile;
            }
            return match == null ? null : match.ArtifactId;
        }

        internal static SupportProfile FindArtifact(string artifactId)
        {
            foreach (var profile in Profiles)
                if (String.Equals(profile.ArtifactId, artifactId, StringComparison.Ordinal)) return profile;
            return null;
        }

        internal static string ConfiguredVersions(string separator)
        {
            var values = new List<string>();
            foreach (var profile in Profiles) values.AddRange(profile.Aliases);
            return String.Join(separator, values.ToArray());
        }

        internal static string GameVersions(string separator)
        {
            var values = new List<string>();
            foreach (var profile in Profiles) values.Add(profile.GameId);
            return String.Join(separator, values.ToArray());
        }

        internal static string[] YsmPackageNames()
        {
            var names = new List<string> { "libraries/moons-ysm-core.jar", "libraries/moons-ysm-codecs.jar",
                "libraries/moons-ysm-images.jar" };
            foreach (var profile in Profiles) names.Add(profile.YsmModuleName);
            return names.ToArray();
        }
    }
}
