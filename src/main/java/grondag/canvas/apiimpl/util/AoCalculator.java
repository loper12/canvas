/*******************************************************************************
 * Copyright 2019 grondag
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package grondag.canvas.apiimpl.util;

import static grondag.canvas.apiimpl.util.GeometryHelper.AXIS_ALIGNED_FLAG;
import static grondag.canvas.apiimpl.util.GeometryHelper.CUBIC_FLAG;
import static grondag.canvas.apiimpl.util.GeometryHelper.LIGHT_FACE_FLAG;
import static net.minecraft.util.math.Direction.DOWN;
import static net.minecraft.util.math.Direction.EAST;
import static net.minecraft.util.math.Direction.NORTH;
import static net.minecraft.util.math.Direction.SOUTH;
import static net.minecraft.util.math.Direction.UP;
import static net.minecraft.util.math.Direction.WEST;
import static java.lang.Math.max;

import java.util.function.ToIntBiFunction;

import grondag.canvas.apiimpl.MutableQuadViewImpl;
import grondag.canvas.apiimpl.QuadViewImpl;
import grondag.canvas.apiimpl.rendercontext.BlockRenderInfo;
import grondag.canvas.apiimpl.util.AoFace.WeightFunction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.ExtendedBlockView;

/**
 * Adaptation of inner, non-static class in BlockModelRenderer that serves same
 * purpose.
 */
@Environment(EnvType.CLIENT)
public class AoCalculator {
    /** Used to receive a method reference in constructor for ao value lookup. */
    @FunctionalInterface
    public static interface AoFunc {
        float apply(BlockPos pos);
    }

    /**
     * Vanilla models with cubic quads have vertices in a certain order, which
     * allows us to map them using a lookup. Adapted from enum in vanilla
     * AoCalculator.
     */
    private static final int[][] VERTEX_MAP = new int[6][4];
    static {
        VERTEX_MAP[DOWN.getId()] = new int[] { 0, 1, 2, 3 };
        VERTEX_MAP[UP.getId()] = new int[] { 2, 3, 0, 1 };
        VERTEX_MAP[NORTH.getId()] = new int[] { 3, 0, 1, 2 };
        VERTEX_MAP[SOUTH.getId()] = new int[] { 0, 1, 2, 3 };
        VERTEX_MAP[WEST.getId()] = new int[] { 3, 0, 1, 2 };
        VERTEX_MAP[EAST.getId()] = new int[] { 1, 2, 3, 0 };
    }

    private final BlockPos.Mutable lightPos = new BlockPos.Mutable();
    private final BlockPos.Mutable searchPos = new BlockPos.Mutable();
    private final BlockRenderInfo blockInfo;
    private final ToIntBiFunction<BlockState, BlockPos> brightnessFunc;
    private final AoFunc aoFunc;

    /**
     * caches results of {@link #computeFace(Direction, boolean)} for the current
     * block
     */
    private final AoFaceData[] faceData = new AoFaceData[12];

    /**
     * indicates which elements of {@link #faceData} have been computed for the
     * current block
     */
    private int completionFlags = 0;

    /** holds per-corner weights - used locally to avoid new allocation */
    private final float[] w = new float[4];

    // outputs
    public final float[] ao = new float[4];
    public final int[] light = new int[4];

    public AoCalculator(BlockRenderInfo blockInfo, ToIntBiFunction<BlockState, BlockPos> brightnessFunc,
            AoFunc aoFunc) {
        this.blockInfo = blockInfo;
        this.brightnessFunc = brightnessFunc;
        this.aoFunc = aoFunc;
        for (int i = 0; i < 12; i++) {
            faceData[i] = new AoFaceData();
        }
    }

    /** call at start of each new block */
    public void clear() {
        completionFlags = 0;
    }

    /** returns true if should match vanilla results */
    public void compute(MutableQuadViewImpl quad) {
        switch (quad.geometryFlags()) {
        case AXIS_ALIGNED_FLAG | CUBIC_FLAG | LIGHT_FACE_FLAG:
            vanillaFullFace(quad, true);
            break;

        case AXIS_ALIGNED_FLAG | LIGHT_FACE_FLAG:
            vanillaPartialFace(quad, true);
            break;

        case AXIS_ALIGNED_FLAG | CUBIC_FLAG:
            blendedFullFace(quad);
            break;

        case AXIS_ALIGNED_FLAG:
            blendedPartialFace(quad);
            break;

        default:
            irregularFace(quad);
            break;
        }
    }

