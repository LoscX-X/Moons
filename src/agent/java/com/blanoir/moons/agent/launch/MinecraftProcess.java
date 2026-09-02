package com.blanoir.moons.agent.launch;

import com.sun.tools.attach.VirtualMachineDescriptor;

record MinecraftProcess(String pid, String displayName, boolean likelyMinecraft) {
    static MinecraftProcess from(VirtualMachineDescriptor descriptor) {
        String displayName = descriptor.displayName() == null ? "" : descriptor.displayName();
        String normalized = displayName.toLowerCase(java.util.Locale.ROOT);
        boolean likely = normalized.contains("minecraft")
                || normalized.contains("knotclient")
                || normalized.contains("launchwrapper")
                || normalized.contains("bootstraplauncher")
                || normalized.contains("lunar")
                || normalized.contains("feather");
        return new MinecraftProcess(descriptor.id(), displayName, likely);
    }

    String label() {
        return pid + "  " + (displayName.isBlank() ? "<unknown Java process>" : displayName);
    }
}
