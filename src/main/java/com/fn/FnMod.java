package com.fn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

/**
 * 地面より下だけをピンポイントで埋める。床や地面の高さは列ごとに全部自動で判定。
 *
 * 各列 (X,Z) を「上から」調べる:
 *   1. 建物・道路・橋・木・空気は通り過ぎて、最初の「自然の地面 (土・草・石など)」を探す
 *   2. そこから下へ、ブロックが詰まっている所 = 地面の塊。その一番下 = 地面の裏側
 *      塊の厚みが MIN_THICK 未満なら浮いたゴミとみなして、さらに下を探す
 *   3. 地面の裏側の1つ下 〜 y=99 を全部埋める (空洞・床・洞窟もまとめて)
 * 地面の塊とそれより上 (道路・建物・橋・木) には一切触らない。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // 処理範囲 (X, Z 共通)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 100..120 深層岩 / 121〜 安山岩
    static final int MIN_THICK = 3;                  // これより薄い地面の塊は浮いたゴミとみなす
    static final boolean CHECK_OVERHANG = false;     // true: 地面の裏側の下が明るい所(崖の張り出し)は埋めない
    static final int SKY_DARK = 7;                   // ↑の「明るい」の基準 (空の光 0〜15)
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();
    static final int NONE = Integer.MIN_VALUE;

    static final int CX_MIN = Math.floorDiv(MIN_XZ, 16), CX_MAX = Math.floorDiv(MAX_XZ, 16);
    static final int SIDE = CX_MAX - CX_MIN + 1, TOTAL = SIDE * SIDE;
    static boolean running = false;
    static int next = 0, lastPercent = -1;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
            CommandManager.literal("start")
                .executes(ctx -> {
                    if (running) {
                        ctx.getSource().sendFeedback(() -> Text.literal("すでに実行中です (" + next + "/" + TOTAL + ")"), false);
                        return 0;
                    }
                    running = true; next = 0; lastPercent = -1;
                    ctx.getSource().sendFeedback(() -> Text.literal("開始: " + TOTAL + " チャンク"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    running = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("停止しました (" + next + "/" + TOTAL + ")"), false);
                    return 1;
                }))
        ));
        ServerTickEvents.END_SERVER_TICK.register(FnMod::tick);
    }

    private static void tick(MinecraftServer server) {
        if (!running) return;
        ServerWorld world = server.getOverworld();
        long end = System.nanoTime() + TICK_BUDGET_NS;
        while (next < TOTAL && System.nanoTime() < end) {
            process(world, world.getChunk(CX_MIN + next % SIDE, CX_MIN + next / SIDE));
            next++;
        }
        int percent = next * 100 / TOTAL;
        if (percent / 5 != lastPercent / 5) {
            lastPercent = percent;
            server.getPlayerManager().broadcast(Text.literal("埋め立て中… " + percent + "%"), true);
        }
        if (next >= TOTAL) {
            running = false;
            server.getPlayerManager().broadcast(Text.literal("完了！ 一度ワールドに入り直すと見た目が更新されます"), false);
        }
    }

    /** すき間 (空気・水・溶岩・木・葉・草花) */
    static boolean passable(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.isReplaceable()
            || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
            || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS);
    }

    /** 自然の地面 (土・草・石・砂など)。道路・建物・橋の材料は含まない */
    static boolean isNaturalGround(BlockState s) {
        if (!s.isOpaqueFullCube()) return false;
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND)
            || s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY)
            || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.SNOW_BLOCK);
    }

    static BlockState get(Chunk c, int lx, int y, int lz) {
        final ChunkSection sec = c.getSection(c.getSectionIndex(y));
        return sec.isEmpty() ? Blocks.AIR.getDefaultState() : sec.getBlockState(lx, y & 15, lz);
    }

    /** 埋める上限のY (地面の裏側の1つ下)。埋めない列は NONE */
    static int fillTop(ServerWorld w, Chunk c, int lx, int lz, BlockPos.Mutable p) {
        int y = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz) - 1;
        if (y <= LOCK_Y) return NONE;

        while (y >= LOCK_Y) {
            // 空のセクションは16段まとめて飛ばす
            final int si = c.getSectionIndex(y);
            if (c.getSection(si).isEmpty()) { y = (c.sectionIndexToCoord(si) << 4) - 1; continue; }

            // 1. 自然の地面を探す (建物・道路・橋・木・空気は通り過ぎる)
            if (!isNaturalGround(get(c, lx, y, lz))) { y--; continue; }

            // 2. ブロックが詰まっている間を下へ = 地面の塊
            final int massTop = y;
            while (y >= LOCK_Y && !passable(get(c, lx, y, lz))) y--;
            final int massBottom = y + 1;

            if (y < LOCK_Y) return LOCK_Y;                           // y=99 まで地面が詰まっている (空洞なし)
            if (massTop - massBottom + 1 < MIN_THICK) continue;      // 薄い = 浮いたゴミ → さらに下を探す
            if (CHECK_OVERHANG && w.getLightLevel(LightType.SKY, p.set(c.getPos().getStartX() + lx, y, c.getPos().getStartZ() + lz)) > SKY_DARK)
                continue;                                            // 下が明るい = 崖の張り出し → さらに下を探す
            return massBottom - 1;                                   // 3. 地面の裏側の1つ下まで埋める
        }
        return NONE;
    }

    static void process(ServerWorld w, Chunk chunk) {
        final int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return;

        final BlockPos.Mutable p = new BlockPos.Mutable();
        final int[] tops = new int[256];
        int maxTop = NONE;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            final int wx = sx + lx, wz = sz + lz;
            int t = (wx < MIN_XZ || wx > MAX_XZ || wz < MIN_XZ || wz > MAX_XZ) ? NONE : fillTop(w, chunk, lx, lz, p);
            tops[(lz << 4) | lx] = t;
            if (t > maxTop) maxTop = t;
        }
        if (maxTop == NONE) return;

        boolean changed = false;
        final int fromSec = chunk.getSectionIndex(LOCK_Y), toSec = chunk.getSectionIndex(maxTop);
        for (int si = fromSec; si <= toSec; si++) {
            final ChunkSection sec = chunk.getSection(si);
            final int base = chunk.sectionIndexToCoord(si) << 4;
            sec.lock();
            try {
                for (int idx = 0; idx < 256; idx++) {
                    final int t = tops[idx];
                    if (t == NONE) continue;
                    final int lx = idx & 15, lz = idx >> 4;
                    final int lo = Math.max(LOCK_Y, base), hi = Math.min(t, base + 15);
                    for (int y = lo; y <= hi; y++) {
                        final BlockState target = y == LOCK_Y ? LOCK : (y <= DEEP_MAX ? DEEP : ANDESITE);
                        final int ly = y - base;
                        final BlockState s = sec.getBlockState(lx, ly, lz);
                        if (s == target) continue;
                        if (s.hasBlockEntity()) chunk.removeBlockEntity(p.set(sx + lx, y, sz + lz));
                        sec.setBlockState(lx, ly, lz, target, false);
                        changed = true;
                    }
                }
            } finally {
                sec.unlock();
            }
        }
        if (changed) chunk.markNeedsSaving();
    }
}
