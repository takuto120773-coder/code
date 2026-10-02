package com.fn;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.BitSet;

/** /start : つながった暗いすき間のバケツ塗り (縦1列ずつまとめて埋める) */
final class Flood {
    static final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    static boolean running = false;
    static long filled = 0, lastReport = 0;

    // 進み具合の目安: 範囲 (MIN_XZ〜MAX_XZ) の中で、埋め終わった列の数
    static final int W = FnMod.MAX_XZ - FnMod.MIN_XZ + 1;
    static final long TOTAL_COLS = (long) W * W;
    static final BitSet cols = new BitSet((int) TOTAL_COLS);
    static long colCount = 0;

    static final BlockPos.Mutable tmp = new BlockPos.Mutable();

    static void add(BlockPos pos) {
        queue.enqueue(pos.asLong());
        if (!running) {
            running = true; filled = 0; colCount = 0; cols.clear();
            lastReport = System.currentTimeMillis();
        }
    }

    static void stop() {
        running = false;
        queue.clear();
    }

    static boolean fillable(ServerWorld w, int x, int y, int z) {
        if (x < -FnMod.FLOOD_LIMIT || x > FnMod.FLOOD_LIMIT || z < -FnMod.FLOOD_LIMIT || z > FnMod.FLOOD_LIMIT) return false;
        if (y < FnMod.LOCK_Y || y > w.getTopYInclusive()) return false;
        tmp.set(x, y, z);
        if (!FnMod.passable(w.getBlockState(tmp))) return false;
        return w.getLightLevel(LightType.SKY, tmp) <= FnMod.SKY_DARK;
    }

    static String whyNot(ServerWorld w, BlockPos p) {
        if (p.getY() < FnMod.LOCK_Y) return "y=99 より下です";
        if (!FnMod.passable(w.getBlockState(p))) return "ブロックの中です (空洞の空気の中で打ってください)";
        int light = w.getLightLevel(LightType.SKY, p);
        if (light > FnMod.SKY_DARK) return "明るすぎます (空の光 " + light + ")。空洞の暗い所で打ってください";
        return null;
    }

    static void report(MinecraftServer server, String prefix) {
        server.getPlayerManager().broadcast(Text.literal(String.format(
            "/start %s: %d / %d 列 (%.1f%%) / %d ブロック埋めた / 残り候補 %d",
            prefix, colCount, TOTAL_COLS, colCount * 100.0 / TOTAL_COLS, filled, queue.size())), false);
    }

    static void tick(MinecraftServer server, ServerWorld w, long end) {
        while (!queue.isEmpty() && System.nanoTime() < end) {
            long l = queue.dequeueLong();
            int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
            if (!fillable(w, x, y, z)) continue;

            int lo = y, hi = y;
            while (fillable(w, x, lo - 1, z)) lo--;
            while (fillable(w, x, hi + 1, z)) hi++;

            push(w, x + 1, z, lo, hi);
            push(w, x - 1, z, lo, hi);
            push(w, x, z + 1, lo, hi);
            push(w, x, z - 1, lo, hi);

            WorldChunk c = w.getChunk(x >> 4, z >> 4);
            for (int yy = lo; yy <= hi; yy++) {
                ChunkSection sec = c.getSection(c.getSectionIndex(yy));
                BlockState s = sec.getBlockState(x & 15, yy & 15, z & 15);
                if (s.hasBlockEntity()) c.removeBlockEntity(new BlockPos(x, yy, z));
                sec.setBlockState(x & 15, yy & 15, z & 15, FnMod.target(yy), true);
                filled++;
            }
            c.markNeedsSaving();

            if (FnMod.inRange(x, z)) {
                int idx = (x - FnMod.MIN_XZ) * W + (z - FnMod.MIN_XZ);
                if (!cols.get(idx)) { cols.set(idx); colCount++; }
            }
        }

        if (queue.isEmpty()) {
            running = false;
            report(server, "完了！ (つながった空洞は全部埋まりました。% は範囲全体に対する目安です) 一度ワールドに入り直してください");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReport >= FnMod.REPORT_MS) {
            lastReport = now;
            report(server, "進み具合");
        }
    }

    private static void push(ServerWorld w, int x, int z, int lo, int hi) {
        boolean prev = false;
        for (int y = lo; y <= hi; y++) {
            boolean f = fillable(w, x, y, z);
            if (f && !prev) queue.enqueue(BlockPos.asLong(x, y, z));
            prev = f;
        }
    }
}
