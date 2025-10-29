package org.acme;

import org.fusesource.jansi.internal.JansiLoader;
import org.fusesource.jansi.internal.OSInfo;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class JansiLoaderTest {

    private static final Logger LOGGER = Logger.getLogger(JansiLoaderTest.class.getName());

    @Test
    public void testJansiSoContent() throws IOException {
        String jansiNativeLibraryName = "libjansi.so";
        String packagePath = JansiLoader.class.getPackage().getName().replace('.', '/');
        String jansiNativeLibraryPath = String.format("/%s/native/%s", packagePath, OSInfo.getNativeLibFolderPathForCurrentOS());
        boolean hasNativeLib = hasResource(jansiNativeLibraryPath + "/" + jansiNativeLibraryName);
        if (!hasNativeLib) {
            throw new IllegalStateException("Couldn't find native library: " + jansiNativeLibraryPath + "/" + jansiNativeLibraryName);
        }

        String tempFolder = getTempDir().getAbsolutePath();
        extractAndLoadLibraryFile(jansiNativeLibraryPath, jansiNativeLibraryName, tempFolder);
    }

    private static void extractAndLoadLibraryFile(String libFolderForCurrentOS, String libraryFileName, String targetFolder) throws IOException {
        // we copy the .so file from the jar to the tmp directory, using what Jansi uses
        String nativeLibraryFilePath = libFolderForCurrentOS + "/" + libraryFileName;
        String uuid = randomUUID();
        String extractedLibFileName = String.format("jansi-%s-%s-%s", getVersion(), uuid, libraryFileName);
        String extractedLckFileName = extractedLibFileName + ".lck";
        File extractedLibFile = new File(targetFolder, extractedLibFileName);
        File extractedLckFile = new File(targetFolder, extractedLckFileName);

        try(InputStream in = JansiLoader.class.getResourceAsStream(nativeLibraryFilePath)) {
            if (!extractedLckFile.exists()) {
                (new FileOutputStream(extractedLckFile)).close();
            }

            Files.copy(in, extractedLibFile.toPath(), new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
        }

        // both the files have identical checksums at this point

        // this is the initial JansiLoader implementation, reading from the jar
        InputStream nativeIn = JansiLoader.class.getResourceAsStream(nativeLibraryFilePath);
        LOGGER.info("Comparing " + extractedLibFile + " to .so file in read from jar with JarURLConnection");

        try (InputStream extractedLibIn = new FileInputStream(extractedLibFile)) {
            List<Byte> jarEndSequence = new ArrayList<>();
            List<Byte> tmpEndSequence = new ArrayList<>();
            List<DifferentByte> differentBytes = checkContentsEquals(nativeIn, extractedLibIn, jarEndSequence, tmpEndSequence);
            if (!differentBytes.isEmpty()) {
                LOGGER.severe("Bytes from jar and byte from temporary file " + extractedLibFile + " differ: "
                        + differentBytes.stream().map(DifferentByte::toString).collect(Collectors.joining("\n- ", "\n- ", "\n"))
                        + "\n\nDiffering sequences:\n\njar: " + jarEndSequence + "\ntmp: " + tmpEndSequence + "\n\n");
            } else {
                LOGGER.info("-> Everything is fine\n\n");
            }
        }

        // Using a ZipFile to check if the issue is jar-related or zip-related, reading a Zip file directly also fails
        String jarInMavenRepo = System.getProperty("maven.repo.local", Path.of(System.getProperty("user.home"), ".m2", "repository").toString()) + "/org/fusesource/jansi/jansi/2.4.2/jansi-2.4.2.jar";
        LOGGER.info("Comparing " + extractedLibFile + " to .so file in read from jar with ZipFile: " + jarInMavenRepo);

        try (ZipFile zf = new ZipFile(jarInMavenRepo)) {
            ZipEntry e = zf.getEntry(nativeLibraryFilePath.substring(1));
            try (InputStream in = zf.getInputStream(e); InputStream extractedLibIn = new FileInputStream(extractedLibFile)) {
                List<Byte> jarEndSequence = new ArrayList<>();
                List<Byte> tmpEndSequence = new ArrayList<>();
                List<DifferentByte> differentBytes = checkContentsEquals(in, extractedLibIn, jarEndSequence, tmpEndSequence);
                if (!differentBytes.isEmpty()) {
                    LOGGER.severe("Bytes from jar and byte from temporary file " + extractedLibFile + " differ: "
                            + differentBytes.stream().map(DifferentByte::toString).collect(Collectors.joining("\n- ", "\n- ", "\n"))
                            + "\n\nDiffering sequences:\n\njar: " + jarEndSequence + "\ntmp: " + tmpEndSequence + "\n\n");
                } else {
                    LOGGER.info("-> Everything is fine\n\n");
                }
            }
        }
    }

    // this is the method used by Jansi, Jansi still supports Java 8
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

    private static List<DifferentByte> checkContentsEquals(InputStream in1, InputStream in2, List<Byte> jarEndSequence, List<Byte> tmpEndSequence) throws IOException {
        // note that the problem is not related to the buffers being reused
        // if you use new buffers for each readNBytes, you have the same issue
        byte[] buffer1 = new byte[8192];
        byte[] buffer2 = new byte[8192];
        List<DifferentByte> differentBytes = new ArrayList<>();
        int bufferStart = 0;

        do {
            int numRead1 = readNBytes(in1, buffer1);
            int numRead2 = readNBytes(in2, buffer2);
            if (numRead1 <= 0) {
                if (numRead2 > 0) {
                    throw new IllegalArgumentException("EOF on first stream but not second");
                }

                return differentBytes;
            }

            if (numRead2 <= 0) {
                throw new IllegalArgumentException("EOF on second stream but not first");
            }

            if (numRead2 != numRead1) {
                throw new IllegalArgumentException("Read size different (" + numRead1 + " vs " + numRead2 + ")");
            }

            for (int i = 0; i < buffer1.length; i++) {
               if (buffer1[i] != buffer2[i]) {
                   differentBytes.add(new DifferentByte(bufferStart + i, buffer1[i], buffer2[i]));
               }
               if (bufferStart + i > 18960 && bufferStart + i < 19000) {
                   jarEndSequence.add(buffer1[i]);
                   tmpEndSequence.add(buffer2[i]);
               }
            }

            bufferStart += 8192;
        } while(true);
    }

    public record DifferentByte(int position, byte jarResourceByte, byte tmpFileByte) {
    }

    private static File getTempDir() {
        return new File(System.getProperty("jansi.tmpdir", System.getProperty("java.io.tmpdir")));
    }

    private static String randomUUID() {
        return Long.toHexString((new Random()).nextLong());
    }

    private static boolean hasResource(String path) {
        return JansiLoader.class.getResource(path) != null;
    }

    public static String getVersion() {
        URL versionFile = JansiLoader.class.getResource("/org/fusesource/jansi/jansi.properties");
        String version = "unknown";

        try {
            if (versionFile != null) {
                Properties versionData = new Properties();
                versionData.load(versionFile.openStream());
                version = versionData.getProperty("version", version);
                version = version.trim().replaceAll("[^0-9.]", "");
            }
        } catch (IOException e) {
            System.err.println(e);
        }

        return version;
    }
}
