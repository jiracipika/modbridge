package com.modbridge.rewriter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure logic for the phase-4 mixin resource remap: rewriting member-reference strings
 * (SRG ids, L-references, dotted class names) and pruning mixin configs/refmaps of
 * neutralized mixins. No I/O and no jar access, so it is unit-testable in isolation
 * (RefmapRemapperTest); Rewriter delegates to this with its live tables.
 *
 * Refmap shapes handled (Forge 1.20.1 "searge" refmaps):
 *   "mappings": { "<mixin class>": { "<dev-space reference>": "<searge-space reference>" } }
 *   "data":     { "searge": { ...same shape... } }
 * Both keys and values go through remapRefString, so the rewritten refmap stays
 * consistent with the rewritten annotation literals in the mixin classes.
 */
public final class RefmapRemapper {

    private RefmapRemapper() {}

    /** matches an L-reference inside descriptors and reference strings; group 1 = internal name */
    private static final Pattern L_REF = Pattern.compile("L([A-Za-z0-9_/$]+)(;|<)");
    /** SRG member ids: m_123_/f_123_ (modern) and func_123_/field_123_ (legacy spellings) */
    private static final Pattern SRG_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_])(m_\\d+_|f_\\d+_|func_\\d+_|field_\\d+_)(?![A-Za-z0-9_])");
    /** a fully-qualified dotted class name, e.g. @Mixin(targets = "net.minecraft....Foo$Bar") */
    private static final Pattern DOTTED_CLASS = Pattern.compile("[a-z][a-z0-9_]*(\\.[A-Za-z0-9_$]+)+");

    /**
     * Remaps one member-reference string from 1.20.1 forge space to 1.21.1 mojmap space.
     * Shapes handled (everything else is returned unchanged):
     *   dotted class FQN          net.minecraft.world.entity.animal.Bee$BeeGrowCropGoal
     *   bare L-reference          Lnet/minecraft/world/level/Level;
     *   method reference          [Lowner;]name(desc)ret        (name may be SRG or dev-space)
     *   field reference           [Lowner;]name:Ldesc;          (@At FIELD targets)
     *   any other string          only SRG tokens inside are replaced
     */
    public static String remapRefString(String s, Function<String, String> srgLookup,
                                        Function<String, String> typeRemap) {
        return remapRefString(s, srgLookup, typeRemap, null);
    }

    /**
     * Remaps one member-reference string from 1.20.1 forge space to 1.21.1 mojmap space.
     * {@code mojRename} (optional) carries the rare mojmap->mojmap member renames
     * (e.g. tryCatchFire -> checkBurnOut) that dev-space names need on top of the SRG table.
     * Shapes handled (everything else is returned unchanged):
     *   dotted class FQN          net.minecraft.world.entity.animal.Bee$BeeGrowCropGoal
     *   bare L-reference          Lnet/minecraft/world/level/Level;
     *   method reference          [Lowner;]name(desc)ret        (name may be SRG or dev-space)
     *   field reference           [Lowner;]name:Ldesc;          (@At FIELD targets)
     *   any other string          SRG tokens and exact mojmap renames inside are replaced
     */
    public static String remapRefString(String s, Function<String, String> srgLookup,
                                        Function<String, String> typeRemap,
                                        Function<String, String> mojRename) {
        if (s == null || s.isEmpty()) return s;
        if (DOTTED_CLASS.matcher(s).matches()) {
            String mapped = typeRemap.apply(s.replace('.', '/'));
            return mapped.replace('/', '.');
        }
        if (s.startsWith("L") && s.endsWith(";") && s.indexOf('(') < 0 && s.indexOf(':') < 0) {
            return remapDesc(s, typeRemap);
        }
        // SRG ids first (also the only treatment for plain names like "m_5965_")
        if (SRG_TOKEN.matcher(s).find()) {
            Matcher m = SRG_TOKEN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String mapped = srgLookup.apply(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(mapped != null ? mapped : m.group(1)));
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        int paren = s.indexOf('(');
        if (paren >= 0) {
            // method reference: [Lowner;]name(desc)ret
            String head = s.substring(0, paren);
            String desc = remapDesc(s.substring(paren), typeRemap);
            String owner = null, name = head;
            if (head.startsWith("L")) {
                int semi = head.indexOf(';');
                if (semi >= 0) {
                    owner = head.substring(0, semi + 1);
                    name = head.substring(semi + 1);
                }
            }
            if (owner != null) owner = remapDesc(owner, typeRemap);
            name = renameMember(name, mojRename);
            return (owner == null ? "" : owner) + name + desc;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && (s.startsWith("L") || s.charAt(colon + 1) == 'L' || s.charAt(colon + 1) == '[')) {
            // field reference: [Lowner;]name:Ldesc;
            String head = s.substring(0, colon);
            String desc = remapDesc(s.substring(colon + 1), typeRemap);
            String owner = null, name = head;
            if (head.startsWith("L")) {
                int semi = head.lastIndexOf(';');
                if (semi >= 0 && semi < head.length() - 1) {
                    owner = head.substring(0, semi + 1);
                    name = head.substring(semi + 1);
                }
            }
            if (owner != null) owner = remapDesc(owner, typeRemap);
            name = renameMember(name, mojRename);
            return (owner == null ? "" : owner) + name + ":" + desc;
        }
        if (s.matches("[a-z][a-z0-9_]*(/[A-Za-z0-9_$]+)+")) {
            // bare internal class name (refmap values often carry the slash form of a target)
            return typeRemap.apply(s);
        }
        // bare member name (e.g. @Inject(method = "...") in dev space)
        return renameMember(s, mojRename);
    }

    private static String renameMember(String name, Function<String, String> mojRename) {
        if (mojRename == null || name == null || name.isEmpty()) return name;
        String mapped = mojRename.apply(name);
        return mapped != null ? mapped : name;
    }

    /** rewrites every L-reference inside a descriptor/signature string */
    public static String remapDesc(String d, Function<String, String> typeRemap) {
        if (d == null || !d.contains("L")) return d;
        Matcher m = L_REF.matcher(d);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "L" + Matcher.quoteReplacement(typeRemap.apply(m.group(1)))
                    + Matcher.quoteReplacement(m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Applies fn to every key and string value in a parsed JSON tree, in place where
     * possible. Returns the (possibly rebuilt) node — callers must assign it back.
     * Keys go through fn as well: refmap keys are the reference strings the Mixin
     * processor rebuilds from the (remapped) annotation literals, so they must be
     * remapped to stay consistent. fn passes non-reference strings through unchanged,
     * which keeps refmap structural keys ("mappings", "data", namespace names) intact.
     */
    public static Object remapTreeStrings(Object node, Function<String, String> fn) {
        if (node instanceof Map<?, ?>) {
            Map<String, Object> in = castMap(node);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : in.entrySet()) {
                Object v = e.getValue();
                out.put(fn.apply(e.getKey()),
                        v instanceof String s ? fn.apply(s) : remapTreeStrings(v, fn));
            }
            return out;
        } else if (node instanceof List<?> l) {
            List<Object> list = castList(l);
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                list.set(i, o instanceof String s ? fn.apply(s) : remapTreeStrings(o, fn));
            }
            return list;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) { return (List<Object>) o; }

    /** a parsed JSON object is a mixin refmap iff it has the mappings+data shape */
    public static boolean isRefmap(Object parsed) {
        return parsed instanceof Map<?, ?> m
                && m.get("mappings") instanceof Map<?, ?>
                && m.containsKey("data");
    }

    /** a parsed JSON object is a mixin config iff it names a package and lists mixins */
    public static boolean isMixinConfig(Object parsed) {
        if (!(parsed instanceof Map<?, ?> m)) return false;
        if (!(m.get("package") instanceof String)) return false;
        return m.get("mixins") instanceof List<?> || m.get("client") instanceof List<?>
                || m.get("server") instanceof List<?>;
    }

    /** config-side mixin class lists that load on a dedicated server (common + server) */
    public static List<String> dedicatedSideArrays() {
        return List.of("mixins", "server");
    }

    /**
     * Removes neutralized mixins from a config: entries whose class equals package+'.'+entry.
     * Only the dedicated-server arrays are touched — "client" entries never load there.
     * {@code neutralizedInternal} holds slash-form internal class names (refmap-key form).
     * Returns the simple names removed.
     */
    public static List<String> stripMixins(Object config, Set<String> neutralizedInternal) {
        List<String> removed = new ArrayList<>();
        if (!(config instanceof Map<?, ?> m)) return removed;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) m;
        String pkg = (String) map.get("package");
        if (pkg == null) return removed;
        String pkgSlash = pkg.replace('.', '/') + "/";
        for (String arrayKey : dedicatedSideArrays()) {
            Object arr = map.get(arrayKey);
            if (!(arr instanceof List<?> l)) continue;
            Iterator<?> it = l.iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof String entry
                        && neutralizedInternal.contains(pkgSlash + entry.replace('.', '/'))) {
                    it.remove();
                    removed.add(entry);
                }
            }
        }
        return removed;
    }

    /** drops refmap entries (per mixin class) for neutralized mixins, in place */
    public static void stripRefmapClasses(Object refmap, Set<String> neutralizedInternal) {
        if (!(refmap instanceof Map<?, ?> m)) return;
        strip(m.get("mappings"), neutralizedInternal);
        if (m.get("data") instanceof Map<?, ?> data) {
            for (Object ns : data.values()) strip(ns, neutralizedInternal);
        }
    }

    private static void strip(Object perClass, Set<String> neutralizedInternal) {
        if (!(perClass instanceof Map<?, ?> m)) return;
        m.keySet().removeIf(k -> k instanceof String s && neutralizedInternal.contains(s));
    }
}
