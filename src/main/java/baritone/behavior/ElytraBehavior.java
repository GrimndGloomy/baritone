/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.*;
import baritone.behavior.elytra.NetherPathfinderContext;
import baritone.behavior.elytra.UnpackedSegment;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.Chunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

public final class ElytraBehavior extends Behavior implements Helper {

    /**
     * 2b2t seed
     */
    private static final long NETHER_SEED = 146008555100680L;

    // Used exclusively for PathRenderer
    public List<Pair<Vec3d, Vec3d>> lines;
    public BlockPos aimPos;
    public List<BetterBlockPos> visiblePath;

    // :sunglasses:
    private final NetherPathfinderContext context;
    private final PathManager pathManager;
    private int sinceFirework;

    public ElytraBehavior(Baritone baritone) {
        super(baritone);
        this.context = new NetherPathfinderContext(NETHER_SEED);
        this.lines = new ArrayList<>();
        this.visiblePath = Collections.emptyList();
        this.pathManager = new PathManager();
    }

    private final class PathManager {

        private BlockPos destination;
        private List<BetterBlockPos> path;
        private boolean completePath;

        private int playerNear;

        private boolean recalculating;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.playerNear = this.calculateNear(this.playerNear);

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public void pathToDestination(BlockPos destination) {
            this.destination = destination;
            this.path0(ctx.playerFeet(), destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final int distance = (int) this.pathAt(0).distanceTo(this.pathAt(this.path.size() - 1));
                        if (this.completePath) {
                            logDirect(String.format("Computed path (%d blocks)", distance));
                        } else {
                            logDirect(String.format("Computed segment (Next %d blocks)", distance));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            logDirect("Failed to compute path to destination");
                        }
                    });
        }

        public void pathRecalcSegment(final int upToIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = this.path.subList(upToIncl, this.path.size());
            final boolean complete = this.completePath;

            this.path0(ctx.playerFeet(), this.path.get(upToIncl), segment -> segment.append(after.stream(), complete))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - after.size() - 1;
                        final int distance = (int) this.pathAt(0).distanceTo(this.pathAt(recompute));
                        logDirect(String.format("Recomputed segment (Next %d blocks)", distance));
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            logDirect("Failed to recompute segment");
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);

