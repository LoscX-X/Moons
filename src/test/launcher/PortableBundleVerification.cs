using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using Tempos.WindowsLauncher;
internal static class PortableBundleVerification {
 static UTF8Encoding U=new UTF8Encoding(false); static void Yes(bool b,string m){if(!b)throw new Exception(m);}
 static Dictionary<string,object> Profile(){return new Dictionary<string,object>{{"format",2},{"values",new Dictionary<string,object>{{"name","中文参数"}}}};}
 internal static void Run(string[] a){
  var p=new Dictionary<string,object>{{"default",Profile()},{"我的参数",Profile()}};
  var x=new Dictionary<string,object>{{"data/ysm/ysm.properties","model=C\\:\\\\models\\\\character.ysm\n"},{"data/ysm/parameters/1234.json","{\"scale\":1.25}"},{"data/ysm/parameters/1234.json.pose.json","{\"bone\":2}"},{"data/ysm/parameters/1234.json.textures.json","{\"texture\":\"variant\"}"}};
  byte[] raw=PortableConfig.Encode(PortableConfig.NewBundle("我的参数",p,x));
  var normalized=PortableConfig.Bundle(raw);Yes((string)normalized["selected"]=="我的参数","selected Unicode");
  string root=Path.Combine(Path.GetTempPath(),"tempos",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);File.WriteAllText(Path.Combine(root,".tempos-session"),"fixture");
  string imported=Path.GetFullPath(a[0]);File.WriteAllBytes(imported,raw);
  var ready=PortableConfig.Prepare(root,Assembly.GetExecutingAssembly().Location,imported);
  Yes(File.ReadAllText(Path.Combine(ready.Home,"config","selected-profile.info")).Trim()=="我的参数","selected pointer");
  byte[] outRaw=PortableConfig.Collect(root);Yes(PortableConfig.Digest(PortableConfig.Encode(PortableConfig.Bundle(outRaw)))==PortableConfig.Digest(PortableConfig.Encode(normalized)),"parameters+extras roundtrip");
  PortableConfig.DeleteSession(root);
  p.Add("DEFAULT",Profile());bool reject=false;try{PortableConfig.Bundle(PortableConfig.Encode(PortableConfig.NewBundle("default",p)));}catch(InvalidDataException){reject=true;}Yes(reject,"Windows case collision accepted");
  Console.WriteLine("PARAMETER_BUNDLE_VERIFIED all-profiles Unicode selected-pointer YSM-text-extras exact-roundtrip Windows-case-collision");
 }
}
