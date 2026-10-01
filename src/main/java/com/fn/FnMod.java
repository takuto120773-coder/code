package com.fn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
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
 * 埋めるかどうかの判定 (空気・水・草花・柵などの「すき間」):
 *   (1) その上のどこかに自然の地面 (土・石・砂など) がある
 *   (2) 空の光がほとんど届いていない (SKY_DARK 以下)
 *   → 両方当てはまったら埋める。
 *   橋・建物・道路の下は (1) が当てはまらない / 谷や張り出しの下は (2) が当てはまらない → 残る
 *
 * 固いブロック: 自然の地面より下にあり、残す「すき間」に一切接していない (=完全に埋もれている) なら置き換える。
 *   表面に見えているブロック (道路・木・草・橋・壁) は絶対に置き換えない。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // 処理範囲 (X, Z 共通)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 100..120 深層岩 / 121..地表 安山岩
    static final int SKY_DARK = 7;                   // 空の光(0〜15)がこれ以下なら「暗い」= 埋める
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();
    static final int NONE = Integer.MIN_VALUE;
    static final int B = 18; // 周囲1ブロック込みの幅

    // ---------------- /start ----------------
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
            new Job(world, CX_MIN + next % SIDE, CX_MIN + next / SIDE).run();
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

    /** 自然の地面ブロック (人工物=木材・レンガ・コンクリート・道路ブロック等は含まない) */
    static boolean isNaturalGround(BlockState s) {
        if (!s.isOpaqueFullCube()) return false;
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD)
            || s.isIn(BlockTags.DIRT)
            || s.isIn(BlockTags.SAND)
            || s.isIn(BlockTags.TERRACOTTA)
            || s.isIn(ConventionalBlockTags.ORES)
            || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY) || s.isOf(Blocks.SNOW_BLOCK)
            || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.RED_SANDSTONE)
            || s.isOf(Blocks.CALCITE) || s.isOf(Blocks.DRIPSTONE_BLOCK)
            || s.isOf(Blocks.SMOOTH_BASALT) || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.PACKED_ICE);
    }

    /** 1チャンク分の処理 */
    static final class Job {
        final ServerWorld w;
        final Chunk[] chunks = new Chunk[9];
        final int sx, sz;
        final int[] natTop = new int[B * B]; // 各列で上から見て最初の自然の地面のY (周囲1列込み)
        final BlockPos.Mutable q = new BlockPos.Mutable();

        Job(ServerWorld w, int cx, int cz) {
            this.w = w;
            this.sx = cx << 4;
            this.sz = cz << 4;
        }

        Chunk chunkAt(int wx, int wz) {
            return chunks[(Math.floorDiv(wz - sz, 16) + 1) * 3 + (Math.floorDiv(wx - sx, 16) + 1)];
        }

        static int firstNatural(Chunk c, int lx, int lz) {
            int y = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz) - 1;
            for (; y >= LOCK_Y; y--) {
                final int si = c.getSectionIndex(y);
                final ChunkSection sec = c.getSection(si);
                if (sec.isEmpty()) { y = c.sectionIndexToCoord(si) << 4; continue; }
                if (isNaturalGround(sec.getBlockState(lx, y & 15, lz))) return y;
            }
            return NONE;
        }

        /** 残す「すき間」か (= 固くない & (上に自然の地面がない or 空の光が届いている)) */
        boolean keptOpen(int wx, int y, int wz, BlockState s) {
            if (s.isOpaqueFullCube()) return false;
            final int nt = natTop[(wz - sz + 1) * B + (wx - sx + 1)];
            if (nt == NONE || y >= nt) return true;                         // (1) 上に自然の地面がない
            return w.getLightLevel(LightType.SKY, q.set(wx, y, wz)) > SKY_DARK; // (2) 明るい
        }

        boolean keptOpenAt(int wx, int y, int wz) {
            return keptOpen(wx, y, wz, chunkAt(wx, wz).getBlockState(q.set(wx, y, wz)));
        }

        void run() {
            if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return;
            final int ccx = sx >> 4, ccz = sz >> 4;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                chunks[(dz + 1) * 3 + (dx + 1)] = w.getChunk(ccx + dx, ccz + dz);
            final Chunk chunk = chunks[4];

            // ---- 1. 各列の「最初の自然の地面」と一番上 ----
            for (int bz = 0; bz < B; bz++) for (int bx = 0; bx < B; bx++) {
                int wx = sx + bx - 1, wz = sz + bz - 1;
                natTop[bz * B + bx] = firstNatural(chunkAt(wx, wz), wx & 15, wz & 15);
            }
            final int[] tops = new int[256];
            int maxTop = NONE;
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int wx = sx + lx, wz = sz + lz;
                int t = (wx < MIN_XZ || wx > MAX_XZ || wz < MIN_XZ || wz > MAX_XZ || natTop[(lz + 1) * B + lx + 1] == NONE)
                        ? NONE : natTop[(lz + 1) * B + lx + 1];
                tops[(lz << 4) | lx] = t;
                if (t > maxTop) maxTop = t;
            }
            if (maxTop == NONE) return;  // 地面が無い列だけ → 何もしない

            // ---- 2. セクションごとにまとめて判定・書き込み ----
            boolean changed = false;
            final BlockPos.Mutable p = new BlockPos.Mutable();
            final int fromSec = chunk.getSectionIndex(LOCK_Y), toSec = chunk.getSectionIndex(maxTop);
            for (int si = fromSec; si <= toSec; si++) {
                final ChunkSection sec = chunk.getSection(si);
                final int base = chunk.sectionIndexToCoord(si) << 4;
                sec.lock();
                try {
                    for (int idx = 0; idx < 256; idx++) {
                        final int t = tops[idx];
                        if (t == NONE) continue;
                        final int lx = idx & 15, lz = idx >> 4, wx = sx + lx, wz = sz + lz;
                        final int lo = Math.max(LOCK_Y, base), hi = Math.min(t, base + 15);
                        for (int y = lo; y <= hi; y++) {
                            final int ly = y - base;
                            final BlockState s = sec.getBlockState(lx, ly, lz);
                            final BlockState target = y == LOCK_Y ? LOCK : (y <= DEEP_MAX ? DEEP : ANDESITE);
                            if (s == target) continue;

                            if (y != LOCK_Y) {
                                if (s.isOpaqueFullCube()) {
                                    // 固いブロック: 自然の地面より下 & 6方向どれも残すすき間に接していない時だけ
                                    if (y >= t) continue;
                                    if (keptOpenAt(wx, y + 1, wz) || keptOpenAt(wx, y - 1, wz)
                                            || keptOpenAt(wx + 1, y, wz) || keptOpenAt(wx - 1, y, wz)
                                            || keptOpenAt(wx, y, wz + 1) || keptOpenAt(wx, y, wz - 1)) continue;
                                } else if (keptOpen(wx, y, wz, s)) {
                                    continue; // 残すすき間 (橋の下・建物の中・谷など)
                                }
                            }
                            if (s.hasBlockEntity()) chunk.removeBlockEntity(p.set(wx, y, wz));
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
}