            this.path0(this.path.get(afterIncl), this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final int distance = (int) this.pathAt(0).distanceTo(this.pathAt(recompute));

                        if (this.completePath) {
                            logDirect(String.format("Computed path (%d blocks)", distance));
                        } else {
                            logDirect(String.format("Computed next segment (Next %d blocks)", distance));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            logDirect("Failed to compute next segment");
                        }
                    });
        }

        private Vec3d pathAt(int i) {
            return new Vec3d(
                    this.path.get(i).x,
                    this.path.get(i).y,
                    this.path.get(i).z
            );
        }

        public void clear() {
            this.path = Collections.emptyList();
            this.playerNear = 0;
            this.completePath = true;
        }

        private void setPath(final UnpackedSegment segment) {
            this.path = segment.collect();
            this.removeBacktracks();
            this.playerNear = 0;
            this.completePath = segment.isFinished();
        }

        public List<BetterBlockPos> getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return ElytraBehavior.this.context.pathFindAsync(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::addScheduledTask);
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            outer:
            while (true) {
                int rangeStartIncl = playerNear;
                int rangeEndExcl = playerNear;
                while (rangeEndExcl < path.size() && ctx.world().isBlockLoaded(path.get(rangeEndExcl), false)) {
                    rangeEndExcl++;
                }
                if (rangeStartIncl >= rangeEndExcl) {
                    // not loaded yet?
                    return;
                }
                if (!passable(ctx.world().getBlockState(path.get(rangeStartIncl)))) {
                    // we're in a wall
                    return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
                }
                for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                    if (!clearView(pathAt(i), pathAt(i + 1))) {
                        // obstacle. where do we return to pathing?
                        // find the next valid segment
                        this.pathRecalcSegment(rangeEndExcl - 1);
                        break outer;
                    }
                }
                break;
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            final int last = this.path.size() - 1;
            if (!this.completePath && ctx.world().isBlockLoaded(this.path.get(last), false)) {
                this.pathNextSegment(last);
            }
        }

        private int calculateNear(int index) {
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            return index;
        }

        private void removeBacktracks() {
            Map<BetterBlockPos, Integer> positionFirstSeen = new HashMap<>();
            for (int i = 0; i < this.path.size(); i++) {
                BetterBlockPos pos = this.path.get(i);
                if (positionFirstSeen.containsKey(pos)) {
                    int j = positionFirstSeen.get(pos);
                    while (i > j) {
                        this.path.remove(i);
                        i--;
                    }
                } else {
                    positionFirstSeen.put(pos, i);
                }
            }
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (event.getState() == EventState.POST && event.getType().isPopulate()) {
            final Chunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        }
    }

    public void path(BlockPos destination) {
        this.pathManager.pathToDestination(destination);
    }

    public void cancel() {
        this.visiblePath = Collections.emptyList();
        this.pathManager.clear();
        this.aimPos = null;
        this.sinceFirework = 0;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        this.lines.clear();

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        }

        this.bsi = new BlockStateInterface(ctx);
        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );

        if (!ctx.player().isElytraFlying()) {
            return;
        }

        baritone.getInputOverrideHandler().clearAllKeys();

        if (ctx.player().collidedHorizontally) {
            logDirect("hbonk");
        }
        if (ctx.player().collidedVertically) {
            logDirect("vbonk");
        }

        final Vec3d start = ctx.playerFeetAsVec();
        final boolean firework = isFireworkActive();
        BetterBlockPos goingTo = null;
        boolean forceUseFirework = false;
        this.sinceFirework++;

        outermost:
        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = firework ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int steps = relaxation < 2 ? firework ? 5 : Baritone.settings().elytraSimulationTicks.value : 3;
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;
            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                for (int dy : heights) {
                    final BetterBlockPos pos = path.get(i);
                    Vec3d dest = new Vec3d(pos).add(0, dy, 0);
                    if (dy != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!clearView(dest, this.pathManager.pathAt(i + lookahead).add(0, dy, 0)) || !clearView(dest, this.pathManager.pathAt(i + lookahead))) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!clearView(dest, this.pathManager.pathAt(i))) {
                                continue;
                            }
                        }
                    }

                    // 1.0 -> 0.25 -> none
                    final Double grow = relaxation == 2 ? null
                            : relaxation == 0 ? 1.0d : 0.25d;

                    if (isClear(start, dest, grow)) {
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(dest.subtract(start), steps, relaxation, firework);
                        if (pitch.first() == null) {
                            baritone.getLookBehavior().updateTarget(new Rotation(yaw, ctx.playerRotations().getPitch()), false);
                            continue;
                        }
                        forceUseFirework = pitch.second();
                        goingTo = pos;
                        this.aimPos = goingTo.add(0, dy, 0);
                        baritone.getLookBehavior().updateTarget(new Rotation(yaw, pitch.first()), false);
                        break outermost;
                    }
                }
            }
            if (relaxation == 2) {
                logDirect("no pitch solution, probably gonna crash in a few ticks LOL!!!");
                return;
            }
        }

        final boolean useOnDescend = !Baritone.settings().conserveFireworks.value || ctx.player().posY < goingTo.y + 5;
        final double currentSpeed = new Vec3d(
                ctx.player().motionX,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().posY < goingTo.y ? Math.max(0, ctx.player().motionY) : ctx.player().motionY,
                ctx.player().motionZ
        ).length();

        if (forceUseFirework || (!firework
                && sinceFirework > 10
                && useOnDescend
                && (ctx.player().posY < goingTo.y - 5 || start.distanceTo(new Vec3d(goingTo.x + 0.5, ctx.player().posY, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < Baritone.settings().elytraFireworkSpeed.value)
        ) {
            logDirect("firework" + (forceUseFirework ? " takeoff" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), EnumHand.MAIN_HAND);
            sinceFirework = 0;
        }
    }

    private boolean isFireworkActive() {
        // TODO: Validate that the EntityFireworkRocket is attached to ctx.player()
        return ctx.world().loadedEntityList.stream()
                .anyMatch(x -> (x instanceof EntityFireworkRocket) && ((EntityFireworkRocket) x).isAttachedToEntity());
    }

    private boolean isClear(final Vec3d start, final Vec3d dest, final Double growAmount) {
        if (!clearView(start, dest)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AxisAlignedBB bb = ctx.player().getEntityBoundingBox().grow(growAmount);
        final Vec3d[] corners = new Vec3d[]{
                new Vec3d(bb.minX, bb.minY, bb.minZ),
                new Vec3d(bb.minX, bb.minY, bb.maxZ),
                new Vec3d(bb.minX, bb.maxY, bb.minZ),
                new Vec3d(bb.minX, bb.maxY, bb.maxZ),
                new Vec3d(bb.maxX, bb.minY, bb.minZ),
                new Vec3d(bb.maxX, bb.minY, bb.maxZ),
                new Vec3d(bb.maxX, bb.maxY, bb.minZ),
                new Vec3d(bb.maxX, bb.maxY, bb.maxZ),
        };

        for (final Vec3d corner : corners) {
            if (!clearView(corner, dest.add(corner.subtract(start)))) {
                return false;
            }
        }
        return true;
    }

    private boolean clearView(Vec3d start, Vec3d dest) {
        lines.add(new Pair<>(start, dest));
        return !rayTraceBlocks(start.x, start.y, start.z, dest.x, dest.y, dest.z);
    }

    private Pair<Float, Boolean> solvePitch(Vec3d goalDirection, int steps, int relaxation, boolean currentlyBoosted) {
        final Float pitch = this.solvePitch(goalDirection, steps, relaxation == 2, currentlyBoosted);
        if (pitch != null) {
            return new Pair<>(pitch, false);
        }

        if (Baritone.settings().experimentalTakeoff.value && relaxation > 0) {
            final Float usingFirework = this.solvePitch(goalDirection, steps, relaxation == 2, true);
            if (usingFirework != null) {
                return new Pair<>(usingFirework, true);
            }
        }

        return new Pair<>(null, false);
    }

    private Float solvePitch(Vec3d goalDirection, int steps, boolean desperate, boolean firework) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        goalDirection = goalDirection.normalize();
        Rotation good = RotationUtils.calcRotationFromVec3d(new Vec3d(0, 0, 0), goalDirection, ctx.playerRotations()); // lazy lol

        Float bestPitch = null;
        double bestDot = Double.NEGATIVE_INFINITY;
        Vec3d motion = new Vec3d(ctx.player().motionX, ctx.player().motionY, ctx.player().motionZ);
        float minPitch = desperate ? -90 : Math.max(good.getPitch() - Baritone.settings().elytraPitchRange.value, -89);
        float maxPitch = desperate ? 90 : Math.min(good.getPitch() + Baritone.settings().elytraPitchRange.value, 89);
        outer:
        for (float pitch = minPitch; pitch <= maxPitch; pitch++) {
            Vec3d stepped = motion;
            Vec3d totalMotion = new Vec3d(0, 0, 0);
            for (int i = 0; i < steps; i++) {
                stepped = step(stepped, pitch, good.getYaw(), firework);
                Vec3d actualPositionPrevTick = ctx.playerFeetAsVec().add(totalMotion);
                totalMotion = totalMotion.add(stepped);
                Vec3d actualPosition = ctx.playerFeetAsVec().add(totalMotion);
                for (int x = MathHelper.floor(Math.min(actualPosition.x, actualPositionPrevTick.x) - 0.31); x <= Math.max(actualPosition.x, actualPositionPrevTick.x) + 0.31; x++) {
                    for (int y = MathHelper.floor(Math.min(actualPosition.y, actualPositionPrevTick.y) - 0.2); y <= Math.max(actualPosition.y, actualPositionPrevTick.y) + 1; y++) {
                        for (int z = MathHelper.floor(Math.min(actualPosition.z, actualPositionPrevTick.z) - 0.31); z <= Math.max(actualPosition.z, actualPositionPrevTick.z) + 0.31; z++) {
                            if (!this.passable(x, y, z)) {
                                continue outer;
                            }
                        }
                    }
                }
            }
            double directionalGoodness = goalDirection.dotProduct(totalMotion.normalize());
            // tried to incorporate a "speedGoodness" but it kept making it do stupid stuff (aka always losing altitude)
            double goodness = directionalGoodness;
            if (goodness > bestDot) {
                bestDot = goodness;
                bestPitch = pitch;
            }
        }
        return bestPitch;
    }

    private BlockStateInterface bsi;

    public boolean passable(int x, int y, int z) {
        return passable(this.bsi.get0(x, y, z));
    }

    public static boolean passable(IBlockState state) {
        return state.getMaterial() == Material.AIR;
    }

    private static Vec3d step(Vec3d motion, float rotationPitch, float rotationYaw, boolean firework) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;
        float flatZ = MathHelper.cos(-rotationYaw * 0.017453292F - (float) Math.PI); // 0.174... is Math.PI / 180
        float flatX = MathHelper.sin(-rotationYaw * 0.017453292F - (float) Math.PI);
        float pitchBase = -MathHelper.cos(-rotationPitch * 0.017453292F);
        float pitchHeight = MathHelper.sin(-rotationPitch * 0.017453292F);
        Vec3d lookDirection = new Vec3d(flatX * pitchBase, pitchHeight, flatZ * pitchBase);

        if (firework) {
            // See EntityFireworkRocket
            motionX += lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motionX) * 0.5;
            motionY += lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motionY) * 0.5;
            motionZ += lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motionZ) * 0.5;
        }

        float pitchRadians = rotationPitch * 0.017453292F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = MathHelper.cos(pitchRadians);
        //System.out.println("always the same lol " + -pitchBase + " " + pitchBase3);
        //System.out.println("always the same lol " + Math.abs(pitchBase3) + " " + pitchBase2);
        //System.out.println("always 1 lol " + thisIsAlwaysOne);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-MathHelper.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99;
        motionY *= 0.98;
        motionZ *= 0.99;
        //System.out.println(motionX + " " + motionY + " " + motionZ);

        return new Vec3d(motionX, motionY, motionZ);
    }

    private boolean rayTraceBlocks(final double startX, final double startY, final double startZ,
                                   final double endX, final double endY, final double endZ) {
        int voxelCurrX = fastFloor(startX);
        int voxelCurrY = fastFloor(startY);
        int voxelCurrZ = fastFloor(startZ);

        if (!this.passable(voxelCurrX, voxelCurrY, voxelCurrZ)) {
            return true;
        }

        final int voxelEndX = fastFloor(endX);
        final int voxelEndY = fastFloor(endY);
        final int voxelEndZ = fastFloor(endZ);
        double currPosX = startX;
        double currPosY = startY;
        double currPosZ = startZ;

        int steps = 200; // TODO: should we lower the max steps?
        while (steps-- >= 0) {
            if (voxelCurrX == voxelEndX && voxelCurrY == voxelEndY && voxelCurrZ == voxelEndZ) {
                return false;
            }

            final double distanceFromStartToEndX = endX - currPosX;
            final double distanceFromStartToEndY = endY - currPosY;
            final double distanceFromStartToEndZ = endZ - currPosZ;

            double nextIntegerX;
            double nextIntegerY;
            double nextIntegerZ;
            // potentially more based branchless impl?
            nextIntegerX = voxelCurrX + ((voxelCurrX - voxelEndX) >>> 31); // if voxelEnd > voxelIn, then voxelIn-voxelEnd will be negative, meaning the sign bit is 1
            nextIntegerY = voxelCurrY + ((voxelCurrY - voxelEndY) >>> 31); // if we do an unsigned right shift by 31, that sign bit becomes the LSB
            nextIntegerZ = voxelCurrZ + ((voxelCurrZ - voxelEndZ) >>> 31); // therefore, this increments nextInteger iff EndX>inX, otherwise it leaves it alone
            // remember: don't have to worry about the case when voxelEnd == voxelIn, because nextInteger value wont be used

            // these just have to be strictly greater than 1, might as well just go up to the next int
            double fracIfSkipX = 2.0D;
            double fracIfSkipY = 2.0D;
            double fracIfSkipZ = 2.0D;

            // reminder to future self: don't "branchlessify" this, it's MUCH slower (pretty obviously, floating point div is much worse than a branch mispredict, but integer increment (like the other two removed branches) are cheap enough to be worth doing either way)
            if (voxelEndX != voxelCurrX) {
                fracIfSkipX = (nextIntegerX - currPosX) / distanceFromStartToEndX;
            }
            if (voxelEndY != voxelCurrY) {
                fracIfSkipY = (nextIntegerY - currPosY) / distanceFromStartToEndY;
            }
            if (voxelEndZ != voxelCurrZ) {
                fracIfSkipZ = (nextIntegerZ - currPosZ) / distanceFromStartToEndZ;
            }

            if (fracIfSkipX < fracIfSkipY && fracIfSkipX < fracIfSkipZ) {
                // note: voxelEndX == voxelInX is impossible because allowSkip would be set to false in that case, meaning that the elapsed distance would stay at default
                currPosX = nextIntegerX;
                currPosY += distanceFromStartToEndY * fracIfSkipX;
                currPosZ += distanceFromStartToEndZ * fracIfSkipX;
                // tested: faster to paste this 3 times with only one of the subtractions in each
                final int xFloorOffset = (voxelEndX - voxelCurrX) >>> 31;
                voxelCurrX = (fastFloor(currPosX) - xFloorOffset);
                voxelCurrY = (fastFloor(currPosY));
                voxelCurrZ = (fastFloor(currPosZ));
            } else if (fracIfSkipY < fracIfSkipZ) {
                currPosX += distanceFromStartToEndX * fracIfSkipY;
                currPosY = nextIntegerY;
                currPosZ += distanceFromStartToEndZ * fracIfSkipY;
                // tested: faster to paste this 3 times with only one of the subtractions in each
                final int yFloorOffset = (voxelEndY - voxelCurrY) >>> 31;
                voxelCurrX = (fastFloor(currPosX));
                voxelCurrY = (fastFloor(currPosY) - yFloorOffset);
                voxelCurrZ = (fastFloor(currPosZ));
            } else {
                currPosX += distanceFromStartToEndX * fracIfSkipZ;
                currPosY += distanceFromStartToEndY * fracIfSkipZ;
                currPosZ = nextIntegerZ;
                // tested: faster to paste this 3 times with only one of the subtractions in each
                final int zFloorOffset = (voxelEndZ - voxelCurrZ) >>> 31;
                voxelCurrX = (fastFloor(currPosX));
                voxelCurrY = (fastFloor(currPosY));
                voxelCurrZ = (fastFloor(currPosZ) - zFloorOffset);
            }

            if (!this.passable(voxelCurrX, voxelCurrY, voxelCurrZ)) {
                return true;
            }
        }
        return false;
    }

    private static final double FLOOR_DOUBLE_D = 1_073_741_824.0;
    private static final int FLOOR_DOUBLE_I = 1_073_741_824;

    private static int fastFloor(final double v) {
        return ((int) (v + FLOOR_DOUBLE_D)) - FLOOR_DOUBLE_I;
    }
}