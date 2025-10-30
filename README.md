# semeru-jansi-issue

On my laptop, running Maven 3.9.11, I don't get any colors in the console output.
I figured out that the problem was due to Jansi failing to load due to a mismatch when comparing the bytes read from the .so in the jar file with the bytes obtained from the .so file copied to the `/tmp` directory.

Both files are identical (verified with `md5sum`) but the way JansiLoader reads them, we end up with different content specifically with Semeru (Temurin doesn't have the issue).

The issue was reproduced with Semeru 21.0.8 and 25.

## Environment

```
$ uname -a

Linux gsmet.redhat.lyon 6.16.9-200.fc42.x86_64 #1 SMP PREEMPT_DYNAMIC Thu Sep 25 18:05:50 UTC 2025 x86_64 GNU/Linux
```

```
$ cat /etc/fedora-release

Fedora release 42 (Adams)
```

Semeru 21 installed via SDKMAN

```
$ java -version

openjdk version "21.0.8" 2025-07-15 LTS
IBM Semeru Runtime Open Edition 21.0.8.0 (build 21.0.8+9-LTS)
Eclipse OpenJ9 VM 21.0.8.0 (build openj9-0.53.0, JRE 21 Linux amd64-64-Bit Compressed References 20250715_547 (JIT enabled, AOT enabled)
OpenJ9   - 017819f167
OMR      - 266a8c6f5
JCL      - d5f1e70d135 based on jdk-21.0.8+9)
```

Semeru 25 installed via SDKMAN

```
$ java -version

openjdk version "25" 2025-09-16 LTS
IBM Semeru Runtime Open Edition 25.0.0.0 (build 25+36-LTS)
Eclipse OpenJ9 VM 25.0.0.0 (build 25+36-openj9-0.55.0, JRE 25 Linux amd64-64-Bit Compressed References 20250916_60 (JIT enabled, AOT enabled)
OpenJ9   - 6fb31293be
OMR      - b62e20077
JCL      - 56cc1351c53 based on jdk-25+36)
```

## Reproducer

The reproducer is in `JansiLoaderTest`.
It is a simplified version of what is done in `JansiLoader` from Jansi.

Make sure you use Semeru as your runtime:

```
$ sdk current java
... // just to make sure you know which one you use at the moment so you can get back to it
$ sdk install java 21.0.8-sem
$ java -version
openjdk version "21.0.8" 2025-07-15 LTS
IBM Semeru Runtime Open Edition 21.0.8.0 (build 21.0.8+9-LTS)
Eclipse OpenJ9 VM 21.0.8.0 (build openj9-0.53.0, JRE 21 Linux amd64-64-Bit Compressed References 20250715_547 (JIT enabled, AOT enabled)
OpenJ9   - 017819f167
OMR      - 266a8c6f5
JCL      - d5f1e70d135 based on jdk-21.0.8+9)
```

Running `mvn clean test` is enough to reproduce the problem on my laptop.

> [!NOTE]
> Make sure you go back to your regular JDK after this experiment.

The issue is not transient, I can reproduce it always.

Note that the test first reads from the jar with the approach used in `JansiLoader` (`JarURLConnection`) but also tries the same comparison with reading the content from the jar file using a `ZipFile`.
The problem seems related to reading Zip content.

The problem seems related to the way the Zip `InputStream` is read.
Jansi uses the following construct to compare the content of both ``InputStream``s:

```java
    private static int readNBytes(InputStream in, byte[] b) throws IOException {
        int n = 0;

        int count;
        for(int len = b.length; n < len; n += count) {
            count = in.read(b, n, len - n);
            if (count <= 0) {
                break;
            }
        }

        return n;
    }

    private static String contentsEquals(InputStream in1, InputStream in2) throws IOException {
        byte[] buffer1 = new byte[8192];
        byte[] buffer2 = new byte[8192];

        do {
            int numRead1 = readNBytes(in1, buffer1);
            int numRead2 = readNBytes(in2, buffer2);
            if (numRead1 <= 0) {
                if (numRead2 > 0) {
                    return "EOF on first stream but not second";
                }

                return null;
            }

            if (numRead2 <= 0) {
                return "EOF on second stream but not first";
            }

            if (numRead2 != numRead1) {
                return "Read size different (" + numRead1 + " vs " + numRead2 + ")";
            }
        } while(Arrays.equals(buffer1, buffer2));

        return "Content differs";
    }
```

Switching to using `InputStream#readAllBytes()` solves the problem.

### Output on Temurin

```
$ mvn clean test

[...]

[INFO] Running org.acme.JansiLoaderTest
Oct 29, 2025 12:13:46 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-fc77786ff8487984-libjansi.so to .so file in read from jar with JarURLConnection
Oct 29, 2025 12:13:46 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: -> Everything is fine


Oct 29, 2025 12:13:46 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-fc77786ff8487984-libjansi.so to .so file in read from jar with ZipFile: /home/gsmet/.m2/repository/org/fusesource/jansi/jansi/2.4.2/jansi-2.4.2.jar
Oct 29, 2025 12:13:46 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: -> Everything is fine
```

### Output on Semeru 21

```
$ mvn clean test

[...]

[INFO] Running org.acme.JansiLoaderTest
Oct 29, 2025 12:15:12 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-12d10fcfed703090-libjansi.so to .so file in read from jar with JarURLConnection
Oct 29, 2025 12:15:12 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
SEVERE: Bytes from jar and byte from temporary file /tmp/jansi-2.4.2-12d10fcfed703090-libjansi.so differ: 
- DifferentByte[position=18976, jarResourceByte=17, tmpFileByte=0]
- DifferentByte[position=18980, jarResourceByte=3, tmpFileByte=0]


Differing sequences:

jar: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
tmp: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]


Oct 29, 2025 12:15:12 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-12d10fcfed703090-libjansi.so to .so file in read from jar with ZipFile: /home/gsmet/.m2/repository/org/fusesource/jansi/jansi/2.4.2/jansi-2.4.2.jar
Oct 29, 2025 12:15:12 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
SEVERE: Bytes from jar and byte from temporary file /tmp/jansi-2.4.2-12d10fcfed703090-libjansi.so differ: 
- DifferentByte[position=18976, jarResourceByte=17, tmpFileByte=0]
- DifferentByte[position=18980, jarResourceByte=3, tmpFileByte=0]


Differing sequences:

jar: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
tmp: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
```

### Output on Semeru 25

```
$ mvn clean test

[...]

[INFO] Running org.acme.JansiLoaderTest
Oct 29, 2025 12:15:57 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-c8ea4ece2f940e4c-libjansi.so to .so file in read from jar with JarURLConnection
Oct 29, 2025 12:15:57 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
SEVERE: Bytes from jar and byte from temporary file /tmp/jansi-2.4.2-c8ea4ece2f940e4c-libjansi.so differ: 
- DifferentByte[position=18976, jarResourceByte=17, tmpFileByte=0]
- DifferentByte[position=18980, jarResourceByte=3, tmpFileByte=0]


Differing sequences:

jar: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
tmp: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]


Oct 29, 2025 12:15:57 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
INFO: Comparing /tmp/jansi-2.4.2-c8ea4ece2f940e4c-libjansi.so to .so file in read from jar with ZipFile: /home/gsmet/.m2/repository/org/fusesource/jansi/jansi/2.4.2/jansi-2.4.2.jar
Oct 29, 2025 12:15:57 PM org.acme.JansiLoaderTest extractAndLoadLibraryFile
SEVERE: Bytes from jar and byte from temporary file /tmp/jansi-2.4.2-c8ea4ece2f940e4c-libjansi.so differ: 
- DifferentByte[position=18976, jarResourceByte=17, tmpFileByte=0]
- DifferentByte[position=18980, jarResourceByte=3, tmpFileByte=0]


Differing sequences:

jar: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
tmp: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
```
