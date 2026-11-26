package dev.bali.util;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class RotationUtil {
    private RotationUtil() {}

    public static float[] rotationsTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90f, 90f)};
    }

    public static Vec3d closestPoint(Box box, Vec3d eye) {
        return new Vec3d(
            MathHelper.clamp(eye.x, box.minX, box.maxX),
            MathHelper.clamp(eye.y, box.minY, box.maxY),
            MathHelper.clamp(eye.z, box.minZ, box.maxZ));
    }

    public static double angleTo(float yaw, float pitch, Vec3d eye, Vec3d target) {
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        Vec3d dir = target.subtract(eye).normalize();
        return Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(dir), -1.0, 1.0)));
    }

    public static double rayBox(Vec3d eye, Vec3d dir, Box box) {
        double len = dir.length();
        if (len < 1e-9) return -1;
        double[] o = {eye.x, eye.y, eye.z}, d = {dir.x / len, dir.y / len, dir.z / len};
        double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
        double near = 0, far = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1;
                continue;
            }
            double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return -1;
        }
        return near;
    }

    public static double rayBox(Vec3d eye, float yaw, float pitch, Box box) {
        return rayBox(eye, Vec3d.fromPolar(pitch, yaw), box);
    }
}
