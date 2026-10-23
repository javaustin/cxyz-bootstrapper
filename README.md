# CXYZ Bootstrapper

The `CXYZ Bootstrapper` reads `cxyz_manifest.json` from the current directory and downloads each declared artifact from the CXYZ API.

## Compile

Use JDK 11 or newer (the source uses `java.net.http.HttpClient`) and mjson 1.4.2:

```text
org.sharegov:mjson:1.4.2
```

Download `mjson-1.4.2.jar` from Maven Central and place it beside the source, or resolve it with your build tool.

```text
javac --release 11 -cp mjson-1.4.2.jar -d out src/Bootstrapper.java
jar --create --file cxyz-bootstrapper.jar --manifest MANIFEST.MF -C out .
```

## Run

Set the credentials and run from the directory in which relative manifest targets should be created:

Bash (Unix):
```bash
IDENTIFIER=your-identifier
SECRET=your-secret
java -jar cxyz-bootstrapper.jar
```

PowerShell (Windows):
```powershell
$env:IDENTIFIER = "your-identifier"
$env:SECRET = "your-secret"
java -jar cxyz-bootstrapper.jar
```

Keep `mjson-1.4.2.jar` beside `cxyz-bootstrapper.jar`; the bootstrapper jar's manifest declares that runtime classpath entry.

The manifest must be at `cxyz_manifest.json` and follow the format below. Existing targets are skipped unless `replace-existing` is `true`. Each artifact is first written to a sibling `.part-*` file and moved into place only after the download completes.

## Format of `cxyz_manifest.json`
```json
{
  "revision" : 1,
  "artifacts": [
    {
      "source" : "a_remote_directory/example-file.txt",
      "target" : "target-file.txt",
      "replace-existing" : true
    },
    {
      "source" : "the_same_remote_system/plugin.jar",
      "target" : "plugins/plugin.jar",
      "replace-existing" : false
    }
  ]
}
```