    private void vanillaFullFace(MutableQuadViewImpl quad, boolean isOnLightFace) {
        final Direction lightFace = quad.lightFace();
        computeFace(lightFace, isOnLightFace).toArray(ao, light, VERTEX_MAP[lightFace.getId()]);
    }

    private void vanillaPartialFace(MutableQuadViewImpl quad, boolean isOnLightFace) {
        final Direction lightFace = quad.lightFace();
        AoFaceData faceData = computeFace(lightFace, isOnLightFace);
        final WeightFunction wFunc = AoFace.get(lightFace).weightFunc;
        final float[] w = this.w;
        for (int i = 0; i < 4; i++) {
            wFunc.apply(quad, i, w);
            light[i] = faceData.weightedCombinedLight(w);
            ao[i] = faceData.weigtedAo(w);
        }
    }

    /**
     * used in {@link #blendedInsetFace(VertexEditorImpl, Direction)} as return
     * variable to avoid new allocation
     */
    AoFaceData tmpFace = new AoFaceData();

    /**
     * Returns linearly interpolated blend of outer and inner face based on depth of
     * vertex in face
     */
    private AoFaceData blendedInsetFace(QuadViewImpl quad, int vertexIndex, Direction lightFace) {
        final float w1 = AoFace.get(lightFace).depthFunc.apply(quad, vertexIndex);
        final float w0 = 1 - w1;
        return AoFaceData.weightedMean(computeFace(lightFace, true), w0, computeFace(lightFace, false), w1, tmpFace);
    }

    /**
     * Like {@link #blendedInsetFace(VertexEditorImpl, Direction)} but optimizes if
     * depth is 0 or 1. Used for irregular faces when depth varies by vertex to
     * avoid unneeded interpolation.
     */
    private AoFaceData gatherInsetFace(QuadViewImpl quad, int vertexIndex, Direction lightFace) {
        final float w1 = AoFace.get(lightFace).depthFunc.apply(quad, vertexIndex);
        if (MathHelper.equalsApproximate(w1, 0)) {
            return computeFace(lightFace, true);
        } else if (MathHelper.equalsApproximate(w1, 1)) {
            return computeFace(lightFace, false);
        } else {
            final float w0 = 1 - w1;
            return AoFaceData.weightedMean(computeFace(lightFace, true), w0, computeFace(lightFace, false), w1,
                    tmpFace);
        }
    }

    private void blendedFullFace(MutableQuadViewImpl quad) {
        final Direction lightFace = quad.lightFace();
        blendedInsetFace(quad, 0, lightFace).toArray(ao, light, VERTEX_MAP[lightFace.getId()]);
    }

    private void blendedPartialFace(MutableQuadViewImpl quad) {
        final Direction lightFace = quad.lightFace();
        AoFaceData faceData = blendedInsetFace(quad, 0, lightFace);
        final WeightFunction wFunc = AoFace.get(lightFace).weightFunc;
        for (int i = 0; i < 4; i++) {
            wFunc.apply(quad, i, w);
            light[i] = faceData.weightedCombinedLight(w);
            ao[i] = faceData.weigtedAo(w);
        }
    }

    /**
     * used exclusively in irregular face to avoid new heap allocations each call.
     */
    private final Vector3f vertexNormal = new Vector3f();

