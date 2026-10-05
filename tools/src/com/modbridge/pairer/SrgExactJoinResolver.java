package com.modbridge.pairer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the same-descriptor sibling overloads that {@link SrgMemberPairer} had to leave
 * ambiguous (the "12,345" gap from Phase 1) by replacing the (class, static, desc) jar-pairing
 * heuristic with the exact identity chain used by Linkie's "Mojang (via SRG)" namespace:
 *
 *   srgId --(MCPConfig joined.tsrg2: obf->srg)--> obfName --(Mojang official mappings)--> mojName
 *
 * Both sides are keyed through the Mojang obfuscation namespace, and same-obf-name overloads
 * are disambiguated by descriptor. Crucially, the Forge "-srg" jar we inventory uses mojmap
 * CLASS names (only members are srg-named), so its descriptors and Mojang's official mapping
 * descriptors live in the same namespace and can be compared verbatim.
 *
 * Inputs:
 *   <srg.jar>          Forge 1.20.1 srg-named server jar (inventory of srg members + desc)
 *   <joined.tsrg2>     MCPConfig 1.20.1 config/joined.tsrg (obf -> srg, with obf descs)
 *   <mojang.txt>...    one or more Mojang official proguard mappings (client.txt/server.txt)
 *   <existing.txt>     rules/srgmembers.txt (srgId=mojName) — never modified, only extended
 * Outputs (written to <outDir>):
 *   additions.txt      srgId=mojName lines to APPEND to srgmembers.txt (additive only)
 *   conflicts.txt      join disagrees with an existing pair or is internally contradictory
 *   unresolved.txt     srgId|owner|static|desc|reason rows that could not be resolved exactly
 *
 * usage: SrgExactJoinResolver <srg.jar> <joined.tsrg2> <mojang.txt>[,<mojang2.txt>...] <existing.txt> <outDir>
 */
public class SrgExactJoinResolver {

    static final Pattern SRG = Pattern.compile("^([mf])_(\\d+)_+$");
    static final Pattern TSRG2_HEADER = Pattern.compile("^tsrg2\\s");
    static final Pattern PROGUARD_LINENUM = Pattern.compile("^\\d+:\\d+:");

    // ---------------------------------------------------------------- pure data model

    /** A member as observed in the srg jar (descriptors are mojmap-namespace). */
    public static final class MemberRef {
        public final String owner;      // internal name, e.g. net/minecraft/world/entity/LivingEntity
        public final String srgId;
        public final boolean isMethod;
        public final boolean isStatic;
        public final String desc;       // method descriptor or field descriptor (mojmap classes)

        public MemberRef(String owner, String srgId, boolean isMethod, boolean isStatic, String desc) {
            this.owner = owner; this.srgId = srgId; this.isMethod = isMethod; this.isStatic = isStatic; this.desc = desc;
        }
    }

    /** One tsrg2 member line: (obfName[, obfDesc]) -> srgName inside a class. */
    public static final class TsrgMember {
        public final String obfName;
        public final String obfDesc; // null for fields
        public final String srgName;
        public TsrgMember(String obfName, String obfDesc, String srgName) {
            this.obfName = obfName; this.obfDesc = obfDesc; this.srgName = srgName;
        }
    }

    /** tsrg2 class block. */
    public static final class TsrgClass {
        public final String obfName;
        public final List<TsrgMember> members = new ArrayList<>();
        public TsrgClass(String obfName) { this.obfName = obfName; }
    }

