package com.fn.mixin;

import com.fn.FnMod;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    // 木・鉱石・構造物の配置の直後、光の計算の前に実行 (ワールド生成スレッドで並列)
    @Inject(method = "generateFeatures", at = @At("TAIL"))
    private void fnmod$afterFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor sa, CallbackInfo ci) {
        if (world.toServerWorld().getRegistryKey() != World.OVERWORLD) return;
        FnMod.process(chunk, (cx, cz) -> world.getChunk(cx, cz, ChunkStatus.EMPTY, false), null);
    }
}
