package com.blanoir.moons.client.module.impl.combat.misplace;

/** Shows applied displacement immediately; only persistent inactivity gets a reason label. */
public final class MisplaceFeedback {
    private double amount;
    private String pendingReason = "", shownReason = "";
    private long reasonSince;

    public void reset() {
        amount = 0;
        pendingReason = shownReason = "";
        reasonSince = 0;
    }

    public void update(double applied, String reason, long now) {
        amount = Double.isFinite(applied) ? Math.clamp(applied, 0, 1.5) : 0;
        if (amount >= .005) {
            pendingReason = shownReason = "";
            reasonSince = now;
            return;
        }
        if (!reason.equals(pendingReason) || now < reasonSince) {
            pendingReason = reason;
            shownReason = "";
            reasonSince = now;
        }
        if (now - reasonSince >= 200) shownReason = pendingReason;
    }

    public String tag(boolean adaptive) {
        long hundredths = Math.round(amount * 100);
        long fraction = hundredths % 100;
        return (adaptive ? "Pull " : "Fixed ")
                + hundredths / 100
                + "."
                + (fraction < 10 ? "0" : "")
                + fraction
                + (shownReason.isEmpty() ? "" : " " + shownReason);
    }
}
