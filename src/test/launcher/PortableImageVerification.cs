using System;
using System.Linq;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using Tempos.WindowsLauncher;
internal static class PortableImageVerification {
 static readonly UTF8Encoding U=new UTF8Encoding(false);
 static void Need(bool b,string s){if(!b)throw new Exception(s);}
 static byte[] Profile(int x){return U.GetBytes("{\"format\":2,\"values\":{\"sprintreset.interval\":"+x+"},\"modules\":{}}");}
 static void Rejected(Action a,string label){try{a();}catch(InvalidDataException){return;}throw new Exception("Accepted "+label);}
 internal static void Run(string[] a){
  if(a[0]=="--hold"){Thread.Sleep(Int32.Parse(a[1]));return;}
  if(a[0]=="--echo"){Console.WriteLine("PE_OVERLAY_LAUNCH_PASS");return;}
  if(a[0]=="--session"){Session(a[1]);return;}
  if(a[0]=="--inspect"){
   var b=PortableConfig.Bundle(PortableConfig.Read(a[1]));
   var ps=PortableConfig.Object(b["profiles"],"profiles");
   var doc=PortableConfig.Object(ps["default"],"profile");
   var v=PortableConfig.Object(doc["values"],"values");
   Need(Convert.ToInt32(v["sprintreset.interval"])==321,"finalizer did not save the actual changed value");
   Console.WriteLine("FINALIZER_CHANGED_VALUE_VERIFIED");return;
  }
  string dir=Path.GetFullPath(a[1]);Directory.CreateDirectory(dir);
  Environment.SetEnvironmentVariable("APPDATA",Path.Combine(dir,"appdata"));
  string target=Path.Combine(dir,"portable.exe");File.Copy(Assembly.GetExecutingAssembly().Location,target,true);
  byte[] image=File.ReadAllBytes(target);Need(PortableConfig.Read(target)==null,"clean exe cache");
  PortableConfig.Write(target,Profile(40));long once=new FileInfo(target).Length;
  PortableConfig.Write(target,Profile(41));Need(new FileInfo(target).Length==once,"cache grew with a second write");
  byte[] amended=File.ReadAllBytes(target);
  for(int i=0;i<image.Length;i++)Need(image[i]==amended[i],"PE bytes changed");
  var loaded=Assembly.ReflectionOnlyLoad(amended);Need(loaded.GetName().Name==Assembly.GetExecutingAssembly().GetName().Name,"invalid CLR image");
  var info=new ProcessStartInfo(target,"--echo"){UseShellExecute=false,RedirectStandardOutput=true,CreateNoWindow=true};
  using(var p=Process.Start(info)){string stdout=p.StandardOutput.ReadToEnd();p.WaitForExit();Need(p.ExitCode==0&&stdout.Contains("PE_OVERLAY_LAUNCH_PASS"),"modified PE did not run");}
  string corrupt=Path.Combine(dir,"corrupt.exe");File.Copy(target,corrupt,true);byte[] broken=File.ReadAllBytes(corrupt);broken[image.Length+60]^=1;File.WriteAllBytes(corrupt,broken);Rejected(()=>PortableConfig.Read(corrupt),"damaged hash");
  File.WriteAllBytes(corrupt,new ArraySegment<byte>(amended,0,amended.Length-8).ToArray());Rejected(()=>PortableConfig.Read(corrupt),"truncated footer");
  Rejected(()=>PortableConfig.Bundle(U.GetBytes("{\"format\":1,\"selected\":\"default\",\"profiles\":{\"default\":{\"format\":2,\"values\":{}}},\"extras\":{\"../outside.json\":\"{}\"}}")),"path traversal");
  string raceRoot=Path.Combine(dir,"appdata",".tempos"),raceFile=Path.Combine(raceRoot,"config","profiles","default.json");Directory.CreateDirectory(Path.GetDirectoryName(raceFile));File.WriteAllBytes(raceFile,Profile(1));
  var raced=new LegacyConfigMigration.Source{Root=raceRoot};string raceRelative="config/profiles/default.json";raced.ParameterFiles.Add(raceRelative);raced.CapturedFiles.Add(raceRelative,PortableConfig.Digest(File.ReadAllBytes(raceFile)));
  File.WriteAllBytes(raceFile,Profile(2));typeof(LegacyConfigMigration).GetMethod("Snapshot",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,new object[]{raced});
  Need(!raced.SafeToDelete,"read-to-snapshot mutation was trusted");var raceMeta=new Dictionary<string,object>{{"sources",new object[]{raced.Metadata()}},{"models",null}};
  Need(!LegacyConfigMigration.Complete(raceMeta,()=>false)&&File.ReadAllBytes(raceFile).SequenceEqual(Profile(2)),"read-to-snapshot new parameters deleted");
  Console.WriteLine("MIGRATION_CAPTURE_RACE_VERIFIED read-old snapshot-new source-retained new-parameters-kept");
  Directory.Delete(raceRoot,true);
  Legacy(dir);
  Console.WriteLine("PORTABLE_CONFIG_VERIFIED PE-prefix exact repeated-write bounded CLR-execution corruption truncation JSON-paths legacy-copy-before-cleanup");
 }
 static void Legacy(string dir){
  string old=Path.Combine(dir,"appdata",".moons"), profiles=Path.Combine(old,"config","profiles");Directory.CreateDirectory(profiles);File.WriteAllBytes(Path.Combine(profiles,"default.json"),Profile(5));
  var c=LegacyConfigMigration.Capture();Need(c!=null&&c.Bundle!=null,"legacy not read");
  var metadata=c.Metadata();File.WriteAllBytes(Path.Combine(profiles,"default.json"),Profile(6));
  Need(!LegacyConfigMigration.Complete(metadata,()=>false)&&Directory.Exists(old),"changed source deleted");
  File.WriteAllBytes(Path.Combine(profiles,"default.json"),Profile(5));
  Need(!LegacyConfigMigration.Complete(metadata,()=>true)&&Directory.Exists(old),"live old game source deleted");
  Need(LegacyConfigMigration.Complete(metadata,()=>false)&&!Directory.Exists(old),"verified source not cleaned");
  Directory.CreateDirectory(profiles);File.WriteAllBytes(Path.Combine(profiles,"default.json"),Profile(7));
  string models=Path.Combine(old,"data","ysm","models");Directory.CreateDirectory(models);File.WriteAllText(Path.Combine(models,"character.ysm"),"model-user-data");
  c=LegacyConfigMigration.Capture();Need(LegacyConfigMigration.Complete(c.Metadata(),()=>false)&&!Directory.Exists(old)&&File.ReadAllText(Path.Combine(dir,"appdata","models","character.ysm"))=="model-user-data","verified model migration did not retain asset");
  Directory.CreateDirectory(profiles);File.WriteAllBytes(Path.Combine(profiles,"default.json"),Profile(8));File.WriteAllText(Path.Combine(old,"notes.txt"),"user note");
  c=LegacyConfigMigration.Capture();Need(!LegacyConfigMigration.Complete(c.Metadata(),()=>false)&&File.Exists(Path.Combine(old,"notes.txt")),"unknown user data deleted");
 }
 static void Session(string dir){
  Directory.CreateDirectory(dir);Environment.SetEnvironmentVariable("APPDATA",Path.Combine(dir,"empty-appdata"));
  string root=Path.Combine(Path.GetTempPath(),"tempos",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);File.WriteAllText(Path.Combine(root,".tempos-session"),"fixture");
  string import=Path.Combine(dir,"import.json");File.WriteAllBytes(import,Profile(40));
  string exe=Assembly.GetExecutingAssembly().Location;var prepared=PortableConfig.Prepare(root,exe,import);
  File.WriteAllBytes(prepared.ProfilePath,Profile(321));
  bool locked=false;try{PortableConfig.Write(exe,Profile(0));}catch(IOException){locked=true;}Console.WriteLine(locked?"RUNNING_EXE_REPLACE_DEFERRED":"RUNNING_EXE_ATOMIC_REPLACEMENT_VERIFIED");
  using(var game=Process.Start(new ProcessStartInfo(exe,"--hold 1500"){UseShellExecute=false,CreateNoWindow=true})){
   PortableConfig.StartFinalizer(root,exe,game.Id,game.StartTime.ToUniversalTime().Ticks);
   var m=PortableConfig.Manifest(root);File.WriteAllText(Path.Combine(dir,"session-info.json"),PortableConfig.Json().Serialize(new Dictionary<string,object>{{"root",root},{"helper",m["helperRoot"]},{"executable",exe}}),U);
   Need(Directory.Exists(root),"session lost while game live");
  }
  Console.WriteLine("SESSION_ARMED_RUNNING_IMAGE_RETAINED");
 }
}
