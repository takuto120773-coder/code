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
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 地面より下の「すき間」だけをピンポイントで埋める。床の高さは場所ごとに自動で判定。
 *
 * 各列 (X,Z) を y=99 から上へ調べ、すき間 (空気・水・木・草花) を下から順に見ていく:
 *   - すき間の天井が「自然の地面 (土・石など)」かつ「暗い」 → 地下の空洞・洞窟なので埋めて、さらに上へ
 *   - それ以外 (天井が空・橋・建物・道路 / 明るい)       → そこが地上。ここで止めて、上は一切触らない
 * 固いブロックは置き換えない (すき間を埋めるだけ) ので、崖の表面や建物の見た目は変わらない。
 */
public class FnMod implements ModInitializer {

    // ===================== 設定 =====================
    static final int MIN_XZ = -1650, MAX_XZ = 1650;  // 処理範囲 (X, Z 共通)
    static final int LOCK_Y = 99;                    // 壊せないブロックの層
    static final int DEEP_MAX = 120;                 // 99 より上〜120 は深層岩 / 121〜 は安山岩
    static final int SKY_DARK = 7;                   // 空の光(0〜15)がこれ以下なら「暗い」
    static final long TICK_BUDGET_NS = 45_000_000L;  // 1tick(50ms)のうち処理に使う時間
    // ===============================================

    static final BlockState LOCK = Blocks.REINFORCED_DEEPSLATE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();
    static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();

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

    /** すき間の中身 (埋めてよいもの): 空気・水・溶岩・木・葉・草花 */
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

    static BlockState target(int y) {
        return y == LOCK_Y ? LOCK : (y <= DEEP_MAX ? DEEP : ANDESITE);
    }

    static void process(ServerWorld w, WorldChunk chunk) {
        final int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        if (sx > MAX_XZ || sx + 15 < MIN_XZ || sz > MAX_XZ || sz + 15 < MIN_XZ) return;

        final BlockPos.Mutable p = new BlockPos.Mutable();
        boolean changed = false;

        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            final int wx = sx + lx, wz = sz + lz;
            if (wx < MIN_XZ || wx > MAX_XZ || wz < MIN_XZ || wz > MAX_XZ) continue;
            final int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz) - 1;
            if (top < LOCK_Y) continue;

            // y=99 が固いブロックならロック層にする
            if (!passable(get(chunk, lx, LOCK_Y, lz))) changed |= put(chunk, p, wx, lx, LOCK_Y, wz, lz);

            int y = LOCK_Y;
            while (y <= top) {
                while (y <= top && !passable(get(chunk, lx, y, lz))) y++;   // 固い所を飛ばす
                final int a = y;                                              // すき間の下端
                while (y <= top && passable(get(chunk, lx, y, lz))) y++;    // すき間の上端まで
                if (y > top) break;                                           // 空まで抜けている = 地上

                final int ceil = y;                                           // 天井
                if (!isNaturalGround(get(chunk, lx, ceil, lz))) break;        // 橋・建物・道路の下 = 地上
                if (w.getLightLevel(LightType.SKY, p.set(wx, ceil - 1, wz)) > SKY_DARK) break; // 明るい = 地上

                for (int yy = a; yy < ceil; yy++) changed |= put(chunk, p, wx, lx, yy, wz, lz); // 地下の空洞 → 埋める
            }
        }
        if (changed) chunk.markNeedsSaving();
    }

    static BlockState get(WorldChunk c, int lx, int y, int lz) {
        final ChunkSection sec = c.getSection(c.getSectionIndex(y));
        return sec.isEmpty() ? Blocks.AIR.getDefaultState() : sec.getBlockState(lx, y & 15, lz);
    }

    static boolean put(WorldChunk c, BlockPos.Mutable p, int wx, int lx, int y, int wz, int lz) {
        final ChunkSection sec = c.getSection(c.getSectionIndex(y));
        final BlockState t = target(y), s = sec.getBlockState(lx, y & 15, lz);
        if (s == t) return false;
        if (s.hasBlockEntity()) c.removeBlockEntity(p.set(wx, y, wz));
        sec.setBlockState(lx, y & 15, lz, t, false);
        return true;
    }
}
