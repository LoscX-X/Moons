package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.config.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import java.nio.file.Files;

/** Checks migration and master/packet preference behavior without a game session. */
public final class XrayMergeVerification {
    public static void main(String[] args) throws Exception {
        Settings.configure(Files.createTempDirectory("moons-xray-verify-"));
        Settings.setBoolean("xray.autoscan.enabled", false);
        Settings.setBoolean("xray.destroyPacket.enabled", true);
        check(OreScanner.isAutoScanEnabled(), "old packet-only profile enables merged Xray");
        check(XrayDestroyPacketMode.isEnabled(), "old packet preference survives");
        OreScanner.init();
        check(Settings.getBoolean("xray.enabled", false), "merged state persisted");
        check(Settings.getString("xray.autoscan.enabled", "missing").equals("missing"), "obsolete master removed");
        OreScanner.setAutoScanEnabled(null, false);
        check(!XrayDestroyPacketMode.isEnabled(), "master off stops packet probes");
        check(XrayDestroyPacketMode.isPacketScanEnabled(), "master off preserves packet preference");
        check(!Settings.getBoolean("xray.enabled", true), "off persists independently of packet preference");
        OreScanner.setAutoScanEnabled(null, true);
        check(XrayDestroyPacketMode.isEnabled(), "master on restores packet mode");
        XrayDestroyPacketMode.setEnabled(null, false);
        check(OreScanner.isAutoScanEnabled(), "turning packet off leaves local scanning on");
        check(!XrayDestroyPacketMode.isEnabled(), "packet option off");
        OreScanner.setAutoScanEnabled(null, false);
        probeHits();
        System.out.println("XRAY_MERGE_VERIFIED");
    }

    private static void probeHits() {
        Vec3 eye = new Vec3(0.5, 0.5, 0);
        Vec3 end = eye.add(0, 0, 5);
        BlockPos target = new BlockPos(0, 0, 2);
        BlockPos offRay = new BlockPos(3, 0, 2);
        BlockHitResult miss = Shapes.block().clip(eye, end, offRay);
        check(miss == null, "vanilla shape clip returns null off ray");
        check(!XrayDestroyPacketMode.isValidProbeHit(miss, offRay, null, eye, 5),
                "static scan skips off-ray blocks without throwing");
        BlockHitResult hit = Shapes.block().clip(eye, end, target);
        check(XrayDestroyPacketMode.isValidProbeHit(hit, target, null, eye, 5), "one clip selects hit face");
        check(XrayDestroyPacketMode.isValidProbeHit(hit, target, Direction.NORTH, eye, 5), "matching face accepted");
        check(!XrayDestroyPacketMode.isValidProbeHit(hit, target, Direction.SOUTH, eye, 5), "wrong face rejected");
        check(!XrayDestroyPacketMode.isValidProbeHit(hit, target, null, eye, 1), "outside reach rejected");
        check(!XrayDestroyPacketMode.isValidProbeHit(hit, offRay, null, eye, 5), "wrong position rejected");
        check(!XrayDestroyPacketMode.isValidProbeHit(
                BlockHitResult.miss(end, Direction.NORTH, target), target, null, eye, 5), "MISS result rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
