package com.fn;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Random;

/**
 * /stone : 地中をバニラ風にする。
 *
 * 【触るブロック】次の全部に当てはまるものだけ (= 地上からは絶対に見えない地中の土や石)
 *   - y=100 以上、かつ その列の「地面の表面」より下
 *   - 土・石・砂・砂利・粘土・鉱石などの自然ブロック (建物・道路の材料は触らない)
 *   - 6方向すべてが不透明なブロックに囲まれている (空気・水・ガラス等に接していない)
 *
 * 【1回目】土台: 見えない地中は全部 石。
 *          y=DEEP_TOP_Y 付近で深層岩と石がバニラのように混ざり、それより下は深層岩。
 * 【2回目】バニラと同じ表 (数・大きさ・高さ分布) と同じ形の作り方で、
 *          土・砂利・花崗岩・閃緑岩・安山岩・凝灰岩のかたまりと、全鉱石を生成。
 *          高さはバニラの y=-64〜0 を y=99〜DEEP_TOP_Y に縮めて対応させ、数も同じ割合で調整。
 */
public final class Stone {

    // ===================== 設定 =====================
    static final int DEEP_TOP_Y = 120;   // バニラの y=0 (深層岩と石の境目) に当たる高さ
    static final int MIX = 4;            // 境目の上下 何ブロックで混ぜるか (バニラは8段の混ざり)
    static final int MIN_THICK = 3;      // 地面の表面を探すとき、これより薄い塊は浮いたゴミとみなす
    // ===============================================

    static final int LOCK_Y = FnMod.LOCK_Y, MIN_Y = LOCK_Y + 1, NONE = Integer.MIN_VALUE;
    /** バニラの高さ → このワールドの高さ の縮尺 (バニラ 64 段 = y99〜DEEP_TOP_Y) */
    static final double S = (DEEP_TOP_Y - LOCK_Y) / 64.0;

    static final BlockState STONE = Blocks.STONE.getDefaultState();
    static final BlockState DEEP = Blocks.DEEPSLATE.getDefaultState();

    static boolean running = false;
    static int phase = 0, next = 0, lastPercent = -1;

    static void begin() {
        running = true; phase = 0; next = 0; lastPercent = -1;
    }

    static void tick(MinecraftServer server, ServerWorld w, long end) {
        final int total = FnMod.TOTAL, side = FnMod.SIDE, cmin = FnMod.CX_MIN;
        while (running && System.nanoTime() < end) {
            if (next >= total) {
                if (phase == 0) { phase = 1; next = 0; continue; }
                running = false;
                server.getPlayerManager().broadcast(Text.literal("/stone 完了！ 一度ワールドに入り直してください"), false);
                return;
            }
            int cx = cmin + next % side, cz = cmin + next / side;
            if (phase == 0) base(w, cx, cz); else features(w, cx, cz);
            next++;
        }
        int percent = (phase * total + next) * 50 / total;
        if (percent / 5 != lastPercent / 5) {
            lastPercent = percent;
            server.getPlayerManager().broadcast(Text.literal("/stone 実行中… " + percent + "% (" + (phase == 0 ? "土台" : "鉱石") + ")"), true);
        }
    }