    /** A proguard-mapped member candidate: mojName under (obfName, mojDesc). */
    public static final class PgCandidate {
        public final String mojName;
        public final String desc;   // method descriptor; for fields the field TYPE descriptor
        public PgCandidate(String mojName, String desc) { this.mojName = mojName; this.desc = desc; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PgCandidate)) return false;
            PgCandidate c = (PgCandidate) o;
            return mojName.equals(c.mojName) && desc.equals(c.desc);
        }
        @Override public int hashCode() { return mojName.hashCode() * 31 + desc.hashCode(); }
    }

    public static final class JoinData {
        /** obfClass -> tsrg2 block */
        public final Map<String, TsrgClass> tsrg2 = new HashMap<>();
        /** obfClass -> mojClass (from all provided Mojang mapping files, merged) */
        public final Map<String, String> classObfToMoj = new HashMap<>();
        /** mojClass -> obfClass */
        public final Map<String, String> classMojToObf = new HashMap<>();
        /** mojClass -> obfMemberName -> candidates */
        public final Map<String, Map<String, List<PgCandidate>>> members = new HashMap<>();
    }

    public static final class Result {
        /** srgId -> mojName, exactly derived, ready to append */
        public final Map<String, String> additions = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
        /** srgId -> human reason: anything not exactly resolvable */
        public final Map<String, String> unresolved = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
        /** srgId -> explanation: join contradicts existing table or itself */
        public final Map<String, String> conflicts = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
        /** existing pairs re-derived by the join: srgId -> "old=mojName new=mojName" for audit */
        public final Map<String, String> verified = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
    }

    // ---------------------------------------------------------------- parsers (pure)

    /** Parses MCPConfig tsrg2 text ("tsrg2 obf srg id" header, tab-indented member blocks). */
    public static void parseTsrg2(String text, JoinData out) {
        TsrgClass current = null;
        int ln = 0;
        for (String raw : text.split("\n", -1)) {
            ln++;
            String line = stripBom(raw);
            if (line.isEmpty() || line.startsWith("#") || TSRG2_HEADER.matcher(line).find()) continue;
            if (!line.startsWith("\t")) { // class line: <obf> <srg> <id>
                String[] t = line.trim().split(" ");
                if (t.length < 2) throw new IllegalArgumentException("tsrg2 line " + ln + ": bad class line: " + line);
                current = new TsrgClass(t[0]);
                out.tsrg2.put(t[0], current);
                continue;
            }
            String body = line.substring(1); // one tab off
            if (body.startsWith("\t")) continue; // nested (params/static markers) — not needed for member identity
            String[] t = body.trim().split(" ");
            if (current == null) throw new IllegalArgumentException("tsrg2 line " + ln + ": member before class: " + line);
            if (t.length == 3) { // field: obf srg id
                current.members.add(new TsrgMember(t[0], null, t[1]));
            } else if (t.length == 4) { // method: obf desc srg id
                current.members.add(new TsrgMember(t[0], t[1], t[2]));
            } else {
                throw new IllegalArgumentException("tsrg2 line " + ln + ": unexpected token count " + t.length + ": " + line);
            }
        }
    }

    /** Parses one Mojang official proguard file; merges class+member maps into out. */
    public static void parseProguard(String text, JoinData out) {
        String mojClass = null;
        int ln = 0;
        for (String raw : text.split("\n", -1)) {
            ln++;
            String line = stripBom(raw);
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                // class line: moj.name.With.Dots -> obf:
                int arrow = line.indexOf("->");
                if (arrow < 0 || !line.trim().endsWith(":")) {
                    throw new IllegalArgumentException("proguard line " + ln + ": bad class line: " + line);
                }
                String moj = line.substring(0, arrow).trim().replace('.', '/');
                String obf = line.substring(arrow + 2).trim();
                if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
                obf = obf.replace('.', '/'); // unobfuscated classes appear dotted on the obf side (tsrg2 keys use slashes)
                out.classObfToMoj.putIfAbsent(obf, moj);
                String prev = out.classMojToObf.putIfAbsent(moj, obf);
                if (prev != null && !prev.equals(obf)) {
                    throw new IllegalStateException("two obf names for moj class " + moj + ": " + prev + " and " + obf);
                }
                mojClass = moj;
                continue;
            }
            String body = line.trim();
            int arrow = body.indexOf("->");
            if (arrow < 0 || mojClass == null) {
                throw new IllegalArgumentException("proguard line " + ln + ": member outside class: " + line);
            }
            String left = body.substring(0, arrow).trim();
            Matcher lineNums = PROGUARD_LINENUM.matcher(left);
            if (lineNums.find()) left = left.substring(lineNums.end()).trim(); // "810:810:java.io.File foo" -> "java.io.File foo"
            String obfName = body.substring(arrow + 2).trim();
            if (obfName.endsWith(":")) obfName = obfName.substring(0, obfName.length() - 1);
            String mojName;
            String desc;
            int paren = left.indexOf('(');
            if (paren >= 0) { // method: [start:end:]retType name(args)
                int close = left.lastIndexOf(')');
                if (close < 0) throw new IllegalArgumentException("proguard line " + ln + ": bad method: " + line);
                String head = left.substring(0, paren).trim();
                String args = left.substring(paren + 1, close).trim();
                String ret;
                String name;
                int sp = head.lastIndexOf(' ');
                if (sp >= 0) { ret = head.substring(0, sp).trim(); name = head.substring(sp + 1).trim(); }
                else { ret = head; name = ""; } // can't happen for valid input; defensive
                String[] argTokens = args.isEmpty() ? new String[0] : args.split(",");
                String argsDesc = descOfArgs(argTokens, 0, argTokens.length);
                desc = "(" + argsDesc + ")" + typeDesc(ret);
                mojName = name;
            } else { // field: [start:end:]type name
                int sp = left.lastIndexOf(' ');
                if (sp < 0) throw new IllegalArgumentException("proguard line " + ln + ": bad field: " + line);
                String type = left.substring(0, sp).trim();
                mojName = left.substring(sp + 1).trim();
                desc = typeDesc(type);
            }
            if (mojName.startsWith("access$") || mojName.equals("<init>") || mojName.equals("<clinit>")) {
                continue; // synthetic accessors / ctors are not srg-mapped members
            }
            out.members.computeIfAbsent(mojClass, k -> new HashMap<>())
                    .computeIfAbsent(obfName, k -> new ArrayList<>());
            List<PgCandidate> cands = out.members.get(mojClass).get(obfName);
            PgCandidate c = new PgCandidate(mojName, desc);
            if (!cands.contains(c)) cands.add(c);
        }
    }

    /** Converts a proguard type expression (java.lang.String[], int, net.minecraft.Foo[]) to a descriptor type. */
    static String typeDesc(String type) {
        int dims = 0;
        while (type.endsWith("[]")) { dims++; type = type.substring(0, type.length() - 2).trim(); }
        String base;
        switch (type) {
            case "int": base = "I"; break;
            case "long": base = "J"; break;
            case "boolean": base = "Z"; break;
            case "byte": base = "B"; break;
            case "short": base = "S"; break;
            case "char": base = "C"; break;
            case "float": base = "F"; break;
            case "double": base = "D"; break;
            case "void": base = "V"; break;
            default: base = "L" + type.replace('.', '/') + ";";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dims; i++) sb.append('[');
        return sb.append(base).toString();
    }

    static String descOfArgs(String[] args, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(typeDesc(args[i].trim()));
        return sb.toString();
    }

    // ---------------------------------------------------------------- join (pure)

    /**
     * Resolves every srg id found in the jar inventory that is missing from the existing table.
     * Also re-derives existing pairs for cross-validation (verified/conflicts) without modifying them.
     */
    public static Result resolve(List<MemberRef> inventory, JoinData data, Map<String, String> existing) {
        Result r = new Result();
        for (MemberRef m : inventory) {
            String known = existing.get(m.srgId);
            String derived = derive(m, data);
            if (known != null) {
                if (derived == null) {
                    r.verified.put(m.srgId, "existing pair kept; join could not re-derive (missing upstream data)");
                } else if (known.equals(derived)) {
                    r.verified.put(m.srgId, known);
                } else {
                    r.conflicts.put(m.srgId, "existing table says '" + known + "' but exact join derives '" + derived
                            + "' — existing pair kept untouched (never rewrite)");
                }
            } else if (derived != null) {
                r.additions.put(m.srgId, derived);
            } else {
                String reason = whyUnresolved(m, data);
                r.unresolved.put(m.srgId, m.owner + "|" + (m.isStatic ? "s" : "i") + "|" + m.desc + "|" + reason);
            }
        }
        return r;
    }

    /** Exact derivation or null. Never guesses: every accepted answer has descriptor-level proof. */
    static String derive(MemberRef m, JoinData data) {
        String obfClass = data.classMojToObf.get(m.owner);
        if (obfClass == null) return null;
        TsrgClass tc = data.tsrg2.get(obfClass);
        if (tc == null) return null;
        // find the tsrg2 member(s) carrying this srg name
        List<TsrgMember> ts = new ArrayList<>();
        for (TsrgMember t : tc.members) if (t.srgName.equals(m.srgId)) ts.add(t);
        if (ts.isEmpty()) return null;
        Map<String, List<PgCandidate>> cls = data.members.get(m.owner);
        if (cls == null) return null;
        // accept only when exactly one distinct mojmap name is descriptor-compatible
        Set<String> names = new LinkedHashSet<>();
        for (TsrgMember t : ts) {
            for (PgCandidate c : cls.getOrDefault(t.obfName, List.of())) {
                if (c.desc.equals(m.desc)) names.add(c.mojName);
            }
        }
        if (names.size() != 1) return null;
        return names.iterator().next();
    }

    static String whyUnresolved(MemberRef m, JoinData data) {
        String obfClass = data.classMojToObf.get(m.owner);
        if (obfClass == null) return "owner class absent from Mojang mappings";
        TsrgClass tc = data.tsrg2.get(obfClass);
        if (tc == null) return "owner class absent from joined.tsrg2";
        List<TsrgMember> ts = new ArrayList<>();
        for (TsrgMember t : tc.members) if (t.srgName.equals(m.srgId)) ts.add(t);
        if (ts.isEmpty()) return "srg id absent from joined.tsrg2 (Forge-side member)";
        if (ts.size() > 1) return "srg id maps to multiple tsrg2 members";
        Map<String, List<PgCandidate>> cls = data.members.get(m.owner);
        if (cls == null) return "owner class has no members in Mojang mappings";
        List<PgCandidate> cands = cls.get(ts.get(0).obfName);
        if (cands == null || cands.isEmpty()) return "obf member absent from Mojang mappings (server-only or Forge-added)";
        int matches = 0;
        for (PgCandidate c : cands) if (c.desc.equals(m.desc)) matches++;
        if (matches == 0) return "no Mojang candidate matches descriptor " + m.desc;
        return "descriptor matched multiple Mojang candidates";
    }

    // ---------------------------------------------------------------- jar inventory

    /** Collects all srg-named methods/fields declared in the jar, with mojmap-namespace descriptors. */
    public static List<MemberRef> inventorySrgJar(String jarPath) throws IOException {
        List<MemberRef> out = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                try (InputStream is = jar.getInputStream(je)) {
                    new ClassReader(is).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                }
                for (MethodNode m : cn.methods) {
                    if (SRG.matcher(m.name).matches()) {
                        out.add(new MemberRef(cn.name, m.name, true,
                                (m.access & Opcodes.ACC_STATIC) != 0, m.desc));
                    }
                }
                for (FieldNode f : cn.fields) {
                    if (SRG.matcher(f.name).matches()) {
                        out.add(new MemberRef(cn.name, f.name, false,
                                (f.access & Opcodes.ACC_STATIC) != 0, f.desc));
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && "--merge-additions".equals(args[0])) {
            // --merge-additions <additions.txt> <provenance-comment> <tableIn> <tableOut>
            List<String> merged = mergeAdditions(Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8),
                    readTable(Path.of(args[1])), args[2]);
            Files.write(Path.of(args[4]), merged, StandardCharsets.UTF_8);
            System.out.printf("merged table written: %d lines (%d pre-existing + %d additions)%n",
                    merged.size(), Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8).size(),
                    readTable(Path.of(args[1])).size());
            return;
        }
        if (args.length != 5) {
            System.err.println("usage: SrgExactJoinResolver [--merge-additions <additions.txt> <comment> <tableIn> <tableOut>]"
                    + " | <srg.jar> <joined.tsrg2> <mojang.txt>[,<mojang2.txt>...] <existing.txt> <outDir>");
            System.exit(2);
        }
        String srgJar = args[0];
        Path tsrg2 = Path.of(args[1]);
        Path existing = Path.of(args[3]);
        Path outDir = Path.of(args[4]);

        List<MemberRef> inventory = inventorySrgJar(srgJar);
        System.out.printf("inventory: %d srg members in %s%n", inventory.size(), srgJar);

        JoinData data = new JoinData();
        parseTsrg2(Files.readString(tsrg2, StandardCharsets.UTF_8), data);
        System.out.printf("tsrg2: %d classes%n", data.tsrg2.size());
        for (String pg : args[2].split(",")) {
            int beforeClasses = data.classObfToMoj.size();
            int beforeMembers = data.members.size();
            parseProguard(Files.readString(Path.of(pg.trim()), StandardCharsets.UTF_8), data);
            System.out.printf("mojang %s: +%d classes, +%d classes-with-members%n",
                    pg, data.classObfToMoj.size() - beforeClasses, data.members.size() - beforeMembers);
        }

        Map<String, String> table = readTable(existing);
        System.out.printf("existing table: %d pairs%n", table.size());

        Result r = resolve(inventory, data, table);

        Files.createDirectories(outDir);
        try (PrintStream a = newPrint(outDir.resolve("additions.txt"))) {
            for (Map.Entry<String, String> e : r.additions.entrySet()) a.println(e.getKey() + "=" + e.getValue());
        }
        try (PrintStream u = newPrint(outDir.resolve("unresolved.txt"))) {
            for (Map.Entry<String, String> e : r.unresolved.entrySet()) u.println(e.getKey() + "|" + e.getValue());
        }
        try (PrintStream m = newPrint(outDir.resolve("missing.txt"))) {
            // every id the old table lacked, with provenance: id|owner|static|desc|outcome
            for (MemberRef mem : inventory) {
                if (table.containsKey(mem.srgId)) continue;
                String outcome = r.additions.containsKey(mem.srgId) ? "resolved=" + r.additions.get(mem.srgId)
                        : (r.unresolved.containsKey(mem.srgId) ? "unresolved" : "conflict");
                m.println(mem.srgId + "|" + mem.owner + "|" + (mem.isStatic ? "s" : "i") + "|" + mem.desc + "|" + outcome);
            }
        }
        try (PrintStream c = newPrint(outDir.resolve("conflicts.txt"))) {
            for (Map.Entry<String, String> e : r.conflicts.entrySet()) c.println(e.getKey() + " " + e.getValue());
        }

        long methods = 0, fields = 0;
        for (String id : r.additions.keySet()) if (id.startsWith("m_")) methods++; else fields++;
        System.out.printf("result: %d resolved additions (%d methods, %d fields), %d still unresolved, %d conflicts, %d existing verified%n",
                r.additions.size(), methods, fields, r.unresolved.size(), r.conflicts.size(), r.verified.size());
        if (!r.conflicts.isEmpty()) {
            System.out.println("CONFLICTS (existing table kept):");
            r.conflicts.values().stream().limit(20).forEach(v -> System.out.println("  " + v));
        }
    }

    static PrintStream newPrint(Path p) throws IOException {
        return new PrintStream(Files.newOutputStream(p), true, "UTF-8");
    }

    /**
     * Pure additive merge: existing table lines are returned VERBATIM, followed by a provenance
     * comment and the new pairs sorted by numeric id. Ids already present anywhere in the table
     * are skipped (first definition wins; the resolver never emits those anyway). This is the
     * only sanctioned way to extend srgmembers.txt — never rewrite, never reorder existing lines.
     */
    public static List<String> mergeAdditions(List<String> existingLines, Map<String, String> additions,
                                              String provenanceComment) {
        List<String> out = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (String line : existingLines) {
            out.add(line); // verbatim, byte-for-byte
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) present.add(line.substring(0, eq).trim());
        }
        if (!additions.isEmpty()) {
            out.add(provenanceComment);
            List<Map.Entry<String, String>> sorted = new ArrayList<>(additions.entrySet());
            sorted.sort((a, b) -> Long.compare(numId(a.getKey()), numId(b.getKey())));
            for (Map.Entry<String, String> e : sorted) {
                if (present.contains(e.getKey())) continue; // never duplicate an id
                out.add(e.getKey() + "=" + e.getValue());
                present.add(e.getKey());
            }
        }
        return out;
    }

    static Map<String, String> readTable(Path p) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    static long numId(String srg) {
        Matcher m = SRG.matcher(srg);
        return m.matches() ? Long.parseLong(m.group(2)) : -1;
    }

    static String stripBom(String s) {
        return s.isEmpty() ? s : (s.charAt(0) == '\uFEFF' ? s.substring(1) : s);
    }
}
