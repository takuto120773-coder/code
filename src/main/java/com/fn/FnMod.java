package com.fn;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

/**
 * 地面より下 (空洞) だけをピンポイントで埋める。
 *
 * 各列 (X,Z) ごとに、空洞の床 FLOOR から真上に調べる:
 *   0. 床の高さが自然の地面 (土・草・石など) でなければ何もしない
 *   1. 床にくっついた小さな出っ張り (高さ BUMP_H まで) は一緒に埋める
 *   2. 空気・水・木・草花を通り抜けて、最初に当たったブロック = 天井 (地面の裏側)
 *   3. 天井が自然の地面 → 埋める / 人工物 → 真っ暗な時だけ埋める / 何も無い (空) → 埋めない
 * 埋めるのは「天井の1つ下 〜 y=99」だけ。天井より上 (地面・道路・建物・橋) には一切触らない。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // 処理範囲 (X, Z 共通)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 100..120 深層岩 / 121〜 安山岩
    static final int BUMP_H = 3;                     // 床からこの高さまでの出っ張りは天井とみなさない
    static final int SKY_DARK = 7;                   // 天井が人工物の時、空の光がこれ以下なら埋める
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();
    static final int NONE = Integer.MIN_VALUE;

    static final int CX_MIN = Math.floorDiv(MIN_XZ, 16), CX_MAX = Math.floorDiv(MAX_XZ, 16);
    static final int SIDE = CX_MAX - CX_MIN + 1, TOTAL = SIDE * SIDE;
    static boolean running = false;
    static int next = 0, lastPercent = -1, floorY = 0;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
            CommandManager.literal("start")
                // /start          → 自分が立っている所の1つ下を「床」にする
                .executes(ctx -> {
                    ServerPlayerEntity pl = ctx.getSource().getPlayer();
                    if (pl == null) { ctx.getSource().sendFeedback(() -> Text.literal("プレイヤーが実行してください"), false); return 0; }
                    return begin(ctx, (int) Math.floor(pl.getY()) - 1);
                })
                // /start <床のY>  → 数字で指定
                .then(CommandManager.argument("floorY", IntegerArgumentType.integer(LOCK_Y + 1, 400))
                    .executes(ctx -> begin(ctx, IntegerArgumentType.getInteger(ctx, "floorY"))))
                .then(CommandManager.literal("stop").executes(ctx -> {
                    running = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("停止しました (" + next + "/" + TOTAL + ")"), false);
                    return 1;
                }))
        ));
        ServerTickEvents.END_SERVER_TICK.register(FnMod::tick);
    }

    private static int begin(CommandContext<ServerCommandSource> ctx, int fy) {
        if (running) {
            ctx.getSource().sendFeedback(() -> Text.literal("すでに実行中です (" + next + "/" + TOTAL + ")"), false);
            return 0;
        }
        if (fy <= LOCK_Y) {
            ctx.getSource().sendFeedback(() -> Text.literal("床の高さ Y=" + fy + " は 99 以下なので実行できません"), false);
            return 0;
        }
        floorY = fy; running = true; next = 0; lastPercent = -1;
        ctx.getSource().sendFeedback(() -> Text.literal("開始: 床の高さ Y=" + fy + " / " + TOTAL + " チャンク"), false);
        return 1;
    }

    private static void tick(MinecraftServer server) {
        if (!running) return;
        ServerWorld world = server.getOverworld();
        long end = System.nanoTime() + TICK_BUDGET_NS;
        while (next < TOTAL && System.nanoTime() < end) {
            process(world, world.getChunk(CX_MIN + next % SIDE, CX_MIN + next / SIDE), floorY);
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

    /** 空洞の中身として埋めてよいもの (空気・水・溶岩・木・葉・草花) */
    static boolean passable(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.isReplaceable()
            || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
            || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS);
    }

    /** 自然の地面 (土・草・石・砂など) */
    static boolean isNaturalGround(BlockState s) {
        if (!s.isOpaqueFullCube()) return false;
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND)
            || s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY)
            || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.SNOW_BLOCK);
    }

    /** 埋める上限のY (天井の1つ下)。埋めない列は NONE */
    static int fillTop(ServerWorld w, Chunk c, int lx, int lz, int fy) {
        final BlockPos.Mutable p = new BlockPos.Mutable();
        final int top = c.getTopYInclusive();
        final int wx = c.getPos().getStartX() + lx, wz = c.getPos().getStartZ() + lz;

        // 0. 床の高さに「自然の地面」が無い列は何もしない (谷・低い土地の建物などを守る)
        if (!isNaturalGround(c.getBlockState(p.set(wx, fy, wz)))) return NONE;

        // 1. 床にくっついた出っ張り
        int y = fy + 1;
        while (y <= fy + BUMP_H && !passable(c.getBlockState(p.set(wx, y, wz)))) y++;
        if (y > fy + BUMP_H && !passable(c.getBlockState(p.set(wx, y, wz)))) {
            return fy; // 床から上がブロックで詰まっている (空洞なし / 柱) → 床から下だけ埋める
        }

        // 2. 空洞の中を上へ。最初の「中身じゃない」ブロックが天井
        for (; y <= top; y++) {
            final int si = c.getSectionIndex(y);
            final ChunkSection sec = c.getSection(si);
            if (sec.isEmpty()) { y = (c.sectionIndexToCoord(si) << 4) + 15; continue; }
            final BlockState s = sec.getBlockState(lx, y & 15, lz);
            if (passable(s)) continue;

            // 3. 天井が自然の地面の裏側 → 埋める
            if (isNaturalGround(s)) return y - 1;
            // 天井が人工物 → 真っ暗な時だけ埋める (地下に垂れ下がった建物の下は暗い / 橋の下は明るい)
            return w.getLightLevel(LightType.SKY, p.set(wx, y - 1, wz)) <= SKY_DARK ? y - 1 : NONE;
        }
        return NONE; // 空まで抜けている
    }

    static void process(ServerWorld w, Chunk chunk, int fy) {
        final int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return;

        final int[] tops = new int[256];
        int maxTop = NONE;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            final int wx = sx + lx, wz = sz + lz;
            int t = (wx < MIN_XZ || wx > MAX_XZ || wz < MIN_XZ || wz > MAX_XZ) ? NONE : fillTop(w, chunk, lx, lz, fy);
            tops[(lz << 4) | lx] = t;
            if (t > maxTop) maxTop = t;
        }
        if (maxTop == NONE) return;

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
