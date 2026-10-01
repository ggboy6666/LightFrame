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

        Path latestFixture = Paths.get(args.length < 2 ? "tests/fixtures/vivo-android16-021-layers.txt" : args[1]);
        String latestDump = new String(Files.readAllBytes(latestFixture), StandardCharsets.UTF_8);
        List<String> latestAll = SurfaceLayers.parse(latestDump);
        ok(latestAll.size() == 249, "0.2.1 diagnostic list remains complete");
        ok(latestAll.contains("#228671"), "anonymous LightFrame buffer remains manually selectable");
        List<String> latestAuto = SurfaceLayers.rankDump(latestDump, "");
        ok(!latestAuto.contains("#228671"), "real anonymous LightFrame descendant excluded automatically");
        ok(!latestAuto.stream().anyMatch(s -> s.contains("com.lightframe.monitor")), "LightFrame named owners excluded");
        ok(!latestAuto.contains("MirrorRoot#228777"), "real recording display anonymous mirror subtree excluded");
        ok(!latestAuto.stream().anyMatch(s -> s.contains("VivoScreenRecorder")), "known recording display excluded");
        ok(latestAll.contains("FakeGestureBar 09-02 21:53:24.908#52"), "real gesture overlay remains in complete manual list");
        ok(!latestAuto.contains("FakeGestureBar 09-02 21:53:24.908#52"), "real FakeGestureBar owner excluded automatically");
        ok(!latestAuto.contains("[BBQ] FakeGestureBar 09-02 21:53:24.908#52#117"), "real FakeGestureBar BBQ descendant excluded by parent link");
        String actualGame = "com.tencent.tmgp.supercell.clashofclans/com.supercell.titan.tencent.GameAppTencent 10-01 16:13:32.075#228813";
        ok(latestAuto.contains(actualGame), "actual real game buffer retained");
        ok(SurfaceLayers.rankDump(latestDump, "com.tencent.tmgp.supercell.clashofclans").contains(actualGame),
                "actual game package filter retained");

        String ownership = String.join("\n", Arrays.asList(
                "RequestedLayerState{#2 parentId=1}", // child precedes its owner in --list
                "RequestedLayerState{#3 parentId=2}",
                "RequestedLayerState{Window:game type=1 com.example.game/.Main#1 parentId=9999}",
                "RequestedLayerState{#5 parentId=4}",
                "RequestedLayerState{Window:overlay type=2038 com.lightframe.monitor#4}",
                "RequestedLayerState{#7 parentId=6}",
                "RequestedLayerState{Window:overlay type=2038 com.example.game/.Helper#6}",
                "RequestedLayerState{#9 parentId=8}",
                "RequestedLayerState{Window:presentation type=2030 com.example.game/.Presentation#8}",
                "RequestedLayerState{#11 parentId=10}",
                "RequestedLayerState{Display 6 name=\"VivoScreenRecorder\"#10 layerStack=6}",
                "RequestedLayerState{#13 parentId=12}",
                "RequestedLayerState{Window:sys type=2000 StatusBar#12}",
                "RequestedLayerState{#14 parentId=424242}",
                "RequestedLayerState{#15 parentId=16}",
                "RequestedLayerState{com.example.game/.Cycle#16 parentId=15}",
                "RequestedLayerState{#17 parentId=18}",
                "RequestedLayerState{com.lightframe.monitor#18 parentId=17}",
                "RequestedLayerState{#19 relativeParentId=4}",
                "OrdinaryRawBuffer#20"));
        List<String> syntheticAll = SurfaceLayers.parse(ownership);
        ok(syntheticAll.size() == 20 && syntheticAll.contains("#5"), "parse is never ownership-filtered");
        List<String> automatic = SurfaceLayers.rankDump(ownership, "");
        ok(automatic.contains("#2") && automatic.contains("#3"), "legal anonymous game buffers and nested descendants survive");
        ok(!automatic.contains("#5"), "anonymous LightFrame buffer excluded through owner");
        ok(automatic.contains("#7") && automatic.contains("#9"), "game overlays and Presentation types are not globally blocked");
        ok(!automatic.contains("#11") && !automatic.contains("#13"), "recorder and explicit system window descendants excluded");
        ok(automatic.contains("#14"), "unknown parent does not imply an excluded owner");
        ok(automatic.contains("#15"), "benign parent cycle terminates and remains available");
        ok(!automatic.contains("#17"), "excluded owner reached within a cycle still excludes descendants");
        ok(automatic.contains("#19"), "relative Z parent is not mistaken for ownership parent");
        ok(automatic.contains("OrdinaryRawBuffer#20"), "ordinary raw layer without metadata remains compatible");
        List<String> inheritedGame = SurfaceLayers.rankDump(ownership, "com.example.game");
        ok(inheritedGame.contains("#2") && inheritedGame.contains("#3"), "package match inherited from multiple ancestors");
        ok(inheritedGame.contains("#7") && inheritedGame.contains("#9"), "package matches legitimate overlay and Presentation owners");
        ok(inheritedGame.contains("#15"), "package inheritance terminates safely through parent cycle");
        ok(!inheritedGame.contains("#14") && !inheritedGame.contains("#5"), "unattributed and excluded buffers do not match target package");
        ok(SurfaceLayers.rankDump(null, "").isEmpty(), "null raw ownership dump");
        ok(SurfaceLayers.rankDump("raw  #1\r\nraw  #1\r\nother#2", "").equals(Arrays.asList("raw  #1", "other#2")),
                "legacy raw list preserves whitespace and deduplicates");
        System.out.println(checks + " SurfaceLayer checks passed");
    }
}