    private void irregularFace(MutableQuadViewImpl quad) {
        final Vector3f faceNorm = quad.faceNormal();
        Vector3f normal;
        final float[] w = this.w;
        final float aoResult[] = this.ao;
        final int[] lightResult = this.light;

        for (int i = 0; i < 4; i++) {
            normal = quad.hasNormal(i) ? quad.copyNormal(i, vertexNormal) : faceNorm;
            float ao = 0, sky = 0, block = 0, maxAo = 0;
            int maxSky = 0, maxBlock = 0;

            final float x = normal.x();
            if (!MathHelper.equalsApproximate(0f, x)) {
                final Direction face = x > 0 ? Direction.EAST : Direction.WEST;
                final AoFaceData fd = gatherInsetFace(quad, i, face);
                AoFace.get(face).weightFunc.apply(quad, i, w);
                final float n = x * x;
                ao += n * fd.weigtedAo(w);
                sky += n * fd.weigtedSkyLight(w);
                block += n * fd.weigtedBlockLight(w);
                maxAo = fd.maxAo(maxAo);
                maxSky = fd.maxSkyLight(maxSky);
                maxBlock = fd.maxBlockLight(maxBlock);
            }

            final float y = normal.y();
            if (!MathHelper.equalsApproximate(0f, y)) {
                final Direction face = y > 0 ? Direction.UP : Direction.DOWN;
                final AoFaceData fd = gatherInsetFace(quad, i, face);
                AoFace.get(face).weightFunc.apply(quad, i, w);
                final float n = y * y;
                ao += n * fd.weigtedAo(w);
                sky += n * fd.weigtedSkyLight(w);
                block += n * fd.weigtedBlockLight(w);
                maxAo = fd.maxAo(maxAo);
                maxSky = fd.maxSkyLight(maxSky);
                maxBlock = fd.maxBlockLight(maxBlock);
            }

            final float z = normal.z();
            if (!MathHelper.equalsApproximate(0f, z)) {
                final Direction face = z > 0 ? Direction.SOUTH : Direction.NORTH;
                final AoFaceData fd = gatherInsetFace(quad, i, face);
                AoFace.get(face).weightFunc.apply(quad, i, w);
                final float n = z * z;
                ao += n * fd.weigtedAo(w);
                sky += n * fd.weigtedSkyLight(w);
                block += n * fd.weigtedBlockLight(w);
                maxAo = fd.maxAo(maxAo);
                maxSky = fd.maxSkyLight(maxSky);
                maxBlock = fd.maxBlockLight(maxBlock);
            }

            aoResult[i] = (ao + maxAo) * 0.5f;
            lightResult[i] = (((int) ((sky + maxSky) * 0.5f) & 0xF0) << 16)
                    | ((int) ((block + maxBlock) * 0.5f) & 0xF0);
        }
    }

