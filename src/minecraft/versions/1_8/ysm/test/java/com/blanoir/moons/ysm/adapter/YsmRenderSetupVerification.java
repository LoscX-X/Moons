package com.blanoir.moons.ysm.adapter;

/** Exercises actual private-member resolution and mesh math against the named 1.8 jar. */
public final class YsmRenderSetupVerification {
    public static void main(String[] args) throws Exception {
        YsmVerticesVerification.verify();
        Class.forName(YsmSubEntities.class.getName(), true, YsmSubEntities.class.getClassLoader());
        System.out.println(
                "YSM_RENDER_SETUP_VERIFIED minecraft=1.8.9 projectile-fields=resolved renderer=fixed-pipeline");
    }
}
