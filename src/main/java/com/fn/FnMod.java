package com.fn;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * /start  : 「つながった空洞」だけを塗りつぶす (バケツ塗りの3D版)。空洞の中で打つ。
 * /startt : 範囲全体の地下を y=99 壊せないブロック / y=100〜120 深層岩 にする。どこで打ってもOK。
 *
 * /start は明るい所 (空の光が届く所) には広がらないので、地上・建物・橋・谷は変わらない。
 * /startt は地上が y=120 より上にある前提で、y=99〜120 を判定なしで全部置き換える。
 * /stone  : 地中 (地上から見えないブロック) をバニラ風の石・深層岩・鉱石にする。Stone.java を参照。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // 処理範囲 (X, Z 共通)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 100..120 深層岩 / 121〜 安山岩
    static final int SKY_DARK = 7;                   // 空の光 (0〜15) がこれ以下 = 暗い = 地下
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();
    static final BlockPos.Mutable tmp = new BlockPos.Mutable();

    // /start (塗りつぶし)
    static final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    static boolean floodRunning = false;
    static long filled = 0, lastReport = 0;

    // /startt (下の層)
    static final int CX_MIN = Math.floorDiv(MIN_XZ, 16), CX_MAX = Math.floorDiv(MAX_XZ, 16);
    static final int SIDE = CX_MAX - CX_MIN + 1, TOTAL = SIDE * SIDE;
    static boolean layerRunning = false;
    static int next = 0, lastPercent = -1;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            dispatcher.register(CommandManager.literal("start")
                .executes(ctx -> {
                    ServerPlayerEntity pl = ctx.getSource().getPlayer();
                    if (pl == null) return 0;
                    ServerWorld w = ctx.getSource().getServer().getOverworld();
                    BlockPos pos = pl.getBlockPos();
                    String why = whyNot(w, pos);
                    if (why != null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("ここからは始められません: " + why), false);
                        return 0;
                    }
                    queue.enqueue(pos.asLong());
                    if (!floodRunning) { floodRunning = true; filled = 0; }
                    ctx.getSource().sendFeedback(() -> Text.literal("開始: " + pos.toShortString() + " からつながった空洞を埋めます"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    floodRunning = false;
                    queue.clear();
                    ctx.getSource().sendFeedback(() -> Text.literal("/start を停止しました (" + filled + " ブロック埋めた)"), false);
                    return 1;
                })));

            dispatcher.register(CommandManager.literal("startt")
                .executes(ctx -> {
                    if (layerRunning) {
                        ctx.getSource().sendFeedback(() -> Text.literal("すでに実行中です (" + next + "/" + TOTAL + ")"), false);
                        return 0;
                    }
                    layerRunning = true; next = 0; lastPercent = -1;
                    ctx.getSource().sendFeedback(() -> Text.literal("開始: y=99 壊せないブロック / y=100〜" + DEEP_MAX + " 深層岩 (" + TOTAL + " チャンク)"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    layerRunning = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("/startt を停止しました (" + next + "/" + TOTAL + ")"), false);
                    return 1;
                })));

            dispatcher.register(CommandManager.literal("stone")
                .executes(ctx -> {
                    if (Stone.running) {
                        ctx.getSource().sendFeedback(() -> Text.literal("すでに実行中です"), false);
                        return 0;
                    }
                    Stone.begin();
                    ctx.getSource().sendFeedback(() -> Text.literal("開始: 地中をバニラ風の石・鉱石にします (" + TOTAL + " チャンク × 2回)"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    Stone.running = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("/stone を停止しました"), false);
                    return 1;
                })));
        });
        ServerTickEvents.END_SERVER_TICK.register(FnMod::tick);
    }

    // ---------------- 共通の判定 ----------------
    static boolean passable(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.isReplaceable()
            || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
            || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS);
    }

    static boolean inRange(int x, int z) {
        return x >= MIN_XZ && x <= MAX_XZ && z >= MIN_XZ && z <= MAX_XZ;
    }

    static BlockState target(int y) {
        return y == LOCK_Y ? LOCK : (y <= DEEP_MAX ? DEEP : ANDESITE);
    }

    /** 明るいすき間 (= 地上の空気。ここと、ここに接しているブロックは触らない) */
    static boolean litOpen(ServerWorld w, int x, int y, int z) {
        tmp.set(x, y, z);
        return passable(w.getBlockState(tmp)) && w.getLightLevel(LightType.SKY, tmp) > SKY_DARK;
    }

    // ================= /start : 塗りつぶし =================
    static boolean fillable(ServerWorld w, int x, int y, int z) {
        if (!inRange(x, z)) return false;
        if (y < LOCK_Y || y > w.getTopYInclusive()) return false;
        tmp.set(x, y, z);
        if (!passable(w.getBlockState(tmp))) return false;
        return w.getLightLevel(LightType.SKY, tmp) <= SKY_DARK;
    }

    static String whyNot(ServerWorld w, BlockPos p) {
        if (!inRange(p.getX(), p.getZ())) return "範囲の外です";
        if (p.getY() < LOCK_Y) return "y=99 より下です";
        if (!passable(w.getBlockState(p))) return "ブロックの中です (空洞の空気の中で打ってください)";
        int light = w.getLightLevel(LightType.SKY, p);
        if (light > SKY_DARK) return "明るすぎます (空の光 " + light + ")。空洞の暗い所で打ってください";
        return null;
    }

    private static void floodTick(MinecraftServer server, ServerWorld w, long end) {
        while (!queue.isEmpty() && System.nanoTime() < end) {
            long l = queue.dequeueLong();
            int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
            if (!fillable(w, x, y, z)) continue;

            int lo = y, hi = y;
            while (fillable(w, x, lo - 1, z)) lo--;
            while (fillable(w, x, hi + 1, z)) hi++;

            pushNeighbors(w, x + 1, z, lo, hi);
            pushNeighbors(w, x - 1, z, lo, hi);
            pushNeighbors(w, x, z + 1, lo, hi);
            pushNeighbors(w, x, z - 1, lo, hi);

            WorldChunk chunk = w.getChunk(x >> 4, z >> 4);
            for (int yy = lo; yy <= hi; yy++) {
                if (set(chunk, x, yy, z, target(yy))) filled++;
            }
            chunk.markNeedsSaving();
        }
        long now = System.currentTimeMillis();
        if (now - lastReport > 3000) {
            lastReport = now;
            server.getPlayerManager().broadcast(Text.literal("/start 埋め立て中… " + filled + " ブロック / 残り候補 " + queue.size()), true);
        }
        if (queue.isEmpty()) {
            floodRunning = false;
            server.getPlayerManager().broadcast(Text.literal("/start 完了！ " + filled + " ブロック埋めました。一度ワールドに入り直してください"), false);
        }
    }

    private static void pushNeighbors(ServerWorld w, int x, int z, int lo, int hi) {
        boolean prev = false;
        for (int y = lo; y <= hi; y++) {
            boolean f = fillable(w, x, y, z);
            if (f && !prev) queue.enqueue(BlockPos.asLong(x, y, z));
            prev = f;
        }
    }

    // ================= /startt : 下の層 =================
    private static void layerTick(MinecraftServer server, ServerWorld w, long end) {
        while (next < TOTAL && System.nanoTime() < end) {
            layerChunk(w, w.getChunk(CX_MIN + next % SIDE, CX_MIN + next / SIDE));
            next++;
        }
        int percent = next * 100 / TOTAL;
        if (percent / 5 != lastPercent / 5) {
            lastPercent = percent;
            server.getPlayerManager().broadcast(Text.literal("/startt 実行中… " + percent + "%"), true);
        }
        if (next >= TOTAL) {
            layerRunning = false;
            server.getPlayerManager().broadcast(Text.literal("/startt 完了！ 一度ワールドに入り直してください"), false);
        }
    }

    /** y=99〜120 を範囲内すべて層ブロックにする (地上が y=120 より上なのは確認済みなので判定なし・最速) */
    private static void layerChunk(ServerWorld w, WorldChunk chunk) {
        final int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return;
        final BlockPos.Mutable p = new BlockPos.Mutable();
        boolean changed = false;

        for (int si = chunk.getSectionIndex(LOCK_Y); si <= chunk.getSectionIndex(DEEP_MAX); si++) {
            final ChunkSection sec = chunk.getSection(si);
            final int base = chunk.sectionIndexToCoord(si) << 4;
            final int lo = Math.max(LOCK_Y, base), hi = Math.min(DEEP_MAX, base + 15);
            sec.lock();
            try {
                for (int y = lo; y <= hi; y++) {
                    final BlockState t = target(y);
                    final int ly = y - base;
                    for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        if (!inRange(sx + lx, sz + lz)) continue;
                        final BlockState s = sec.getBlockState(lx, ly, lz);
                        if (s == t) continue;
                        if (s.hasBlockEntity()) chunk.removeBlockEntity(p.set(sx + lx, y, sz + lz));
                        sec.setBlockState(lx, ly, lz, t, false);
                        changed = true;
                    }
                }
            } finally {
                sec.unlock();
            }
        }
        if (changed) chunk.markNeedsSaving();
    }

    // ---------------- 書き込み ----------------
    static boolean set(WorldChunk chunk, int x, int y, int z, BlockState t) {
        final ChunkSection sec = chunk.getSection(chunk.getSectionIndex(y));
        final BlockState s = sec.getBlockState(x & 15, y & 15, z & 15);
        if (s == t) return false;
        if (s.hasBlockEntity()) chunk.removeBlockEntity(new BlockPos(x, y, z));
        sec.setBlockState(x & 15, y & 15, z & 15, t, true);
        return true;
    }

    // ---------------- tick ----------------
    private static void tick(MinecraftServer server) {
        if (!floodRunning && !layerRunning && !Stone.running) return;
        ServerWorld w = server.getOverworld();
        long end = System.nanoTime() + TICK_BUDGET_NS;
        if (floodRunning) floodTick(server, w, end);          // 順番: /start → /startt → /stone
        else if (layerRunning) layerTick(server, w, end);
        else Stone.tick(server, w, end);
    }
}