    /**
     * Computes smoothed brightness and Ao shading for four corners of a block face.
     * Outer block face is what you normally see and what you get get when second
     * parameter is true. Inner is light *within* the block and usually darker. It
     * is blended with the outer face for inset surfaces, but is also used directly
     * in vanilla logic for some blocks that aren't full opaque cubes. Except for
     * parameterization, the logic itself is practically identical to vanilla.
     */
    private AoFaceData computeFace(Direction lightFace, boolean isOnBlockFace) {
        final int faceDataIndex = isOnBlockFace ? lightFace.getId() : lightFace.getId() + 6;
        final int mask = 1 << faceDataIndex;
        final AoFaceData result = faceData[faceDataIndex];
        if ((completionFlags & mask) == 0) {
            completionFlags |= mask;

            final ExtendedBlockView world = blockInfo.blockView;
            final BlockState blockState = blockInfo.blockState;
            final BlockPos pos = blockInfo.blockPos;
            final BlockPos.Mutable lightPos = this.lightPos;
            final BlockPos.Mutable searchPos = this.searchPos;

            lightPos.set(isOnBlockFace ? pos.offset(lightFace) : pos);
            AoFace aoFace = AoFace.get(lightFace);

            // PERF: make these lookups lazy - they may not get used if neighbor is obscured
            searchPos.set(lightPos).setOffset(aoFace.neighbors[0]);
            final int light0 = brightnessFunc.applyAsInt(blockState, searchPos);
            final float ao0 = aoFunc.apply(searchPos);
            searchPos.set(lightPos).setOffset(aoFace.neighbors[1]);
            final int light1 = brightnessFunc.applyAsInt(blockState, searchPos);
            final float ao1 = aoFunc.apply(searchPos);
            searchPos.set(lightPos).setOffset(aoFace.neighbors[2]);
            final int light2 = brightnessFunc.applyAsInt(blockState, searchPos);
            final float ao2 = aoFunc.apply(searchPos);
            searchPos.set(lightPos).setOffset(aoFace.neighbors[3]);
            final int light3 = brightnessFunc.applyAsInt(blockState, searchPos);
            final float ao3 = aoFunc.apply(searchPos);

            // vanilla was further offsetting these in the direction of the light face
            // but it was actually mis-sampling and causing visible artifacts in certain situation
            searchPos.set(lightPos).setOffset(aoFace.neighbors[0]);//.setOffset(lightFace);
            final boolean isClear0 = world.getBlockState(searchPos).getLightSubtracted(world, searchPos) == 0;
            searchPos.set(lightPos).setOffset(aoFace.neighbors[1]);//.setOffset(lightFace);
            final boolean isClear1 = world.getBlockState(searchPos).getLightSubtracted(world, searchPos) == 0;
            searchPos.set(lightPos).setOffset(aoFace.neighbors[2]);//.setOffset(lightFace);
            final boolean isClear2 = world.getBlockState(searchPos).getLightSubtracted(world, searchPos) == 0;
            searchPos.set(lightPos).setOffset(aoFace.neighbors[3]);//.setOffset(lightFace);
            final boolean isClear3 = world.getBlockState(searchPos).getLightSubtracted(world, searchPos) == 0;

            // c = corner - values at corners of face
            int cLight0, cLight1, cLight2, cLight3;
            float cAo0, cAo1, cAo2, cAo3;

            // If neighbors on both side of the corner are opaque, then apparently we use
            // the light/shade
            // from one of the sides adjacent to the corner. If either neighbor is clear (no
            // light subtraction)
            // then we use values from the outwardly diagonal corner. (outwardly = position
            // is one more away from light face)
            if (!isClear2 && !isClear0) {
                cAo0 = ao0;
                cLight0 = light0;
            } else {
                searchPos.set(lightPos).setOffset(aoFace.neighbors[0]).setOffset(aoFace.neighbors[2]);
                cAo0 = aoFunc.apply(searchPos);
                cLight0 = brightnessFunc.applyAsInt(blockState, searchPos);
            }

            if (!isClear3 && !isClear0) {
                cAo1 = ao0;
                cLight1 = light0;
            } else {
                searchPos.set(lightPos).setOffset(aoFace.neighbors[0]).setOffset(aoFace.neighbors[3]);
                cAo1 = aoFunc.apply(searchPos);
                cLight1 = brightnessFunc.applyAsInt(blockState, searchPos);
            }

            if (!isClear2 && !isClear1) {
                cAo2 = ao1;
                cLight2 = light1;
            } else {
                searchPos.set(lightPos).setOffset(aoFace.neighbors[1]).setOffset(aoFace.neighbors[2]);
                cAo2 = aoFunc.apply(searchPos);
                cLight2 = brightnessFunc.applyAsInt(blockState, searchPos);
            }

            if (!isClear3 && !isClear1) {
                cAo3 = ao1;
                cLight3 = light1;
            } else {
                searchPos.set(lightPos).setOffset(aoFace.neighbors[1]).setOffset(aoFace.neighbors[3]);
                cAo3 = aoFunc.apply(searchPos);
                cLight3 = brightnessFunc.applyAsInt(blockState, searchPos);
            }

            // If on block face or neighbor isn't occluding, "center" will be neighbor
            // brightness
            // Doesn't use light pos because logic not based solely on this block's geometry
            int lightCenter;
            searchPos.set((Vec3i) pos).setOffset(lightFace);
            if (isOnBlockFace || !world.getBlockState(searchPos).isFullOpaque(world, searchPos)) {
                lightCenter = brightnessFunc.applyAsInt(blockState, searchPos);
            } else {
                lightCenter = brightnessFunc.applyAsInt(blockState, pos);
            }

            float aoCenter = aoFunc.apply(isOnBlockFace ? lightPos : pos);

            result.a0 = (ao3 + ao0 + cAo1 + aoCenter) * 0.25F;
            result.a1 = (ao2 + ao0 + cAo0 + aoCenter) * 0.25F;
            result.a2 = (ao2 + ao1 + cAo2 + aoCenter) * 0.25F;
            result.a3 = (ao3 + ao1 + cAo3 + aoCenter) * 0.25F;

            result.l0(meanBrightness(light3, light0, cLight1, lightCenter));
            result.l1(meanBrightness(light2, light0, cLight0, lightCenter));
            result.l2(meanBrightness(light2, light1, cLight2, lightCenter));
            result.l3(meanBrightness(light3, light1, cLight3, lightCenter));
        }
        return result;
    }
    
    /** 
     * Vanilla code excluded missing light values from mean but was not isotropic.
     * Still need to substitute or edges are too dark but consistently use the min 
     * value from all four samples.
     */
    private static int meanBrightness(int a, int b, int c, int d) {
        return a == 0 || b == 0 || c == 0 || d == 0 ? meanEdgeBrightness(a, b, c, d) : meanInnerBrightness(a, b, c, d);
    }
    
    private static int meanInnerBrightness(int a, int b, int c, int d) {
        // bitwise divide by 4, clamp to expected (positive) range
        return a + b + c + d >> 2 & 16711935;
    }

    private static int nonZeroMin(int a, int b) {
        if(a == 0) return b;
        if(b == 0) return a;
        return Math.min(a, b);
    }
    
    private static int meanEdgeBrightness(int a, int b, int c, int d) {
        final int min = nonZeroMin(nonZeroMin(a, b), nonZeroMin(c, d));
        return meanInnerBrightness(max(a, min), max(b, min), max(c, min), max(d, min));
    }
}
