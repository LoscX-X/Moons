package com.blanoir.tempos.client.render.world;

import com.blanoir.tempos.client.module.impl.render.xray.OreCache;
import com.blanoir.tempos.client.module.impl.render.xray.OreGroups;
import com.blanoir.tempos.client.module.impl.render.xray.XrayBlockTarget;
import com.blanoir.tempos.client.render.WorldOverlayRenderer.ColoredBox;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Checks exterior topology through emitted vertices, plus the real grouping/cache seam. */
public final class BlockUnionVerification {
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var vertices=new ArrayList<float[]>();
        VertexConsumer sink=(VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),new Class<?>[]{VertexConsumer.class},(proxy,method,values)->{
            if(method.getName().equals("addVertex") && values.length==3)vertices.add(new float[]{((Number)values[0]).floatValue(),((Number)values[1]).floatValue(),((Number)values[2]).floatValue()});
            return method.getReturnType()==VertexConsumer.class?proxy:null;
        });
        var pair=new long[]{BlockPos.asLong(0,0,0),BlockPos.asLong(1,0,0)};
        if(args.length>0 && args[0].equals("--baseline")) {
            for(int x=0;x<2;x++)OverlayGeometry.renderOutlineBox(new Matrix4f(),sink,new ColoredBox(x,0,0,x+1,1,1,1,1,1,.35f));
        } else BlockUnionMesh.of(pair).outline(new Matrix4f(),sink,1,1,1);
        require(vertices.size()/2==12,"Adjacent blocks still render separate cell outlines: lines="+vertices.size()/2);
        var cube=new long[8];int index=0;
        for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++)cube[index++]=BlockPos.asLong(x,y,z);
        var full=BlockUnionMesh.of(cube);
        require(full.faceCount()==6 && full.edgeCount()==12 && full.surfaceArea()==24,"Solid cube was not reduced to one exterior");
        var elbow=BlockUnionMesh.of(new long[]{BlockPos.asLong(0,0,0),BlockPos.asLong(1,0,0),BlockPos.asLong(0,0,1)});
        require(elbow.surfaceArea()==14 && elbow.edgeCount()==18,"L-shaped group filled its empty corner or retained grid edges");
        var ring=new long[8];index=0;
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)if(x!=1||z!=1)ring[index++]=BlockPos.asLong(x,0,z);
        var hole=BlockUnionMesh.of(ring);
        require(hole.surfaceArea()==32 && hole.edgeCount()==24,"Group's internal hole was lost");
        var negative=BlockUnionMesh.of(new long[]{BlockPos.asLong(-3,-64,-4),BlockPos.asLong(-2,-64,-4)});
        require(negative.minX()==-3 && negative.maxX()==-1 && negative.minY()==-64 && negative.edgeCount()==12,"Negative coordinate packing");
        var source=List.of(new OreCache.CachedXrayBlock(new BlockPos(0,0,0),XrayBlockTarget.DIAMOND),new OreCache.CachedXrayBlock(new BlockPos(1,0,0),XrayBlockTarget.DIAMOND),new OreCache.CachedXrayBlock(new BlockPos(2,0,0),XrayBlockTarget.GOLD),new OreCache.CachedXrayBlock(new BlockPos(8,0,0),XrayBlockTarget.DIAMOND));
        var groups=new OreGroups();var first=groups.get(source);
        require(first.size()==3,"Different types or disconnected blocks merged");
        require(groups.get(source)==first,"Unchanged snapshot rebuilt geometry");
        require(groups.get(new ArrayList<>(source))!=first,"Changed snapshot did not invalidate geometry");
        var large=new ArrayList<OreCache.CachedXrayBlock>();
        for(int x=0;x<32;x++)for(int y=0;y<26;y++)for(int z=0;z<32;z++)large.add(new OreCache.CachedXrayBlock(new BlockPos(x,y,z),XrayBlockTarget.DIAMOND));
        long start=System.nanoTime();var dense=groups.get(large);long elapsed=System.nanoTime()-start;
        require(dense.size()==1 && dense.getFirst().mesh().edgeCount()==12 && dense.getFirst().mesh().faceCount()==6,"Large cluster did not form one render exterior");
        start=System.nanoTime();for(int i=0;i<1000;i++)require(groups.get(large)==dense,"Cache hit changed mesh");long cached=System.nanoTime()-start;
        System.out.println("TEMPOS_BLOCK_UNION_VERIFIED shared-faces grid-lines holes negative-coordinates type-boundaries cache-invalidation dense_blocks="+large.size()+" faces=6 edges=12 build_ms="+elapsed/1e6+" cached_1000_ms="+cached/1e6+" scope=CPU-not-FPS");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
