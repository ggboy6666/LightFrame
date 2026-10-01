package com.lightframe.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SurfaceFlinger --list names, independent of Android APIs and frame statistics. */
public final class SurfaceLayers {
    private static final String PREFIX = "RequestedLayerState{";
    // AOSP appends #sequence to the NAME, then these fields to its debug wrapper.
    // Anchor the whole suffix so words such as parentId inside NAME are untouched.
    private static final Pattern REQUESTED = Pattern.compile(
            "^(.*#[0-9]+)((?: (?:parentId=[0-9]+|relativeParentId=[0-9]+"
            + "|mirrorId=\\{[0-9,]*\\}|!handle|z=-?[0-9]+|layerStack=[0-9]+))*)$");
    private static final Pattern APP_BUFFER = Pattern.compile(
            "^(?:\\[BBQ\\] )?(?:[A-Za-z_][A-Za-z0-9_]*\\.)+[A-Za-z_][A-Za-z0-9_]*/");
    private static final Pattern APP_COMPONENT = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_]*\\.)+[A-Za-z_][A-Za-z0-9_]*/[A-Za-z_.$]");
    private static final Pattern LAYER_ID = Pattern.compile("#([0-9]+)$");
    private static final Pattern PARENT_ID = Pattern.compile("(?:^| )parentId=([0-9]+)(?: |$)");
    private static final Pattern SYSTEM_WINDOW = Pattern.compile(
            "^Window:\\S+ type=[0-9]+ (?:StatusBar|FakeStatusBar|FakeGestureBar|NavigationBar|NotificationShade"
            + "|InputMethod|ScreenDecorOverlay(?:Bottom)?|SideSlideGestureBar-(?:Left|Right|Bottom))(?= |#|$)");

    private SurfaceLayers() {}

    /** Return a precise --latency argument; keep ordinary raw names unchanged. */
    public static String name(String line) {
        if (line == null || line.trim().isEmpty()) return "";
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (!line.startsWith(PREFIX) || !line.endsWith("}")) return line;
        String body = line.substring(PREFIX.length(), line.length() - 1);
        Matcher match = REQUESTED.matcher(body);
        return match.matches() ? match.group(1) : line;
    }

    /** Preserve order and exact names while removing duplicate list entries. */
    public static List<String> parse(String dump) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (dump != null) for (String line : dump.split("\\r?\\n")) {
            String value = name(line);
            if (!value.isEmpty()) result.add(value);
        }
        return new ArrayList<>(result);
    }

    /**
     * Return all matching candidates in a stable heuristic order, never a fixed
     * first-eight subset. A caller must verify fresh presentation timestamps;
     * a name or SurfaceView label alone does not prove visibility or activity.
     */
    public static List<String> rank(List<String> names, String packageFilter) {
        String filter = packageFilter == null ? "" : packageFilter.trim();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (names != null) for (String raw : names) {
            String value = name(raw);
            String lower = value.toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && (filter.isEmpty() || value.contains(filter))
                    && !lower.contains("com.lightframe.monitor")) unique.add(value);
        }
        ArrayList<String> result = new ArrayList<>(unique);
        boolean hasAppCandidates = false;
        for (String value : result) {
            if (priority(value) <= 2 || value.startsWith("Window:")
                    && APP_COMPONENT.matcher(value).find()) {
                hasAppCandidates = true;
                break;
            }
        }
        // These hierarchy containers have no app presentation buffer of their own.
        // Keep unknown raw/vendor names available when no app candidate is identifiable.
        if (hasAppCandidates) result.removeIf(SurfaceLayers::isContainer);
        Collections.sort(result, Comparator.comparingInt(SurfaceLayers::priority));
        return result;
    }

    /**
     * Automatic discovery with ownership inherited through actual parentId links.
     * Excluded recorder/system/LightFrame ancestors exclude anonymous descendants,
     * while a requested package on an ancestor admits the game's anonymous buffer.
     * Manual selection and parse() deliberately retain every exact name.
     */
    public static List<String> rankDump(String dump, String packageFilter) {
        String filter = packageFilter == null ? "" : packageFilter.trim();
        List<LayerNode> nodes = new ArrayList<>();
        Map<String, LayerNode> byId = new HashMap<>();
        if (dump != null) for (String line : dump.split("\\r?\\n")) {
            String value = name(line);
            if (value.isEmpty()) continue;
            String parent = null;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.startsWith(PREFIX) && line.endsWith("}")) {
                Matcher wrapped = REQUESTED.matcher(line.substring(PREFIX.length(), line.length() - 1));
                if (wrapped.matches()) {
                    Matcher link = PARENT_ID.matcher(wrapped.group(2));
                    if (link.find()) parent = link.group(1);
                }
            }
            Matcher id = LAYER_ID.matcher(value);
            LayerNode node = new LayerNode(value, id.find() ? id.group(1) : null, parent);
            nodes.add(node);
            if (node.id != null) byId.put(node.id, node);
        }
        List<String> candidates = new ArrayList<>();
        for (LayerNode node : nodes) {
            boolean excluded = false;
            boolean matches = filter.isEmpty();
            Set<LayerNode> visited = new HashSet<>();
            for (LayerNode ancestor = node; ancestor != null && visited.add(ancestor);
                    ancestor = ancestor.parent == null ? null : byId.get(ancestor.parent)) {
                if (excludedOwner(ancestor.name)) {
                    excluded = true;
                    break;
                }
                if (!matches && ancestor.name.contains(filter)) matches = true;
            }
            if (!excluded && matches) candidates.add(node.name);
        }
        // The package filter was already applied to ownership, not just child NAME.
        return rank(candidates, "");
    }

    private static final class LayerNode {
        final String name, id, parent;
        LayerNode(String name, String id, String parent) {
            this.name = name;
            this.id = id;
            this.parent = parent;
        }
    }

    private static boolean excludedOwner(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("com.lightframe.monitor") || lower.contains("com.android.systemui")
                || lower.contains("moe.shizuku.privileged.api")
                || lower.contains("vivoscreenrecorder") || lower.contains("com.vivo.screenrecorder")) return true;
        if (name.startsWith("Display ") && (lower.contains("screenrecord")
                || lower.contains("screen_record") || lower.contains("screen recorder"))) return true;
        if (SYSTEM_WINDOW.matcher(name).find()) return true;
        // Explicit platform overlays; window type numbers alone do not imply ownership.
        return name.startsWith("Display Overlays ") || name.startsWith("Input Overlays ")
                || name.startsWith("Accessibility Overlays ") || name.startsWith("[Gesture Monitor] ")
                || name.startsWith("StatusBar ") || name.startsWith("FakeStatusBar ")
                || name.startsWith("FakeGestureBar ")
                || name.startsWith("NavigationBar ") || name.startsWith("NotificationShade ")
                || name.startsWith("SideSlideGestureBar-");
    }

    private static boolean isContainer(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.startsWith("ActivityRecord{") || name.startsWith("WindowToken{")
                || name.startsWith("WallpaperWindowToken{") || name.startsWith("Task=")
                || name.startsWith("TaskFragment{") || name.startsWith("Leaf:")
                || name.startsWith("Display ") || name.startsWith("Surface(name=")
                || name.startsWith("AppZoomOut:") || name.startsWith("OneHanded:")
                || name.startsWith("WindowedMagnification:") || name.startsWith("HideDisplayCutout:")
                || name.startsWith("ImePlaceholder:") || name.startsWith("ImeContainer ")
                || name.startsWith("DefaultTaskDisplayArea ") || name.startsWith("Dim layer ")
                || name.startsWith("Input Consumer ") || name.startsWith("[Gesture Monitor] ")
                || lower.contains("activityrecordinputsink");
    }

    private static int priority(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("systemui") || lower.contains("launcher")
                || lower.contains("inputmethod") || lower.contains("shizuku")
                || lower.contains("statusbar") || lower.contains("navigationbar")
                || lower.contains("gesturebar") || lower.contains("notificationshade")) return 6;
        if (isContainer(name)) return 5;
        if (lower.contains("surfaceview")) return 0;
        if (APP_BUFFER.matcher(name).find()) return 1;
        if (lower.contains("blast") || name.startsWith("[BBQ] ")) return 2;
        if (name.startsWith("Window:")) return 4;
        return 3;
    }
}
