package com.modbridge.agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.modbridge.rewriter.Rewriter;

/**
 * Connector-style drop-in runtime. Add to user_jvm_args.txt / JVM args:
 *   -javaagent:/path/to/modbridge-agent.jar
 *
 * On boot (premain, before FML discovers mods) every jar in
 * {@code mods/source-1201/} is translated to NeoForge 1.21.1 and copied into
 * {@code mods/}. Results are cached under {@code modbridge-cache/} keyed by the
 * source jar's SHA-256, so unchanged mods cost nothing on reboot.
 */
public final class ModBridgeAgent {

    private static final String PREFIX = "[ModBridge]";

    public static void premain(String args, java.lang.instrument.Instrumentation inst) {
        safeRun();
    }

    /** manual CLI: java -cp modbridge-agent.jar com.modbridge.agent.ModBridgeAgent <sourceDir> <modsDir> */
    public static void main(String[] argv) throws Exception {
        if (argv.length != 2) {
            System.err.println("usage: ModBridgeAgent <sourceDir> <modsDir>");
            System.exit(2);
        }
        translateAll(Path.of(argv[0]), Path.of(argv[1]),
                Path.of(argv[1]).getParent() == null ? Path.of(".") : Path.of(argv[1]).getParent());
    }

    private static void safeRun() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir"));
            Path mods = cwd.resolve("mods");
            if (!Files.isDirectory(mods)) {
                return; // client launcher CWD etc. — nothing to do
            }
            Path source = mods.resolve("source-1201");
            if (!Files.isDirectory(source)) {
                System.out.println(PREFIX + " mods/source-1201 not present; nothing to translate");
                return;
            }
            translateAll(source, mods, cwd);
        } catch (Throwable t) {
            System.err.println(PREFIX + " FATAL (boot continues without translations): " + t);
            t.printStackTrace();
        }
    }

    static void translateAll(Path sourceDir, Path modsDir, Path instanceDir) throws Exception {
        Path cacheDir = instanceDir.resolve("modbridge-cache").resolve("1.21.1");
        Files.createDirectories(cacheDir);
        List<Path> jars;
        try (Stream<Path> s = Files.list(sourceDir)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        if (jars.isEmpty()) {
            System.out.println(PREFIX + " no jars in " + sourceDir.getFileName() + "/" + sourceDir.getParent().getFileName());
            return;
        }
        System.out.println(PREFIX + " translating " + jars.size() + " jar(s) from " + sourceDir + " (NeoForge 1.21.1 target)");
        for (Path jar : jars) {
            try {
                handle(jar, modsDir, cacheDir);
            } catch (Throwable t) {
                System.err.println(PREFIX + " FAILED to translate " + jar.getFileName() + " (skipped): " + t);
            }
        }
    }

    private static void handle(Path jar, Path modsDir, Path cacheDir) throws Exception {
        String name = jar.getFileName().toString();
        String hash = sha256(jar);
        Path cacheFile = cacheDir.resolve(name + "." + hash + ".jar");

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("coremods.json") != null || zip.getEntry("META-INF/coremods.json") != null) {
                System.out.println(PREFIX + " " + name
                        + " contains coremods (JS bytecode patches) — not supported yet, skipping");
                return;
            }
        }
        String probe = probeJar(jar);
        if (probe.equals("neoforge")) {
            System.out.println(PREFIX + " " + name + " is already a NeoForge 1.21+ mod; copying verbatim");
            if (!Files.exists(cacheFile)) Files.copy(jar, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } else if (probe.equals("forge")) {
            if (!Files.exists(cacheFile)) {
                long start = System.currentTimeMillis();
                System.out.println(PREFIX + " translating " + name + " (sha " + hash.substring(0, 8) + ")...");
                Rewriter.translate(jar, cacheFile);
                System.out.println(PREFIX + " " + name + " translated in " + (System.currentTimeMillis() - start) + " ms");
            } else {
                System.out.println(PREFIX + " " + name + " unchanged (sha " + hash.substring(0, 8) + "), using cache");
            }
        } else {
            System.out.println(PREFIX + " " + name + " has no mod metadata; skipping");
            return;
        }

        Path target = modsDir.resolve(name);
        Files.copy(cacheFile, target, StandardCopyOption.REPLACE_EXISTING);
        System.out.println(PREFIX + " installed -> mods/" + name);
    }

    /** returns "neoforge", "forge", or "none" based on the metadata inside the jar */
    private static String probeJar(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("META-INF/neoforge.mods.toml") != null) return "neoforge";
            if (zip.getEntry("META-INF/mods.toml") != null) return "forge";
            return "none";
        }
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest()).toUpperCase(Locale.ROOT);
    }
}
