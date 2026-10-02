package com.fn;

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
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * /start  : 今いる場所からつながった「暗いすき間」を全部埋める (バケツ塗りの3D版)。空洞の中で打つ。
 *           壁・地面で囲まれた所の外には広がらない。明るい所 (空の光が届く所) にも広がらないので、
 *           地面に穴があっても地上へは漏れない。どれだけ広くても、つながっていれば最後まで埋める。
 * /startt : y=99 を壊せないブロック / y=100〜120 を深層岩にする (地上が 120 より上なのは確認済み)。
 * /stone  : 地中をバニラ風の石・深層岩・鉱石にする (Stone.java)。
 * 進み具合は 10 秒ごとにチャットに出る。続けて打てば /start → /startt → /stone の順に進む。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // /startt・/stone の範囲 (X, Z 共通)。/start の % の目安にも使う
    static final int FLOOD_LIMIT = 30000;            // /start が広がれる最大の X, Z (安全のための上限。普通は気にしなくてOK)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 100..120 深層岩 / 121〜 安山岩
    static final int SKY_DARK = 7;                   // 空の光 (0〜15) がこれ以下 = 暗い = 埋める
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    static final long REPORT_MS = 10_000;            // 進み具合をチャットに出す間隔
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState STONE = Blocks.STONE.getDefaultState();
    static final int NONE = Integer.MIN_VALUE;

    static final int CX_MIN = Math.floorDiv(MIN_XZ, 16), CX_MAX = Math.floorDiv(MAX_XZ, 16);
    static final int SIDE = CX_MAX - CX_MIN + 1, TOTAL = SIDE * SIDE;

    /** 範囲全体をチャンクごとに処理するジョブ */
    static final class Job {
        final String name;
        boolean running = false;
        int next = 0;
        long filled = 0, lastReport = 0;
        Job(String name) { this.name = name; }
        void begin() { running = true; next = 0; filled = 0; lastReport = System.currentTimeMillis(); }
    }
    static final Job STARTT = new Job("/startt");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            dispatcher.register(CommandManager.literal("start")
                .executes(ctx -> {
                    ServerPlayerEntity pl = ctx.getSource().getPlayer();
                    if (pl == null) return 0;
                    MinecraftServer server = ctx.getSource().getServer();
                    ServerWorld w = server.getOverworld();
                    BlockPos pos = pl.getBlockPos();
                    String why = Flood.whyNot(w, pos);
                    if (why != null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("ここからは始められません: " + why), false);
                        if (Flood.running) Flood.report(server, "進み具合");
                        return 0;
                    }
                    Flood.add(pos);   // 実行中に別の空洞で打てば、そこも追加される
                    ctx.getSource().sendFeedback(() -> Text.literal("/start 開始: " + pos.toShortString() + " からつながった空洞を全部埋めます"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    Flood.stop();
                    ctx.getSource().sendFeedback(() -> Text.literal("/start を停止しました"), false);
                    return 1;
                })));

            dispatcher.register(CommandManager.literal("startt")
                .executes(ctx -> {
                    if (STARTT.running) { report(ctx.getSource().getServer(), STARTT, "すでに実行中です"); return 0; }
                    STARTT.begin();
                    ctx.getSource().sendFeedback(() -> Text.literal("/startt 開始: y=99 壊せないブロック / y=100〜" + DEEP_MAX + " 深層岩 (" + TOTAL + " チャンク)"), false);
                    return 1;
                })
                .then(CommandManager.literal("stop").executes(ctx -> {
                    STARTT.running = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("/startt を停止しました"), false);
                    return 1;
                })));

            dispatcher.register(CommandManager.literal("stone")
                .executes(ctx -> {
                    if (Stone.running) { ctx.getSource().sendFeedback(() -> Text.literal("すでに実行中です"), false); return 0; }
                    Stone.begin();
                    ctx.getSource().sendFeedback(() -> Text.literal("/stone 開始: 地中をバニラ風の石・鉱石にします (" + TOTAL + " チャンク × 2回)"), false);
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

    // ---------------- 共通 ----------------
    static boolean passable(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.isReplaceable()
            || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
            || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS);
    }

    static boolean inRange(int x, int z) {
        return x >= MIN_XZ && x <= MAX_XZ && z >= MIN_XZ && z <= MAX_XZ;
    }

    static BlockState target(int y) {
        return y == LOCK_Y ? LOCK : (y <= DEEP_MAX ? DEEP : STONE);
    }

    static void report(MinecraftServer server, Job j, String prefix) {
        String msg = String.format("%s %s: %d / %d チャンク (%.1f%%)%s", j.name, prefix, j.next, TOTAL, j.next * 100.0 / TOTAL,
                j.filled > 0 ? " / " + j.filled + " ブロック" : "");
        server.getPlayerManager().broadcast(Text.literal(msg), false);
    }

    private static void runJob(MinecraftServer server, ServerWorld w, long end, Job j) {
        while (j.next < TOTAL && System.nanoTime() < end) {
            j.filled += layerChunk(w.getChunk(CX_MIN + j.next % SIDE, CX_MIN + j.next / SIDE));
            j.next++;
        }
        long now = System.currentTimeMillis();
        if (j.next >= TOTAL) {
            j.running = false;
            report(server, j, "完了！ 一度ワールドに入り直してください");
        } else if (now - j.lastReport >= REPORT_MS) {
            j.lastReport = now;
            report(server, j, "進み具合");
        }
    }

    // ================= /startt : 下の層 =================
    static int layerChunk(WorldChunk chunk) {
        final int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return 0;
        final BlockPos.Mutable p = new BlockPos.Mutable();
        int count = 0;
        for (int si = chunk.getSectionIndex(LOCK_Y); si <= chunk.getSectionIndex(DEEP_MAX); si++) {
            final ChunkSection sec = chunk.getSection(si);
            final int base = chunk.sectionIndexToCoord(si) << 4;
            final int lo = Math.max(LOCK_Y, base), hi = Math.min(DEEP_MAX, base + 15);
            sec.lock();
            try {
                for (int y = lo; y <= hi; y++) {
                    final BlockState t = target(y);
                    for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        if (!inRange(sx + lx, sz + lz)) continue;
                        final BlockState s = sec.getBlockState(lx, y & 15, lz);
                        if (s == t) continue;
                        if (s.hasBlockEntity()) chunk.removeBlockEntity(p.set(sx + lx, y, sz + lz));
                        sec.setBlockState(lx, y & 15, lz, t, false);
                        count++;
                    }
                }
            } finally {
                sec.unlock();
            }
        }
        if (count > 0) chunk.markNeedsSaving();
        return count;
    }

    // ---------------- tick ----------------
    private static void tick(MinecraftServer server) {
        if (!Flood.running && !STARTT.running && !Stone.running) return;
        ServerWorld w = server.getOverworld();
        long end = System.nanoTime() + TICK_BUDGET_NS;
        if (Flood.running) Flood.tick(server, w, end);
        else if (STARTT.running) runJob(server, w, end, STARTT);
        else Stone.tick(server, w, end);
    }
}
