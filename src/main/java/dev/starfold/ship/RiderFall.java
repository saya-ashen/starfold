package dev.starfold.ship;

/** Per movement-packet fall displacement, not damage immunity. Losing the hull immediately restores vanilla. */
public final class RiderFall {
    private Object hull;
    private double y;

    public double step(Object next, double localY, double worldY) {
        double result = next != null && next == hull ? localY - y : worldY;
        hull = next;
        y = localY;
        return next != null && Math.abs(result) < .001 ? 0 : result;
    }
}
