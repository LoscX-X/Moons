using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using Tempos.WindowsLauncher;
internal static class PortableRecoveryVerification {
 static byte[] P(int n){return Encoding.UTF8.GetBytes("{\"format\":2,\"values\":{\"n\":"+n+"}}");}
 static string R(){string p=Path.Combine(Path.GetTempPath(),"tempos",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(p);File.WriteAllText(Path.Combine(p,".tempos-session"),"fixture");return p;}
 static void Yes(bool ok,string m){if(!ok)throw new Exception(m);}
 internal static void Run(string[] a){
  string d=Path.GetFullPath(a[0]);Directory.CreateDirectory(d);Environment.SetEnvironmentVariable("APPDATA",Path.Combine(d,"empty-appdata"));
  string exe=Path.Combine(d,"tempos.exe");File.Copy(Assembly.GetExecutingAssembly().Location,exe,true);string json=Path.Combine(d,"import.json");File.WriteAllBytes(json,P(1));
  string old=R();var initial=PortableConfig.Prepare(old,exe,json);File.WriteAllBytes(initial.ProfilePath,P(99));
  var m=PortableConfig.Manifest(old);m["launcherPid"]=Int32.MaxValue-1;m["launcherStarted"]="1";PortableConfig.WriteManifest(old,m);
  string fresh=R();var recovered=PortableConfig.Prepare(fresh,exe,null);
  var doc=PortableConfig.Profile(PortableConfig.Json().DeserializeObject(File.ReadAllText(recovered.ProfilePath)));var values=PortableConfig.Object(doc["values"],"values");
  Yes(Convert.ToInt32(values["n"])==99,"newest failed-write parameters were not recovered");
  Yes(Directory.Exists(old),"recovery deleted its only source before EXE commit");
  PortableConfig.Write(exe,PortableConfig.Collect(fresh));PortableConfig.CompleteRecoveries(PortableConfig.Manifest(fresh));
  Yes(!Directory.Exists(old),"verified old pending source was not cleaned");PortableConfig.DeleteSession(fresh);
  Console.WriteLine("PARAMETER_RECOVERY_VERIFIED dead-owner same-exe latest-parameters source-kept-until-verified-commit cleanup");
 }
}
