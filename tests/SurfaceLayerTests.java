package com.lightframe.monitor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Regression cases from the supplied vivo PA2573 Android 16 diagnostics. */
public final class SurfaceLayerTests {
    private static int checks;
    private static void ok(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void same(String actual, String expected, String description) {
        ok(expected.equals(actual), description + " got=" + actual + " expected=" + expected);
    }
    public static void main(String[] args) throws Exception {
        same(SurfaceLayers.name("SurfaceView[com.game/.Main]#19"),
                "SurfaceView[com.game/.Main]#19", "raw SurfaceView is preserved");
        same(SurfaceLayers.name("  raw  name parentId=18  #20  "),
                "  raw  name parentId=18  #20  ", "raw whitespace and metadata-like words survive");
        same(SurfaceLayers.name("RequestedLayerState{SideSlideGestureBar-Bottom 09-02 21:53:34.209#109 parentId=97}"),
                "SideSlideGestureBar-Bottom 09-02 21:53:34.209#109", "real gesture wrapper");
        same(SurfaceLayers.name("RequestedLayerState{MultiLandscapeDivider_taskId_14761  10-01 15:37:53.729#227017 parentId=226997 relativeParentId=226997 z=29999}"),
                "MultiLandscapeDivider_taskId_14761  10-01 15:37:53.729#227017", "real repeated spaces");
        same(SurfaceLayers.name("RequestedLayerState{[BBQ] drag surface 09-29 21:14:46.028#196186#196187 parentId=196186 !handle}"),
                "[BBQ] drag surface 09-29 21:14:46.028#196186#196187", "real multiple ids and handle flag");
        same(SurfaceLayers.name("RequestedLayerState{Surface(name=WindowToken{e981503 type=2024 android.os.BinderProxy@2ecb1b2})/@0x7a0c80 - animation-leash of window_animation 09-23 14:31:12.095#147904 z=2147483644}"),
                "Surface(name=WindowToken{e981503 type=2024 android.os.BinderProxy@2ecb1b2})/@0x7a0c80 - animation-leash of window_animation 09-23 14:31:12.095#147904", "real nested braces");
        same(SurfaceLayers.name("RequestedLayerState{Display 90000 name=\"vivo_rms_screen\" 09-20 05:35:41.962#124591 layerStack=90000}"),
                "Display 90000 name=\"vivo_rms_screen\" 09-20 05:35:41.962#124591", "real display layer stack");
        same(SurfaceLayers.name("RequestedLayerState{Display 0 name=\"内置屏幕\" 09-02 21:53:24.901#47}"),
                "Display 0 name=\"内置屏幕\" 09-02 21:53:24.901#47", "real wrapper with no metadata");
        same(SurfaceLayers.name("RequestedLayerState{Name parentId=3 z=5#50 parentId=9 mirrorId={4,5,} !handle z=-2 layerStack=90000}"),
                "Name parentId=3 z=5#50", "name fields differ from metadata suffix");
        for (String raw : Arrays.asList("RequestedLayerState{ordinary layer}",
                "RequestedLayerState{Name#3 parentId=oops}",
                "RequestedLayerState{Name#3 vendorField=x}",
                "RequestedLayerState{Name#3 parentId=4", "prefix RequestedLayerState{Name#4}"))
            same(SurfaceLayers.name(raw), raw, "ambiguous or malformed wrappers remain intact");
        ok(SurfaceLayers.parse(null).isEmpty(), "null dump");
        ok(SurfaceLayers.parse("\r\n  \r\nfoo#1\r\nfoo#1\r\n").equals(Arrays.asList("foo#1")),
                "line endings, blank lines, deduplication");

        Path fixture = Paths.get(args.length == 0 ? "tests/fixtures/vivo-android16-layers.txt" : args[0]);
        String dump = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
        List<String> parsed = SurfaceLayers.parse(dump);
        ok(parsed.size() == 200, "all 200 real diagnostic layer names preserved");
        for (String value : parsed) ok(!value.startsWith("RequestedLayerState{"), "real wrapper decoded");
        ok(parsed.contains("Window:648e850 type=1 com.tencent.tmgp.supercell.clashofclans/com.supercell.titan.tencent.GameAppTencent 10-01 15:39:04.474#227294"),
                "exact target game Window name from diagnostics");
        List<String> all = SurfaceLayers.rank(parsed, "");
        ok(all.size() > 8, "discovery has no first-eight truncation");
        ok(!all.stream().anyMatch(s -> s.contains("com.lightframe.monitor")), "own overlay excluded");
        List<String> game = SurfaceLayers.rank(parsed, "com.tencent.tmgp.supercell.clashofclans");
        ok(game.size() == 1, "real game non-buffer ActivityRecord omitted when app Window exists");
        ok(game.get(0).startsWith("Window:"), "real game Window remains the latency candidate");
        ok(SurfaceLayers.rank(parsed, "com.game.not.installed").isEmpty(), "explicit target does not select another app");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 24; i++) many.add("Window:fake type=1 com.game/.Main#" + i);
        many.add("SurfaceView[com.game/.Main](BLAST)#900");
        List<String> ordered = SurfaceLayers.rank(many, "com.game");
        ok(ordered.size() == 25, "all candidates beyond eight retained");
        ok(ordered.get(0).startsWith("SurfaceView"), "late SurfaceView gets first probe priority");
        ok(ordered.get(1).endsWith("#0") && ordered.get(24).endsWith("#23"), "stable same-priority order");
        List<String> buffers = SurfaceLayers.rank(Arrays.asList(
                "Window:fake type=1 com.game/.Main#1", "Task=1#2", "com.game/.Main#3"), "");
        same(buffers.get(0), "com.game/.Main#3", "direct app buffer precedes its window and task containers");
        ok(buffers.size() == 2, "pure hierarchy container skipped when app buffers exist");
        ok(SurfaceLayers.rank(Arrays.asList("VendorUnknown#1", "Task=1#2"), "").size() == 2,
                "unknown vendor-only layouts retain fallback candidates");
        System.out.println(checks + " SurfaceLayer checks passed");
    }
}
