# CXYZ Bootstrapper

The `CXYZ Bootstrapper` reads `cxyz_manifest.json` from the current directory and downloads each declared artifact from the CXYZ API. If the manifest is missing, it fetches it from the authenticated `/get_manifest` endpoint.

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

## Flow

1. Read `IDENTIFIER` and `SECRET` from the environment. If either is missing, stop with an error.
2. Check for `cxyz_manifest.json` in the current working directory.
   - If it exists, use it as-is. The API is not contacted for a replacement manifest.
   - If it does not exist, make an authenticated `GET /get_manifest` request with no query parameters and no body. Validate the JSON response and write it to `cxyz_manifest.json`.
3. Read the manifest's integer `revision` and compare it with `.cxyz_revision` in the same directory. A missing `.cxyz_revision` is treated as revision `0`.
4. Process each artifact in manifest order:
   - A missing target is downloaded.
   - An existing target is skipped when `replace-existing` is `false`.
   - When the manifest revision differs from `.cxyz_revision`, an existing target is downloaded again only when `replace-existing` is `true`.
   - Downloads use temporary sibling `.part-*` files and replace the target only after the download succeeds.
5. After every artifact has completed successfully, write the manifest revision to `.cxyz_revision`. If any download fails, the revision file is not updated.

To update the manifest automatically, an external startup script can remove `cxyz_manifest.json` before launching the bootstrapper. The next run will fetch a fresh manifest; otherwise, the existing local manifest remains authoritative.

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
