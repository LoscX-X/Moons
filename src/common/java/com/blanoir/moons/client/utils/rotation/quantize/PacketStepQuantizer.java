package com.blanoir.moons.client.utils.rotation.quantize;

/** Selects whole mouse counts from the packet's acceleration and physical interval. */
public final class PacketStepQuantizer {
    private PacketStepQuantizer() {}

    public static float step(
            float desiredStep,
            float previousStep,
            double acceleration,
            double quantum,
            float physicalMinimum,
            float physicalMaximum) {
        if (!Double.isFinite(quantum) || quantum <= 1.0E-7D) return desiredStep;
        double lower = Math.max(physicalMinimum, previousStep - acceleration);
        double upper = Math.min(physicalMaximum, previousStep + acceleration);
        long minimumCount = (long) Math.ceil(lower / quantum - 1.0E-7D);
        long maximumCount = (long) Math.floor(upper / quantum + 1.0E-7D);
        if (minimumCount > maximumCount) {
            // A new sensitivity can put the old velocity between the new grid lines.
            long physicalMinimumCount = (long) Math.ceil(physicalMinimum / quantum);
            long physicalMaximumCount = (long) Math.floor(physicalMaximum / quantum);
            long nearest = Math.round(previousStep / quantum);
            return (float)
                    (Math.max(physicalMinimumCount, Math.min(physicalMaximumCount, nearest))
                            * quantum);
        }
        long desiredCount = Math.round(desiredStep / quantum);
        long selected = Math.max(minimumCount, Math.min(maximumCount, desiredCount));
        return (float) (selected * quantum);
    }
}