    // ================= 判定 =================
    /** 置き換えてよい自然ブロック */
    static boolean groundish(BlockState s) {
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND)
            || s.isIn(ConventionalBlockTags.ORES) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY)
            || s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.RED_SANDSTONE);
    }

    /** 地面の表面を探すときの「自然の地面」 */
    static boolean natural(BlockState s) {
        if (!s.isOpaqueFullCube()) return false;
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND)
            || s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY)
            || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.SNOW_BLOCK);
    }

    static BlockState get(WorldChunk c, int x, int y, int z) {
        ChunkSection sec = c.getSection(c.getSectionIndex(y));
        return sec.isEmpty() ? Blocks.AIR.getDefaultState() : sec.getBlockState(x & 15, y & 15, z & 15);
    }

    /** その列の地面の表面Y (建物・道路・木・橋・浮いたゴミの下の、自然の地面の一番上)。なければ NONE */
    static int surface(ServerWorld w, int x, int z) {
        WorldChunk c = w.getChunk(x >> 4, z >> 4);
        int y = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) - 1;
        while (y >= MIN_Y) {
            int si = c.getSectionIndex(y);
            if (c.getSection(si).isEmpty()) { y = (c.sectionIndexToCoord(si) << 4) - 1; continue; }
            if (!natural(get(c, x, y, z))) { y--; continue; }
            int top = y;
            while (y >= MIN_Y && !FnMod.passable(get(c, x, y, z))) y--;
            if (y >= MIN_Y && top - y < MIN_THICK) continue;   // 薄い = 浮いたゴミ
            return top;
        }
        return NONE;
    }

    static final BlockPos.Mutable tmp = new BlockPos.Mutable();

    static boolean opaque(ServerWorld w, int x, int y, int z) {
        return w.getBlockState(tmp.set(x, y, z)).isOpaqueFullCube();
    }

    /** 6方向すべて不透明 = 地上から見えない */
    static boolean hidden(ServerWorld w, WorldChunk c, int x, int y, int z) {
        final int lx = x & 15, lz = z & 15;
        // 同じチャンク内は直接読む (速い)
        if (!get(c, x, y + 1, z).isOpaqueFullCube() || !get(c, x, y - 1, z).isOpaqueFullCube()) return false;
        if (lx < 15 ? !get(c, x + 1, y, z).isOpaqueFullCube() : !opaque(w, x + 1, y, z)) return false;
        if (lx > 0 ? !get(c, x - 1, y, z).isOpaqueFullCube() : !opaque(w, x - 1, y, z)) return false;
        if (lz < 15 ? !get(c, x, y, z + 1).isOpaqueFullCube() : !opaque(w, x, y, z + 1)) return false;
        if (lz > 0 ? !get(c, x, y, z - 1).isOpaqueFullCube() : !opaque(w, x, y, z - 1)) return false;
        return true;
    }

    /** 位置で決まる 0〜1 の乱数 (何回やっても同じ結果) */
    static double hash01(long seed, int x, int y, int z) {
        long h = seed ^ (x * 3129871L) ^ (z * 116129781L) ^ ((long) y * 42317861L);
        h = h * h * 42317861L + h * 11L;
        h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL; h ^= (h >>> 33);
        return (h >>> 11) * 0x1.0p-53;
    }

    // ================= 1回目: 土台 =================
    static void base(ServerWorld w, int cx, int cz) {
        final int sx = cx << 4, sz = cz << 4;
        if (sx > FnMod.MAX_XZ || sx + 15 < FnMod.MIN_XZ || sz > FnMod.MAX_XZ || sz + 15 < FnMod.MIN_XZ) return;
        final WorldChunk c = w.getChunk(cx, cz);
        final long seed = w.getSeed();

        final int[] surf = new int[256];
        int maxSurf = NONE;
        for (int i = 0; i < 256; i++) {
            int x = sx + (i & 15), z = sz + (i >> 4);
            surf[i] = FnMod.inRange(x, z) ? surface(w, x, z) : NONE;
            if (surf[i] != NONE) maxSurf = Math.max(maxSurf, surf[i]);
        }
        if (maxSurf == NONE) return;

        boolean changed = false;
        for (int si = c.getSectionIndex(MIN_Y); si <= c.getSectionIndex(maxSurf - 1); si++) {
            final ChunkSection sec = c.getSection(si);
            final int baseY = c.sectionIndexToCoord(si) << 4;
            for (int i = 0; i < 256; i++) {
                if (surf[i] == NONE) continue;
                final int lx = i & 15, lz = i >> 4, x = sx + lx, z = sz + lz;
                final int lo = Math.max(MIN_Y, baseY), hi = Math.min(surf[i] - 1, baseY + 15);
                for (int y = lo; y <= hi; y++) {
                    final BlockState s = sec.getBlockState(lx, y & 15, lz);
                    if (!groundish(s) || !hidden(w, c, x, y, z)) continue;

                    BlockState t;
                    if (y <= DEEP_TOP_Y - MIX) t = DEEP;
                    else if (y >= DEEP_TOP_Y + MIX) t = STONE;
                    else {
                        // バニラと同じ「下ほど深層岩が多い」ランダムな混ざり
                        double pDeep = (DEEP_TOP_Y + MIX - y) / (2.0 * MIX);
                        t = hash01(seed, x, y, z) < pDeep ? DEEP : STONE;
                    }
                    if (s != t) { sec.setBlockState(lx, y & 15, lz, t, true); changed = true; }
                }
            }
        }
        if (changed) c.markNeedsSaving();
    }

    // ================= 2回目: かたまり・鉱石 =================
    /** バニラの鉱石・岩石の表 (1.21) */
    record F(double count, double rarity, int size, boolean trapezoid, int vMin, int vMax,
             BlockState stone, BlockState deep, boolean blob) {}

    static F blob(double count, double rarity, int size, int vMin, int vMax, BlockState s) {
        return new F(count, rarity, size, false, vMin, vMax, s, s, true);
    }
    static F ore(double count, int size, boolean trap, int vMin, int vMax, BlockState s, BlockState d) {
        return new F(count, 1, size, trap, vMin, vMax, s, d, false);
    }
    static F rareOre(double rarity, int size, boolean trap, int vMin, int vMax, BlockState s, BlockState d) {
        return new F(1, rarity, size, trap, vMin, vMax, s, d, false);
    }

    static final F[] TABLE = {
        // 岩石・土のかたまり (バニラの順番どおり)
        blob(7, 1, 33, 0, 160, Blocks.DIRT.getDefaultState()),
        blob(14, 1, 33, -64, 320, Blocks.GRAVEL.getDefaultState()),
        blob(1, 1.0 / 6, 64, 64, 128, Blocks.GRANITE.getDefaultState()),
        blob(2, 1, 64, 0, 60, Blocks.GRANITE.getDefaultState()),
        blob(1, 1.0 / 6, 64, 64, 128, Blocks.DIORITE.getDefaultState()),
        blob(2, 1, 64, 0, 60, Blocks.DIORITE.getDefaultState()),
        blob(1, 1.0 / 6, 64, 64, 128, Blocks.ANDESITE.getDefaultState()),
        blob(2, 1, 64, 0, 60, Blocks.ANDESITE.getDefaultState()),
        blob(2, 1, 64, -64, 0, Blocks.TUFF.getDefaultState()),
        // 鉱石 (数/チャンク, 大きさ, 台形分布?, 高さ下限, 上限)
        ore(30, 17, false, 136, 320, Blocks.COAL_ORE.getDefaultState(), Blocks.DEEPSLATE_COAL_ORE.getDefaultState()),
        ore(20, 17, true, 0, 192, Blocks.COAL_ORE.getDefaultState(), Blocks.DEEPSLATE_COAL_ORE.getDefaultState()),
        ore(90, 9, true, 80, 384, Blocks.IRON_ORE.getDefaultState(), Blocks.DEEPSLATE_IRON_ORE.getDefaultState()),
        ore(10, 9, true, -24, 56, Blocks.IRON_ORE.getDefaultState(), Blocks.DEEPSLATE_IRON_ORE.getDefaultState()),
        ore(10, 4, false, -64, 72, Blocks.IRON_ORE.getDefaultState(), Blocks.DEEPSLATE_IRON_ORE.getDefaultState()),
        ore(4, 9, true, -64, 32, Blocks.GOLD_ORE.getDefaultState(), Blocks.DEEPSLATE_GOLD_ORE.getDefaultState()),
        ore(0.5, 9, false, -64, -48, Blocks.GOLD_ORE.getDefaultState(), Blocks.DEEPSLATE_GOLD_ORE.getDefaultState()),
        ore(4, 8, false, -64, 15, Blocks.REDSTONE_ORE.getDefaultState(), Blocks.DEEPSLATE_REDSTONE_ORE.getDefaultState()),
        ore(8, 8, true, -96, -32, Blocks.REDSTONE_ORE.getDefaultState(), Blocks.DEEPSLATE_REDSTONE_ORE.getDefaultState()),
        ore(7, 4, true, -144, 16, Blocks.DIAMOND_ORE.getDefaultState(), Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState()),
        ore(2, 8, false, -64, -4, Blocks.DIAMOND_ORE.getDefaultState(), Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState()),
        rareOre(1.0 / 9, 12, true, -144, 16, Blocks.DIAMOND_ORE.getDefaultState(), Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState()),
        ore(4, 8, true, -144, 16, Blocks.DIAMOND_ORE.getDefaultState(), Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState()),
        ore(2, 7, true, -32, 32, Blocks.LAPIS_ORE.getDefaultState(), Blocks.DEEPSLATE_LAPIS_ORE.getDefaultState()),
        ore(4, 7, false, -64, 64, Blocks.LAPIS_ORE.getDefaultState(), Blocks.DEEPSLATE_LAPIS_ORE.getDefaultState()),
        ore(16, 10, true, -16, 112, Blocks.COPPER_ORE.getDefaultState(), Blocks.DEEPSLATE_COPPER_ORE.getDefaultState()),
    };

    static int sampleV(Random r, F f) {
        if (!f.trapezoid) return f.vMin + r.nextInt(f.vMax - f.vMin + 1);
        int k = f.vMax - f.vMin, l = k / 2, m = k - l;          // バニラの台形分布 (平らな部分なし)
        return f.vMin + r.nextInt(m + 1) + r.nextInt(l + 1);
    }

    /** バニラの y → このワールドの y */
    static int toWorldY(double v) {
        return (int) Math.round(LOCK_Y + (v + 64) * S);
    }

    static void features(ServerWorld w, int cx, int cz) {
        final int sx = cx << 4, sz = cz << 4;
        if (sx > FnMod.MAX_XZ || sx + 15 < FnMod.MIN_XZ || sz > FnMod.MAX_XZ || sz + 15 < FnMod.MIN_XZ) return;
        final Random r = new Random(w.getSeed() ^ (cx * 341873128712L) ^ (cz * 132897987541L) ^ 0x5EED5L);
        final Placer pl = new Placer(w, sx - 16, sz - 16);

        for (F f : TABLE) {
            // 高さを縮めた分だけ数も減らす (= 1ブロックあたりの量はバニラと同じ)
            double expected = f.count * f.rarity * S;
            int n = (int) expected + (r.nextDouble() < expected - (int) expected ? 1 : 0);
            for (int k = 0; k < n; k++) {
                int x = sx + r.nextInt(16), z = sz + r.nextInt(16);
                int y = toWorldY(sampleV(r, f));
                vein(pl, r, x, y, z, f);
            }
        }
        pl.save();
    }

    /** バニラ OreFeature と同じ形の作り方 (線分に沿って大きさの変わる球を並べる) */
    static void vein(Placer pl, Random r, int ox, int oy, int oz, F f) {
        final int size = f.size;
        float a = r.nextFloat() * (float) Math.PI;
        float g = size / 8.0F;
        int i = MathHelper.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double x0 = ox + Math.sin(a) * g, x1 = ox - Math.sin(a) * g;
        double z0 = oz + Math.cos(a) * g, z1 = oz - Math.cos(a) * g;
        double y0 = oy + r.nextInt(3) - 2, y1 = oy + r.nextInt(3) - 2;
        int bx = ox - MathHelper.ceil(g) - i, by = oy - 2 - i, bz = oz - MathHelper.ceil(g) - i;

        double[] ds = new double[size * 4];
        for (int k = 0; k < size; k++) {
            float t = (float) k / size;
            double rad = r.nextDouble() * size / 16.0;
            ds[k * 4] = MathHelper.lerp(t, x0, x1);
            ds[k * 4 + 1] = MathHelper.lerp(t, y0, y1);
            ds[k * 4 + 2] = MathHelper.lerp(t, z0, z1);
            ds[k * 4 + 3] = ((MathHelper.sin((float) Math.PI * t) + 1.0F) * rad + 1.0) / 2.0;
        }
        for (int k = 0; k < size - 1; k++) {
            if (ds[k * 4 + 3] <= 0) continue;
            for (int m = k + 1; m < size; m++) {
                if (ds[m * 4 + 3] <= 0) continue;
                double dx = ds[k * 4] - ds[m * 4], dy = ds[k * 4 + 1] - ds[m * 4 + 1], dz = ds[k * 4 + 2] - ds[m * 4 + 2];
                double dr = ds[k * 4 + 3] - ds[m * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0) ds[m * 4 + 3] = -1; else ds[k * 4 + 3] = -1;
                }
            }
        }
        for (int m = 0; m < size; m++) {
            double e = ds[m * 4 + 3];
            if (e < 0) continue;
            double cxd = ds[m * 4], cyd = ds[m * 4 + 1], czd = ds[m * 4 + 2];
            int nx = Math.max(MathHelper.floor(cxd - e), bx), ny = Math.max(MathHelper.floor(cyd - e), by), nz = Math.max(MathHelper.floor(czd - e), bz);
            int qx = Math.max(MathHelper.floor(cxd + e), nx), qy = Math.max(MathHelper.floor(cyd + e), ny), qz = Math.max(MathHelper.floor(czd + e), nz);
            for (int x = nx; x <= qx; x++) {
                double u = (x + 0.5 - cxd) / e;
                if (u * u >= 1) continue;
                for (int y = ny; y <= qy; y++) {
                    double v = (y + 0.5 - cyd) / e;
                    if (u * u + v * v >= 1) continue;
                    for (int z = nz; z <= qz; z++) {
                        double s = (z + 0.5 - czd) / e;
                        if (u * u + v * v + s * s < 1) pl.place(x, y, z, f);
                    }
                }
            }
        }
    }

    /** 3x3チャンクの範囲に、ルールを守って書き込む */
    static final class Placer {
        final ServerWorld w;
        final int ox, oz;                    // 3x3 範囲の北西の角
        final int[] surf = new int[48 * 48];
        final WorldChunk[] chunks = new WorldChunk[9];
        final boolean[] dirty = new boolean[9];

        Placer(ServerWorld w, int ox, int oz) {
            this.w = w; this.ox = ox; this.oz = oz;
            for (int i = 0; i < 9; i++) chunks[i] = w.getChunk((ox >> 4) + i % 3, (oz >> 4) + i / 3);
            java.util.Arrays.fill(surf, Integer.MAX_VALUE); // まだ調べていない
        }

        int surfAt(int x, int z) {
            int i = (z - oz) * 48 + (x - ox);
            if (surf[i] == Integer.MAX_VALUE) surf[i] = surface(w, x, z);
            return surf[i];
        }

        void place(int x, int y, int z, F f) {
            if (x < ox || x >= ox + 48 || z < oz || z >= oz + 48) return;
            if (!FnMod.inRange(x, z) || y < MIN_Y) return;
            int s = surfAt(x, z);
            if (s == NONE || y >= s) return;                       // 地面の表面より上は触らない
            int ci = ((z - oz) >> 4) * 3 + ((x - ox) >> 4);
            WorldChunk c = chunks[ci];
            ChunkSection sec = c.getSection(c.getSectionIndex(y));
            BlockState cur = sec.getBlockState(x & 15, y & 15, z & 15);

            BlockState t;
            if (f.blob) t = cur.isIn(BlockTags.BASE_STONE_OVERWORLD) ? f.stone : null;
            else if (cur.isIn(BlockTags.STONE_ORE_REPLACEABLES)) t = f.stone;
            else if (cur.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) t = f.deep;
            else t = null;
            if (t == null || t == cur) return;
            if (!hidden(w, c, x, y, z)) return;                    // 見えている所は触らない

            sec.setBlockState(x & 15, y & 15, z & 15, t, true);
            dirty[ci] = true;
        }

        void save() {
            for (int i = 0; i < 9; i++) if (dirty[i]) chunks[i].markNeedsSaving();
        }
    }
}
