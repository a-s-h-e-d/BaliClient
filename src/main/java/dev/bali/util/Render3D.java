package dev.bali.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Immediate-mode world rendering through walls. Call only inside a Render3D event.
 * All coordinates are world coordinates; the camera offset is applied here.
 */
public final class Render3D {
    private Render3D() {}

    private static final RenderPipeline LINES_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("bali", "pipeline/lines_through_walls"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build());

    private static final RenderPipeline QUADS_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("bali", "pipeline/quads_through_walls"))
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build());

    private static final RenderLayer LINES = RenderLayer.of("bali_lines", RenderSetup.builder(LINES_PIPELINE).build());
    private static final RenderLayer QUADS = RenderLayer.of("bali_quads", RenderSetup.builder(QUADS_PIPELINE).translucent().build());

    private static final VertexConsumerProvider.Immediate QUAD_BUFFER = VertexConsumerProvider.immediate(new BufferAllocator(1 << 18));
    private static final VertexConsumerProvider.Immediate LINE_BUFFER = VertexConsumerProvider.immediate(new BufferAllocator(1 << 18));

    private static final MatrixStack STACK = new MatrixStack();
    private static Vec3d cam = Vec3d.ZERO;
    private static boolean active;

    public static void begin(MatrixStack ignored, Vec3d cameraPos) {
        cam = cameraPos;
        active = true;
    }

    public static void end() {
        if (!active) return;
        active = false;
        QUAD_BUFFER.draw();
        LINE_BUFFER.draw();
    }

    // ---- primitives ----

    public static void boxFill(Box box, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        Box b = box.offset(-cam.x, -cam.y, -cam.z);
        VertexConsumer vc = QUAD_BUFFER.getBuffer(QUADS);
        MatrixStack.Entry e = STACK.peek();
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
        // bottom
        quad(vc, e, argb, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        // top
        quad(vc, e, argb, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
        // north
        quad(vc, e, argb, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
        // south
        quad(vc, e, argb, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
        // west
        quad(vc, e, argb, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
        // east
        quad(vc, e, argb, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
    }

    private static void quad(VertexConsumer vc, MatrixStack.Entry e, int c,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vc.vertex(e, ax, ay, az).color(c);
        vc.vertex(e, bx, by, bz).color(c);
        vc.vertex(e, cx, cy, cz).color(c);
        vc.vertex(e, dx, dy, dz).color(c);
    }

    public static void boxOutline(Box box, int argb, float width) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        Box b = box.offset(-cam.x, -cam.y, -cam.z);
        VertexConsumer vc = LINE_BUFFER.getBuffer(LINES);
        MatrixStack.Entry e = STACK.peek();
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
        line(vc, e, x1, y1, z1, x2, y1, z1, argb, width);
        line(vc, e, x2, y1, z1, x2, y1, z2, argb, width);
        line(vc, e, x2, y1, z2, x1, y1, z2, argb, width);
        line(vc, e, x1, y1, z2, x1, y1, z1, argb, width);
        line(vc, e, x1, y2, z1, x2, y2, z1, argb, width);
        line(vc, e, x2, y2, z1, x2, y2, z2, argb, width);
        line(vc, e, x2, y2, z2, x1, y2, z2, argb, width);
        line(vc, e, x1, y2, z2, x1, y2, z1, argb, width);
        line(vc, e, x1, y1, z1, x1, y2, z1, argb, width);
        line(vc, e, x2, y1, z1, x2, y2, z1, argb, width);
        line(vc, e, x2, y1, z2, x2, y2, z2, argb, width);
        line(vc, e, x1, y1, z2, x1, y2, z2, argb, width);
    }

    public static void box(Box box, int fill, int outline, float width) {
        boxFill(box, fill);
        boxOutline(box, outline, width);
    }

    /** Line between two world positions. */
    public static void line(Vec3d a, Vec3d b, int argb, float width) {
        VertexConsumer vc = LINE_BUFFER.getBuffer(LINES);
        line(vc, STACK.peek(),
            (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z),
            (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z), argb, width);
    }

    /** Line from a camera-relative start (e.g. the crosshair) to a world position. */
    public static void lineFromCamera(Vec3d camRelativeStart, Vec3d worldEnd, int argb, float width) {
        VertexConsumer vc = LINE_BUFFER.getBuffer(LINES);
        line(vc, STACK.peek(),
            (float) camRelativeStart.x, (float) camRelativeStart.y, (float) camRelativeStart.z,
            (float) (worldEnd.x - cam.x), (float) (worldEnd.y - cam.y), (float) (worldEnd.z - cam.z), argb, width);
    }

    private static void line(VertexConsumer vc, MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, int c, float w) {
        Vector3f n = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
        if (n.lengthSquared() < 1e-8f) return;
        n.normalize();
        vc.vertex(e, x1, y1, z1).color(c).normal(e, n).lineWidth(w);
        vc.vertex(e, x2, y2, z2).color(c).normal(e, n).lineWidth(w);
    }

    public static Vec3d cameraPos() { return cam; }

    // ---- view bobbing aware camera math ----

    /** Bob + hurt-tilt matrix and the full world projection, captured each frame in GameRenderer.renderWorld. */
    private static final org.joml.Matrix4f BOB = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f PROJ = new org.joml.Matrix4f();

    public static void captureProjection(org.joml.Matrix4fc bob, org.joml.Matrix4fc proj) {
        BOB.set(bob);
        PROJ.set(proj);
    }

    /**
     * Camera-relative start point for tracers that lands exactly on the crosshair.
     */
    public static Vec3d crosshair() {
        org.joml.Vector4f v = new org.joml.Vector4f(0, 0, -0.1f, 1);
        new org.joml.Matrix4f(BOB).invert().transform(v);
        Vector3f p = new Vector3f(v.x / v.w, v.y / v.w, v.z / v.w);
        net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getRotation().transform(p);
        return new Vec3d(p.x, p.y, p.z);
    }

    /**
     * World position to GUI coordinates (scaled screen pixels) using this frame's camera, or null
     * when the point is behind the camera. x, y = screen position; z = view depth.
     */
    public static Vec3d project(Vec3d world, int guiWidth, int guiHeight) {
        var c = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera();
        Vec3d rel = world.subtract(c.getCameraPos());
        Vector3f view = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        c.getRotation().conjugate(new org.joml.Quaternionf()).transform(view);
        org.joml.Vector4f clip = new org.joml.Vector4f(view, 1);
        PROJ.transform(clip);
        if (clip.w <= 0.05f) return null;
        double nx = clip.x / clip.w, ny = clip.y / clip.w;
        return new Vec3d((nx + 1) / 2 * guiWidth, (1 - ny) / 2 * guiHeight, -view.z);
    }

    /**
     * Called from mod init so our pipelines are registered before the first resource reload,
     * which is what compiles every registered pipeline's shaders.
     */
    public static void init() {}

    public static boolean precompile() {
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        return device.precompilePipeline(LINES_PIPELINE).isValid() && device.precompilePipeline(QUADS_PIPELINE).isValid();
    }
}
