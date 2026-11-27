using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using Tempos.WindowsLauncher;
internal static class PortableLegacyPropertiesVerification {
 static void Need(bool b,string m){if(!b)throw new Exception(m);}
 internal static void Run(string[] args){
  string app=Path.GetFullPath(args[0]);Environment.SetEnvironmentVariable("APPDATA",app);
  var originals=new Dictionary<string,byte[]>();int tick=0;
  foreach(string leaf in new[]{"moons","tempos"}){
   string config=Path.Combine(app,"."+leaf,"config");Directory.CreateDirectory(Path.Combine(config,"profiles"));
   File.WriteAllText(Path.Combine(config,"profiles","default.json"),"{\"format\":2,\"values\":{\"client.name\":\"JSON stays active\"}}");
   foreach(string name in new[]{"moons","tempos"}){
    string key="config/legacy/"+leaf+"-"+name+".properties";
    byte[] bytes=new byte[]{0xef,0xbb,0xbf}.Concat(Encoding.GetEncoding(28591).GetBytes("client.name="+leaf+name+"\nlatin=\u00e9\u00ff\nmodel=old\\path\n")).ToArray();
    string path=Path.Combine(config,name+".properties");File.WriteAllBytes(path,bytes);File.SetLastWriteTimeUtc(path,DateTime.UtcNow.AddMinutes(-10).AddSeconds(++tick));originals.Add(key,bytes);
   }
  }
  var candidate=LegacyConfigMigration.Capture();var bundle=PortableConfig.Bundle(candidate.Bundle);
  var extras=PortableConfig.Object(bundle["extras"],"extras");Need(extras.Count==4,"lost legacy properties");
  foreach(var pair in originals)Need(pair.Value.SequenceEqual(PortableConfig.ExtraEncoding(pair.Key).GetBytes((string)extras[pair.Key])),"capture not byte exact "+pair.Key);
  string root=Path.Combine(Path.GetTempPath(),"tempos",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);File.WriteAllText(Path.Combine(root,".tempos-session"),"fixture");
  string executable=Path.Combine(Path.GetDirectoryName(app),"properties-cache.exe");File.Copy(Assembly.GetExecutingAssembly().Location,executable,true);
  try{
   var prepared=PortableConfig.Prepare(root,executable,null);
   foreach(var pair in originals)Need(pair.Value.SequenceEqual(File.ReadAllBytes(Path.Combine(prepared.Home,pair.Key.Replace('/',Path.DirectorySeparatorChar)))),"release not byte exact "+pair.Key);
   var saved=PortableConfig.Bundle(PortableConfig.Collect(root));
   var values=PortableConfig.Object(PortableConfig.Object(PortableConfig.Object(saved["profiles"],"profiles")["default"],"profile")["values"],"values");Need((string)values["client.name"]=="JSON stays active","legacy changed active profile");
   var collected=PortableConfig.Object(saved["extras"],"extras");foreach(var pair in originals)Need(pair.Value.SequenceEqual(PortableConfig.ExtraEncoding(pair.Key).GetBytes((string)collected[pair.Key])),"collect not byte exact");
   PortableConfig.Write(executable,PortableConfig.Encode(saved));Need(PortableConfig.Read(executable)!=null,"write failed");Need(LegacyConfigMigration.Complete(prepared.Legacy.Metadata(),()=>false),"verified properties cleanup failed");
   Need(!Directory.Exists(Path.Combine(app,".moons"))&&!Directory.Exists(Path.Combine(app,".tempos")),"legacy root kept after full archive");
  }finally{PortableConfig.DeleteSession(root);}
  Console.WriteLine("LEGACY_PROPERTIES_VERIFIED four-originals byte-exact Latin1+BOM active-JSON-priority EXE-archive-before-cleanup");
 }
}
