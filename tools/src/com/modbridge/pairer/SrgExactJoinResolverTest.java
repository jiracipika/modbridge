package com.modbridge.pairer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-logic tests for the Phase-2 SRG gap resolver (SrgExactJoinResolver): tsrg2 parsing,
 * Mojang proguard parsing, sibling-overload disambiguation by descriptor, existing-table
 * verification, and the additive-only merge contract. Fixture data is synthetic and tiny;
 * no network, no jars, no filesystem — run with SrgResolverTestRunner (scripts/test-srg-resolver.sh).
 */
public final class SrgExactJoinResolverTest {

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) {
        new SrgExactJoinResolverTest().run();
    }

    void run() {
        // ------------------------------------------------ fixtures
        // Two same-descriptor-overload hazards, one unobfuscated-named owner class, one field dup:
        // class obf "aaa" (moj: net/minecraft/Foo): obf methods a(int)->m_100_ and a(J)->m_101_ —
        // the Phase-1 jar-pairing could not tell these apart; the exact join must.
        String tsrg2 = """
                tsrg2 obf srg id
                aaa net/minecraft/src/C_1_ 1
                \ta (I)V m_100_ 100
                \t\tstatic
                \t\t0 0 o p_1_ 1
                \ta (J)V m_101_ 101
                \tb m_102_ 102
                \tc ()I m_103_ 103
                net/minecraft/server/MinecraftServer net/minecraft/server/MinecraftServer 2
                \tz ()Ljava/io/File; m_6237_ 6237
                """;
        String mojang = """
                # comment line
                net.minecraft.Foo -> aaa:
                    10:10:void bar(int) -> a
                    20:21:void baz(long) -> a
                    30:30:int getQux() -> c
                    net.minecraft.world.level.Level field -> b
                net.minecraft.server.MinecraftServer -> net.minecraft.server.MinecraftServer:
                    810:810:java.io.File getServerDirectory() -> z
                """;

        SrgExactJoinResolver.JoinData data = new SrgExactJoinResolver.JoinData();
        SrgExactJoinResolver.parseTsrg2(tsrg2, data);
        SrgExactJoinResolver.parseProguard(mojang, data);

        check(data.tsrg2.size() == 2 && data.tsrg2.containsKey("aaa"), "tsrg2: class blocks keyed by obf name");
        check(data.tsrg2.get("aaa").members.size() == 4, "tsrg2: members parsed (params/static nested lines ignored)");
        check(data.classMojToObf.get("net/minecraft/server/MinecraftServer").equals("net/minecraft/server/MinecraftServer"),
                "proguard: unobfuscated class dotted obf side normalized to slashes");
        check(data.members.get("net/minecraft/Foo").get("a").size() == 2,
                "proguard: two same-obf-name overloads kept as distinct candidates");
        check(data.members.get("net/minecraft/Foo").get("a").get(0).desc.equals("(I)V")
                        && data.members.get("net/minecraft/Foo").get("a").get(1).desc.equals("(J)V"),
                "proguard: line-number prefix stripped, descs parsed mojmap-typed");
        check(data.members.get("net/minecraft/Foo").get("b").get(0).desc.equals("Lnet/minecraft/world/level/Level;"),
                "proguard: field type converted to descriptor");
        check(data.members.get("net/minecraft/server/MinecraftServer").get("z").get(0).desc.equals("()Ljava/io/File;"),
                "proguard: method with line numbers on unobfuscated class parses exactly");

        // jar inventory: descriptors are mojmap-namespace (Forge srg jar property)
        List<SrgExactJoinResolver.MemberRef> inventory = List.of(
                new SrgExactJoinResolver.MemberRef("net/minecraft/Foo", "m_100_", true, true, "(I)V"),
                new SrgExactJoinResolver.MemberRef("net/minecraft/Foo", "m_101_", true, true, "(J)V"),
                new SrgExactJoinResolver.MemberRef("net/minecraft/Foo", "m_102_", false, false, "Lnet/minecraft/world/level/Level;"),
                new SrgExactJoinResolver.MemberRef("net/minecraft/server/MinecraftServer", "m_6237_", true, true, "()Ljava/io/File;"),
                new SrgExactJoinResolver.MemberRef("net/minecraft/Foo", "m_103_", true, false, "()I"));

        Map<String, String> existing = new LinkedHashMap<>();
        existing.put("m_103_", "getQux"); // a known pair the join must re-verify, not touch

        SrgExactJoinResolver.Result r = SrgExactJoinResolver.resolve(inventory, data, existing);

        check(r.additions.size() == 4, "resolve: all sibling-overload/field/unobf-owner members resolved");
        check("bar".equals(r.additions.get("m_100_")), "resolve: same-obf sibling m_100_ -> bar by descriptor (I)V");
        check("baz".equals(r.additions.get("m_101_")), "resolve: same-obf sibling m_101_ -> baz by descriptor (J)V");
        check("field".equals(r.additions.get("m_102_")), "resolve: field resolved via tsrg2 obf name + type");
        check("getServerDirectory".equals(r.additions.get("m_6237_")), "resolve: member on unobfuscated owner resolved");

        check(r.verified.containsKey("m_103_") && "getQux".equals(r.verified.get("m_103_")),
                "verify: existing pair re-derived identically (47,007-pair contract)");
        check(!r.additions.containsKey("m_103_"), "verify: existing pair NOT re-emitted as addition");

        check(r.unresolved.size() == 0 && r.conflicts.size() == 0, "resolve: clean fixture has no gaps or conflicts");

        // conflict path: existing table disagrees with the exact join -> recorded, existing kept
        Map<String, String> wrongExisting = new LinkedHashMap<>();
        wrongExisting.put("m_100_", "notBar");
        SrgExactJoinResolver.Result rc = SrgExactJoinResolver.resolve(inventory, data, wrongExisting);
        check(rc.conflicts.containsKey("m_100_") && !rc.additions.containsKey("m_100_"),
                "conflict: disagreement recorded as conflict, existing pair never rewritten");

        // unresolved path: owner class absent from Mojang mappings
        List<SrgExactJoinResolver.MemberRef> inv2 = List.of(
                new SrgExactJoinResolver.MemberRef("net/minecraft/Unknown", "m_999_", true, false, "()V"));
        SrgExactJoinResolver.Result ru = SrgExactJoinResolver.resolve(inv2, data, Map.of());
        check(ru.unresolved.containsKey("m_999_")
                        && ru.unresolved.get("m_999_").contains("owner class absent from Mojang mappings"),
                "unresolved: missing owner class reported with reason, never guessed");

        // merge contract: additive-only, existing bytes verbatim, additions sorted by numeric id
        List<String> table = Arrays.asList(
                "# header comment",
                "m_99_=oldName",
                "m_5_=five");
        Map<String, String> adds = new LinkedHashMap<>();
        adds.put("m_6000_", "a");
        adds.put("m_44_", "b");
        adds.put("m_99_", "ignored-duplicate"); // must be skipped, not duplicated
        List<String> merged = SrgExactJoinResolver.mergeAdditions(table, adds, "# provenance");
        check(merged.get(0).equals("# header comment") && merged.get(1).equals("m_99_=oldName")
                        && merged.get(2).equals("m_5_=five"),
                "merge: pre-existing lines byte-for-byte, in order");
        check(merged.size() == 6, "merge: header + 2 additions + original 3 lines (duplicate id skipped)");
        check("m_44_=b".equals(merged.get(4)) && "m_6000_=a".equals(merged.get(5)),
                "merge: additions appended sorted by numeric id");

        System.out.println("SrgExactJoinResolverTest: " + passed + " checks passed");
    }
}
